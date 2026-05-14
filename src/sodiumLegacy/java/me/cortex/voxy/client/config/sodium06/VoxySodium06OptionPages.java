package me.cortex.voxy.client.config.sodium06;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import net.caffeinemc.mods.sodium.client.gui.options.OptionFlag;
import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpact;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatter;
import net.caffeinemc.mods.sodium.client.gui.options.control.CyclingControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Builds the Voxy {@link OptionPage} for Sodium 0.6's option GUI (mirrors {@code VoxySodiumConfigEntry}).
 */
public final class VoxySodium06OptionPages {
    private VoxySodium06OptionPages() {}

    private static final OptionStorage<VoxyConfig> VOXY_STORAGE = new OptionStorage<>() {
        @Override
        public VoxyConfig getData() {
            return VoxyConfig.CONFIG;
        }

        @Override
        public void save() {
            VoxyConfig.CONFIG.save();
        }
    };

    private static final Component[] SSAO_MODE_LABELS = {
            Component.translatable("voxy.config.general.ssao_mode.auto"),
            Component.translatable("voxy.config.general.ssao_mode.basic"),
            Component.translatable("voxy.config.general.ssao_mode.better"),
            Component.translatable("voxy.config.general.ssao_mode.best")
    };

    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX / SUBDIV_MIN) / Math.log(2);

    private static OptionPage cached;

    public static synchronized OptionPage voxyPage() {
        if (cached == null) {
            cached = buildPage();
        }
        return cached;
    }

    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN * Math.pow(2, SUBDIV_CONST * ((double) in / SUBDIV_IN_MAX)));
    }

    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double) in) / SUBDIV_MIN) / Math.log(2)) / SUBDIV_CONST) * SUBDIV_IN_MAX);
    }

    private static void reloadActiveRenderer() {
        try {
            var minecraft = Minecraft.getInstance();
            var renderer = (IGetVoxyRenderSystem) minecraft.levelRenderer;
            if (renderer != null && minecraft.level != null && VoxyConfig.CONFIG.isRenderingEnabled()) {
                renderer.voxy$shutdownRenderer();
                renderer.voxy$createRenderer();
            }
        } catch (Throwable ignored) {}

        try {
            IrisUtil.reload();
        } catch (Throwable ignored) {}
    }

    private static OptionPage buildPage() {
        VoxyConfig cfg = VoxyConfig.CONFIG;

        var gEnabled = OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(Boolean.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.enabled"))
                        .setTooltip(Component.translatable("voxy.config.general.enabled.tooltip"))
                        .setBinding((c, v) -> {
                            c.enabled = v;
                            if (v && ClientSessionEvents.inSession) {
                                VoxyCommon.createInstance();
                            }
                            if (!v) {
                                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                if (vrsh != null) {
                                    vrsh.voxy$shutdownRenderer();
                                }
                                VoxyCommon.shutdownInstance();
                            }
                            try {
                                IrisUtil.reload();
                            } catch (Throwable ignored) {}
                            VOXY_STORAGE.save();
                        }, c -> c.enabled)
                        .setControl(TickBoxControl::new)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build();

        var gThreads = OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(Integer.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.serviceThreads"))
                        .setTooltip(Component.translatable("voxy.config.general.serviceThreads.tooltip"))
                        .setBinding((c, v) -> {
                            c.serviceThreads = v;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                            VOXY_STORAGE.save();
                        }, c -> c.serviceThreads)
                        .setControl(o -> new SliderControl(o, 1, Runtime.getRuntime().availableProcessors() * 2, 1, ControlValueFormatter.number()))
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(Boolean.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.useSodiumBuilder"))
                        .setTooltip(Component.translatable("voxy.config.general.useSodiumBuilder.tooltip"))
                        .setBinding((c, v) -> {
                            c.dontUseSodiumBuilderThreads = !v;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                            VOXY_STORAGE.save();
                        }, c -> !c.dontUseSodiumBuilderThreads)
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.VARIES)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(Boolean.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.ingest"))
                        .setTooltip(Component.translatable("voxy.config.general.ingest.tooltip"))
                        .setBinding((c, v) -> {
                            c.ingestEnabled = v;
                            VOXY_STORAGE.save();
                        }, c -> c.ingestEnabled)
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .build();

        var gRender = OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(Boolean.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.rendering"))
                        .setTooltip(Component.translatable("voxy.config.general.rendering.tooltip"))
                        .setBinding((c, v) -> {
                            c.enableRendering = v;
                            var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                            if (vrsh != null) {
                                if (v) {
                                    vrsh.voxy$createRenderer();
                                } else {
                                    vrsh.voxy$shutdownRenderer();
                                }
                            }
                            try {
                                IrisUtil.reload();
                            } catch (Throwable ignored) {}
                            VOXY_STORAGE.save();
                        }, c -> c.enableRendering)
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.subDivisionSize"))
                        .setTooltip(Component.translatable("voxy.config.general.subDivisionSize.tooltip"))
                        .setBinding((c, v) -> {
                            c.subDivisionSize = ln2subDiv(v);
                            VOXY_STORAGE.save();
                        }, c -> subDiv2ln(c.subDivisionSize))
                        .setControl(o -> new SliderControl(o, 0, SUBDIV_IN_MAX, 1, v -> Component.literal(Integer.toString(Math.round(ln2subDiv(v))))))
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.renderDistance"))
                        .setTooltip(Component.translatable("voxy.config.general.renderDistance.tooltip"))
                        .setBinding((c, v) -> {
                            c.sectionRenderDistance = (int) (((float) v) / 16.0f);
                            var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                            if (vrsh != null) {
                                var vrs = vrsh.voxy$getRenderSystem();
                                if (vrs != null) {
                                    vrs.setRenderDistance(c.sectionRenderDistance);
                                }
                            }
                            ChunkGenerationManager.getInstance().scheduleConfigReload();
                            VOXY_STORAGE.save();
                        }, c -> Math.round(c.sectionRenderDistance * 16))
                        .setControl(o -> new SliderControl(o, 10, 64 * 16, 1, v -> Component.literal(Integer.toString(v * 2))))
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build();

        var gFog = OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(Boolean.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.render_fog"))
                        .setTooltip(Component.translatable("voxy.config.general.render_fog.tooltip"))
                        .setBinding((c, v) -> {
                            c.renderVanillaFog = v;
                            VOXY_STORAGE.save();
                        }, c -> c.renderVanillaFog)
                        .setControl(TickBoxControl::new)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(SSAO.SSAOMode.class, VOXY_STORAGE)
                        .setName(Component.translatable("voxy.config.general.ssao_mode"))
                        .setTooltip(Component.translatable("voxy.config.general.ssao_mode.tooltip"))
                        .setBinding((c, v) -> {
                            c.setSSAOMode(v);
                            reloadActiveRenderer();
                            VOXY_STORAGE.save();
                        }, c -> c.getSSAOMode())
                        .setControl(o -> new CyclingControl<>(o, SSAO.SSAOMode.class, SSAO_MODE_LABELS))
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build();

        return new OptionPage(
                Component.translatable("voxy.config.title"),
                ImmutableList.of(gEnabled, gThreads, gRender, gFog));
    }
}
