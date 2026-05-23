package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.worldgen.NetworkState;
import me.cortex.voxy.client.worldgen.WorldgenProgressOverlay;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import me.cortex.voxy.server.worldgen.VoxyWorldGenNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class VoxyCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register(boolean singleplayer) {
        var imports = Commands.literal("import")
                .then(Commands.literal("world")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(Commands.literal("bobby")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(Commands.literal("raw")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(Commands.literal("zip")
                        .then(Commands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(Commands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(Commands.literal("current")
                        .executes(VoxyCommands::importCurrentWorldIn))
                .then(Commands.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(Commands.literal("distant_horizons")
                            .then(Commands.argument("sqlDbPath", StringArgumentType.string())
                                    .executes(VoxyCommands::importDistantHorizons)));
        }

        var debug = Commands.literal("debug")
                .then(Commands.literal("verifyTLNChildMask")
                        .executes(ctx -> verifyTLNs(ctx, false))
                        .then(Commands.argument("attemptRepair", BoolArgumentType.bool())
                                .executes(ctx -> verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair"))))
                );

        var overlay = Commands.literal("overlay")
                .then(Commands.literal("progress")
                        .then(Commands.literal("enabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setProgressVisible(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy worldgen progress overlay enabled"), false);
                                    return 0;
                                }))
                        .then(Commands.literal("disabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setProgressVisible(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy worldgen progress overlay disabled"), false);
                                    return 0;
                                }))
                        .then(Commands.literal("memory_pressure_indicator")
                                .then(Commands.literal("enabled")
                                        .executes(ctx -> {
                                            WorldgenProgressOverlay.setMemoryPressureVisible(true);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Voxy memory pressure indicator enabled"), false);
                                            return 0;
                                        }))
                                .then(Commands.literal("disabled")
                                        .executes(ctx -> {
                                            WorldgenProgressOverlay.setMemoryPressureVisible(false);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Voxy memory pressure indicator disabled"), false);
                                            return 0;
                                        })))
                        .then(Commands.literal("server_progress")
                                .then(Commands.literal("enabled")
                                        .executes(ctx -> {
                                            WorldgenProgressOverlay.setServerProgressVisible(true);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Voxy server progress overlay enabled"), false);
                                            return 0;
                                        }))
                                .then(Commands.literal("disabled")
                                        .executes(ctx -> {
                                            WorldgenProgressOverlay.setServerProgressVisible(false);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Voxy server progress overlay disabled"), false);
                                            return 0;
                                        }))))
                .then(Commands.literal("server_sync")
                        .then(Commands.literal("enabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setSyncVisible(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy server sync overlay enabled"), false);
                                    return 0;
                                }))
                        .then(Commands.literal("disabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setSyncVisible(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy server sync overlay disabled"), false);
                                    return 0;
                                })))
                .then(Commands.literal("sync")
                        .then(Commands.literal("enabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setSyncVisible(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy LOD sync overlay enabled"), false);
                                    return 0;
                                }))
                        .then(Commands.literal("disabled")
                                .executes(ctx -> {
                                    WorldgenProgressOverlay.setSyncVisible(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy LOD sync overlay disabled"), false);
                                    return 0;
                                })));

        var voxy = Commands.literal("voxy")
                .then(Commands.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(debug)
                .then(overlay);
        if (singleplayer) {
            voxy = voxy.then(buildPregen());
        }
        return voxy
                .then(Commands.literal("resync")
                        .executes(ctx -> {
                            if (!NetworkState.isServerConnected()) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "Not connected to a Voxy-aware server"));
                                return 1;
                            }
                            PacketDistributor.sendToServer(
                                    new VoxyWorldGenNetworking.ClientRequestResyncPayload());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Voxy LOD resync requested"), false);
                            return 0;
                        }));
    }

    // ---------------------------------------------------------------------------
    // Pregen — mirrors VoxyServerCommands.buildPregen() so that the client-side
    // /voxy dispatcher (which takes priority over the server-side one) exposes
    // these subcommands. Only works in singleplayer (integrated server).
    // ---------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> buildPregen() {
        return Commands.literal("pregen")
                .then(Commands.literal("dynamic")
                        .then(Commands.literal("enable")
                                .executes(ctx -> {
                                    ChunkGenerationManager mgr = requirePregen(ctx);
                                    if (mgr == null) return 1;
                                    mgr.startDynamic();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy pre-generation started (dynamic — follows players)"), false);
                                    return 0;
                                }))
                        .then(Commands.literal("disable")
                                .executes(ctx -> {
                                    ChunkGenerationManager mgr = requirePregen(ctx);
                                    if (mgr == null) return 1;
                                    if (mgr.getPregenMode() != ChunkGenerationManager.PregenMode.DYNAMIC) {
                                        ctx.getSource().sendFailure(Component.literal("Dynamic pre-generation is not running"));
                                        return 1;
                                    }
                                    mgr.stop();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy dynamic pre-generation stopped"), false);
                                    return 0;
                                })))
                .then(Commands.literal("start")
                        .then(Commands.argument("dimension", StringArgumentType.word())
                                .suggests((ctx, sb) -> {
                                    var ssp = Minecraft.getInstance().getSingleplayerServer();
                                    java.util.List<String> dims = new java.util.ArrayList<>();
                                    if (ssp != null) {
                                        for (var level : ssp.getAllLevels())
                                            dims.add(level.dimension().location().toString());
                                    } else {
                                        dims.addAll(java.util.List.of(
                                                "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
                                    }
                                    return SharedSuggestionProvider.suggest(dims, sb);
                                })
                                .then(Commands.argument("center", ColumnPosArgument.columnPos())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                                .executes(VoxyCommands::startRegion)))))
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            ChunkGenerationManager mgr = requirePregen(ctx);
                            if (mgr == null) return 1;
                            if (mgr.getPregenMode() == ChunkGenerationManager.PregenMode.NONE) {
                                ctx.getSource().sendFailure(Component.literal("No pre-generation task is running"));
                                return 1;
                            }
                            mgr.stop();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Voxy pre-generation stopped and task discarded"), false);
                            return 0;
                        }))
                .then(Commands.literal("pause")
                        .executes(ctx -> {
                            ChunkGenerationManager mgr = requirePregen(ctx);
                            if (mgr == null) return 1;
                            if (mgr.getPregenMode() == ChunkGenerationManager.PregenMode.NONE) {
                                ctx.getSource().sendFailure(Component.literal("No pre-generation task is running"));
                                return 1;
                            }
                            if (mgr.isUserPaused()) {
                                ctx.getSource().sendFailure(Component.literal("Pre-generation is already paused"));
                                return 1;
                            }
                            mgr.pause();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Voxy pre-generation paused"), false);
                            return 0;
                        }))
                .then(Commands.literal("resume")
                        .executes(ctx -> {
                            ChunkGenerationManager mgr = requirePregen(ctx);
                            if (mgr == null) return 1;
                            if (mgr.getPregenMode() == ChunkGenerationManager.PregenMode.NONE) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "No task set — use /voxy pregen dynamic enable or /voxy pregen start"));
                                return 1;
                            }
                            if (!mgr.isUserPaused()) {
                                ctx.getSource().sendFailure(Component.literal("Pre-generation is already running"));
                                return 1;
                            }
                            mgr.resume();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Voxy pre-generation resumed"), false);
                            return 0;
                        }));
    }

    private static ChunkGenerationManager requirePregen(CommandContext<CommandSourceStack> ctx) {
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Voxy pregen is only available in singleplayer. On a server, use /voxy pregen as an operator."));
            return null;
        }
        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        if (!mgr.isRunning()) {
            ctx.getSource().sendFailure(Component.literal("Voxy worldgen is not active"));
            return null;
        }
        return mgr;
    }

    private static int startRegion(CommandContext<CommandSourceStack> ctx) {
        ChunkGenerationManager mgr = requirePregen(ctx);
        if (mgr == null) return 1;

        String dimStr = StringArgumentType.getString(ctx, "dimension");
        Vec3 center   = ctx.getArgument("center", Coordinates.class).getPosition(ctx.getSource());
        int centerX   = (int) center.x;
        int centerZ   = (int) center.z;
        int radius    = IntegerArgumentType.getInteger(ctx, "radius");

        String dimLocation = dimStr.contains(":") ? dimStr : "minecraft:" + dimStr;
        ResourceKey<Level> dimKey;
        try {
            dimKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(dimLocation));
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Unknown dimension: " + dimStr));
            return 1;
        }

        mgr.startRegion(dimKey, centerX, centerZ, radius);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "Voxy pre-generation started: %s [%d,%d] radius %d blocks",
                dimStr, centerX, centerZ, radius)), false);
        return 0;
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr != null) {
            ((IGetVoxyRenderSystem) wr).voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) {
            r.allChanged();
        }
        return 0;
    }

    private static int verifyTLNs(CommandContext<CommandSourceStack> ctx, boolean attemptRepair) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }

    private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine == null) {
            return 1;
        }
        return instance.getImportManager().makeAndRunIfNone(engine, () ->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter)) ? 0 : 1;
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine == null) {
            return false;
        }
        return instance.getImportManager().makeAndRunIfNone(engine, () -> {
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class))) ? 0 : 1;
    }

    private static int importBobby(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file) ? 0 : 1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }

    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0, str.length() - 1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx + 1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx + 1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"' + wn)) {
                    wn = str + wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException ignored) {
        }

        return sb.buildFuture();
    }

    private static int importCurrentWorldIn(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendFailure(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if (!regionPath.toFile().exists() || !regionPath.toFile().isDirectory()) {
            ctx.getSource().sendFailure(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(regionPath.toFile()) ? 0 : 1;
    }

    private static int importWorld(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) {
                return 1;
            }
            return fileBasedImporter(dimFile) ? 0 : 1;
        } else {
            if (!name.endsWith("region")) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<CommandSourceStack> ctx) {
        var zip = new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception ignored) {
        }

        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world) ? 0 : 1;
        }
        return 1;
    }
}
