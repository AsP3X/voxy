package me.cortex.voxy.server.worldgen;

import me.cortex.voxy.client.worldgen.VoxyWorldGenClientReceiver;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class VoxyWorldGenBootstrap {
    private VoxyWorldGenBootstrap() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("voxy_worldgen_1");
        reg.playToClient(
                VoxyWorldGenNetworking.HandshakePayload.TYPE,
                VoxyWorldGenNetworking.HandshakePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> VoxyWorldGenClientReceiver.onHandshake(payload)));
        reg.playToClient(
                VoxyWorldGenNetworking.LodColumnPayload.TYPE,
                VoxyWorldGenNetworking.LodColumnPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> VoxyWorldGenClientReceiver.onLodColumn(payload)));
    }

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, VoxyWorldGenBootstrap::registerPayloads);
    }
}
