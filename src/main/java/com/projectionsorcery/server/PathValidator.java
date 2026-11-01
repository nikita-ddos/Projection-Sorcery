package com.projectionsorcery.server;

import com.projectionsorcery.Config;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server-side reference simulation. For every claimed frame it moves a body toward the claimed position using
 * real block collisions and the player's own speed / jump / gravity (infinite air jumps, 3 block step height,
 * water walking - the same rules the planning body has). A frame is accepted if it is at most
 * {@code tolerance} blocks away from where the simulation got to ({@code toleranceJumping} while airborne).
 * The simulation reads the live world, so blocks that were insta-mined earlier in the replay are really gone.
 */
final class PathValidator {
    private static final double STEP = 3.0, W = 0.6, H = 1.8;

    private final ServerLevel level;
    private double x, y, z, vy;
    private boolean onGround;
    private final double maxH, jump, gravity, tol, tolJump;

    PathValidator(ServerPlayer sp) {
        level = sp.serverLevel();
        x = sp.getX();
        y = sp.getY();
        z = sp.getZ();
        onGround = sp.onGround();
        double attr = sp.getAttributeValue(Attributes.MOVEMENT_SPEED) / (sp.isSprinting() ? 1.3 : 1.0);
        maxH = attr * 2.158 * 1.3 * 1.15; // sprint speed + 15 % slack
        MobEffectInstance j = sp.getEffect(MobEffects.JUMP);
        jump = sp.getAttributeValue(Attributes.JUMP_STRENGTH) + (j == null ? 0.0 : 0.1 * (j.getAmplifier() + 1));
        gravity = sp.getAttributeValue(Attributes.GRAVITY);
        tol = Config.PATH_TOLERANCE.get();
        tolJump = Config.PATH_TOLERANCE_JUMPING.get();
    }

    /** @param cx,cy,cz claimed FEET position of the next frame. @return false if the frame is not physically possible. */
    boolean advance(double cx, double cy, double cz) {
        double dx = cx - x, dz = cz - z;
        double hd = Math.hypot(dx, dz);
        double mx = 0, mz = 0;
        if (hd > 1.0E-6) {
            double s = Math.min(hd, maxH);
            mx = dx / hd * s;
            mz = dz / hd * s;
        }
        boolean ground = onGround || liftOnWater();
        if (cy > y + 0.2) {
            if (ground) vy = jump;
            else if (vy < 0.1) vy = jump; // air jump
        }
        move(mx, vy, mz);
        vy = (vy - gravity) * 0.98;

        double ex = cx - x, ey = cy - y, ez = cz - z;
        double dev = Math.sqrt(ex * ex + ey * ey + ez * ez);
        boolean jumping = !onGround || cy > y + 0.05;
        return dev <= (jumping ? tolJump : tol);
    }

    private boolean liftOnWater() {
        BlockPos bp = BlockPos.containing(x, y, z);
        FluidState in = level.getFluidState(bp);
        if (in.is(FluidTags.WATER)) {
            double top = bp.getY() + in.getHeight(level, bp);
            if (y < top) {
                y = top;
                if (vy < 0) vy = 0;
                return true;
            }
        }
        FluidState below = level.getFluidState(bp.below());
        if (below.is(FluidTags.WATER) && vy <= 0 && y - bp.getY() < 0.15) {
            y = bp.getY();
            return true;
        }
        return false;
    }

    private static AABB box(double x, double y, double z) {
        return new AABB(x - W / 2, y, z - W / 2, x + W / 2, y + H, z + W / 2);
    }

    private List<VoxelShape> shapes(AABB r) {
        List<VoxelShape> out = new ArrayList<>();
        int x0 = Mth.floor(r.minX), x1 = Mth.floor(r.maxX);
        int y0 = Mth.floor(r.minY) - 1, y1 = Mth.floor(r.maxY);
        int z0 = Mth.floor(r.minZ), z1 = Mth.floor(r.maxZ);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    p.set(bx, by, bz);
                    if (!level.isLoaded(p)) continue;
                    BlockState st = level.getBlockState(p);
                    VoxelShape s = st.getCollisionShape(level, p);
                    if (!s.isEmpty()) out.add(s.move(bx, by, bz));
                }
            }
        }
        return out;
    }

    private void move(double mx, double my, double mz) {
        AABB box = box(x, y, z);
        AABB region = box.expandTowards(mx, my, mz).expandTowards(0, STEP + 0.5, 0)
                .expandTowards(0, -1.0, 0).inflate(0.3, 0, 0.3);
        List<VoxelShape> sh = shapes(region);

        double ry = Shapes.collide(Direction.Axis.Y, box, sh, my);
        boolean landed = my < 0 && ry > my;
        boolean hitHead = my > 0 && ry < my;
        box = box.move(0, ry, 0);

        double rx = Shapes.collide(Direction.Axis.X, box, sh, mx);
        AABB b2 = box.move(rx, 0, 0);
        double rz = Shapes.collide(Direction.Axis.Z, b2, sh, mz);
        boolean blocked = Math.abs(rx - mx) > 1.0E-7 || Math.abs(rz - mz) > 1.0E-7;
        AABB result = b2.move(0, 0, rz);

        if (blocked && (onGround || landed)) { // step up (up to 3 blocks)
            double up = Shapes.collide(Direction.Axis.Y, box, sh, STEP);
            if (up > 0) {
                AABB u = box.move(0, up, 0);
                double sx = Shapes.collide(Direction.Axis.X, u, sh, mx);
                AABB u2 = u.move(sx, 0, 0);
                double sz = Shapes.collide(Direction.Axis.Z, u2, sh, mz);
                AABB u3 = u2.move(0, 0, sz);
                double down = Shapes.collide(Direction.Axis.Y, u3, sh, -up);
                u3 = u3.move(0, down, 0);
                if (sx * sx + sz * sz > rx * rx + rz * rz + 1.0E-7 && u3.minY >= box.minY - 1.0E-7) result = u3;
            }
        }

        x = (result.minX + result.maxX) / 2;
        y = result.minY;
        z = (result.minZ + result.maxZ) / 2;
        double probe = Shapes.collide(Direction.Axis.Y, result, sh, -0.02);
        onGround = probe > -0.02 + 1.0E-9;
        if (onGround && vy < 0) vy = 0;
        if (hitHead && vy > 0) vy = 0;
    }
}
