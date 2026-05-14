package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.mixin.ServerChunkCacheInvoker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.UUID;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.Locale;

public final class ChunkGenerationManager {

    // -------------------------------------------------------------------------
    // Mode

    public enum PregenMode { NONE, DYNAMIC, REGION }

    private static final ChunkGenerationManager INSTANCE = new ChunkGenerationManager();

    private static class DimensionState {
        final ServerLevel level;
        final LongSet completedChunks = LongSets.synchronize(new LongOpenHashSet());
        final LongSet trackedChunks = LongSets.synchronize(new LongOpenHashSet());
        final DistanceGraph distanceGraph = new DistanceGraph();
        final Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
        final Map<Long, AtomicInteger> batchCounters = new ConcurrentHashMap<>();
        final AtomicInteger remainingInRadius = new AtomicInteger(0);
        boolean tellusActive = false;
        boolean loaded = false;

        DimensionState(ServerLevel level) {
            this.level = level;
        }
    }

    private final Map<ResourceKey<Level>, DimensionState> dimensionStates = new ConcurrentHashMap<>();

    // global state
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final GenerationStats stats = new GenerationStats();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configReloadScheduled = new AtomicBoolean(false);
    private final AtomicBoolean userPaused = new AtomicBoolean(true); // starts stopped
    private final AtomicLong totalTarget = new AtomicLong(0);
    /** Cached sum of all {@code remainingInRadius} — updated incrementally to avoid stream overhead on every HUD frame. */
    private final AtomicLong totalRemaining = new AtomicLong(0);
    /** Bitmask of reached milestones: bit 0 = 25%, bit 1 = 50%, bit 2 = 75%, bit 3 = 100% */
    private byte milestonesReached = 0;
    private int syncTotalTickCounter = 0;
    private int serverProgressTickCounter = 0;
    private int pregenLogTickCounter = 0;
    private int lodSaveTickCounter = 0;
    /** Auto-save LOD payload store every N ticks while pregen is active. */
    private static final int LOD_SAVE_INTERVAL_TICKS = 6000; // 5 minutes

    // mode
    private volatile PregenMode pregenMode = PregenMode.NONE;

    // Isolated graph for region pregen — boundary phantom-completions stay here
    // and never pollute the shared DimensionState.distanceGraph used by dynamic pregen.
    private volatile DistanceGraph regionGraph = null;

    // region-mode parameters (chunk coords)
    private volatile ResourceKey<Level> regionDimension;
    private volatile int regionMinCx, regionMinCz, regionMaxCx, regionMaxCz;

    // components
    private final TpsMonitor tpsMonitor = new TpsMonitor();
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey = null;
    private ServerLevel currentLevel = null;
    private final java.util.Map<java.util.UUID, ChunkPos> lastPlayerPositions = new java.util.concurrent.ConcurrentHashMap<>();
    /** Batches of completed columns with nothing loaded in-world yet: wait before treating as "defer". */
    private final java.util.Map<java.util.UUID, Integer> joinResyncUnloadedStreak
            = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Chunk column keys the join resync should not re-query from the distance graph (unloaded for several
     * backoffs, or loaded but with no voxy-relevant section data). Does not go into the player's
     * "synced" set — so chunk load can still send LOD when a column later loads.
     */
    private final java.util.Map<java.util.UUID, LongOpenHashSet> joinResyncCollectSkip
            = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.BooleanSupplier pauseCheck = () -> false;
    // Effective generation radius — client may inject a tighter bound via Voxy render distance
    private IntSupplier effectiveRadiusSupplier = () -> VoxyWorldGenConfig.DATA.generationRadius;

    // worker
    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    /** Throttles repeated memory-pressure log spam — one warning per 30 s. */
    private long lastMemoryPressureLogMs = 0;

    // c2me compatibility - queue ticket operations to process at safe time
    private record TicketOp(ServerLevel level, ChunkPos pos, boolean add) {}
    private final ConcurrentLinkedQueue<TicketOp> pendingTicketOps = new ConcurrentLinkedQueue<>();

    private ChunkGenerationManager() {}

    public static ChunkGenerationManager getInstance() {
        return INSTANCE;
    }

    private static boolean chunkHasRenderableData(LevelChunk chunk) {
        for (var s : chunk.getSections()) {
            if (s != null && !s.hasOnlyAir()) {
                return true;
            }
        }
        return false;
    }

    private DimensionState getOrSetupState(ServerLevel level) {
        return dimensionStates.computeIfAbsent(level.dimension(), k -> {
            DimensionState state = new DimensionState(level);
            state.tellusActive = TellusGenStub.isTellusWorld(level);
            return state;
        });
    }

    public ServerLevel getCurrentLevel() {
        return currentLevel;
    }

    public void initialize(MinecraftServer server) {
        this.server = server;
        this.running.set(true);
        this.userPaused.set(true);       // always start stopped
        this.pregenMode = PregenMode.NONE;
        this.pauseCheck = () -> false;
        VoxyWorldGenConfig.load();
        this.throttle = new Semaphore(VoxyWorldGenConfig.DATA.maxActiveTasks);
        startWorker();
        Logger.info("voxy world gen initialized (stopped — use /voxy pregen dynamic or start)");
    }

    public void shutdown() {
        running.set(false);
        stopWorker();
        TellusGenStub.shutdown();

        for (var entry : dimensionStates.entrySet()) {
            DimensionState state = entry.getValue();
            if (state.loaded) {
                ChunkPersistence.save(state.level, entry.getKey(), state.completedChunks);
            }
        }

        dimensionStates.clear();
        pendingTicketOps.clear();
        server = null;
        stats.reset();
        activeTaskCount.set(0);
        tpsMonitor.reset();
        currentDimensionKey = null;
        currentLevel = null;
        lastPlayerPositions.clear();
        pregenMode = PregenMode.NONE;
        joinResyncUnloadedStreak.clear();
        joinResyncCollectSkip.clear();
    }

    // -------------------------------------------------------------------------
    // Control API

    /** Clears 4x4 batch bookkeeping so findWorkInBounds can claim work after a new pregen start. */
    private void clearPregenBatchStateAllDimensions() {
        for (DimensionState ds : dimensionStates.values()) {
            ds.trackedBatches.clear();
            ds.batchCounters.clear();
        }
    }

    /** Start following players and generating their surroundings. */
    public void startDynamic() {
        stats.reset();
        regionGraph = null;
        clearPregenBatchStateAllDimensions();

        var players = PlayerTracker.getInstance().getPlayers();
        if (!players.isEmpty()) {
            java.util.Map<DimensionState, Integer> maxCounts = new java.util.HashMap<>();
            for (ServerPlayer player : players) {
                DimensionState state = getOrSetupState((ServerLevel) player.level());
                if (!state.loaded) {
                    ensureDimensionLoaded(state, (ServerLevel) player.level(), ((ServerLevel) player.level()).dimension());
                }
                int radius = effectiveRadius(state);
                int missing = state.distanceGraph.countMissingInRange(player.chunkPosition(), radius);
                maxCounts.merge(state, missing, Math::max);
            }
            long total = 0;
            for (int missing : maxCounts.values()) {
                total += missing;
            }
            totalTarget.set(total);
            totalRemaining.set(total);
        } else {
            totalTarget.set(0);
            totalRemaining.set(0);
        }

        pregenMode = PregenMode.DYNAMIC;
        userPaused.set(false);
        milestonesReached = 0;
        scheduleConfigReload();
        Logger.info("Voxy pregen started in dynamic mode");
    }

    /**
     * Pre-generate a square of chunks.
     * All coordinates are in <em>block</em> space; the square covers
     * {@code [centerBlockX ± blockRadius] × [centerBlockZ ± blockRadius]}.
     */
    public void startRegion(ResourceKey<Level> dimension,
                            int centerBlockX, int centerBlockZ, int blockRadius) {
        stats.reset();
        // Fresh region run — must not leave stale 4x4 batch keys that block findWorkInBounds().
        clearPregenBatchStateAllDimensions();
        regionDimension = dimension;
        regionMinCx = (centerBlockX - blockRadius) >> 4;
        regionMaxCx = (centerBlockX + blockRadius) >> 4;
        regionMinCz = (centerBlockZ - blockRadius) >> 4;
        regionMaxCz = (centerBlockZ + blockRadius) >> 4;

        // Build an isolated DistanceGraph for this region task.
        // Seeding it with already-completed chunks means we skip work that's done,
        // and any boundary phantom-completions stay here, never touching the shared
        // DimensionState.distanceGraph used by dynamic pregen.
        DistanceGraph rg = new DistanceGraph();
        int missingInRegion = 0;
        ServerLevel regionLevel = null;
        if (server != null) {
            regionLevel = server.getLevel(dimension);
            if (regionLevel != null) {
                DimensionState ds = getOrSetupState(regionLevel);
                ensureDimensionLoaded(ds, regionLevel, dimension);
                synchronized (ds.completedChunks) {
                    for (long pos : ds.completedChunks) {
                        rg.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
                    }
                }
                missingInRegion = rg.countMissingInBounds(
                        regionMinCx, regionMinCz, regionMaxCx, regionMaxCz);
                ds.remainingInRadius.set(missingInRegion);
            }
        }
        regionGraph = rg;
        totalTarget.set(missingInRegion);
        totalRemaining.set(missingInRegion);

        pregenMode = PregenMode.REGION;
        userPaused.set(false);
        milestonesReached = 0;
        scheduleConfigReload();
        // Logger joins varargs; do not use slf4j {@code {}}-style here.
        Logger.info(String.format(Locale.ROOT,
                "Voxy pregen started region: dim=%s, chunk bounds cx=[%d,%d] cz=[%d,%d] (%d chunk columns still to generate; cache may already mark many as done).",
                dimension.location(), regionMinCx, regionMaxCx, regionMinCz, regionMaxCz, missingInRegion));
        if (missingInRegion == 0) {
            var p = regionLevel != null ? ChunkPersistence.getGenerationCachePath(regionLevel, dimension) : null;
            Logger.warn("Voxy region pregen has nothing to do: every column in that range is already recorded in the "
                    + "voxy pregen cache. The task will auto-stop."
                    + (p != null ? (" Cache: " + p) : " (world/voxy_gen_*.bin)") + ".");
        }
    }

    /** Stop any active task and discard it. Requires a new start command to resume. */
    public void stop() {
        userPaused.set(true);
        PregenMode prev = pregenMode;
        pregenMode = PregenMode.NONE;
        milestonesReached = 0;
        // Discard in-flight batch tracking so a fresh start won't be confused
        for (DimensionState ds : dimensionStates.values()) {
            ds.trackedBatches.clear();
            ds.batchCounters.clear();
            ds.remainingInRadius.set(0);
        }
        regionGraph = null;
        totalTarget.set(0);
        totalRemaining.set(0);
        stats.reset();
        Logger.info("Voxy pregen stopped and task discarded (was " + prev + ")");
    }

    /** Pause mid-task — can be resumed with {@link #resume()}. */
    public void pause() {
        if (pregenMode == PregenMode.NONE) {
            Logger.warn("Voxy pregen pause called but no task is set");
            return;
        }
        userPaused.set(true);
        Logger.info("Voxy pregen paused");
    }

    /** Resume a paused task. */
    public void resume() {
        if (pregenMode == PregenMode.NONE) {
            Logger.warn("Voxy pregen resume called but no task is set — use /voxy pregen dynamic or start");
            return;
        }
        userPaused.set(false);
        Logger.info("Voxy pregen resumed");
    }

    // -------------------------------------------------------------------------
    // Worker

    private void startWorker() {
        if (workerRunning.getAndSet(true)) return;
        workerThread = new Thread(this::workerLoop, "Voxy-WorldGen-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopWorker() {
        workerRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    private void workerLoop() {
        while (workerRunning.get() && running.get()) {
            try {
                if (!VoxyWorldGenConfig.DATA.enabled || server == null || !server.isRunning()) {
                    Thread.sleep(100);
                    continue;
                }

                if (!WorldGenVoxyHooks.isGenerationUnpaused()) {
                    Thread.sleep(500);
                    continue;
                }

                if (userPaused.get() || pregenMode == PregenMode.NONE) {
                    Thread.sleep(500);
                    continue;
                }

                if (tpsMonitor.isThrottled() || pauseCheck.getAsBoolean()) {
                    Thread.sleep(500);
                    continue;
                }

                if (isMemoryPressureHigh()) {
                    long now = System.currentTimeMillis();
                    if (now - lastMemoryPressureLogMs > 30000L) {
                        lastMemoryPressureLogMs = now;
                        int active = activeTaskCount.get();
                        if (active > 0) {
                            Logger.warn("Voxy pregen pausing briefly due to high memory pressure. "
                                    + "Waiting for " + active + " active task(s) to finish and chunks to unload.");
                        } else {
                            Logger.warn("Voxy pregen pausing briefly due to high memory pressure. "
                                    + "Waiting for chunk unload to catch up.");
                        }
                    }
                    Thread.sleep(5000);
                    continue;
                }

                if (pregenMode == PregenMode.DYNAMIC) {
                    workerLoopDynamic();
                } else if (pregenMode == PregenMode.REGION) {
                    workerLoopRegion();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Logger.error("error in worker loop", e);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void workerLoopDynamic() throws InterruptedException {
        var players = new ArrayList<>(PlayerTracker.getInstance().getPlayers());
        if (players.isEmpty()) {
            Thread.sleep(100);
            return;
        }

        List<ChunkPos> batch = null;
        DimensionState activeState = null;

        for (ServerPlayer player : players) {
            DimensionState ds = getOrSetupState((ServerLevel) player.level());
            int radius = effectiveRadius(ds);
            batch = ds.distanceGraph.findWork(player.chunkPosition(), radius, ds.trackedBatches);
            if (batch != null) {
                activeState = ds;
                break;
            }
        }

        if (batch == null) {
            // Catch-up sync: collect work for ALL players in one pass, dispatch in one server.execute.
            // Key: chunk positions → list of players that still need that chunk.
            // This lets us build sections once per chunk regardless of how many players need it.
            Map<ChunkPos, List<UUID>> chunkToPlayers = new java.util.LinkedHashMap<>();

            for (ServerPlayer player : players) {
                var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
                if (synced == null) continue;

                DimensionState ds = getOrSetupState((ServerLevel) player.level());
                int radius = effectiveRadius(ds);
                List<ChunkPos> syncBatch = new ArrayList<>();
                // 256 chunks per player per pass — 4× the old cap
                ds.distanceGraph.collectCompletedInRange(player.chunkPosition(), radius, synced, syncBatch, 256);

                if (syncBatch.isEmpty()) continue;

                UUID pid = player.getUUID();
                ResourceKey<Level> dimKey = ((ServerLevel) player.level()).dimension();
                for (ChunkPos syncPos : syncBatch) {
                    // Mark synced now to avoid re-queuing while server.execute is pending
                    synced.add(syncPos.toLong());
                    chunkToPlayers.computeIfAbsent(syncPos, k -> new ArrayList<>()).add(pid);
                }
            }

            if (!chunkToPlayers.isEmpty()) {
                // Snapshot dim→level map so server.execute closure doesn't hold live player refs
                Map<UUID, ServerLevel> playerLevels = new java.util.HashMap<>();
                for (ServerPlayer player : players) {
                    playerLevels.put(player.getUUID(), (ServerLevel) player.level());
                }
                server.execute(() -> {
                    for (var entry : chunkToPlayers.entrySet()) {
                        ChunkPos syncPos = entry.getKey();
                        List<UUID> pids  = entry.getValue();

                        // Determine level from the first player (all share the same dim per collection above)
                        ServerLevel level = null;
                        for (UUID pid : pids) {
                            level = playerLevels.get(pid);
                            if (level != null) break;
                        }
                        if (level == null) continue;

                        LevelChunk c = level.getChunkSource().getChunk(syncPos.x, syncPos.z, false);
                        if (c == null) continue;

                        // Build sections once for this chunk, send to every player that needs it
                        var sections = VoxyWorldGenNetworking.buildSections(c);
                        if (sections.isEmpty()) continue;

                        int minY = c.getMinSection();
                        ResourceKey<Level> dim = level.dimension();
                        for (UUID pid : pids) {
                            ServerPlayer p = server.getPlayerList().getPlayer(pid);
                            if (p != null) {
                                VoxyWorldGenNetworking.sendLODDataPrebuilt(p, dim, syncPos, minY, sections);
                            }
                        }
                    }
                });
                // Yield briefly so the server thread can actually drain the queue
                Thread.sleep(1);
                return;
            }
            Thread.sleep(10);
            return;
        }

        dispatchBatch(activeState, batch);
    }

    private void workerLoopRegion() throws InterruptedException {
        if (server == null || !server.isRunning()) { Thread.sleep(100); return; }

        DistanceGraph rg = regionGraph;
        if (rg == null) { Thread.sleep(100); return; }

        ServerLevel level = server.getLevel(regionDimension);
        if (level == null) {
            Thread.sleep(100);
            return;
        }

        DimensionState ds = getOrSetupState(level);
        if (!ds.loaded) {
            ensureDimensionLoaded(ds, level, regionDimension);
        }

        // Use the isolated regionGraph — boundary phantom-completions stay here
        List<ChunkPos> batch = rg.findWorkInBounds(
                regionMinCx, regionMinCz, regionMaxCx, regionMaxCz, ds.trackedBatches);

        if (batch == null) {
            if (ds.remainingInRadius.get() == 0 && totalRemaining.get() == 0) {
                Logger.info("Voxy region pregen completed — all columns finished.");
                stop();
            }
            Thread.sleep(10);
            return;
        }

        dispatchBatch(ds, batch);
    }

    private void ensureDimensionLoaded(DimensionState state, ServerLevel level, ResourceKey<Level> key) {
        if (state.loaded) return;
        if (state.tellusActive) {
            Logger.info("tellus world detected for " + key + ", enabling fast generation");
        }
        ChunkPersistence.load(level, key, state.completedChunks);

        // Load persisted LOD payloads synchronously so the cache is ready before any
        // player sync runs. The file is small enough that this is fast; the slow
        // part (distance graph seeding) stays on a background thread.
        ServerLodPayloadStore.getInstance().load(level);

        state.loaded = true;

        // Seed distance graph on a background thread — avoid blocking the server tick thread.
        final DimensionState capturedState = state;
        Thread graphSeedThread = new Thread(() -> {
            synchronized (capturedState.completedChunks) {
                for (long pos : capturedState.completedChunks) {
                    capturedState.distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
                }
            }
        }, "Voxy-Graph-Seed");
        graphSeedThread.setDaemon(true);
        graphSeedThread.start();
    }

    private void dispatchBatch(DimensionState finalState, List<ChunkPos> batch) throws InterruptedException {
        long batchKey = DistanceGraph.getBatchKey(batch.get(0).x, batch.get(0).z);
        finalState.batchCounters.put(batchKey, new AtomicInteger(batch.size()));

        // Only "completed" is done voxy work. "tracked" = load still in progress — do not call
        // onSuccess here, or we mark the column done before the chunk exists and break batch/counter state.
        List<ChunkPos> preFiltered = new ArrayList<>(batch.size());
        for (ChunkPos pos : batch) {
            long key = pos.toLong();
            if (finalState.completedChunks.contains(key)) {
                onSuccess(finalState, pos);
            } else if (!finalState.trackedChunks.contains(key)) {
                preFiltered.add(pos);
            }
        }

        if (preFiltered.isEmpty()) {
            finalState.trackedBatches.remove(batchKey);
            finalState.batchCounters.remove(batchKey);
            return;
        }

        List<ChunkPos> readyToGenerate = new ArrayList<>(preFiltered.size());
        int processedCount = 0;
        for (ChunkPos pos : preFiltered) {
            if (!workerRunning.get()) break;

            boolean acquired;
            try {
                acquired = throttle.tryAcquire(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!acquired) break;

            processedCount++;
            if (finalState.trackedChunks.add(pos.toLong())) {
                activeTaskCount.incrementAndGet();
                stats.incrementQueued();

                // Hold a reference on the world engine so the idle cleaner does not shut it down
                // while chunk generation is in progress. Re-creating the world later requires
                // opening RocksDB on the server thread, which can block for 60+ seconds and crash
                // the server via ServerHangWatchdog.
                var voxyInstance = VoxyCommon.getInstance();
                if (voxyInstance != null) {
                    var worldId = WorldIdentifier.of(finalState.level);
                    if (worldId != null) {
                        voxyInstance.getOrCreate(worldId, true);
                    }
                }

                if (finalState.tellusActive) {
                    TellusGenStub.enqueueGenerate(finalState.level, pos, () -> {
                        onSuccess(finalState, pos);
                        completeTask(finalState, pos);
                    });
                    continue;
                }
                readyToGenerate.add(pos);
            } else {
                throttle.release();
                onFailure(finalState, pos);
            }
        }

        if (processedCount < preFiltered.size()) {
            finalState.trackedBatches.remove(batchKey);
            finalState.batchCounters.remove(batchKey);
        }

        if (!readyToGenerate.isEmpty()) {
            if (server == null || !server.isRunning()) {
                for (ChunkPos pos : readyToGenerate) {
                    cleanupTask(finalState.level, pos);
                }
                return;
            }
            server.execute(() -> {
                try {
                    ServerChunkCache cache = finalState.level.getChunkSource();
                    List<ChunkPos> actuallyGenerate = new ArrayList<>(readyToGenerate.size());

                    for (ChunkPos pos : readyToGenerate) {
                        if (finalState.level.hasChunk(pos.x, pos.z)) {
                            LevelChunk existingChunk = finalState.level.getChunk(pos.x, pos.z);
                            if (existingChunk != null && chunkHasRenderableData(existingChunk)) {
                                WorldGenVoxyHooks.ingestChunk(existingChunk);
                                // Cache the column for milestone sync, but do not live-broadcast during pregen.
                                var sections = VoxyWorldGenNetworking.buildSections(existingChunk);
                                if (!sections.isEmpty()) {
                                    ServerLodPayloadStore.getInstance().storeColumn(
                                            existingChunk.getLevel().dimension(), existingChunk.getPos(),
                                            existingChunk.getMinSection(), sections);
                                    if (pregenMode == PregenMode.NONE) {
                                        VoxyWorldGenNetworking.broadcastLODData(existingChunk);
                                    }
                                }
                            }
                            onSuccess(finalState, pos);
                            completeTask(finalState, pos);
                        } else {
                            queueTicketAdd(finalState.level, pos);
                            actuallyGenerate.add(pos);
                        }
                    }

                    if (!actuallyGenerate.isEmpty()) {
                        processPendingTickets();
                        for (ChunkPos pos : actuallyGenerate) {
                            ((ServerChunkCacheInvoker) cache)
                                    .invokeGetChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
                                    .whenCompleteAsync((result, throwable) -> {
                                        try {
                                            if (throwable == null && result != null && result.isSuccess()
                                                    && result.orElse(null) instanceof LevelChunk chunk) {
                                                onSuccess(finalState, pos);
                                                if (chunkHasRenderableData(chunk)) {
                                                    WorldGenVoxyHooks.ingestChunk(chunk);
                                                    // Cache the column for milestone sync, but do not live-broadcast during pregen.
                                                    var sections = VoxyWorldGenNetworking.buildSections(chunk);
                                                    if (!sections.isEmpty()) {
                                                        ServerLodPayloadStore.getInstance().storeColumn(
                                                                chunk.getLevel().dimension(), chunk.getPos(),
                                                                chunk.getMinSection(), sections);
                                                        if (pregenMode == PregenMode.NONE) {
                                                            VoxyWorldGenNetworking.broadcastLODData(chunk);
                                                        }
                                                    }
                                                }
                                            } else {
                                                onFailure(finalState, pos);
                                            }
                                            cleanupTask(finalState.level, pos);
                                        } catch (Exception e) {
                                            Logger.error("Exception in chunk generation callback for " + pos, e);
                                            cleanupTask(finalState.level, pos);
                                        }
                                    }, server);
                        }
                    }
                } catch (Exception e) {
                    Logger.error("Exception in dispatch batch server task", e);
                    for (ChunkPos pos : readyToGenerate) {
                        try {
                            cleanupTask(finalState.level, pos);
                        } catch (Exception ignored) {}
                    }
                }
            });
        }
    }

    // -------------------------------------------------------------------------
    // Tick

    public void tick() {
        if (!running.get() || server == null) return;

        processPendingTickets();

        if (configReloadScheduled.compareAndSet(true, false)) {
            VoxyWorldGenConfig.load();
            updateThrottleCapacity();
            restartScan();
        }

        tpsMonitor.tick();
        stats.tick();

        // Check for pregen progress milestones and trigger batched LOD sync
        checkMilestones();

        if (pregenMode == PregenMode.DYNAMIC) {
            checkPlayerMovement();
        }

        Set<ServerLevel> activeLevels = new HashSet<>();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            activeLevels.add((ServerLevel) player.level());
        }
        for (ServerLevel level : activeLevels) {
            ChunkUpdateTracker.getInstance().processDirty(level);
        }

        // Broadcast sync totals to all players every 5 seconds so the client
        // progress bar stays accurate as chunks get generated and the player moves.
        if (++syncTotalTickCounter >= 100) {
            syncTotalTickCounter = 0;
            for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
                VoxyWorldGenNetworking.sendSyncTotal(player);
            }
        }

        // Broadcast server pregen progress to all players every 20 ticks (1 s)
        if (isRunning() && pregenMode != PregenMode.NONE) {
            if (++serverProgressTickCounter >= 20) {
                serverProgressTickCounter = 0;
                ServerPregenProgressPayload payload = new ServerPregenProgressPayload(
                        totalTarget.get(),
                        totalRemaining.get(),
                        stats.getChunksPerSecond(),
                        activeTaskCount.get(),
                        (byte) pregenMode.ordinal(),
                        userPaused.get()
                );
                for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
                    VoxyWorldGenNetworking.safeSendToPlayer(player, payload);
                }
            }
        } else {
            serverProgressTickCounter = 0;
        }

        // Log pregen progress to server console at configured interval
        int logInterval = VoxyWorldGenConfig.DATA.pregenLogIntervalTicks;
        if (logInterval > 0 && isRunning() && pregenMode != PregenMode.NONE && !userPaused.get()) {
            if (++pregenLogTickCounter >= logInterval) {
                pregenLogTickCounter = 0;
                long target = totalTarget.get();
                long remaining = totalRemaining.get();
                double pct = target > 0 ? ((target - remaining) * 100.0 / target) : 0.0;
                double cps = stats.getChunksPerSecond();
                String cpsStr = cps > 0 ? String.format(Locale.ROOT, "%.0f", cps) : "stalled";
                String modeName = pregenMode == PregenMode.DYNAMIC ? "DYNAMIC" : "REGION";
                Logger.info(String.format(Locale.ROOT,
                        "[Pregen %s] %.1f%% complete (%d/%d left) — %s cps, %d tasks",
                        modeName, pct, remaining, target, cpsStr, activeTaskCount.get()));
            }
        } else {
            pregenLogTickCounter = 0;
        }

        // Auto-save LOD payload store periodically so natural chunk loads are not lost
        // if the server crashes while pregen is stopped.
        if (isRunning() && server != null && server.isRunning()) {
            if (++lodSaveTickCounter >= LOD_SAVE_INTERVAL_TICKS) {
                lodSaveTickCounter = 0;
                ServerLodPayloadStore.getInstance().saveAll(server);
            }
        } else {
            lodSaveTickCounter = 0;
        }
    }

    private void checkPlayerMovement() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) {
            if (!lastPlayerPositions.isEmpty()) lastPlayerPositions.clear();
            return;
        }

        boolean shouldRescan = false;
        Map<ServerLevel, Integer> levelCounts = new HashMap<>();

        for (ServerPlayer player : players) {
            levelCounts.merge((ServerLevel) player.level(), 1, Integer::sum);
            ChunkPos currentPos = player.chunkPosition();
            ChunkPos lastPos = lastPlayerPositions.get(player.getUUID());
            if (lastPos == null || distSq(lastPos, currentPos) >= 4) {
                lastPlayerPositions.put(player.getUUID(), currentPos);
                shouldRescan = true;
            }
        }

        ServerLevel majorLevel = currentLevel;
        int maxCount = levelCounts.getOrDefault(currentLevel, 0);
        for (var entry : levelCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                majorLevel = entry.getKey();
            }
        }

        if (majorLevel != currentLevel && majorLevel != null) {
            setupLevel(majorLevel);
            return;
        }

        Set<java.util.UUID> currentPlayerIds = new java.util.HashSet<>();
        for (ServerPlayer p : players) currentPlayerIds.add(p.getUUID());
        if (lastPlayerPositions.size() > currentPlayerIds.size()) {
            lastPlayerPositions.keySet().removeIf(uuid -> !currentPlayerIds.contains(uuid));
            shouldRescan = true;
        }

        if (shouldRescan) restartScan();
    }

    private double distSq(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return (double) dx * dx + dz * dz;
    }

    private void setupLevel(ServerLevel newLevel) {
        if (currentLevel != null && currentDimensionKey != null) {
            DimensionState oldState = dimensionStates.get(currentDimensionKey);
            if (oldState != null) ChunkPersistence.save(currentLevel, currentDimensionKey, oldState.completedChunks);
        }
        currentLevel = newLevel;
        currentDimensionKey = newLevel.dimension();
        DimensionState state = getOrSetupState(newLevel);
        if (!state.loaded) {
            ensureDimensionLoaded(state, newLevel, currentDimensionKey);
        }
        restartScan();
    }

    private void restartScan() {
        if (pregenMode == PregenMode.DYNAMIC) {
            var players = PlayerTracker.getInstance().getPlayers();
            if (players.isEmpty()) return;
            java.util.Map<DimensionState, Integer> maxCounts = new java.util.HashMap<>();
            for (ServerPlayer player : players) {
                DimensionState state = getOrSetupState((ServerLevel) player.level());
                int radius = effectiveRadius(state);
                int missing = state.distanceGraph.countMissingInRange(player.chunkPosition(), radius);
                maxCounts.merge(state, missing, Math::max);
            }
            maxCounts.forEach((state, count) -> state.remainingInRadius.set(count));
            long total = 0;
            for (int missing : maxCounts.values()) {
                total += missing;
            }
            totalTarget.set(total);
            totalRemaining.set(total);
        } else if (pregenMode == PregenMode.REGION && server != null) {
            DistanceGraph rg = regionGraph;
            ServerLevel level = server.getLevel(regionDimension);
            if (level != null && rg != null) {
                DimensionState ds = getOrSetupState(level);
                ds.remainingInRadius.set(
                        rg.countMissingInBounds(regionMinCx, regionMinCz, regionMaxCx, regionMaxCz));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internals

    /**
     * Returns true only when the JVM is genuinely running out of heap room.
     * A simple percentage of max memory falsely triggers on modded Minecraft
     * because the JVM commits a large heap early and freeMemory fluctuates
     * wildly depending on when the last GC ran.
     *
     * We only report pressure when BOTH are true:
     * 1. The heap is nearly fully expanded (totalMemory >= 90% of maxMemory)
     * 2. The committed heap itself is critically low on free space (< 5% free)
     *
     * Note: we intentionally do NOT call System.gc() here. Forced full GCs cause
     * long stop-the-world pauses and do not help with native/off-heap memory held
     * by Minecraft's chunk cache, which is the actual source of most pressure.
     */
    private boolean isMemoryPressureHigh() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        // Phase 1: if the heap hasn't expanded much yet, the JVM still has
        // headroom to grow totalMemory without GC pressure — don't throttle.
        if (totalMemory < maxMemory * 9L / 10L) {
            return false;
        }

        // Phase 2: heap is expanded; check if committed space is critically low.
        // < 5% free inside the committed heap means GC is struggling to find room.
        return freeMemory < totalMemory / 20L;
    }

    private void updateThrottleCapacity() {
        int target = VoxyWorldGenConfig.DATA.maxActiveTasks;
        int available = throttle.availablePermits();
        int maxPossible = available + activeTaskCount.get();
        if (target > maxPossible) throttle.release(target - maxPossible);
    }

    private void processPendingTickets() {
        TicketOp op;
        java.util.Set<ServerLevel> modifiedLevels = new java.util.HashSet<>();
        while ((op = pendingTicketOps.poll()) != null) {
            try {
                ServerChunkCache cache = op.level().getChunkSource();
                if (op.add()) {
                    cache.addRegionTicket(TicketType.FORCED, op.pos(), 0, op.pos());
                } else {
                    cache.removeRegionTicket(TicketType.FORCED, op.pos(), 0, op.pos());
                }
                modifiedLevels.add(op.level());
            } catch (Exception e) {
                Logger.error("Exception processing ticket op for " + op.pos(), e);
            }
        }
        for (ServerLevel level : modifiedLevels) {
            try {
                ((ServerChunkCacheInvoker) level.getChunkSource()).invokeRunDistanceManagerUpdates();
            } catch (Exception e) {
                Logger.error("Exception running distance manager updates for " + level.dimension(), e);
            }
        }
    }

    private void queueTicketAdd(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, true));
    }

    private void queueTicketRemove(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, false));
    }

    private void cleanupTask(ServerLevel level, ChunkPos pos) {
        queueTicketRemove(level, pos);
        DimensionState state = dimensionStates.get(level.dimension());
        if (state != null) completeTask(state, pos);
    }

    private void onSuccess(DimensionState state, ChunkPos pos) {
        long key = pos.toLong();
        if (state.completedChunks.add(key)) {
            stats.incrementCompleted();
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
            // Also mark in the isolated region graph so it converges correctly
            DistanceGraph rg = regionGraph;
            if (rg != null) rg.markChunkCompleted(pos.x, pos.z);
            state.remainingInRadius.updateAndGet(v -> Math.max(0, v - 1));
            totalRemaining.updateAndGet(v -> Math.max(0, v - 1));
        } else {
            stats.incrementSkipped();
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
            DistanceGraph rg = regionGraph;
            if (rg != null) rg.markChunkCompleted(pos.x, pos.z);
        }
        decrementBatch(state, pos);
    }

    private void onFailure(DimensionState state, ChunkPos pos) {
        stats.incrementFailed();
        state.remainingInRadius.updateAndGet(v -> Math.max(0, v - 1));
        totalRemaining.updateAndGet(v -> Math.max(0, v - 1));
        decrementBatch(state, pos);
    }

    private void decrementBatch(DimensionState state, ChunkPos pos) {
        long batchKey = DistanceGraph.getBatchKey(pos.x, pos.z);
        AtomicInteger counter = state.batchCounters.get(batchKey);
        if (counter != null && counter.decrementAndGet() <= 0) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
        }
    }

    private void completeTask(DimensionState state, ChunkPos pos) {
        if (state.trackedChunks.remove(pos.toLong())) {
            activeTaskCount.decrementAndGet();
            throttle.release();

            var instance = VoxyCommon.getInstance();
            if (instance != null) {
                var worldId = WorldIdentifier.of(state.level);
                if (worldId != null) {
                    var engine = instance.getNullable(worldId);
                    if (engine != null) {
                        try {
                            engine.releaseRef();
                        } catch (IllegalStateException e) {
                            // World was already freed; nothing to release
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Milestone helpers

    /** Check if a given percentage milestone has already been triggered. */
    private boolean isMilestoneReached(int pct) {
        int bit = pct / 25 - 1;
        return bit >= 0 && bit < 4 && (milestonesReached & (1 << bit)) != 0;
    }

    /** Mark a percentage milestone as triggered. */
    private void setMilestoneReached(int pct) {
        int bit = pct / 25 - 1;
        if (bit >= 0 && bit < 4) {
            milestonesReached |= (byte) (1 << bit);
        }
    }

    /** Compute current completion percentage (0-100). */
    private int computeProgressPercent() {
        long target = totalTarget.get();
        long remaining = totalRemaining.get();
        if (target <= 0) return 0;
        long done = target - remaining;
        return (int) ((done * 100L) / target);
    }

    /** Called from tick() to check if any new milestone has been reached. */
    private void checkMilestones() {
        if (pregenMode == PregenMode.NONE || userPaused.get()) return;
        int pct = computeProgressPercent();
        for (int milestone : new int[]{25, 50, 75, 100}) {
            if (pct >= milestone && !isMilestoneReached(milestone)) {
                setMilestoneReached(milestone);
                triggerMilestoneSync(milestone);
            }
        }
    }

    /** Send a batched delta sync to all players in active pregen dimension(s). */
    private void triggerMilestoneSync(int pct) {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) return;

        if (pregenMode == PregenMode.REGION) {
            ServerLevel level = server != null ? server.getLevel(regionDimension) : null;
            if (level == null) return;
            int scheduled = 0;
            for (ServerPlayer player : players) {
                if (player.level().dimension().equals(regionDimension)) {
                    ServerLodPayloadStore.getInstance().scheduleDeltaSync(player);
                    scheduled++;
                }
            }
            if (scheduled > 0) {
                Logger.info(String.format(Locale.ROOT,
                        "[Pregen REGION] %d%% milestone reached — scheduled LOD sync for %d player(s) in %s",
                        pct, scheduled, regionDimension.location()));
            }
        } else if (pregenMode == PregenMode.DYNAMIC) {
            // Find dimensions that still have remaining work
            Set<ResourceKey<Level>> activeDims = new HashSet<>();
            for (DimensionState ds : dimensionStates.values()) {
                if (ds.remainingInRadius.get() > 0) {
                    activeDims.add(ds.level.dimension());
                }
            }
            int scheduled = 0;
            for (ServerPlayer player : players) {
                if (activeDims.contains(player.level().dimension())) {
                    ServerLodPayloadStore.getInstance().scheduleDeltaSync(player);
                    scheduled++;
                }
            }
            if (scheduled > 0) {
                Logger.info(String.format(Locale.ROOT,
                        "[Pregen DYNAMIC] %d%% milestone reached — scheduled LOD sync for %d player(s)",
                        pct, scheduled));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public accessors

    public void scheduleConfigReload() {
        configReloadScheduled.set(true);
    }

    public boolean isChunkCompleted(ServerLevel level, ChunkPos pos) {
        DimensionState state = dimensionStates.get(level.dimension());
        return state != null && state.completedChunks.contains(pos.toLong());
    }

    public GenerationStats getStats() { return stats; }
    public boolean isRunning() { return running.get(); }
    public PregenMode getPregenMode() { return pregenMode; }
    public boolean isUserPaused() { return userPaused.get(); }
    public int getActiveTaskCount() { return activeTaskCount.get(); }

    public int getRemainingInRadius() {
        if (pregenMode == PregenMode.REGION && regionDimension != null) {
            DimensionState ds = dimensionStates.get(regionDimension);
            return ds != null ? ds.remainingInRadius.get() : 0;
        }
        if (currentDimensionKey == null) return 0;
        DimensionState state = dimensionStates.get(currentDimensionKey);
        return state != null ? state.remainingInRadius.get() : 0;
    }

    public long getTotalRemaining() {
        return totalRemaining.get();
    }

    public long getTotalTarget() {
        return totalTarget.get();
    }

    public boolean isThrottled() { return tpsMonitor.isThrottled(); }
    public int getQueueSize() { return 0; }

    public void setPauseCheck(java.util.function.BooleanSupplier check) {
        this.pauseCheck = check;
    }

    public void setEffectiveRadiusSupplier(IntSupplier supplier) {
        this.effectiveRadiusSupplier = supplier != null ? supplier : () -> VoxyWorldGenConfig.DATA.generationRadius;
        scheduleConfigReload();
    }

    private int effectiveRadius(DimensionState ds) {
        int configRadius = VoxyWorldGenConfig.DATA.generationRadius;
        int effective = Math.min(effectiveRadiusSupplier.getAsInt(), configRadius);
        return ds.tellusActive ? Math.max(effective, 128) : effective;
    }

    // Region info for overlay
    public int getRegionCenterBlockX() { return ((regionMinCx + regionMaxCx) >> 1) << 4; }
    public int getRegionCenterBlockZ() { return ((regionMinCz + regionMaxCz) >> 1) << 4; }
    public int getRegionRadiusBlocks() { return ((regionMaxCx - regionMinCx) >> 1) << 4; }

    /**
     * Push server-side voxy LOD for every completed column in this player's voxy sync radius, in small
     * batches over successive ticks. Call after login so a joiner matches clients who already had
     * those chunks (already-loaded areas do not re-fire {@link net.neoforged.neoforge.event.level.ChunkEvent.Load}).
     * <p>Uses the same distance graph and section payloads as the worldgen catch-up; runs even when
     * pregen is stopped (the background catch-up only runs with an active pregen task).
     * Must be scheduled on the main server thread; first step runs in {@code tick+1} via a {@link TickTask}.
     */
    public void scheduleJoinLodResync(java.util.UUID playerId) {
        if (server == null || !running.get()) {
            return;
        }
        // Fresh join: do not keep stale "defer" state from a previous half-finished resync
        joinResyncUnloadedStreak.remove(playerId);
        joinResyncCollectSkip.remove(playerId);
        server.tell(new TickTask(server.getTickCount() + 1, () -> runJoinResyncStep(playerId)));
    }

    public void clearJoinResyncState(java.util.UUID playerId) {
        joinResyncUnloadedStreak.remove(playerId);
        joinResyncCollectSkip.remove(playerId);
    }

    private void runJoinResyncStep(java.util.UUID playerId) {
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            clearJoinResyncState(playerId);
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            clearJoinResyncState(playerId);
            return;
        }

        DimensionState ds = getOrSetupState(level);
        ensureDimensionLoaded(ds, level, level.dimension());
        int radius = effectiveRadius(ds);
        LongSet synced = PlayerTracker.getInstance().getSyncedChunks(playerId);
        if (synced == null) {
            clearJoinResyncState(playerId);
            return;
        }

        LongSet alreadyHandledForCollect = new LongOpenHashSet(synced);
        LongOpenHashSet extra = joinResyncCollectSkip.get(playerId);
        if (extra != null) {
            alreadyHandledForCollect.addAll(extra);
        }

        List<ChunkPos> batch = new ArrayList<>();
        ds.distanceGraph.collectCompletedInRange(
                player.chunkPosition(), radius, alreadyHandledForCollect, batch, 256);
        if (batch.isEmpty()) {
            clearJoinResyncState(playerId);
            return;
        }

        int sent = 0;
        boolean anyColumnLoaded = false;
        for (ChunkPos pos : batch) {
            if (!level.hasChunk(pos.x, pos.z)) {
                continue;
            }
            anyColumnLoaded = true;
            LevelChunk c = level.getChunk(pos.x, pos.z);
            if (c == null) {
                continue;
            }
            // Delta sync may have already sent this chunk — skip duplicate.
            if (synced != null && synced.contains(pos.toLong())) {
                continue;
            }
            var sections = VoxyWorldGenNetworking.buildSections(c);
            if (sections.isEmpty()) {
                joinResyncCollectSkip
                        .computeIfAbsent(playerId, k -> new LongOpenHashSet())
                        .add(pos.toLong());
                continue;
            }
            VoxyWorldGenNetworking.sendLODDataPrebuilt(
                    player, level.dimension(), pos, c.getMinSection(), sections);
            if (synced != null) synced.add(pos.toLong());
            sent++;
        }

        if (sent > 0) {
            joinResyncUnloadedStreak.remove(playerId);
            if (server.isRunning()) {
                server.tell(new TickTask(server.getTickCount() + 1, () -> runJoinResyncStep(playerId)));
            }
            return;
        }

        if (anyColumnLoaded) {
            // All loaded columns in this batch were air-only: skip them for further collect
            // passes and try the next set on the next tick.
            joinResyncUnloadedStreak.remove(playerId);
            if (server.isRunning()) {
                server.tell(new TickTask(server.getTickCount() + 1, () -> runJoinResyncStep(playerId)));
            }
            return;
        }

        int streak = joinResyncUnloadedStreak.merge(playerId, 1, Integer::sum);
        if (streak < 6) {
            if (server.isRunning()) {
                server.tell(new TickTask(server.getTickCount() + 20, () -> runJoinResyncStep(playerId)));
            }
        } else {
            joinResyncUnloadedStreak.remove(playerId);
            LongOpenHashSet def = joinResyncCollectSkip.computeIfAbsent(
                    playerId, k -> new LongOpenHashSet());
            for (ChunkPos p : batch) {
                def.add(p.toLong());
            }
            if (server.isRunning()) {
                server.tell(new TickTask(server.getTickCount() + 1, () -> runJoinResyncStep(playerId)));
            }
        }
    }

    /**
     * Returns the total number of completed LOD chunks within the player's effective radius.
     * Forces the dimension state to be loaded from persistence if not already done, so it
     * works correctly even when pregen is stopped.
     * Must be called on the server thread.
     */
    public long computeSyncTotal(ServerLevel level, ChunkPos playerPos) {
        if (!running.get()) return 0;
        DimensionState ds = getOrSetupState(level);
        ensureDimensionLoaded(ds, level, level.dimension());
        int radius = effectiveRadius(ds);
        return ds.distanceGraph.countCompletedInRange(playerPos, radius);
    }
}
