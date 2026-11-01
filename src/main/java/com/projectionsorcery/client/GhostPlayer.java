package com.projectionsorcery.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;

/** Afterimage body: never shows a name tag. */
final class GhostPlayer extends RemotePlayer {
    GhostPlayer(ClientLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }
}