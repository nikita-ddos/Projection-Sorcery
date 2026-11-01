package com.projectionsorcery;

import com.projectionsorcery.net.Payloads;
import com.projectionsorcery.server.ServerLogic;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(ProjectionSorcery.MODID)
public class ProjectionSorcery {
    public static final String MODID = "projectionsorcery";

    public ProjectionSorcery(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.register(ServerLogic.class);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("2");

        // client -> server
        r.playToServer(Payloads.PlanChunk.TYPE, Payloads.PlanChunk.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerLogic.onPlan(p, sp);
        }));
        r.playToServer(Payloads.TouchPlan.TYPE, Payloads.TouchPlan.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerLogic.onTouchPlan(p, sp);
        }));
        r.playToServer(Payloads.StartRequest.TYPE, Payloads.StartRequest.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerLogic.onStartRequest(sp);
        }));
        r.playToServer(Payloads.EndSession.TYPE, Payloads.EndSession.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerLogic.onEndSession(sp);
        }));
        r.playToServer(Payloads.Jump.TYPE, Payloads.Jump.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerLogic.onJump(sp);
        }));

        // server -> client (handlers live in a tiny class that only forwards to client code)
        r.playToClient(Payloads.Session.TYPE, Payloads.Session.CODEC, ClientPayloadHandler::session);
        r.playToClient(Payloads.Ce.TYPE, Payloads.Ce.CODEC, ClientPayloadHandler::ce);
        r.playToClient(Payloads.FreezeFx.TYPE, Payloads.FreezeFx.CODEC, ClientPayloadHandler::freeze);
        r.playToClient(Payloads.TouchFx.TYPE, Payloads.TouchFx.CODEC, ClientPayloadHandler::touch);
        r.playToClient(Payloads.ReplayEnd.TYPE, Payloads.ReplayEnd.CODEC, ClientPayloadHandler::replayEnd);
    }
}
