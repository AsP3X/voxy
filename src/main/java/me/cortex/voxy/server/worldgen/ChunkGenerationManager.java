package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.server.mixin.ServerChunkCacheInvoker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
import java.util.function.IntSupplier;

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

    // mode
    private volatile PregenMode pregenMode = PregenMode.NONE;

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
    private java.util.function.BooleanSupplier pauseCheck = () -> false;
    // Effective generation radius — client may inject a tighter bound via Voxy render distance
    private IntSupplier effectiveRadiusSupplier = () -> VoxyWorldGenConfig.DATA.generationRadius;

    // worker
    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

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
    }

    // -------------------------------------------------------------------------
    // Control API

    /** Start following players and generating their surroundings. */
    public void startDynamic() {
        pregenMode = PregenMode.DYNAMIC;
        userPaused.set(false);
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
        regionDimension = dimension;
        regionMinCx = (centerBlockX - blockRadius) >> 4;
        regionMaxCx = (centerBlockX + blockRadius) >> 4;
        regionMinCz = (centerBlockZ - blockRadius) >> 4;
        regionMaxCz = (centerBlockZ + blockRadius) >> 4;

        // Ensure dimension state is initialized so the count is available
        if (server != null) {
            ServerLevel level = server.getLevel(dimension);
            if (level != null) {
                DimensionState ds = getOrSetupState(level);
                if (!ds.loaded) {
                    ensureDimensionLoaded(ds, level, dimension);
                }
                ds.remainingInRadius.set(
                        ds.distanceGraph.countMissingInBounds(regionMinCx, regionMinCz, regionMaxCx, regionMaxCz));
            }
        }

        pregenMode = PregenMode.REGION;
        userPaused.set(false);
        Logger.info("Voxy pregen started region: dim={}, chunks=[{},{} → {},{}]",
                dimension.location(), regionMinCx, regionMinCz, regionMaxCx, regionMaxCz);
    }

    /** Stop any active task and discard it. Requires a new start command to resume. */
    public void stop() {
        userPaused.set(true);
        PregenMode prev = pregenMode;
        pregenMode = PregenMode.NONE;
        // Discard in-flight batch tracking so a fresh start won't be confused
        for (DimensionState ds : dimensionStates.values()) {
            ds.trackedBatches.clear();
            ds.batchCounters.clear();
            ds.remainingInRadius.set(0);
        }
        Logger.info("Voxy pregen stopped and task discarded (was {})", prev);
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
                if (!VoxyWorldGenConfig.DATA.enabled || server == null) {
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
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void workerLoopDynamic() throws InterruptedException {
        var players = new ArrayList<>(PlayerTracker.getInstance().getPlayers());
        if (players.isEmpty()) {
            Thread.sleep(1000);
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
            // catch up on syncing
            for (ServerPlayer player : players) {
                var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
                if (synced == null) continue;

                DimensionState ds = getOrSetupState((ServerLevel) player.level());
                int radius = effectiveRadius(ds);
                List<ChunkPos> syncBatch = new ArrayList<>();
                ds.distanceGraph.collectCompletedInRange(player.chunkPosition(), radius, synced, syncBatch, 64);

                if (!syncBatch.isEmpty()) {
                    final List<ChunkPos> finalSyncBatch = new ArrayList<>(syncBatch);
                    final ServerLevel level = ds.level;
                    final UUID playerUUID = player.getUUID();
                    for (ChunkPos syncPos : finalSyncBatch) synced.add(syncPos.toLong());
                    server.execute(() -> {
                        ServerPlayer p = server.getPlayerList().getPlayer(playerUUID);
                        if (p != null) {
                            for (ChunkPos syncPos : finalSyncBatch) {
                                LevelChunk c = level.getChunkSource().getChunk(syncPos.x, syncPos.z, false);
                                if (c != null) VoxyWorldGenNetworking.sendLODData(p, c);
                            }
                        }
                    });
                    Thread.sleep(10);
                    return;
                }
            }
            Thread.sleep(100);
            return;
        }

        dispatchBatch(activeState, batch);
    }

    private void workerLoopRegion() throws InterruptedException {
        if (server == null) { Thread.sleep(500); return; }

        ServerLevel level = server.getLevel(regionDimension);
        if (level == null) {
            Thread.sleep(1000);
            return;
        }

        DimensionState ds = getOrSetupState(level);
        if (!ds.loaded) {
            ensureDimensionLoaded(ds, level, regionDimension);
        }

        List<ChunkPos> batch = ds.distanceGraph.findWorkInBounds(
                regionMinCx, regionMinCz, regionMaxCx, regionMaxCz, ds.trackedBatches);

        if (batch == null) {
            Thread.sleep(100);
            return;
        }

        dispatchBatch(ds, batch);
    }

    private void ensureDimensionLoaded(DimensionState state, ServerLevel level, ResourceKey<Level> key) {
        if (state.loaded) return;
        if (state.tellusActive) {
            Logger.info("tellus world detected for {}, enabling fast generation", key);
        }
        ChunkPersistence.load(level, key, state.completedChunks);
        synchronized (state.completedChunks) {
            for (long pos : state.completedChunks) {
                state.distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
            }
        }
        state.loaded = true;
    }

    private void dispatchBatch(DimensionState finalState, List<ChunkPos> batch) throws InterruptedException {
        long batchKey = DistanceGraph.getBatchKey(batch.get(0).x, batch.get(0).z);
        finalState.batchCounters.put(batchKey, new AtomicInteger(batch.size()));

        List<ChunkPos> preFiltered = new ArrayList<>(batch.size());
        for (ChunkPos pos : batch) {
            long key = pos.toLong();
            if (finalState.completedChunks.contains(key) || finalState.trackedChunks.contains(key)) {
                onSuccess(finalState, pos);
            } else {
                preFiltered.add(pos);
            }
        }

        if (preFiltered.isEmpty()) {
            finalState.trackedBatches.remove(batchKey);
            finalState.batchCounters.remove(batchKey);
            return;
        }

        List<ChunkPos> readyToGenerate = new ArrayList<>();
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
            server.execute(() -> {
                ServerChunkCache cache = finalState.level.getChunkSource();
                List<ChunkPos> actuallyGenerate = new ArrayList<>();

                for (ChunkPos pos : readyToGenerate) {
                    if (finalState.level.hasChunk(pos.x, pos.z)) {
                        LevelChunk existingChunk = finalState.level.getChunk(pos.x, pos.z);
                        if (existingChunk != null && chunkHasRenderableData(existingChunk)) {
                            WorldGenVoxyHooks.ingestChunk(existingChunk);
                            VoxyWorldGenNetworking.broadcastLODData(existingChunk);
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
                                    if (throwable == null && result != null && result.isSuccess()
                                            && result.orElse(null) instanceof LevelChunk chunk) {
                                        onSuccess(finalState, pos);
                                        if (chunkHasRenderableData(chunk)) {
                                            WorldGenVoxyHooks.ingestChunk(chunk);
                                            VoxyWorldGenNetworking.broadcastLODData(chunk);
                                        }
                                    } else {
                                        onFailure(finalState, pos);
                                    }
                                    cleanupTask(finalState.level, pos);
                                }, server);
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
        } else if (pregenMode == PregenMode.REGION && server != null) {
            ServerLevel level = server.getLevel(regionDimension);
            if (level != null) {
                DimensionState ds = getOrSetupState(level);
                ds.remainingInRadius.set(
                        ds.distanceGraph.countMissingInBounds(regionMinCx, regionMinCz, regionMaxCx, regionMaxCz));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internals

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
            ServerChunkCache cache = op.level().getChunkSource();
            if (op.add()) {
                cache.addRegionTicket(TicketType.FORCED, op.pos(), 0, op.pos());
            } else {
                cache.removeRegionTicket(TicketType.FORCED, op.pos(), 0, op.pos());
            }
            modifiedLevels.add(op.level());
        }
        for (ServerLevel level : modifiedLevels) {
            ((ServerChunkCacheInvoker) level.getChunkSource()).invokeRunDistanceManagerUpdates();
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
            state.remainingInRadius.decrementAndGet();
        } else {
            stats.incrementSkipped();
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
        }
        decrementBatch(state, pos);
    }

    private void onFailure(DimensionState state, ChunkPos pos) {
        stats.incrementFailed();
        state.remainingInRadius.updateAndGet(v -> Math.max(0, v - 1));
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

    public int getTotalRemaining() {
        return dimensionStates.values().stream().mapToInt(s -> s.remainingInRadius.get()).sum();
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
}
