package me.cortex.voxy.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import me.cortex.voxy.server.worldgen.PlayerSyncStateStore;
import me.cortex.voxy.server.worldgen.ServerLodPayloadStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import me.cortex.voxy.server.worldgen.PlayerTracker;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side /voxy commands registered via RegisterCommandsEvent.
 * These run on the server thread and are safe to execute on both dedicated
 * servers and the integrated server (singleplayer). No client APIs are used.
 *
 * Client-only commands (import, reload, debug, overlay) remain in VoxyCommands
 * and are registered via RegisterClientCommandsEvent.
 */
public final class VoxyServerCommands {
    private VoxyServerCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("voxy")
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(buildPregen());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildPregen() {
        return Commands.literal("pregen")
                // /voxy pregen dynamic enable|disable
                .then(Commands.literal("dynamic")
                        .then(Commands.literal("enable")
                                .executes(ctx -> {
                                    ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
                                    if (!mgr.isRunning()) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "Voxy worldgen is not active on this server"));
                                        return 1;
                                    }
                                    mgr.startDynamic();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy pre-generation started (dynamic \u2014 follows players)"), true);
                                    return 0;
                                }))
                        .then(Commands.literal("disable")
                                .executes(ctx -> {
                                    ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
                                    if (!mgr.isRunning()) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "Voxy worldgen is not active on this server"));
                                        return 1;
                                    }
                                    if (mgr.getPregenMode() != ChunkGenerationManager.PregenMode.DYNAMIC) {
                                        ctx.getSource().sendFailure(Component.literal(
                                                "Dynamic pre-generation is not running"));
                                        return 1;
                                    }
                                    mgr.stop();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Voxy dynamic pre-generation stopped"), true);
                                    return 0;
                                })))
                // /voxy pregen start <dimension> <centerX> <centerZ> <radius>
                // centerX and centerZ support ~ for the source's current position
                .then(Commands.literal("start")
                        .then(Commands.argument("dimension", StringArgumentType.word())
                                .suggests((ctx, sb) -> {
                                    java.util.List<String> dims = new java.util.ArrayList<>();
                                    var server = ctx.getSource().getServer();
                                    if (server != null) {
                                        for (var level : server.getAllLevels()) {
                                            var loc = level.dimension().location();
                                            dims.add("minecraft".equals(loc.getNamespace()) ? loc.getPath() : loc.toString());
                                    }
                                    } else {
                                        dims.addAll(java.util.List.of(
                                                "overworld", "the_nether", "the_end"));
                                    }
                                    return SharedSuggestionProvider.suggest(dims, sb);
                                })
                                .then(Commands.argument("center", ColumnPosArgument.columnPos())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                                .executes(VoxyServerCommands::startRegion)))))
                // /voxy pregen stop
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
                            if (!mgr.isRunning()) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "Voxy worldgen is not active on this server"));
                                return 1;
                            }
                            if (mgr.getPregenMode() == ChunkGenerationManager.PregenMode.NONE) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "No pre-generation task is running"));
                                return 1;
                            }
                            mgr.stop();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Voxy pre-generation stopped and task discarded"), true);
                            return 0;
                        }))
                // /voxy pregen pause
                .then(Commands.literal("pause")
                        .executes(ctx -> {
                            ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
                            if (!mgr.isRunning()) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "Voxy worldgen is not active on this server"));
                                return 1;
                            }
                            if (mgr.getPregenMode() == ChunkGenerationManager.PregenMode.NONE) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "No pre-generation task is running"));
                                return 1;
                            }
                            if (mgr.isUserPaused()) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "Pre-generation is already paused"));
                                return 1;
                            }
                            mgr.pause();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Voxy pre-generation paused"), true);
                            return 0;
                        }))
                // /voxy pregen resume
                .then(Commands.literal("resume")
                        .executes(ctx -> {
                            ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
                            if (!mgr.isRunning()) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "Voxy worldgen is not active on this server"));
                                return 1;
                            }
                            if (mgr.getPregenMode() == ChunkGenerationManager.PregenMode.NONE) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "No task set \u2014 use /voxy pregen dynamic enable or /voxy pregen start"));
                                return 1;
                            }
                            if (!mgr.isUserPaused()) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "Pre-generation is already running"));
                                return 1;
                            }
                            mgr.resume();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Voxy pre-generation resumed"), true);
                            return 0;
                        }))
                // /voxy pregen resync [player]
                .then(Commands.literal("resync")
                        .executes(VoxyServerCommands::resyncSelf)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(VoxyServerCommands::resyncTarget)));
    }

    private static int startRegion(CommandContext<CommandSourceStack> ctx) {
        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        if (!mgr.isRunning()) {
            ctx.getSource().sendFailure(Component.literal("Voxy worldgen is not active on this server"));
            return 1;
        }

        String dimStr  = StringArgumentType.getString(ctx, "dimension");
        Vec3 center    = ctx.getArgument("center", Coordinates.class).getPosition(ctx.getSource());
        int centerX    = (int) center.x;
        int centerZ    = (int) center.z;
        int radius     = IntegerArgumentType.getInteger(ctx, "radius");

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
                dimStr, centerX, centerZ, radius)), true);
        return 0;
    }

    private static int resyncSelf(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer self = ctx.getSource().getPlayer();
        if (self == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "This command must be run by a player, or specify a target player"));
            return 1;
        }
        return doResync(ctx, self);
    }

    private static int resyncTarget(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer target;
        try {
            target = EntityArgument.getPlayer(ctx, "player");
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Invalid target player"));
            return 1;
        }
        return doResync(ctx, target);
    }

    private static int doResync(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        if (!mgr.isRunning()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Voxy worldgen is not active on this server"));
            return 1;
        }
        PlayerSyncStateStore.getInstance().resetWatermarks(target.getUUID(), target.getServer());
        var synced = PlayerTracker.getInstance().getSyncedChunks(target.getUUID());
        if (synced != null) synced.clear();
        // Use the persistent payload store only, not live in-memory chunks.
        ServerLodPayloadStore.getInstance().scheduleDeltaSync(target);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Voxy LOD resync started for " + target.getName().getString()), true);
        return 0;
    }
}
