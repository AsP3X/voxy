package me.cortex.voxy;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.client.VoxyKeyBindings;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(VoxyMod.MODID)
public final class VoxyMod {
    public static final String MODID = "voxy";
    /** Set when {@link VoxyCommon#initNeoForge} runs. */
    public static boolean INITIALISED;

    public VoxyMod(IEventBus modEventBus, ModContainer container) {
        VoxyCommon.initNeoForge(container);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() ->
                    VoxyClient.onNeoForgeClientInit()
            ));
            // Register voxy/* client commands; execution paths still check VoxyCommon
            modEventBus.addListener((RegisterClientCommandsEvent evt) ->
                    evt.getDispatcher().register(VoxyCommands.register())
            );
            modEventBus.addListener(RegisterKeyMappingsEvent.class, VoxyKeyBindings::registerKeys);
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, VoxyKeyBindings::onClientTick);
        }
    }
}
