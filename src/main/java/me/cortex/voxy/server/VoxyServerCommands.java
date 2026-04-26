package me.cortex.voxy.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

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
                .requires(src -> src.hasPermission(2))
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
                .then(Commands.literal("start")
                        .then(Commands.argument("dimension", StringArgumentType.word())
                                .suggests((ctx, sb) -> SharedSuggestionProvider.suggest(
                                        new String[]{"overworld", "the_nether", "the_end"}, sb))
                                .then(Commands.argument("centerX", IntegerArgumentType.integer())
                                        .then(Commands.argument("centerZ", IntegerArgumentType.integer())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                                        .executes(VoxyServerCommands::startRegion))))))
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
                        }));
    }

    private static int startRegion(CommandContext<CommandSourceStack> ctx) {
        ChunkGenerationManager mgr = ChunkGenerationManager.getInstance();
        if (!mgr.isRunning()) {
            ctx.getSource().sendFailure(Component.literal("Voxy worldgen is not active on this server"));
            return 1;
        }

        String dimStr  = StringArgumentType.getString(ctx, "dimension");
        int centerX    = IntegerArgumentType.getInteger(ctx, "centerX");
        int centerZ    = IntegerArgumentType.getInteger(ctx, "centerZ");
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
}
