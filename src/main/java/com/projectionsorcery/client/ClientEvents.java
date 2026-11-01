package com.projectionsorcery.client;

import com.projectionsorcery.ClientConfig;
import com.projectionsorcery.Config;
import com.projectionsorcery.ProjectionSorcery;
import com.projectionsorcery.Theme;
import com.projectionsorcery.net.Frame;
import com.projectionsorcery.net.Payloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = ProjectionSorcery.MODID, value = Dist.CLIENT)
public class ClientEvents {
    enum Mode { IDLE, REQUESTING, PLANNING, TOUCH, REPLAYING }

    record GhostPt(Vec3 feet, float yaw, boolean attack, float phase) {}

    static Mode mode = Mode.IDLE;
    static Sim self, victim;
    static final List<Frame> frames = new ArrayList<>();
    static final List<GhostPt> ghosts = new ArrayList<>();
    static final List<GhostPt> victimGhosts = new ArrayList<>();
    static final List<GhostPt> pathGhosts = new ArrayList<>();
    /** The route that is drawn right now; Quick move to target sends exactly this one. */
    private static PathPlanner.Candidate bestPath;
    private static Vec3 lastPathStart, lastPathTarget;
    private static int pathTick;
    static Entity selfGhostEntity, victimGhostEntity;
    static List<PathPlanner.Candidate> candidates = new ArrayList<>();
    static int hovered = -1;
    static int targetEntityId = -1;
    static BlockPos targetBlock;
    static List<Vec3> fxPts = List.of();   
    static int fxIndex;

    private static final Map<BlockPos, BlockState> breaks = new LinkedHashMap<>();
    private static final List<Vec3> touchRec = new ArrayList<>();
    static final List<Vec3> touchPts = new ArrayList<>();
    private static LivingEntity touchVictim;
    private static int touchVictimId = -1;
    private static int sessionMax, sessionTicks, requestTicks;
    private static int ceCur, ceMax = 1, frozenTicks;
    private static boolean ceInf = true;
    private static float lockYaw, lockPitch;
    private static byte pendingAction;
    private static long pendingPos;
    private static Vec3 lastEye;
    private static float lastYaw, lastPitch;
    private static long lastFrameTime;
    private static Vec3 frozenPos;

    private static void playSound(Minecraft mc, String sound, float volume, float pitch) {
        if (mc.level == null || mc.player == null) return;
        SoundEvent sfx = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ProjectionSorcery.MODID, sound));
        mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), sfx, SoundSource.PLAYERS, volume, pitch, false);
    }

    public static void onSession(int kind, int max, String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (kind == 0) {
            if (mode == Mode.REQUESTING) mode = Mode.IDLE;
            msg(mc, msg);
        } else if (kind == 1) {
            if (mode == Mode.REQUESTING) beginPlanning(mc, max);
            else PacketDistributor.sendToServer(Payloads.EndSession.INSTANCE);
        } else if (kind == 2) {
            if (mode == Mode.TOUCH) endTouch(mc);
            if (mode == Mode.PLANNING) {
                msg(mc, "Time's up - executing!");
                execute(mc);
            }
        }
    }

    public static void onCe(int ce, int max, boolean inf) {
        ceCur = ce;
        ceMax = Math.max(1, max);
        ceInf = inf;
    }

    public static void onFreeze(int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        frozenTicks = ticks;
        lockYaw = mc.player.getYRot();
        lockPitch = mc.player.getXRot();
        if (mode == Mode.PLANNING || mode == Mode.TOUCH) cancel(mc, false);
        else if (mode == Mode.REPLAYING) endReplay();
    }

    public static void onTouchFx(int index, List<Vec3> pts) {
        fxPts = pts;
        fxIndex = index;
    }

    public static void onReplayEnd() {
        endReplay();
    }

    private static void endReplay() {
        mode = Mode.IDLE;
        ghosts.clear();
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        if (mode == Mode.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.level.tickRateManager().setTickRate(20.0F);
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        if (mode == Mode.IDLE && frozenTicks <= 0) return;
        Options o = Minecraft.getInstance().options;
        o.keyAttack.setDown(false);
        o.keyUse.setDown(false);
        o.keyPickItem.setDown(false);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            hardReset();
            return;
        }
        if (frozenTicks > 0) frozenTicks--;

        while (ClientModEvents.SETTINGS.consumeClick()) mc.setScreen(new SettingsScreen());
        while (ClientModEvents.TARGET.consumeClick()) toggleTarget(mc);
        while (ClientModEvents.TOGGLE.consumeClick()) {
            if (mode == Mode.IDLE && frozenTicks <= 0) requestStart();
            else if (mode == Mode.PLANNING) execute(mc);
        }
        while (ClientModEvents.CANCEL.consumeClick()) {
            if (mode == Mode.PLANNING || mode == Mode.TOUCH) cancel(mc, true);
        }
        while (ClientModEvents.TOUCH.consumeClick()) {
            if (mode == Mode.PLANNING) startTouch(mc);
            else if (mode == Mode.TOUCH) endTouch(mc);
        }
        while (ClientModEvents.QUICK.consumeClick()) {
            if (mode == Mode.IDLE && frozenTicks <= 0) quickMove(mc);
        }
        while (ClientModEvents.QUICK_TARGET.consumeClick()) {
            if (mode == Mode.IDLE && frozenTicks <= 0) quickToTarget(mc);
        }

        if (targetEntityId >= 0) {
            Entity t = mc.level.getEntity(targetEntityId);
            if (t == null || !t.isAlive()) targetEntityId = -1;
        }

        if (mode == Mode.REQUESTING && ++requestTicks > 60) mode = Mode.IDLE;
        if (mode == Mode.PLANNING) planTick(mc);
        else if (mode == Mode.TOUCH) touchTick(mc);
        else if (mode == Mode.REPLAYING) replayTick(mc);
        if (mode == Mode.PLANNING || mode == Mode.TOUCH) freezeMain(mc);
        if (mode == Mode.IDLE && frozenTicks <= 0) tickPath(mc);
    }

    /** Keeps the shown path fresh: every few ticks, but only if you or the target actually moved. */
    private static void tickPath(Minecraft mc) {
        if (targetEntityId < 0 && targetBlock == null) {
            bestPath = null;
            return;
        }
        if (++pathTick % Config.TARGET_PATH_UPDATE_TICKS.get() != 0) return;
        Vec3 start = mc.player.position();
        Vec3 tgt = targetPos(mc);
        if (tgt == null) return;
        if (lastPathStart != null && start.distanceToSqr(lastPathStart) < 0.25
                && tgt.distanceToSqr(lastPathTarget) < 0.25) return;
        updatePathGhosts(mc);
    }

    private static Vec3 targetPos(Minecraft mc) {
        if (targetEntityId >= 0) {
            Entity t = mc.level.getEntity(targetEntityId);
            return t == null ? null : t.position();
        }
        return targetBlock == null ? null : Vec3.atCenterOf(targetBlock);
    }

    private static void replayTick(Minecraft mc) {
        if (mc.player == null) return;
        double clear = Config.GHOST_CLEAR_DISTANCE.get();
        double d2 = clear * clear;
        Vec3 pos = mc.player.position();
        ghosts.removeIf(g -> g.feet().distanceToSqr(pos) <= d2);
    }

    private static void freezeMain(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || frozenPos == null) return;
        p.setDeltaMovement(Vec3.ZERO);
        p.setPos(frozenPos.x, frozenPos.y, frozenPos.z);
        p.fallDistance = 0;
    }

    private static void msg(Minecraft mc, String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(s), true);
    }

    private static void hardReset() {
        mode = Mode.IDLE;
        self = null;
        victim = null;
        frames.clear();
        ghosts.clear();
        victimGhosts.clear();
        pathGhosts.clear();
        bestPath = null;
        candidates = new ArrayList<>();
        breaks.clear();
        touchPts.clear();
        touchRec.clear();
        frozenTicks = 0;
        fxPts = List.of();
        targetEntityId = -1;
        targetBlock = null;
        frozenPos = null;
    }

    private static void requestStart() {
        mode = Mode.REQUESTING;
        requestTicks = 0;
        PacketDistributor.sendToServer(Payloads.StartRequest.INSTANCE);
    }

    private static void beginPlanning(Minecraft mc, int max) {
        LocalPlayer p = mc.player;
        mode = Mode.PLANNING;
        sessionMax = max;
        sessionTicks = 0;
        lastFrameTime = System.currentTimeMillis();
        frames.clear();
        ghosts.clear();
        victimGhosts.clear();
        touchPts.clear();
        touchRec.clear();
        touchVictimId = -1;
        pendingAction = Frame.NONE;
        lastEye = null;
        lastYaw = p.getYRot();
        lastPitch = p.getXRot();
        frozenPos = p.position();
        
        p.setInvisible(true); 
        
        self = Sim.hero(mc);
        selfGhostEntity = new GhostPlayer(mc.level, p.getGameProfile());
        mc.setCameraEntity(self.body);
        candidates = new ArrayList<>(computeCandidates(mc));
        updatePathGhosts(mc);
        hovered = -1;
        if (!candidates.isEmpty()) msg(mc, "Paths ready: aim at one and right click.");
    }

    private static void planTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Options o = mc.options;
        sessionTicks++;
        if (sessionMax > 0 && sessionTicks >= sessionMax) {
            execute(mc);
            return;
        }
        float yaw = p.getYRot(), pitch = p.getXRot();
        self.setRot(yaw, pitch, p.yRotO, p.xRotO);

        double fw = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double st = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
        int clicks = 0;
        while (o.keyJump.consumeClick()) clicks++;
        boolean canAir = p.getFoodData().getFoodLevel() > 0;
        boolean air = self.tick(mc, fw, st, yaw, o.keyJump.isDown(), clicks, o.keySprint.isDown(), canAir);
        if (air) PacketDistributor.sendToServer(Payloads.Jump.INSTANCE);

        long now = System.currentTimeMillis();
        if (now - lastFrameTime >= 1000 / 24) {
            lastFrameTime = now;
            Vec3 eye = self.body.getEyePosition();
            boolean moved = lastEye == null || eye.distanceToSqr(lastEye) > 1.0E-6;
            boolean rot = Math.abs(yaw - lastYaw) > 0.5F || Math.abs(pitch - lastPitch) > 0.5F;
            if (moved || rot || pendingAction != Frame.NONE) {
                frames.add(new Frame(eye.x, eye.y, eye.z, yaw, pitch, pendingAction, pendingPos));
                lastEye = eye;
                lastYaw = yaw;
                lastPitch = pitch;
                Vec3 feet = self.body.position();
                GhostPt lastGhost = ghosts.isEmpty() ? null : ghosts.get(ghosts.size() - 1);
                if (lastGhost == null || lastGhost.feet().distanceTo(feet) >= Config.GHOST_SPACING.get() || pendingAction != Frame.NONE) {
                    ghosts.add(new GhostPt(feet, yaw, pendingAction != Frame.NONE, ghosts.size() * 0.6F));
                    playSound(mc, "planned_move_created", 1.0F, 1.0F); // Trigger sound on move
                }
            }
            pendingAction = Frame.NONE;
        }
        updateHover();
    }

    private static void updateHover() {
        hovered = -1;
        if (candidates.isEmpty() || self == null) return;
        Vec3 o = self.body.getEyePosition();
        Vec3 d = Vec3.directionFromRotation(Minecraft.getInstance().player.getXRot(), Minecraft.getInstance().player.getYRot());
        double bestD = 1.5;
        for (int i = 0; i < candidates.size(); i++) {
            for (Vec3 pt : candidates.get(i).pts()) {
                Vec3 w = pt.add(0, 0.9, 0).subtract(o);
                double t = w.dot(d);
                if (t < 0.5 || t > 48) continue;
                double dist = w.subtract(d.scale(t)).length();
                if (dist < bestD) {
                    bestD = dist;
                    hovered = i;
                }
            }
        }
    }

    private static void handleAttack(Minecraft mc) {
        LocalPlayer p = mc.player;
        Vec3 eye = self.body.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(p.getXRot(), p.getYRot());

        Vec3 reachEnd = eye.add(look.scale(3.0));
        boolean hitEntity = false;
        for (Entity e : mc.level.getEntities(self.body, self.body.getBoundingBox().inflate(4.0),
                x -> x instanceof LivingEntity && x != p && x.isAlive())) {
            if (e.getBoundingBox().inflate(0.1).clip(eye, reachEnd).isPresent()) {
                hitEntity = true;
                break;
            }
        }
        if (hitEntity) {
            pendingAction = Frame.STRIKE;
            return;
        }
        BlockHitResult br = mc.level.clip(new ClipContext(eye, eye.add(look.scale(4.5)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, self.body));
        if (br.getType() == HitResult.Type.BLOCK) {
            markBreak(mc, br.getBlockPos());
        } else {
            pendingAction = Frame.STRIKE;
        }
    }

    private static void markBreak(Minecraft mc, BlockPos pos) {
        BlockState st = mc.level.getBlockState(pos);
        if (st.isAir() || breaks.containsKey(pos)) return;
        if (st.getDestroySpeed(mc.level, pos) < 0
                || st.getBlock().getExplosionResistance() > Config.MAX_BLAST_RESISTANCE.get()) {
            msg(mc, "That block is too durable to break.");
            return;
        }
        BlockPos key = pos.immutable();
        breaks.put(key, st);
        mc.level.setBlock(key, Blocks.AIR.defaultBlockState(), 2);
        pendingAction = Frame.BREAK;
        pendingPos = key.asLong();
    }

    private static void restoreBreaks(Minecraft mc) {
        if (mc.level != null) {
            for (Map.Entry<BlockPos, BlockState> e : breaks.entrySet()) mc.level.setBlock(e.getKey(), e.getValue(), 2);
        }
        breaks.clear();
    }

    static void sendPlan(List<Frame> fr) {
        int n = fr.size();
        for (int i = 0; i < n; i += Payloads.PlanChunk.CHUNK) {
            int end = Math.min(n, i + Payloads.PlanChunk.CHUNK);
            PacketDistributor.sendToServer(new Payloads.PlanChunk(i == 0, end == n, new ArrayList<>(fr.subList(i, end))));
        }
    }

    private static void execute(Minecraft mc) {
        restoreBreaks(mc);
        if (!touchPts.isEmpty() && touchVictimId >= 0) {
            List<Vec3> pts = touchPts.size() > 200 ? new ArrayList<>(touchPts.subList(0, 200)) : new ArrayList<>(touchPts);
            PacketDistributor.sendToServer(new Payloads.TouchPlan(touchVictimId, pts));
        }
        if (frames.isEmpty()) PacketDistributor.sendToServer(Payloads.EndSession.INSTANCE);
        else sendPlan(new ArrayList<>(frames));
        leavePlanning(mc, true);
    }

    private static void executeCandidate(Minecraft mc, int i) {
        PathPlanner.Candidate c = candidates.get(i);
        frames.clear();
        frames.addAll(c.frames());
        execute(mc);
    }

    private static void cancel(Minecraft mc, boolean notifyServer) {
        restoreBreaks(mc);
        if (notifyServer) PacketDistributor.sendToServer(Payloads.EndSession.INSTANCE);
        leavePlanning(mc, false);
    }

    /** @param replay true = keep afterimages and enter REPLAYING; false = full cleanup back to IDLE. */
    private static void leavePlanning(Minecraft mc, boolean replay) {
        mode = replay ? Mode.REPLAYING : Mode.IDLE;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            mc.player.setInvisible(false);
            mc.player.input.up = false;
            mc.player.input.down = false;
            mc.player.input.left = false;
            mc.player.input.right = false;
            mc.player.input.jumping = false;
            mc.player.input.shiftKeyDown = false;
            mc.player.input.forwardImpulse = 0;
            mc.player.input.leftImpulse = 0;
        }
        self = null;
        victim = null;
        frames.clear();
        candidates = new ArrayList<>();
        hovered = -1;
        touchPts.clear();
        touchRec.clear();
        touchVictim = null;
        touchVictimId = -1;
        frozenPos = null;
        victimGhosts.clear();
        if (!replay) ghosts.clear();
    }

    private static void quickMove(Minecraft mc) {
        LocalPlayer p = mc.player;
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        double dist = Config.QUICK_MOVE_DISTANCE.get();
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(look.scale(dist)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double travel = hit.getType() == HitResult.Type.MISS ? dist : eye.distanceTo(hit.getLocation());
        for (double d = travel; d >= 0.5; d -= 0.25) {
            Vec3 feet = eye.add(look.scale(d)).subtract(0, p.getEyeHeight(), 0);
            AABB box = p.getBoundingBox().move(feet.subtract(p.position()));
            if (mc.level.noCollision(p, box)) {
                List<Vec3> pts = List.of(p.position(), feet);
                PathPlanner.Candidate c = PathPlanner.fromPoints(pts, PathPlanner.BLINK_STEP, eye.add(look.scale(8)));
                playSound(mc, "planned_move_created", 1.0F, 1.0F);
                sendPlan(c.frames());
                return;
            }
        }
    }

    private static List<PathPlanner.Candidate> computeCandidates(Minecraft mc) {
        Vec3 start = mc.player.position();
        Entity te = targetEntityId >= 0 ? mc.level.getEntity(targetEntityId) : null;
        if (te != null) {
            BlockPos g = PathPlanner.goalBehind(mc.level, te);
            return PathPlanner.plan(mc.level, start, g, te.position(), te.getEyePosition());
        }
        if (targetBlock != null) {
            BlockPos g = PathPlanner.goalForBlock(mc.level, targetBlock, start);
            return PathPlanner.plan(mc.level, start, g, Vec3.atCenterOf(targetBlock), Vec3.atCenterOf(targetBlock));
        }
        return List.of();
    }

    /** Builds the always-visible target path preview from the shortest candidate route. */
    private static void updatePathGhosts(Minecraft mc) {
        pathGhosts.clear();
        bestPath = null;
        if (targetEntityId < 0 && targetBlock == null) return;
        Vec3 tgt = targetPos(mc);
        if (tgt == null) return;
        lastPathStart = mc.player.position();
        lastPathTarget = tgt;
        PathPlanner.Candidate best = null;
        for (PathPlanner.Candidate c : computeCandidates(mc)) {
            if (best == null || c.length() < best.length()) best = c;
        }
        if (best == null || best.pts().isEmpty()) return;
        bestPath = best;
        double spacing = Config.GHOST_SPACING.get();
        Vec3 last = null;
        int idx = 0;
        for (Vec3 p : best.pts()) {
            if (last == null || last.distanceTo(p) >= spacing) {
                pathGhosts.add(new GhostPt(p, 0, false, idx * 0.6F));
                last = p;
                idx++;
            }
        }
    }

    private static void quickToTarget(Minecraft mc) {
        if (targetEntityId < 0 && targetBlock == null) {
            msg(mc, "No target set.");
            return;
        }
        // send the route that is currently drawn; only compute one if nothing is shown yet
        PathPlanner.Candidate best = bestPath;
        if (best == null) {
            for (PathPlanner.Candidate c : computeCandidates(mc)) {
                if (best == null || c.length() < best.length()) best = c;
            }
        }
        if (best == null) {
            msg(mc, "No path to the target.");
            return;
        }
        playSound(mc, "planned_move_created", 1.0F, 1.0F);
        sendPlan(best.frames());
    }

    /** Own ray for the Target key so it reaches targetRange blocks (the vanilla crosshair only reaches ~5). */
    private static HitResult targetRaycast(Minecraft mc) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        double range = Config.TARGET_RANGE.get();
        Vec3 eye = cam.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult block = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, cam));
        double bestD = block.getType() == HitResult.Type.MISS ? range * range : eye.distanceToSqr(block.getLocation());
        Entity best = null;
        for (Entity e : mc.level.getEntities(cam, cam.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0),
                x -> x != mc.player && x.isAlive() && !x.isSpectator() && x.isPickable())) {
            AABB bb = e.getBoundingBox().inflate(e.getPickRadius() + 0.1);
            Optional<Vec3> hit = bb.clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
        }
        return best != null ? new EntityHitResult(best) : block;
    }

    private static void toggleTarget(Minecraft mc) {
        HitResult h = targetRaycast(mc);
        if (h instanceof EntityHitResult eh) {
            if (targetEntityId == eh.getEntity().getId()) {
                targetEntityId = -1;
                msg(mc, "Target cleared.");
            } else {
                targetEntityId = eh.getEntity().getId();
                targetBlock = null;
                msg(mc, "Target: " + eh.getEntity().getName().getString());
            }
        } else if (h instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
            if (bh.getBlockPos().equals(targetBlock)) {
                targetBlock = null;
                msg(mc, "Target cleared.");
            } else {
                targetBlock = bh.getBlockPos().immutable();
                targetEntityId = -1;
                msg(mc, "Target: block");
            }
        } else {
            targetEntityId = -1;
            targetBlock = null;
            msg(mc, "Target cleared.");
        }
        if (mode == Mode.PLANNING) {
            candidates = new ArrayList<>(computeCandidates(mc));
            hovered = -1;
        }
        updatePathGhosts(mc);
    }

    private static void startTouch(Minecraft mc) {
        double range = Config.TOUCH_RANGE.get();
        LivingEntity best = null;
        double bd = range * range;
        for (Entity e : mc.level.getEntities(self.body, self.body.getBoundingBox().inflate(range),
                x -> (x instanceof Player || x instanceof Mob) && x != mc.player && x.isAlive())) {
            double d = e.distanceToSqr(self.body);
            if (d <= bd && e instanceof LivingEntity le) {
                bd = d;
                best = le;
            }
        }
        if (best == null) {
            msg(mc, "Touch: no mob or player in range.");
            return;
        }
        Sim v = Sim.victim(mc, best);
        if (v == null) return;
        touchVictim = best;
        victim = v;
        touchRec.clear();
        victimGhosts.clear();
        victimGhostEntity = Sim.copyOf(mc, best, true);
        mode = Mode.TOUCH;
        mc.setCameraEntity(victim.body);
        msg(mc, "Plan the victim's path (max " + Config.TOUCH_MAX_BLOCKS.get().intValue() + " blocks). Press Touch again to return.");
    }

    private static void touchTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Options o = mc.options;
        victim.setRot(p.getYRot(), p.getXRot(), p.yRotO, p.xRotO);
        double fw = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double st = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
        int clicks = 0;
        while (o.keyJump.consumeClick()) clicks++;
        victim.tick(mc, fw, st, p.getYRot(), o.keyJump.isDown(), clicks, o.keySprint.isDown(), false);

        if (victim.body.onGround()) {
            Vec3 c = Vec3.atBottomCenterOf(victim.body.blockPosition());
            if (touchRec.isEmpty() || !touchRec.get(touchRec.size() - 1).equals(c)) touchRec.add(c);
        }
        Vec3 feet = victim.body.position();
        if (victimGhosts.isEmpty() || victimGhosts.get(victimGhosts.size() - 1).feet().distanceTo(feet) >= Config.GHOST_SPACING.get()) {
            victimGhosts.add(new GhostPt(feet, p.getYRot(), false, victimGhosts.size() * 0.6F));
        }
        if (victim.traveled >= Config.TOUCH_MAX_BLOCKS.get()) {
            msg(mc, "Touch plan limit reached.");
            endTouch(mc);
        }
    }

    private static void endTouch(Minecraft mc) {
        if (touchVictim == null || mode != Mode.TOUCH) return;
        touchPts.clear();
        BlockPos start = touchVictim.blockPosition();
        for (Vec3 v : touchRec) {
            if (BlockPos.containing(v.x, v.y + 0.1, v.z).equals(start)) continue;
            touchPts.add(v);
        }
        touchVictimId = touchVictim.getId();
        victim = null;
        mode = Mode.PLANNING;
        mc.setCameraEntity(self.body);
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (mode == Mode.IDLE && frozenTicks <= 0) return;
        if (mode == Mode.PLANNING) {
            Minecraft mc = Minecraft.getInstance();
            if (event.isAttack()) handleAttack(mc);
            else if (event.isUseItem() && hovered >= 0 && hovered < candidates.size()) executeCandidate(mc, hovered);
        }
        event.setSwingHand(false);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (mode == Mode.IDLE && frozenTicks <= 0) return;
        Input i = event.getInput();
        i.up = false;
        i.down = false;
        i.left = false;
        i.right = false;
        i.forwardImpulse = 0;
        i.leftImpulse = 0;
        i.jumping = false;
        i.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (frozenTicks > 0) {
            event.setYaw(lockYaw);
            event.setPitch(lockPitch);
        }
    }

    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        Theme th = ClientConfig.THEME.get();
        if (mode == Mode.PLANNING || mode == Mode.TOUCH) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), (th.overlayAlpha << 24) | th.overlayRgb);
        } else if (mode == Mode.REPLAYING) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), (th.replayAlpha << 24) | th.replayRgb);
        }
        String ce = ceInf ? "\u221e" : ceCur + "/" + ceMax;
        int y = 6;
        if (mode == Mode.PLANNING || mode == Mode.TOUCH) {
            String time = sessionMax > 0 ? "  |  Time: " + Math.max(0, (sessionMax - sessionTicks + 19) / 20) + "s" : "";
            g.drawString(mc.font, "PROJECTION  |  Frames: " + frames.size() + "  |  CE: " + ce + time, 6, y, th.primary);
            y += 12;
            if (mode == Mode.TOUCH) {
                g.drawString(mc.font, "TOUCH: " + (int) victim.traveled + "/" + Config.TOUCH_MAX_BLOCKS.get().intValue()
                        + " blocks  |  C: return", 6, y, 0xFF5555);
            } else {
                g.drawString(mc.font, "WASD fly-walk, Space = (air) jump costs hunger, LMB = attack / insta-mine", 6, y, 0xAAAAAA);
                y += 12;
                g.drawString(mc.font, "R execute | X cancel | C touch | Z target" + (candidates.isEmpty() ? "" : " | RMB on a path"), 6, y, 0xAAAAAA);
            }
        } else if (mode == Mode.REQUESTING) {
            g.drawString(mc.font, "Projection: requesting...", 6, y, th.primary);
        } else {
            g.drawString(mc.font, "CE: " + ce, 6, y, th.primary);
            if (mode == Mode.REPLAYING) g.drawString(mc.font, "REPLAYING...", 6, y + 12, th.primary);
            if (targetEntityId >= 0 || targetBlock != null) g.drawString(mc.font, "TARGET SET", 6, y + 24, 0xFF3030);
        }
        if (frozenTicks > 0) {
            g.drawCenteredString(mc.font, "FROZEN", g.guiWidth() / 2, g.guiHeight() / 2 - 20, 0xFFFFFF);
        }
        if (!fxPts.isEmpty()) {
            g.drawCenteredString(mc.font, "Follow the marked blocks: " + fxIndex + "/" + fxPts.size(),
                    g.guiWidth() / 2, g.guiHeight() / 2 + 20, 0xFF5555);
        }
    }
}
