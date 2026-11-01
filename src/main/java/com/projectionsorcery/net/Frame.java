package com.projectionsorcery.net;

import net.minecraft.network.FriendlyByteBuf;

/** One planned frame: eye position, look angles and an optional action. */
public record Frame(double x, double y, double z, float yaw, float pitch, byte action, long blockPos) {
    public static final byte NONE = 0, STRIKE = 1, BREAK = 2;

    public void write(FriendlyByteBuf b) {
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(yaw);
        b.writeFloat(pitch);
        b.writeByte(action);
        b.writeLong(blockPos);
    }

    public static Frame read(FriendlyByteBuf b) {
        return new Frame(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readByte(), b.readLong());
    }
}
