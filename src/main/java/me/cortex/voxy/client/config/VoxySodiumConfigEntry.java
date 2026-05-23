package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.server.worldgen.ChunkGenerationManager;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.EnumSet;

/**
 * Registers Voxy's Sodium video-settings tab via the Sodium 0.8+ config API
 * ({@link ConfigEntryPoint}), replacing mixins into the removed {@code SodiumOptionsGUI}.
 */
@ConfigEntryPointForge("voxy")
public final class VoxySodiumConfigEntry implements ConfigEntryPoint {
    /** Sodium requires this on every stateful option; we persist the shared {@link VoxyConfig} JSON file. */
    private static final StorageEventHandler VOXY_JSON_STORAGE = () -> VoxyConfig.CONFIG.save();

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

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("voxy", path);
    }

    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN * Math.pow(2, SUBDIV_CONST * ((double) in / SUBDIV_IN_MAX)));
    }

    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double) in) / SUBDIV_MIN) / Math.log(2)) / SUBDIV_CONST) * SUBDIV_IN_MAX);
    }

    /** Defaults aligned with {@link VoxyConfig} field initializers (used for Sodium reset + validation). */
    private static int defaultServiceThreads() {
        return (int) Math.max(Runtime.getRuntime().availableProcessors() * 2 / 1.5, 1);
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

    private static void persist() {
        VoxyConfig.CONFIG.save();
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        VoxyConfig cfg = VoxyConfig.CONFIG;
        String version = ModList.get().getModContainerById("voxy")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");

        var mod = builder.registerModOptions("voxy");
        mod.setName("Voxy").setVersion(version);

        var page = builder.createOptionPage();
        page.setName(Component.translatable("voxy.config.title"));

        var gEnabled = builder.createOptionGroup();
        gEnabled.addOption(builder.createBooleanOption(id("enabled"))
                .setName(Component.translatable("voxy.config.general.enabled"))
                .setTooltip(Component.translatable("voxy.config.general.enabled.tooltip"))
                .setDefaultValue(true)
                .setBinding(v -> {
                    cfg.enabled = v;
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
                    persist();
                }, () -> cfg.enabled)
                .setStorageHandler(VOXY_JSON_STORAGE)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        page.addOptionGroup(gEnabled);

        var gThreads = builder.createOptionGroup();
        gThreads.addOption(builder.createIntegerOption(id("service_threads"))
                .setName(Component.translatable("voxy.config.general.serviceThreads"))
                .setTooltip(Component.translatable("voxy.config.general.serviceThreads.tooltip"))
                .setRange(1, Runtime.getRuntime().availableProcessors() * 2, 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(v)))
                .setDefaultValue(defaultServiceThreads())
                .setBinding(v -> {
                    cfg.serviceThreads = v;
                    var instance = VoxyCommon.getInstance();
                    if (instance != null) {
                        instance.updateDedicatedThreads();
                    }
                    persist();
                }, () -> cfg.serviceThreads)
                .setStorageHandler(VOXY_JSON_STORAGE)
                .setImpact(OptionImpact.HIGH));
        gThreads.addOption(builder.createBooleanOption(id("use_sodium_builder"))
                .setName(Component.translatable("voxy.config.general.useSodiumBuilder"))
                .setTooltip(Component.translatable("voxy.config.general.useSodiumBuilder.tooltip"))
                .setImpact(OptionImpact.VARIES)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setDefaultValue(true)
                .setBinding(v -> {
                    cfg.dontUseSodiumBuilderThreads = !v;
                    var instance = VoxyCommon.getInstance();
                    if (instance != null) {
                        instance.updateDedicatedThreads();
                    }
                    persist();
                }, () -> !cfg.dontUseSodiumBuilderThreads)
                .setStorageHandler(VOXY_JSON_STORAGE));
        gThreads.addOption(builder.createBooleanOption(id("ingest"))
                .setName(Component.translatable("voxy.config.general.ingest"))
                .setTooltip(Component.translatable("voxy.config.general.ingest.tooltip"))
                .setDefaultValue(true)
                .setBinding(v -> {
                    cfg.ingestEnabled = v;
                    persist();
                }, () -> cfg.ingestEnabled)
                .setStorageHandler(VOXY_JSON_STORAGE)
                .setImpact(OptionImpact.MEDIUM));
        page.addOptionGroup(gThreads);

        var gRender = builder.createOptionGroup();
        gRender.addOption(builder.createBooleanOption(id("rendering"))
                .setName(Component.translatable("voxy.config.general.rendering"))
                .setTooltip(Component.translatable("voxy.config.general.rendering.tooltip"))
                .setDefaultValue(true)
                .setBinding(v -> {
                    cfg.enableRendering = v;
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
                    persist();
                }, () -> cfg.enableRendering)
                .setStorageHandler(VOXY_JSON_STORAGE)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        gRender.addOption(builder.createIntegerOption(id("subdivision"))
                .setName(Component.translatable("voxy.config.general.subDivisionSize"))
                .setTooltip(Component.translatable("voxy.config.general.subDivisionSize.tooltip"))
                .setRange(0, SUBDIV_IN_MAX, 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(Math.round(ln2subDiv(v)))))
                .setDefaultValue(subDiv2ln(64f))
                .setBinding(v -> {
                    cfg.subDivisionSize = ln2subDiv(v);
                    persist();
                }, () -> subDiv2ln(cfg.subDivisionSize))
                .setStorageHandler(VOXY_JSON_STORAGE)
                .setImpact(OptionImpact.HIGH));
        gRender.addOption(builder.createIntegerOption(id("render_distance"))
                .setName(Component.translatable("voxy.config.general.renderDistance"))
                .setTooltip(Component.translatable("voxy.config.general.renderDistance.tooltip"))
                .setRange(10, 64 * 16, 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(v * 2)))
                .setDefaultValue(256)
                .setBinding(v -> {
                    cfg.sectionRenderDistance = (int) (((float) v) / 16.0f);
                    var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                    if (vrsh != null) {
                        var vrs = vrsh.voxy$getRenderSystem();
                        if (vrs != null) {
                            vrs.setRenderDistance(cfg.sectionRenderDistance);
                        }
                    }
                    ChunkGenerationManager.getInstance().scheduleConfigReload();
                    persist();
                }, () -> Math.round(cfg.sectionRenderDistance * 16))
                .setStorageHandler(VOXY_JSON_STORAGE)
                .setImpact(OptionImpact.LOW)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        page.addOptionGroup(gRender);

        var gFog = builder.createOptionGroup();
        gFog.addOption(builder.createBooleanOption(id("render_fog"))
                .setName(Component.translatable("voxy.config.general.render_fog"))
                .setTooltip(Component.translatable("voxy.config.general.render_fog.tooltip"))
                .setDefaultValue(true)
                .setBinding(v -> {
                    cfg.renderVanillaFog = v;
                    persist();
                }, () -> cfg.renderVanillaFog)
                .setStorageHandler(VOXY_JSON_STORAGE)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        gFog.addOption(builder.createEnumOption(id("ssao_mode"), SSAO.SSAOMode.class)
                .setName(Component.translatable("voxy.config.general.ssao_mode"))
                .setTooltip(Component.translatable("voxy.config.general.ssao_mode.tooltip"))
                .setAllowedValues(EnumSet.allOf(SSAO.SSAOMode.class))
                .setElementNameProvider(m -> switch (m) {
                    case AUTO -> SSAO_MODE_LABELS[0];
                    case BASIC -> SSAO_MODE_LABELS[1];
                    case BETTER -> SSAO_MODE_LABELS[2];
                    case BEST -> SSAO_MODE_LABELS[3];
                })
                .setDefaultValue(SSAO.SSAOMode.AUTO)
                .setBinding(v -> {
                    cfg.setSSAOMode(v);
                    reloadActiveRenderer();
                    persist();
                }, cfg::getSSAOMode)
                .setStorageHandler(VOXY_JSON_STORAGE)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        page.addOptionGroup(gFog);

        mod.addPage(page);
    }
}
