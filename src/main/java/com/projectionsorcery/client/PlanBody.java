package com.projectionsorcery.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Detached player body used while planning. Never added to the world; ignores powder snow / block slowdowns. */
final class PlanBody extends RemotePlayer {
    PlanBody(ClientLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Override
    public void makeStuckInBlock(BlockState state, Vec3 multiplier) {
        // not slowed by powder snow, cobwebs, berries...
    }

    @Override
    protected float getBlockSpeedFactor() {
        return 1.0F; // not slowed by soul sand / honey
    }
}