package com.projectionsorcery.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.projectionsorcery.ClientConfig;
import com.projectionsorcery.Config;
import com.projectionsorcery.ProjectionSorcery;
import com.projectionsorcery.Theme;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Afterimages, path previews, target indicator and touch markers. */
@EventBusSubscriber(modid = ProjectionSorcery.MODID, value = Dist.CLIENT)
public final class Render {
    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    private static final double MAX_DIST = 64.0;
    private static final int RED = 0xFF2020, DARK_RED = 0xB01010;

    private Render() {}

    private static int R(int c) { return (c >> 16) & 255; }
    private static int G(int c) { return (c >> 8) & 255; }
    private static int B(int c) { return c & 255; }

    private static List<ClientEvents.GhostPt> visible(List<ClientEvents.GhostPt> all, Vec3 cam) {
        List<ClientEvents.GhostPt> out = new ArrayList<>();
        int max = Config.MAX_GHOSTS.get();
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            if (all.get(i).feet().distanceToSqr(cam) <= MAX_DIST * MAX_DIST) out.add(all.get(i));
        }
        return out;
    }

    /** Puts the (reused) ghost entity into a vanilla running pose at the requested animation phase. */
    private static void setWalkPhase(LivingEntity le, float phase) {
        WalkAnimationState w = le.walkAnimation;
        float c = w.position();
        w.setSpeed(phase - c);
        w.update(phase - c, 1.0F);
        w.setSpeed(1.0F);
    }

    private static void place(Entity e, ClientEvents.GhostPt g) {
        Vec3 f = g.feet();
        e.setPos(f.x, f.y, f.z);
        e.xo = e.xOld = f.x;
        e.yo = e.yOld = f.y;
        e.zo = e.zOld = f.z;
        e.setYRot(g.yaw());
        e.yRotO = g.yaw();
        e.setXRot(0);
        e.xRotO = 0;
        if (e instanceof LivingEntity le) {
            le.yBodyRot = le.yBodyRotO = g.yaw();
            le.yHeadRot = le.yHeadRotO = g.yaw();
            setWalkPhase(le, g.phase());
        }
    }

    private static void drawGhosts(Minecraft mc, PoseStack ps, Vec3 cam, MultiBufferSource.BufferSource buf,
                                   Entity ghost, List<ClientEvents.GhostPt> pts, Theme th) {
        if (ghost == null) return;
        EntityRenderDispatcher disp = mc.getEntityRenderDispatcher();
        disp.setRenderShadow(false);

        if (th.wireframe) {
            for (ClientEvents.GhostPt g : visible(pts, cam)) {
                OutlineConsumer outline = new OutlineConsumer();
                MultiBufferSource src = rt -> {
                    if (rt.format() == DefaultVertexFormat.NEW_ENTITY) return outline;
                    return buf.getBuffer(rt);
                };
                place(ghost, g);
                disp.render(ghost, g.feet().x - cam.x, g.feet().y - cam.y, g.feet().z - cam.z,
                        g.yaw(), 1.0F, ps, src, LightTexture.FULL_BRIGHT);

                int rgb = g.attack() ? RED : 0xFFFFFF;
                VertexConsumer lines = buf.getBuffer(RenderType.lines());
                PoseStack.Pose pose = ps.last();
                for (float[] quad : outline.getQuads()) {
                    for (int i = 0; i < 4; i++) {
                        int j = (i + 1) % 4;
                        float ax = quad[i * 3], ay = quad[i * 3 + 1], az = quad[i * 3 + 2];
                        float bx = quad[j * 3], by = quad[j * 3 + 1], bz = quad[j * 3 + 2];
                        float nx = bx - ax, ny = by - ay, nz = bz - az;
                        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (len < 1.0E-6F) continue;
                        nx /= len; ny /= len; nz /= len;
                        int rr = R(rgb), gg = G(rgb), bb = B(rgb);
                        lines.addVertex(pose, ax, ay, az).setColor(rr, gg, bb, 255).setNormal(pose, nx, ny, nz);
                        lines.addVertex(pose, bx, by, bz).setColor(rr, gg, bb, 255).setNormal(pose, nx, ny, nz);
                    }
                }
            }
        } else {
            for (ClientEvents.GhostPt g : visible(pts, cam)) {
                int rgb = g.attack() ? RED : th.ghostRgb;
                int alpha = th.ghostAlpha;
                MultiBufferSource src = rt -> {
                    if (rt.format() == DefaultVertexFormat.NEW_ENTITY) {
                        return new TintConsumer(buf.getBuffer(RenderType.entityTranslucent(WHITE)),
                                R(rgb), G(rgb), B(rgb), alpha);
                    }
                    return buf.getBuffer(rt);
                };
                place(ghost, g);
                disp.render(ghost, g.feet().x - cam.x, g.feet().y - cam.y, g.feet().z - cam.z,
                        g.yaw(), 1.0F, ps, src, LightTexture.FULL_BRIGHT);
            }
            buf.endBatch(RenderType.entityTranslucent(WHITE));
        }
        disp.setRenderShadow(true);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer vc, Vec3 a, Vec3 b, int color, int alpha) {
        Vec3 n = b.subtract(a);
        double len = n.length();
        if (len < 1.0E-6) return;
        n = n.scale(1.0 / len);
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(R(color), G(color), B(color), alpha)
                .setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(R(color), G(color), B(color), alpha)
                .setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
    }

    private static void box(PoseStack ps, VertexConsumer vc, AABB bb, int color, int alpha) {
        LevelRenderer.renderLineBox(ps, vc, bb, R(color) / 255F, G(color) / 255F, B(color) / 255F, alpha / 255F);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        boolean planning = ClientEvents.mode == ClientEvents.Mode.PLANNING || ClientEvents.mode == ClientEvents.Mode.TOUCH;
        boolean replaying = ClientEvents.mode == ClientEvents.Mode.REPLAYING;
        boolean hasTarget = ClientEvents.targetEntityId >= 0 || ClientEvents.targetBlock != null;
        boolean hasFx = !ClientEvents.fxPts.isEmpty();
        if (!planning && !replaying && !hasTarget && !hasFx) return;

        Theme th = ClientConfig.THEME.get();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        // 1. afterimages (during planning AND replay; cleared one-by-one as the player passes them)
        if (planning || replaying) {
            drawGhosts(mc, ps, cam, buf, ClientEvents.selfGhostEntity, ClientEvents.ghosts, th);
            if (planning) drawGhosts(mc, ps, cam, buf, ClientEvents.victimGhostEntity, ClientEvents.victimGhosts, th);
        }

        // 2. target path preview (always visible until cleared)
        if (hasTarget) {
            drawPath(mc, ps, cam, buf, th);
        }

        // 3. gameplay markers (red touch blocks) in world coordinates
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer lines = buf.getBuffer(RenderType.lines());

        // target marker (client only): Naoya = ghost blue, Naobito = white. Entities get a box around them too.
        if (ClientEvents.targetEntityId >= 0) {
            Entity t = mc.level.getEntity(ClientEvents.targetEntityId);
            if (t != null) box(ps, lines, t.getBoundingBox().inflate(0.12), th.ghostRgb, 255);
        } else if (ClientEvents.targetBlock != null) {
            box(ps, lines, new AABB(ClientEvents.targetBlock).inflate(0.01), th.ghostRgb, 255);
        }

        if (planning) {
            // blocks planned for the victim: always red, whatever the theme
            for (Vec3 p : ClientEvents.touchPts) {
                box(ps, lines, new AABB(p.x - 0.5, p.y, p.z - 0.5, p.x + 0.5, p.y + 0.06, p.z + 0.5), DARK_RED, 200);
            }
        }

        // blocks a touched player has to follow
        for (int i = 0; i < ClientEvents.fxPts.size(); i++) {
            Vec3 p = ClientEvents.fxPts.get(i);
            boolean next = i == ClientEvents.fxIndex;
            box(ps, lines, new AABB(p.x - 0.5, p.y, p.z - 0.5, p.x + 0.5, p.y + 0.06, p.z + 0.5),
                    next ? RED : DARK_RED, next ? 255 : 170);
        }

        ps.popPose();
        buf.endBatch(RenderType.lines());
    }

    private static void drawPath(Minecraft mc, PoseStack ps, Vec3 cam, MultiBufferSource.BufferSource buf, Theme th) {
        List<ClientEvents.GhostPt> path = ClientEvents.pathGhosts;
        if (path.isEmpty()) return;
        // only when the target is within 2 ghost-spacings is the preview cut down to the 2nd and pre-last ghost
        double close = 2.0 * Config.GHOST_SPACING.get();
        List<ClientEvents.GhostPt> toRender = path;
        if (targetDistSq(mc) <= close * close && path.size() >= 4) {
            toRender = List.of(path.get(1), path.get(path.size() - 2));
        }
        drawGhosts(mc, ps, cam, buf, ClientEvents.selfGhostEntity, toRender, th);
    }

    private static double targetDistSq(Minecraft mc) {
        if (ClientEvents.targetEntityId >= 0) {
            Entity t = mc.level.getEntity(ClientEvents.targetEntityId);
            if (t != null) return mc.player.distanceToSqr(t);
        } else if (ClientEvents.targetBlock != null) {
            return mc.player.distanceToSqr(Vec3.atCenterOf(ClientEvents.targetBlock));
        }
        return Double.MAX_VALUE;
    }
}
