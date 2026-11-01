package com.projectionsorcery;

import com.projectionsorcery.client.ClientEvents;
import com.projectionsorcery.net.Payloads;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Forwards server->client payloads to client-only code (loaded lazily, so dedicated servers never touch it). */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {}

    public static void session(Payloads.Session p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientEvents.onSession(p.kind(), p.maxTicks(), p.msg()));
    }

    public static void ce(Payloads.Ce p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientEvents.onCe(p.ce(), p.max(), p.infinite()));
    }

    public static void freeze(Payloads.FreezeFx p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientEvents.onFreeze(p.ticks()));
    }

    public static void touch(Payloads.TouchFx p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientEvents.onTouchFx(p.index(), p.waypoints()));
    }

    public static void replayEnd(Payloads.ReplayEnd p, IPayloadContext ctx) {
        ctx.enqueueWork(ClientEvents::onReplayEnd);
    }
}
