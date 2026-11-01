package com.projectionsorcery.server;

import com.projectionsorcery.Config;
import com.projectionsorcery.ProjectionSorcery;
import com.projectionsorcery.net.Frame;
import com.projectionsorcery.net.Payloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class ServerLogic {
    private static final float EYE = 1.62F;
    private static final int MAX_FRAMES = 1_000_000;
    private static final String CE_KEY = "projectionsorcery_ce";

    private static final SoundEvent FRAME_PASS_SFX = SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath(ProjectionSorcery.MODID, "planned_move_created"));
    private static final SoundEvent GLASS_BREAK_SFX = SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath(ProjectionSorcery.MODID, "unfreeze"));

    private static long tick;

    private static UUID sessionOwner;
    private static int sessionMax;            
    private static long sessionStartMs, sessionMaxMs;
    private static boolean timeUpSent;
    private static boolean slowed;            
    private static float prevRate = 20.0F;
    private static Vec3 frozenPos;
    private static float frozenYaw, frozenPitch;
    private static final List<ServerPlayer> START_REQUESTS = new ArrayList<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL_MS = new HashMap<>(); 

    private static final Map<UUID, List<Frame>> PENDING = new HashMap<>();
    private static final Map<UUID, Payloads.TouchPlan> PENDING_TOUCH = new HashMap<>();
    private static final Map<UUID, Replay> REPLAYS = new HashMap<>();
    private static final Map<UUID, FreezeState> FROZEN = new HashMap<>();
    private static final Map<UUID, Touch> TOUCHES = new HashMap<>();
    private static final Map<UUID, Integer> LAST_CE_SENT = new HashMap<>();

    private static final class Replay {
        final List<Frame> frames;
        int index;
        UUID victim;
        List<Vec3> touchPts;
        final PathValidator validator;
        Vec3 lastPos;
        double damageMultiplier = 1.0;
        boolean chargedCreeperTriggered = false;

        Replay(List<Frame> frames, ServerPlayer sp) {
            this.frames = frames;
            this.validator = new PathValidator(sp);
            this.lastPos = sp.position();
        }
    }

    private static final class FreezeState {
        int ticks;
        Vec3 pos;
        float yaw, pitch;
        final List<BlockPos> glass = new ArrayList<>();
        final List<BlockState> old = new ArrayList<>();
        ServerLevel level;
    }

    private static final class Touch {
        UUID victim;
        List<Vec3> pts;
        int idx, aware, timer;
    }

    private static boolean limited(MinecraftServer s) {
        return s.getPlayerList().getPlayerCount() > Config.LIMIT_PLAYER_THRESHOLD.get();
    }

    private static void msg(ServerPlayer sp, String s) {
        sp.displayClientMessage(Component.literal(s), true);
    }

    private static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    private static int getCe(ServerPlayer sp) {
        CompoundTag t = sp.getPersistentData();
        int max = Config.CE_MAX.get();
        return t.contains(CE_KEY) ? Math.min(max, t.getInt(CE_KEY)) : max;
    }

    private static void setCe(ServerPlayer sp, int v) {
        sp.getPersistentData().putInt(CE_KEY, Math.max(0, v));
    }

    private static boolean spend(ServerPlayer sp, int cost) {
        if (Config.CE_INFINITE.get() || cost <= 0) return true;
        int ce = getCe(sp);
        if (ce < cost) return false;
        setCe(sp, ce - cost);
        return true;
    }

    public static void onStartRequest(ServerPlayer sp) {
        if (!START_REQUESTS.contains(sp)) START_REQUESTS.add(sp);
    }

    public static void onEndSession(ServerPlayer sp) {
        if (sp.getUUID().equals(sessionOwner)) endSession(sp.server);
    }

    public static void onJump(ServerPlayer sp) {
        if (!sp.getUUID().equals(sessionOwner)) return;
        sp.getFoodData().addExhaustion((float) (Config.JUMP_HUNGER_COST.get() * 4.0)); 
    }

    public static void onTouchPlan(Payloads.TouchPlan p, ServerPlayer sp) {
        PENDING_TOUCH.put(sp.getUUID(), p);
    }

    public static void onPlan(Payloads.PlanChunk p, ServerPlayer sp) {
        UUID id = sp.getUUID();
        List<Frame> list;
        if (p.first()) {
            list = new ArrayList<>();
            PENDING.put(id, list);
        } else {
            list = PENDING.get(id);
            if (list == null) return;
        }
        if (list.size() + p.frames().size() > MAX_FRAMES) {
            PENDING.remove(id);
            return;
        }
        list.addAll(p.frames());
        if (p.execute()) {
            PENDING.remove(id);
            Payloads.TouchPlan tp = PENDING_TOUCH.remove(id);
            if (!list.isEmpty() && !REPLAYS.containsKey(id)) {
                Replay r = new Replay(list, sp);
                if (tp != null && !tp.waypoints().isEmpty()) {
                    Entity v = sp.serverLevel().getEntity(tp.victimId());
                    if (v != null && v != sp && (v instanceof Mob || v instanceof ServerPlayer)) {
                        r.victim = v.getUUID();
                        r.touchPts = tp.waypoints();
                    }
                }
                REPLAYS.put(id, r);
            }
            if (id.equals(sessionOwner)) endSession(sp.server);
        }
    }

    private static void deny(ServerPlayer sp, String why) {
        PacketDistributor.sendToPlayer(sp, new Payloads.Session(0, 0, why));
    }

    private static String checkStart(ServerPlayer sp, MinecraftServer server) {
        if (sessionOwner != null) return "Someone else is already using Projection Sorcery.";
        UUID id = sp.getUUID();
        if (REPLAYS.containsKey(id) || FROZEN.containsKey(id)) return "Busy.";
        if (limited(server)) {
            long until = COOLDOWN_UNTIL_MS.getOrDefault(id, 0L);
            long now = System.currentTimeMillis();
            if (now < until) return "Cooldown: " + ((until - now + 999) / 1000) + "s";
        }
        return null;
    }

    private static void startSession(ServerPlayer sp, MinecraftServer server) {
        sessionOwner = sp.getUUID();
        sessionStartMs = System.currentTimeMillis();
        timeUpSent = false;
        sessionMax = limited(server) ? Config.LIMIT_MAX_PLAN_SECONDS.get() * 20 : 0;
        sessionMaxMs = sessionMax * 50L;
        frozenPos = sp.position();
        frozenYaw = sp.getYRot();
        frozenPitch = sp.getXRot();
        
        // Apply 500% base speed directly to the server player
        var speedAttr = sp.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) speedAttr.setBaseValue(0.5);
        
        var rm = server.tickRateManager();
        if (!slowed) {
            prevRate = rm.tickrate();
            rm.setTickRate(1.0F);
            slowed = true;
        }
        PacketDistributor.sendToPlayer(sp, new Payloads.Session(1, sessionMax, ""));
    }

    private static void endSession(MinecraftServer server) {
        if (sessionOwner == null) return;
        
        ServerPlayer sp = server.getPlayerList().getPlayer(sessionOwner);
        if (sp != null) {
            // Reset base speed to default 0.1
            var speedAttr = sp.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) speedAttr.setBaseValue(0.1);
        }
        
        if (slowed) {
            server.tickRateManager().setTickRate(prevRate);
            slowed = false;
        }
        if (limited(server)) {
            COOLDOWN_UNTIL_MS.put(sessionOwner, System.currentTimeMillis() + Config.LIMIT_COOLDOWN_SECONDS.get() * 1000L);
        }
        sessionOwner = null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        tick++;

        if (!START_REQUESTS.isEmpty()) {
            List<ServerPlayer> reqs = new ArrayList<>(START_REQUESTS);
            START_REQUESTS.clear();
            if (reqs.size() > 1) {
                for (ServerPlayer p : reqs) deny(p, "Simultaneous use: both cancelled.");
            } else {
                ServerPlayer p = reqs.get(0);
                String err = checkStart(p, server);
                if (err != null) deny(p, err);
                else startSession(p, server);
            }
        }

        if (sessionOwner != null) {
            ServerPlayer owner = server.getPlayerList().getPlayer(sessionOwner);
            if (owner == null) {
                endSession(server);
            } else {
                // Main model stays completely frozen while planning (no drift/fall/knockback)
                owner.setDeltaMovement(Vec3.ZERO);
                if (frozenPos != null && owner.position().distanceToSqr(frozenPos) > 1.0E-6) {
                    owner.connection.teleport(frozenPos.x, frozenPos.y, frozenPos.z, frozenYaw, frozenPitch);
                }
                if (sessionMaxMs > 0) {
                    long el = System.currentTimeMillis() - sessionStartMs;
                    if (el >= sessionMaxMs && !timeUpSent) {
                        timeUpSent = true;
                        PacketDistributor.sendToPlayer(owner, new Payloads.Session(2, 0, ""));
                    } else if (el > sessionMaxMs + 3000) {
                        endSession(server);
                    }
                }
            }
        }

        tickReplays(server);
        tickTouches(server);
        tickFrozen(server);
        tickCe(server);
    }

    private static void tickReplays(MinecraftServer server) {
        if (REPLAYS.isEmpty()) return;
        int perTick = Config.REPLAY_FRAMES_PER_TICK.get();
        Iterator<Map.Entry<UUID, Replay>> it = REPLAYS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Replay> en = it.next();
            ServerPlayer sp = server.getPlayerList().getPlayer(en.getKey());
            if (sp == null || !sp.isAlive()) {
                it.remove();
                continue;
            }
            Replay r = en.getValue();
            int res = 0;
            for (int i = 0; i < perTick && r.index < r.frames.size() && res == 0; i++) {
                res = step(sp, r, r.frames.get(r.index++));
            }
            if (res == 1) {
                it.remove();
                finish(sp);
                msg(sp, "Out of cursed energy!");
                freeze(sp);
                PacketDistributor.sendToPlayer(sp, Payloads.ReplayEnd.INSTANCE);
            } else if (res == 2) {
                it.remove();
                finish(sp);
                msg(sp, "Path rejected: not physically possible.");
                freeze(sp);
                PacketDistributor.sendToPlayer(sp, Payloads.ReplayEnd.INSTANCE);
            } else if (r.index >= r.frames.size()) {
                it.remove();
                finish(sp);
                PacketDistributor.sendToPlayer(sp, Payloads.ReplayEnd.INSTANCE);
            }
        }
    }

    private static int step(ServerPlayer sp, Replay r, Frame f) {
        if (Config.PATH_VALIDATION.get() && !r.validator.advance(f.x(), f.y() - EYE, f.z())) return 2;
        if (!spend(sp, Config.FRAME_COST.get())) return 1;
        
        Vec3 currentPos = new Vec3(f.x(), f.y() - EYE, f.z());
        double dist = r.lastPos.distanceTo(currentPos);
        double speedBps = dist * 20; 
        r.damageMultiplier = Math.max(1.0, speedBps / 10.0); 
        r.lastPos = currentPos;

        // Teleport (not setPos) so the client's player actually moves along the path
        sp.connection.teleport(f.x(), f.y() - EYE, f.z(), f.yaw(), f.pitch());
        sp.hurtMarked = true;
        sp.fallDistance = 0;

        // "frame passed" sound on every replayed frame
        sp.serverLevel().playSound(null, sp.getX(), sp.getY(), sp.getZ(), FRAME_PASS_SFX,
                SoundSource.PLAYERS, Config.FRAME_SOUND_VOLUME.get().floatValue(), 1.0F);

        // Speed levels increase by 5 every 15 frames
        int speedLevel = Math.min(30, (r.index / 15) * 5);
        if (speedLevel > 0) {
            sp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20, speedLevel - 1, false, false, false));
        }

        // TNT/Charged Creeper explosions + custom glass-break sound on every level milestone
        if (r.index > 0 && r.index % 15 == 0) {
            sp.serverLevel().playSound(null, sp.getX(), sp.getY(), sp.getZ(), GLASS_BREAK_SFX, SoundSource.PLAYERS, 1.0F, 1.0F);
            if (r.frames.size() >= Config.EXPLOSION_MIN_FRAMES.get()) {
                if (speedLevel == 30 && !r.chargedCreeperTriggered) {
                    r.chargedCreeperTriggered = true;
                    sp.level().explode(sp, sp.getX(), sp.getY(), sp.getZ(), 6.0f, Level.ExplosionInteraction.NONE);
                } else if (speedLevel < 30) {
                    sp.level().explode(sp, sp.getX(), sp.getY(), sp.getZ(), 4.0f, Level.ExplosionInteraction.NONE);
                }
            }
        }

        if (f.action() == Frame.STRIKE) {
            if (!spend(sp, Config.ATTACK_COST.get())) return 1;
            strike(sp, new Vec3(f.x(), f.y(), f.z()), f.yaw(), f.pitch(), r.damageMultiplier);
        } else if (f.action() == Frame.BREAK) {
            if (!tryBreak(sp, new Vec3(f.x(), f.y(), f.z()), BlockPos.of(f.blockPos()))) return 1;
        }

        if (r.victim != null) {
            Entity v = find(sp.server, r.victim);
            if (v == null) r.victim = null;
            else if (v.distanceTo(sp) <= Config.TOUCH_RANGE.get()) {
                armTouch(v, r.touchPts);
                r.victim = null;
            }
        }
        return 0;
    }

    private static void finish(ServerPlayer sp) {
        sp.setDeltaMovement(Vec3.ZERO);
        sp.hurtMarked = true;
        sp.fallDistance = 0;
        ServerLevel level = sp.serverLevel();
        double y = sp.getY();
        boolean moved = false;
        for (int i = 0; i < 256; i++) {
            AABB box = sp.getDimensions(sp.getPose()).makeBoundingBox(sp.getX(), y, sp.getZ());
            if (level.noCollision(sp, box)) break;
            y += 1;
            moved = true;
        }
        if (moved) sp.connection.teleport(sp.getX(), y, sp.getZ(), sp.getYRot(), sp.getXRot());
        sp.removeEffect(MobEffects.MOVEMENT_SPEED);
    }

    private static void strike(ServerPlayer sp, Vec3 eye, float yaw, float pitch, double damageMultiplier) {
        ServerLevel level = sp.serverLevel();
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        double reach = Config.ATTACK_REACH.get();
        Vec3 end = eye.add(dir.scale(reach));
        float damage = (float) (Config.ATTACK_DAMAGE.get() * damageMultiplier);
        
        List<Entity> candidates = level.getEntities(sp, new AABB(eye, end).inflate(1.5),
                e -> e instanceof LivingEntity le && le.isAlive() && !e.isSpectator());
        for (Entity e : candidates) {
            AABB bb = e.getBoundingBox().inflate(0.3);
            if (bb.contains(eye) || bb.clip(eye, end).isPresent()) {
                e.invulnerableTime = 0;
                e.hurt(level.damageSources().playerAttack(sp), damage);
            }
        }
        Vec3 fx = eye.add(dir.scale(Math.min(reach, 2.5)));
        level.sendParticles(ParticleTypes.CRIT, fx.x, fx.y, fx.z, 8, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, fx.x, fx.y, fx.z, SoundEvents.AMETHYST_CLUSTER_HIT, SoundSource.PLAYERS, 1.0F, 1.4F);
    }

    private static boolean tryBreak(ServerPlayer sp, Vec3 eye, BlockPos pos) {
        ServerLevel level = sp.serverLevel();
        if (!level.isLoaded(pos)) return true;
        if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > 7.0 * 7.0) return true; 
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return true;
        if (st.getDestroySpeed(level, pos) < 0) return true;                                   
        if (st.getBlock().getExplosionResistance() > Config.MAX_BLAST_RESISTANCE.get()) return true; 
        if (sp.blockActionRestricted(level, pos, sp.gameMode.getGameModeForPlayer())) return true;
        if (!spend(sp, Config.BLOCK_COST.get())) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (!sp.isCreative()) Block.dropResources(st, level, pos, be, sp, sp.getMainHandItem()); 
        level.destroyBlock(pos, false, sp);
        return true;
    }

    private static void freeze(ServerPlayer sp) {
        UUID id = sp.getUUID();
        if (FROZEN.containsKey(id)) return;
        FreezeState fs = new FreezeState();
        fs.ticks = Config.FREEZE_TICKS.get();
        fs.pos = sp.position();
        fs.yaw = sp.getYRot();
        fs.pitch = sp.getXRot();
        fs.level = sp.serverLevel();
        BlockPos feet = sp.blockPosition();
        for (BlockPos p : new BlockPos[] {feet, feet.above()}) {
            BlockState st = fs.level.getBlockState(p);
            if (st.isAir() || st.canBeReplaced()) {
                fs.glass.add(p.immutable());
                fs.old.add(st);
                fs.level.setBlock(p, Blocks.BLUE_ICE.defaultBlockState(), 3);
            }
        }
        sp.setDeltaMovement(Vec3.ZERO);
        sp.hurtMarked = true;
        FROZEN.put(id, fs);
        
        SoundEvent sfx = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ProjectionSorcery.MODID, "freeze"));
        fs.level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), sfx, SoundSource.PLAYERS, 1.0F, 1.0F);
        
        PacketDistributor.sendToPlayer(sp, new Payloads.FreezeFx(fs.ticks));
    }

    private static void unfreeze(FreezeState fs) {
        for (int i = 0; i < fs.glass.size(); i++) {
            BlockPos p = fs.glass.get(i);
            if (fs.level.getBlockState(p).is(Blocks.BLUE_ICE)) {
                fs.level.setBlock(p, fs.old.get(i), 3);
            }
        }
        SoundEvent sfx = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ProjectionSorcery.MODID, "unfreeze"));
        fs.level.playSound(null, fs.pos.x, fs.pos.y, fs.pos.z, sfx, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void tickFrozen(MinecraftServer server) {
        if (FROZEN.isEmpty()) return;
        Iterator<Map.Entry<UUID, FreezeState>> it = FROZEN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, FreezeState> en = it.next();
            FreezeState fs = en.getValue();
            ServerPlayer sp = server.getPlayerList().getPlayer(en.getKey());
            if (sp == null || --fs.ticks <= 0) {
                unfreeze(fs);
                it.remove();
                continue;
            }
            if (sp.position().distanceToSqr(fs.pos) > 1.0E-4) {
                sp.connection.teleport(fs.pos.x, fs.pos.y, fs.pos.z, fs.yaw, fs.pitch);
            }
            sp.setDeltaMovement(Vec3.ZERO);
            sp.hurtMarked = true;
        }
    }

    private static void armTouch(Entity victim, List<Vec3> pts) {
        if (pts == null || pts.isEmpty()) return;
        Touch t = new Touch();
        t.victim = victim.getUUID();
        t.pts = new ArrayList<>(pts);
        t.timer = Config.TOUCH_STEP_TICKS.get();
        if (victim instanceof ServerPlayer vp) {
            t.aware = Config.TOUCH_AWARE_TICKS.get();
            PacketDistributor.sendToPlayer(vp, new Payloads.TouchFx(0, t.pts));
            vp.displayClientMessage(Component.literal("You were touched! Follow the marked blocks - 1 second per block."), false);
        }
        TOUCHES.put(t.victim, t);
    }

    private static boolean near(Entity e, Vec3 p) {
        return Math.abs(e.getX() - p.x) <= 1.0 && Math.abs(e.getZ() - p.z) <= 1.0 && Math.abs(e.getY() - p.y) <= 1.5;
    }

    private static void tickTouches(MinecraftServer server) {
        if (TOUCHES.isEmpty()) return;
        Iterator<Touch> it = TOUCHES.values().iterator();
        while (it.hasNext()) {
            Touch t = it.next();
            Entity v = find(server, t.victim);
            if (v == null || !v.isAlive()) {
                it.remove();
                continue;
            }
            if (v instanceof ServerPlayer vp) {
                if (FROZEN.containsKey(vp.getUUID())) continue;
                if (t.aware > 0) {
                    t.aware--;
                    continue;
                }
                if (near(vp, t.pts.get(t.idx))) {
                    t.idx++;
                    t.timer = Config.TOUCH_STEP_TICKS.get();
                    if (t.idx >= t.pts.size()) {
                        PacketDistributor.sendToPlayer(vp, new Payloads.TouchFx(0, List.of()));
                        msg(vp, "You kept up with the frames.");
                        it.remove();
                    } else {
                        PacketDistributor.sendToPlayer(vp, new Payloads.TouchFx(t.idx, t.pts));
                    }
                } else if (--t.timer <= 0) {
                    PacketDistributor.sendToPlayer(vp, new Payloads.TouchFx(0, List.of()));
                    freeze(vp);
                    it.remove();
                }
            } else {
                if (v.tickCount % 2 == 0 && t.idx < t.pts.size()) {
                    Vec3 p = t.pts.get(t.idx++);
                    v.moveTo(p.x, p.y, p.z);
                    if (v instanceof Mob m) m.getNavigation().stop();
                }
                if (t.idx >= t.pts.size()) it.remove();
            }
        }
    }

    private static void tickCe(MinecraftServer server) {
        boolean sync = tick % 10 == 0;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            boolean inf = Config.CE_INFINITE.get();
            if (!inf && !REPLAYS.containsKey(sp.getUUID())) {
                int max = Config.CE_MAX.get();
                int ce = getCe(sp);
                if (ce < max) setCe(sp, Math.min(max, ce + Config.CE_REGEN_PER_TICK.get()));
            }
            if (sync) {
                int ce = inf ? -1 : getCe(sp);
                Integer last = LAST_CE_SENT.get(sp.getUUID());
                if (last == null || last != ce) {
                    LAST_CE_SENT.put(sp.getUUID(), ce);
                    PacketDistributor.sendToPlayer(sp, new Payloads.Ce(Math.max(ce, 0), Config.CE_MAX.get(), inf));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp
                && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            UUID id = sp.getUUID();
            if (id.equals(sessionOwner) || REPLAYS.containsKey(id)) event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        if (event.getEntity() instanceof ServerPlayer sp) {
            if (id.equals(sessionOwner)) endSession(sp.server);
            FreezeState fs = FROZEN.remove(id);
            if (fs != null) unfreeze(fs);
        }
        PENDING.remove(id);
        PENDING_TOUCH.remove(id);
        REPLAYS.remove(id);
        TOUCHES.remove(id);
        LAST_CE_SENT.remove(id);
        START_REQUESTS.removeIf(p -> p.getUUID().equals(id));
    }
}