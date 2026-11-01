package com.projectionsorcery.net;

import com.projectionsorcery.ProjectionSorcery;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** All network payloads of the mod. */
public final class Payloads {
    private Payloads() {}

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ProjectionSorcery.MODID, path));
    }

    static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(BiConsumer<T, FriendlyByteBuf> enc, Function<FriendlyByteBuf, T> dec) {
        return new StreamCodec<>() {
            @Override
            public T decode(RegistryFriendlyByteBuf buf) {
                return dec.apply(buf);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, T value) {
                enc.accept(value, buf);
            }
        };
    }

    static void writeVecs(FriendlyByteBuf b, List<Vec3> l) {
        b.writeVarInt(l.size());
        for (Vec3 v : l) {
            b.writeDouble(v.x);
            b.writeDouble(v.y);
            b.writeDouble(v.z);
        }
    }

    static List<Vec3> readVecs(FriendlyByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > 256) throw new IllegalArgumentException("Bad list size " + n);
        List<Vec3> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(new Vec3(b.readDouble(), b.readDouble(), b.readDouble()));
        return l;
    }

    // ---------------- client -> server ----------------

    /** A chunk of the plan; plans of any length are streamed in chunks (packet size limit). */
    public record PlanChunk(boolean first, boolean execute, List<Frame> frames) implements CustomPacketPayload {
        public static final int CHUNK = 600;
        public static final Type<PlanChunk> TYPE = Payloads.type("plan");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlanChunk> CODEC = codec(
                (p, b) -> {
                    b.writeBoolean(p.first);
                    b.writeBoolean(p.execute);
                    b.writeVarInt(p.frames.size());
                    for (Frame f : p.frames) f.write(b);
                },
                b -> {
                    boolean first = b.readBoolean();
                    boolean exec = b.readBoolean();
                    int n = b.readVarInt();
                    if (n < 0 || n > CHUNK) throw new IllegalArgumentException("Bad chunk size " + n);
                    List<Frame> l = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) l.add(Frame.read(b));
                    return new PlanChunk(first, exec, l);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The victim plan made with the Touch key; sent right before the plan chunks. */
    public record TouchPlan(int victimId, List<Vec3> waypoints) implements CustomPacketPayload {
        public static final Type<TouchPlan> TYPE = Payloads.type("touch_plan");
        public static final StreamCodec<RegistryFriendlyByteBuf, TouchPlan> CODEC = codec(
                (p, b) -> {
                    b.writeVarInt(p.victimId);
                    writeVecs(b, p.waypoints);
                },
                b -> new TouchPlan(b.readVarInt(), readVecs(b)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record StartRequest() implements CustomPacketPayload {
        public static final StartRequest INSTANCE = new StartRequest();
        public static final Type<StartRequest> TYPE = Payloads.type("start_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, StartRequest> CODEC = codec((p, b) -> {}, b -> INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record EndSession() implements CustomPacketPayload {
        public static final EndSession INSTANCE = new EndSession();
        public static final Type<EndSession> TYPE = Payloads.type("end_session");
        public static final StreamCodec<RegistryFriendlyByteBuf, EndSession> CODEC = codec((p, b) -> {}, b -> INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sent for every in-air jump while planning (hunger cost is applied server side). */
    public record Jump() implements CustomPacketPayload {
        public static final Jump INSTANCE = new Jump();
        public static final Type<Jump> TYPE = Payloads.type("jump");
        public static final StreamCodec<RegistryFriendlyByteBuf, Jump> CODEC = codec((p, b) -> {}, b -> INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------- server -> client ----------------

    /** kind: 0 = denied, 1 = granted, 2 = time is up (execute now). */
    public record Session(int kind, int maxTicks, String msg) implements CustomPacketPayload {
        public static final Type<Session> TYPE = Payloads.type("session");
        public static final StreamCodec<RegistryFriendlyByteBuf, Session> CODEC = codec(
                (p, b) -> {
                    b.writeVarInt(p.kind);
                    b.writeVarInt(p.maxTicks);
                    b.writeUtf(p.msg, 256);
                },
                b -> new Session(b.readVarInt(), b.readVarInt(), b.readUtf(256)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Ce(int ce, int max, boolean infinite) implements CustomPacketPayload {
        public static final Type<Ce> TYPE = Payloads.type("ce");
        public static final StreamCodec<RegistryFriendlyByteBuf, Ce> CODEC = codec(
                (p, b) -> {
                    b.writeVarInt(p.ce);
                    b.writeVarInt(p.max);
                    b.writeBoolean(p.infinite);
                },
                b -> new Ce(b.readVarInt(), b.readVarInt(), b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record FreezeFx(int ticks) implements CustomPacketPayload {
        public static final Type<FreezeFx> TYPE = Payloads.type("freeze_fx");
        public static final StreamCodec<RegistryFriendlyByteBuf, FreezeFx> CODEC = codec(
                (p, b) -> b.writeVarInt(p.ticks), b -> new FreezeFx(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sent to a touched player: the blocks they must follow and the next index. Empty list = effect over. */
    public record TouchFx(int index, List<Vec3> waypoints) implements CustomPacketPayload {
        public static final Type<TouchFx> TYPE = Payloads.type("touch_fx");
        public static final StreamCodec<RegistryFriendlyByteBuf, TouchFx> CODEC = codec(
                (p, b) -> {
                    b.writeVarInt(p.index);
                    writeVecs(b, p.waypoints);
                },
                b -> new TouchFx(b.readVarInt(), readVecs(b)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sent to the planner when their replay finishes (normal end, out of CE, or rejected path). */
    public record ReplayEnd() implements CustomPacketPayload {
        public static final ReplayEnd INSTANCE = new ReplayEnd();
        public static final Type<ReplayEnd> TYPE = Payloads.type("replay_end");
        public static final StreamCodec<RegistryFriendlyByteBuf, ReplayEnd> CODEC = codec((p, b) -> {}, b -> INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}