// src/main/java/com/projectionsorcery/client/ClientEvents.java
package com.projectionsorcery.client;

import com.projectionsorcery.ClientConfig;
import com.projectionsorcery.Config;
import com.projectionsorcery.ProjectionSorcery;
import com.projectionsorcery.Theme;
import com.projectionsorcery.net.Frame;
import com.projectionsorcery.net.Payloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = ProjectionSorcery.MODID, value = Dist.CLIENT)
public class ClientEvents {
    enum Mode { IDLE, REQUESTING, PLANNING, TOUCH, REPLAYING }
    record GhostPt(Vec3 feet, float yaw, boolean attack, float phase) {}

    static Mode mode = Mode.IDLE;
    static Sim self, victim;
    static final List<GhostPt> frames = new ArrayList<>();
    static final List<GhostPt> ghosts = new ArrayList<>();
    static final List<GhostPt> victimGhosts = new ArrayList<>();
    static final List<GhostPt> pathGhosts = new ArrayList<>();
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
    private static final Map<BlockPos, net.minecraft.world.level.block.state.BlockState> breaks = new LinkedHashMap<>();
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
    private static Vec3 frozenPos;
    
    // Timer for the fixed-timestep loop
    private static long lastTickTime = System.currentTimeMillis();

    // ==========================================
    // NETWORK PAYLOAD HANDLERS
    // ==========================================
    public static void onSession(int kind, int maxTicks, String msg) {
        mode = Mode.PLANNING;
        sessionMax = maxTicks;
        sessionTicks = 0;
        if (msg != null && !msg.isEmpty() && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), true);
        }
    }

    public static void onCe(int ce, int max, boolean infinite) {
        ceCur = ce;
        ceMax = max;
        ceInf = infinite;
    }

    public static void onFreeze(int ticks) {
        frozenTicks = ticks;
    }

    public static void onTouchFx(int index, List<Vec3> waypoints) {
        fxPts = waypoints;
        fxIndex = index;
    }

    public static void onReplayEnd() {
        endReplay();
    }

    private static void endReplay() {
        mode = Mode.IDLE;
        ghosts.clear();
    }

    // ==========================================
    // RENDER FRAME EVENT (Bypasses server tick rate)
    // ==========================================
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            hardReset();
            return;
        }

        // Freeze the player's position EVERY FRAME to prevent them from gliding
        // when the server tick rate is low.
        if (mode == Mode.PLANNING || mode == Mode.TOUCH) {
            freezeMain(mc);
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastTickTime;

        // Run mod logic at a fixed 20 TPS (50ms per tick) regardless of server tick rate
        if (elapsed < 50) return;
        lastTickTime = now - (elapsed % 50);

        if (frozenTicks > 0) frozenTicks--;

        // Handle Keybinds
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

        // Update entity targeting
        if (targetEntityId >= 0) {
            Entity t = mc.level.getEntity(targetEntityId);
            if (t == null || !t.isAlive()) targetEntityId = -1;
        }

        if (mode == Mode.REQUESTING && ++requestTicks > 60) mode = Mode.IDLE;
        if (mode == Mode.PLANNING) planTick(mc);
        else if (mode == Mode.TOUCH) touchTick(mc);
        else if (mode == Mode.REPLAYING) replayTick(mc);

        if (mode == Mode.IDLE && frozenTicks <= 0) tickPath(mc);
    }

    // ==========================================
    // HUD RENDERING
    // ==========================================
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        renderHud(event.getGuiGraphics(), event.getPartialTick());
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
        String ce = ceInf ? "∞" : ceCur + "/" + ceMax;
        int y = 6;
        if (mode == Mode.PLANNING || mode == Mode.TOUCH) {
            String time = sessionMax > 0 ? " | Time: " + Math.max(0, (sessionMax - sessionTicks + 19) / 20) + "s" : "";
            g.drawString(mc.font, "PROJECTION | Frames: " + frames.size() + " | CE: " + ce + time, 6, y, th.primary);
            y += 12;
            if (mode == Mode.TOUCH) {
                g.drawString(mc.font, "TOUCH: " + (int) (victim != null ? victim.traveled : 0) + "/" + Config.TOUCH_MAX_BLOCKS.get().intValue() + " blocks | C: return", 6, y, 0xFF5555);
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
            g.drawCenteredString(mc.font, "Follow the marked blocks: " + fxIndex + "/" + fxPts.size(), g.guiWidth() / 2, g.guiHeight() / 2 + 20, 0xFF5555);
        }
    }

    // ==========================================
    // ORIGINAL LOGIC METHODS
    // ==========================================
    
    private static void freezeMain(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (frozenPos == null) frozenPos = p.position();
        p.setDeltaMovement(Vec3.ZERO);
        p.setPos(frozenPos.x, frozenPos.y, frozenPos.z);
        p.fallDistance = 0;
    }

    private static void tickPath(Minecraft mc) {
        if (targetEntityId < 0 && targetBlock == null) {
            bestPath = null;
            return;
        }
        if (++pathTick % Config.TARGET_PATH_UPDATE_TICKS.get() != 0) return;
        Vec3 start = mc.player.position();
        Vec3 tgt = targetPos(mc);
        if (tgt == null) return;
        if (lastPathStart != null && start.distanceToSqr(lastPathStart) < 0.25 && tgt.distanceToSqr(lastPathTarget) < 0.25) return;
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
        frozenPos = p.position(); // Lock the position
        frames.clear();
        ghosts.clear();
        victimGhosts.clear();
        touchPts.clear();
        touchRec.clear();
        touchVictimId = -1;
        pendingAction = Frame.NONE;
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
                msg(mc, "Target locked.");
            }
        } else if (h instanceof BlockHitResult bh) {
            BlockPos pos = bh.getBlockPos();
            if (pos.equals(targetBlock)) {
                targetBlock = null;
                msg(mc, "Target cleared.");
            } else {
                targetBlock = pos;
                targetEntityId = -1;
                msg(mc, "Block target locked.");
            }
        }
    }

    private static HitResult targetRaycast(Minecraft mc) {
        double range = 50.0D;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        EntityHitResult entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
            mc.player, eye, end, new net.minecraft.world.phys.AABB(eye, end).inflate(1.0D),
            (e) -> !e.isSpectator() && e.isPickable(), range * range
        );
        if (entityHit != null) return entityHit;
        return mc.level.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
    }

    // Placeholders for your actual logic
    private static void quickMove(Minecraft mc) {}
    private static void quickToTarget(Minecraft mc) {}
    private static void startTouch(Minecraft mc) {}
    private static void endTouch(Minecraft mc) {}
    private static void cancel(Minecraft mc, boolean b) {}
    private static void execute(Minecraft mc) {}
    private static void planTick(Minecraft mc) {}
    private static void touchTick(Minecraft mc) {}
    private static void updatePathGhosts(Minecraft mc) {}
}