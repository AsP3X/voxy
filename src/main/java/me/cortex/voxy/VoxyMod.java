package me.cortex.voxy;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.client.VoxyKeyBindings;
import me.cortex.voxy.client.worldgen.WorldgenProgressOverlay;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.server.VoxyServerLifecycle;
import me.cortex.voxy.server.worldgen.VoxyWorldGenBootstrap;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(VoxyMod.MODID)
public final class VoxyMod {
    public static final String MODID = "voxy";
    /** Set when {@link VoxyCommon#initNeoForge} runs. */
    public static boolean INITIALISED;

    public VoxyMod(IEventBus modEventBus, ModContainer container) {
        VoxyCommon.initNeoForge(container);
        VoxyWorldGenBootstrap.init(modEventBus);
        VoxyServerLifecycle.register(NeoForge.EVENT_BUS);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() ->
                    VoxyClient.onNeoForgeClientInit()
            ));
            modEventBus.addListener(RegisterKeyMappingsEvent.class, VoxyKeyBindings::registerKeys);
            modEventBus.addListener(RegisterGuiLayersEvent.class, event ->
                    event.registerAboveAll(
                            ResourceLocation.fromNamespaceAndPath(MODID, "worldgen_overlay"),
                            (guiGraphics, deltaTracker) ->
                                    WorldgenProgressOverlay.render(guiGraphics,
                                            deltaTracker.getGameTimeDeltaPartialTick(true))));
            // RegisterClientCommandsEvent is a NeoForge game-bus event, not IModBusEvent
            NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent evt) ->
                    evt.getDispatcher().register(
                            VoxyCommands.register(net.minecraft.client.Minecraft.getInstance().getSingleplayerServer() != null)));
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, VoxyKeyBindings::onClientTick);
        }
    }
}
