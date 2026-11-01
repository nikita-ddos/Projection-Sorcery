package com.projectionsorcery.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

final class Sim {
    final Entity body;
    final boolean hero;
    final double speed;      
    final double jumpPower;
    final double gravity;
    double vy;
    double traveled;

    private Sim(Entity body, boolean hero, double speed, double jumpPower, double gravity) {
        this.body = body;
        this.hero = hero;
        this.speed = speed;
        this.jumpPower = jumpPower;
        this.gravity = gravity;
    }

    private static double jumpBonus(LivingEntity e) {
        MobEffectInstance j = e.getEffect(MobEffects.JUMP);
        return j == null ? 0.0 : 0.1 * (j.getAmplifier() + 1);
    }

    static Sim hero(Minecraft mc) {
        LocalPlayer p = mc.player;
        PlanBody b = new PlanBody(mc.level, p.getGameProfile());
        b.moveTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        
        b.noPhysics = false;
        b.setInvisible(false);
        
        // Direct 500% movement speed
        AttributeInstance speedAttr = b.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) speedAttr.setBaseValue(0.5);
        
        AttributeInstance step = b.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) step.setBaseValue(3.0);
        
        double attr = 0.5; 
        double jump = p.getAttributeValue(Attributes.JUMP_STRENGTH) + jumpBonus(p);
        return new Sim(b, true, attr * 2.158, jump, p.getAttributeValue(Attributes.GRAVITY));
    }

    static Entity copyOf(Minecraft mc, LivingEntity v, boolean ghost) {
        Entity b;
        if (v instanceof Player pl) b = ghost ? new GhostPlayer(mc.level, pl.getGameProfile()) : new RemotePlayer(mc.level, pl.getGameProfile());
        else b = v.getType().create(mc.level);
        if (b == null) return null;
        b.moveTo(v.getX(), v.getY(), v.getZ(), v.getYRot(), v.getXRot());
        b.noPhysics = false;
        b.setInvisible(false);
        
        if (b instanceof LivingEntity le) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                le.setItemSlot(slot, v.getItemBySlot(slot));
            }
        }
        return b;
    }

    static Sim victim(Minecraft mc, LivingEntity v) {
        Entity b = copyOf(mc, v, false);
        if (b == null) return null;
        double attr = v.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (attr <= 0.0) attr = 0.1;
        double jump = v.getAttributeValue(Attributes.JUMP_STRENGTH);
        if (jump <= 0.0) jump = 0.42;
        return new Sim(b, false, attr * 2.158, jump + jumpBonus(v), v.getAttributeValue(Attributes.GRAVITY));
    }

    void setRot(float yaw, float pitch, float yawO, float pitchO) {
        body.setYRot(yaw);
        body.setXRot(pitch);
        body.yRotO = yawO;
        body.xRotO = pitchO;
        if (body instanceof LivingEntity le) {
            le.yBodyRot = yaw;
            le.yHeadRot = yaw;
            le.yBodyRotO = yawO;
            le.yHeadRotO = yawO;
        }
    }

    private boolean liftOnWater(Level level) {
        BlockPos bp = body.blockPosition();
        FluidState inside = level.getFluidState(bp);
        if (inside.is(FluidTags.WATER)) {
            double top = bp.getY() + inside.getHeight(level, bp);
            if (body.getY() < top) {
                body.setPos(body.getX(), top, body.getZ());
                vy = Math.max(vy, 0) > 0 ? vy : 0;
                return true;
            }
        }
        FluidState below = level.getFluidState(bp.below());
        if (below.is(FluidTags.WATER) && vy <= 0 && body.getY() - bp.getY() < 0.15) {
            body.setPos(body.getX(), bp.getY(), body.getZ());
            return true;
        }
        return false;
    }

    boolean tick(Minecraft mc, double forward, double strafe, float yaw, boolean jumpHeld, int clicks,
                 boolean sprint, boolean canAirJump) {
        Level level = mc.level;
        body.xo = body.xOld = body.getX();
        body.yo = body.yOld = body.getY();
        body.zo = body.zOld = body.getZ();
        Vec3 before = body.position();

        Vec3 f = Vec3.directionFromRotation(0, yaw);
        Vec3 r = Vec3.directionFromRotation(0, yaw + 90);
        Vec3 dir = f.scale(forward).add(r.scale(strafe));
        if (dir.lengthSqr() > 1) dir = dir.normalize();
        double spd = speed * (sprint && forward > 0 ? 1.3 : 1.0);

        boolean onGround = body.onGround();
        if (hero && liftOnWater(level)) onGround = true;

        boolean air = false;
        if (onGround) {
            if (jumpHeld || clicks > 0) vy = jumpPower;
        } else if (hero && clicks > 0 && canAirJump) {
            vy = jumpPower;
            air = true;
        }

        body.move(MoverType.SELF, new Vec3(dir.x * spd, vy, dir.z * spd));
        if (body.verticalCollision) vy = 0;
        vy = (vy - gravity) * 0.98;

        traveled += before.distanceTo(body.position());
        return air;
    }
}