package com.projectionsorcery.client;

import com.projectionsorcery.net.Frame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class PathPlanner {
    public static final double WALK_STEP = 0.28;   
    public static final double BLINK_STEP = WALK_STEP; 
    private static final int MAX_NODES = 6000;
    private static final float EYE = 1.62F;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[] DY_ORDER = {0, 1, -1, 2, -2, 3, -3, -4, -5};

    public record Candidate(List<Vec3> pts, List<Frame> frames, double length) {}

    private PathPlanner() {}

    private static boolean passable(Level l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getCollisionShape(l, p).isEmpty() && !s.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean standable(Level l, BlockPos p) {
        if (!passable(l, p) || !passable(l, p.above())) return false;
        if (l.getFluidState(p).is(FluidTags.WATER)) return false;
        BlockPos b = p.below();
        BlockState bs = l.getBlockState(b);
        if (bs.isFaceSturdy(l, b, Direction.UP)) return true;
        return bs.getFluidState().is(FluidTags.WATER); 
    }

    public static BlockPos nearestStandable(Level l, BlockPos c, int radius) {
        for (int rr = 0; rr <= radius; rr++) {
            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != rr) continue;
                    for (int dy : DY_ORDER) {
                        BlockPos p = c.offset(dx, dy, dz);
                        if (standable(l, p)) return p;
                    }
                }
            }
        }
        return null;
    }

    private record Node(BlockPos pos, double g, double f, Node parent) {}

    private static List<BlockPos> astar(Level l, BlockPos s, BlockPos g) {
        int radius = Math.min(96, (int) (Math.hypot(g.getX() - s.getX(), g.getZ() - s.getZ()) * 1.5) + 24);
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<BlockPos, Double> best = new HashMap<>();
        open.add(new Node(s, 0, 0, null));
        best.put(s, 0.0);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node cur = open.poll();
            if (cur.g > best.getOrDefault(cur.pos, 1e18) + 1e-9) continue;
            if (cur.pos.equals(g)) {
                List<BlockPos> out = new ArrayList<>();
                for (Node n = cur; n != null; n = n.parent) out.add(0, n.pos);
                return out;
            }
            BlockPos from = cur.pos;
            for (int[] d : DIRS) {
                int nx = from.getX() + d[0], nz = from.getZ() + d[1];
                if (Math.abs(nx - s.getX()) > radius || Math.abs(nz - s.getZ()) > radius) continue;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && !(passable(l, from.offset(d[0], 0, 0)) && passable(l, from.offset(d[0], 1, 0))
                        && passable(l, from.offset(0, 0, d[1])) && passable(l, from.offset(0, 1, d[1])))) continue;
                for (int dy : DY_ORDER) {
                    BlockPos to = new BlockPos(nx, from.getY() + dy, nz);
                    if (!standable(l, to)) continue;
                    boolean ok = true;
                    if (dy > 0) {
                        for (int k = 1; k <= dy + 1 && ok; k++) ok = passable(l, from.above(k));
                    } else if (dy < 0) {
                        for (int k = 2; k <= -dy + 1 && ok; k++) ok = passable(l, to.above(k));
                        ok = ok && passable(l, new BlockPos(nx, from.getY(), nz)) && passable(l, new BlockPos(nx, from.getY() + 1, nz));
                    } else {
                        ok = passable(l, new BlockPos(nx, from.getY(), nz)) && passable(l, new BlockPos(nx, from.getY() + 1, nz));
                    }
                    if (!ok) continue;
                    double cost = (diag ? 1.414 : 1.0) + 0.4 * Math.abs(dy);
                    double ng = cur.g + cost;
                    if (ng < best.getOrDefault(to, 1e18)) {
                        best.put(to, ng);
                        double h = Math.sqrt(to.distSqr(g));
                        open.add(new Node(to, ng, ng + h, cur));
                    }
                    break; 
                }
            }
        }
        return null;
    }

    private static List<BlockPos> via(Level l, BlockPos s, BlockPos w, BlockPos g) {
        if (w == null) return null;
        List<BlockPos> a = astar(l, s, w);
        List<BlockPos> b = astar(l, w, g);
        if (a == null || b == null) return null;
        List<BlockPos> out = new ArrayList<>(a);
        out.addAll(b.subList(1, b.size()));
        return out;
    }

    public static BlockPos goalBehind(Level l, Entity e) {
        Vec3 back = e.position().subtract(Vec3.directionFromRotation(0, e.getYRot()).scale(1.6));
        BlockPos g = nearestStandable(l, BlockPos.containing(back), 2);
        return g != null ? g : nearestStandable(l, e.blockPosition(), 3);
    }

    public static BlockPos goalForBlock(Level l, BlockPos b, Vec3 from) {
        BlockPos best = null;
        double bd = 1e18;
        for (BlockPos c : new BlockPos[] {b.above(), b.north(), b.south(), b.east(), b.west()}) {
            BlockPos g = nearestStandable(l, c, 1);
            if (g == null) continue;
            double d = Vec3.atBottomCenterOf(g).distanceToSqr(from);
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        return best;
    }

    public static List<Candidate> plan(Level l, Vec3 startPos, BlockPos goal, Vec3 focus, Vec3 lookAt) {
        List<Candidate> out = new ArrayList<>();
        if (goal == null) return out;
        BlockPos s = nearestStandable(l, BlockPos.containing(startPos), 2);
        if (s == null) return out;

        List<List<BlockPos>> routes = new ArrayList<>();
        routes.add(astar(l, s, goal));
        Vec3 sv = Vec3.atBottomCenterOf(s), gv = Vec3.atBottomCenterOf(goal);
        Vec3 mid = sv.add(gv).scale(0.5);
        Vec3 dir = new Vec3(gv.x - sv.x, 0, gv.z - sv.z);
        Vec3 perp = dir.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : new Vec3(-dir.z, 0, dir.x).normalize();
        double side = Math.max(6, Math.min(14, dir.length() * 0.5));
        routes.add(via(l, s, nearestStandable(l, BlockPos.containing(mid.add(perp.scale(side))), 3), goal));
        routes.add(via(l, s, nearestStandable(l, BlockPos.containing(mid.subtract(perp.scale(side))), 3), goal));
        routes.add(via(l, s, nearestStandable(l, BlockPos.containing(focus.add(perp.scale(5))), 3), goal));
        routes.add(via(l, s, nearestStandable(l, BlockPos.containing(focus.subtract(perp.scale(5))), 3), goal));

        List<Set<BlockPos>> seen = new ArrayList<>();
        for (List<BlockPos> r : routes) {
            if (r == null || r.size() < 2) continue;
            Set<BlockPos> set = new HashSet<>(r);
            boolean dup = false;
            for (Set<BlockPos> o : seen) {
                int common = 0;
                for (BlockPos p : set) if (o.contains(p)) common++;
                if (common > 0.85 * Math.min(set.size(), o.size())) dup = true;
            }
            if (dup) continue;
            seen.add(set);
            List<Vec3> pts = new ArrayList<>();
            pts.add(startPos);
            for (int i = 1; i < r.size(); i++) pts.add(Vec3.atBottomCenterOf(r.get(i)));
            out.add(fromPoints(pts, WALK_STEP, lookAt));
            if (out.size() >= 4) break;
        }
        return out;
    }

    public static Candidate fromPoints(List<Vec3> pts, double step, Vec3 lookAt) {
        List<Frame> frames = new ArrayList<>();
        double length = 0;
        float lastYaw = 0;
        for (int i = 1; i < pts.size(); i++) {
            Vec3 a = pts.get(i - 1), b = pts.get(i);
            double len = a.distanceTo(b);
            length += len;
            int n = Math.max(1, (int) Math.ceil(len / step));
            float yaw = (len < 1e-6) ? lastYaw : (float) Math.toDegrees(Math.atan2(-(b.x - a.x), b.z - a.z));
            lastYaw = yaw;
            for (int k = 1; k <= n; k++) {
                Vec3 p = a.lerp(b, (double) k / n);
                frames.add(new Frame(p.x, p.y + EYE, p.z, yaw, 0f, Frame.NONE, 0L));
            }
        }
        if (lookAt != null && !frames.isEmpty()) {
            int li = frames.size() - 1;
            Frame last = frames.get(li);
            double dx = lookAt.x - last.x(), dy = lookAt.y - last.y(), dz = lookAt.z - last.z();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            frames.set(li, new Frame(last.x(), last.y(), last.z(), yaw, pitch, Frame.NONE, 0L));
        }
        return new Candidate(pts, frames, length);
    }
}