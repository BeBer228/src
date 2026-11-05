package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.pipeline.ClientPipelines;

/** Three procedural, texture-free styles built on the existing NeonKit pipeline. */
public final class CinematicTargetEspRenderer {
    public static final int SINGULARITY = 0;
    public static final int JUDGEMENT = 1;
    public static final int FRACTURE = 2;
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final NeonKit PRIMARY = new NeonKit(112);
    private static final NeonKit SECONDARY = new NeonKit(112);
    private static LivingEntity trackedTarget;
    private static int trackedStyle = -1;
    private static int previousHurt;
    private static long previousNs;
    private static double animationTime;
    private static float impactAge = 2.0f;

    private CinematicTargetEspRenderer() { }

    public static void reset() {
        trackedTarget = null;
        trackedStyle = -1;
        previousHurt = 0;
        previousNs = 0L;
        animationTime = 0.0;
        impactAge = 2.0f;
        PRIMARY.begin();
        SECONDARY.begin();
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider,
                              TargetEspRenderContext context, Vec3 cam,
                              int style, int quality, boolean hitEffects) {
        LivingEntity target = context.target();
        if (target == null || context.alpha() <= 0.01f || style < 0 || style > 2) return;
        long now = System.nanoTime();
        if (trackedTarget != target || trackedStyle != style || previousNs == 0L
                || now - previousNs > 500000000L) {
            reset();
            trackedTarget = target;
            trackedStyle = style;
            previousHurt = target.hurtTime;
            previousNs = now;
        }
        float dt = LightningFx.clamp((now - previousNs) / 1000000000.0f, 0.0f, 0.1f);
        previousNs = now;
        animationTime = (animationTime + dt) % (Math.PI * 400.0);
        impactAge = Math.min(2.0f, impactAge + dt);
        if (hitEffects && target.hurtTime > previousHurt && target.hurtTime > 0) impactAge = 0.0f;
        previousHurt = target.hurtTime;
        if (!hitEffects) impactAge = 2.0f;

        quality = Math.max(0, Math.min(2, quality));
        double distanceSq = cam.x * cam.x + cam.y * cam.y + cam.z * cam.z;
        if (distanceSq > 42.0 * 42.0) quality = 0;
        else if (distanceSq > 22.0 * 22.0) quality = Math.max(0, quality - 1);
        float height = LightningFx.clamp(target.getBbHeight(), 0.6f, 5.0f);
        float radius = LightningFx.clamp(target.getBbWidth() * 0.5f + 0.42f, 0.58f, 2.0f);
        float t = (float) animationTime;
        float hit = hitEffects ? (float) Math.exp(-impactAge * 6.0f) : 0.0f;
        // No entry flash: a pulse exists only after an observed increase in hurtTime.
        if (impactAge >= 1.0f) hit = 0.0f;
        int segments = 24 + quality * 12;
        PRIMARY.begin();
        SECONDARY.begin();
        if (style == SINGULARITY) singularity(height, radius, t, hit, quality, segments);
        else if (style == JUDGEMENT) judgement(height, radius, t, hit, quality, segments, cam);
        else fracture(height, radius, t, hit, quality, segments);
        if (hitEffects && impactAge < 0.75f) shockwave(radius, impactAge / 0.75f, segments);

        VertexConsumer consumer = provider.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS);
        PoseStack.Pose pose = stack.last();
        float px = NeonKit.px(cam, height * 0.5f);
        int primary = hitEffects ? LightningFx.hitColor(context) : context.primaryColor();
        PRIMARY.flush(consumer, pose, cam, px, primary, context.alpha() * 0.88f);
        SECONDARY.flush(consumer, pose, cam, px, context.secondaryColor(), context.alpha() * 0.78f);
    }

    private static void singularity(float h, float r, float t, float hit, int quality, int segments) {
        float cy = h * 0.54f;
        float spin = t * 0.65f;
        float expansion = 1.0f + hit * 0.16f;
        // Precessing accretion rings: a narrow waist, broad luminous outer rim.
        for (int ring = 0; ring < 3 + quality; ring++) {
            float radius = r * (0.78f + ring * 0.17f) * expansion;
            LightningFx.Line line = (ring % 2 == 0 ? PRIMARY : SECONDARY).line(0.34f + ring * 0.08f, 0.34f + ring * 0.08f);
            if (line == null) continue;
            for (int i = 0; i <= segments; i++) {
                float a = TAU * i / segments;
                float y = cy + (float) Math.sin(a) * radius * 0.29f;
                float x = (float) Math.cos(a) * radius;
                float z = (float) Math.sin(a) * radius * 0.94f;
                rotated(line, x, y, z, spin);
            }
        }
        // In-falling spiral streams; the thick luminous end orbits the waist.
        int streams = 4 + quality * 2;
        for (int stream = 0; stream < streams; stream++) {
            LightningFx.Line line = PRIMARY.line(0.62f + hit * 0.20f, 0.82f);
            if (line == null) continue;
            int points = 16 + quality * 6;
            float start = TAU * stream / streams + t * 1.5f;
            for (int i = 0; i < points; i++) {
                float u = i / (float) (points - 1);
                float a = start + u * 2.9f;
                float rr = r * (1.62f - u * 0.92f) * expansion;
                float y = cy + (1.0f - u) * h * 0.30f * (stream % 2 == 0 ? 1 : -1);
                line.add((float) Math.cos(a) * rr, y, (float) Math.sin(a) * rr);
            }
            NeonKit.cometTail(line);
            int last = line.n - 1;
            PRIMARY.spark(line.x[last], line.y[last], line.z[last], 0.75f);
        }
        // Two tapering polar jets; curved, never full-screen flash panels.
        for (int side = -1; side <= 1; side += 2) {
            for (int strand = 0; strand < 2; strand++) {
                LightningFx.Line jet = SECONDARY.line(0.62f, 0.55f + hit * 0.25f);
                if (jet == null) continue;
                for (int i = 0; i <= 16; i++) {
                    float u = i / 16.0f;
                    float a = spin * 2.0f + strand * (float) Math.PI + u * 3.0f;
                    float rr = r * (0.08f + u * 0.16f);
                    jet.add((float) Math.cos(a) * rr, cy + side * u * h * 0.66f, (float) Math.sin(a) * rr);
                    jet.w[i] = 1.0f - u * 0.85f;
                    jet.a[i] = (1.0f - u) * (1.0f - u);
                }
            }
        }
    }

    private static void judgement(float h, float r, float t, float hit, int quality, int segments, Vec3 cam) {
        float crownY = h + 0.30f + (float) Math.sin(t * 1.3f) * 0.05f;
        NeonKit.circle(SECONDARY.line(0.55f, 0.70f), crownY, r * 0.74f, segments, t * 0.28f);
        NeonKit.circle(PRIMARY.line(0.30f, 0.50f), crownY + 0.07f, r * 0.90f, segments, -t * 0.35f);
        // Suspended blades dive partway around the target on each damage pulse.
        int blades = 4 + quality * 2;
        for (int i = 0; i < blades; i++) {
            float a = TAU * i / blades + t * 0.35f;
            float rr = r * (1.18f + hit * 0.18f);
            float x = (float) Math.cos(a) * rr, z = (float) Math.sin(a) * rr;
            float y = h * 0.90f + 0.35f + 0.08f * (float) Math.sin(t * 1.4f + i) - hit * h * 0.42f;
            blade(x, y, z, a, 0.42f + h * 0.13f, 0.09f + r * 0.06f, hit);
        }
        // Three angular feather pairs form a camera-facing celestial silhouette.
        double yaw = Math.atan2(cam.x, cam.z);
        float bx = (float) Math.cos(yaw), bz = -(float) Math.sin(yaw);
        int feathers = 2 + quality;
        for (int side = -1; side <= 1; side += 2) {
            for (int feather = 0; feather < feathers; feather++) {
                float spread = r * (1.00f + feather * 0.34f) * (1.0f + hit * 0.12f);
                float y = h * 0.65f - feather * 0.08f;
                float lift = h * (0.26f + feather * 0.055f) + (float) Math.sin(t * 1.1f) * 0.06f;
                LightningFx.Line wing = SECONDARY.line(0.44f + hit * 0.12f, 0.66f);
                if (wing == null) continue;
                wing.add(side * bx * r * 0.18f, y, side * bz * r * 0.18f);
                wing.add(side * bx * spread * 0.58f, y + lift * 0.65f, side * bz * spread * 0.58f);
                wing.add(side * bx * spread, y + lift, side * bz * spread);
                wing.add(side * bx * spread * 0.79f, y + lift * 0.16f, side * bz * spread * 0.79f);
                wing.add(side * bx * spread * 0.28f, y - lift * 0.23f, side * bz * spread * 0.28f);
                NeonKit.fadeEnds(wing, 0.5f);
                SECONDARY.spark(side * bx * spread, y + lift, side * bz * spread, 0.65f);
            }
        }
        // A slowly rotating ground sigil connects the floating crown to the floor.
        for (int tri = 0; tri < 2; tri++) {
            LightningFx.Line star = PRIMARY.line(0.40f, 0.53f);
            if (star == null) continue;
            for (int i = 0; i <= 3; i++) {
                float a = -t * 0.22f + tri * (float) Math.PI / 3.0f + TAU * i / 3.0f;
                star.add((float) Math.cos(a) * r * 1.16f, 0.035f, (float) Math.sin(a) * r * 1.16f);
            }
        }
    }

    private static void blade(float x, float y, float z, float a, float length, float width, float hit) {
        float tx = -(float) Math.sin(a), tz = (float) Math.cos(a);
        LightningFx.Line edge = PRIMARY.line(0.70f + hit * 0.18f, 0.92f);
        if (edge != null) {
            edge.add(x, y - length, z);
            edge.add(x + tx * width, y - length * 0.30f, z + tz * width);
            edge.add(x, y + length * 0.48f, z);
            edge.add(x - tx * width, y - length * 0.30f, z - tz * width);
            edge.add(x, y - length, z);
        }
        LightningFx.Line core = SECONDARY.line(0.33f, 0.62f);
        if (core != null) { core.add(x, y - length, z); core.add(x, y + length * 0.48f, z); }
        LightningFx.Line hilt = SECONDARY.line(0.55f, 0.80f);
        if (hilt != null) {
            hilt.add(x - tx * width * 1.6f, y + length * 0.28f, z - tz * width * 1.6f);
            hilt.add(x, y + length * 0.20f, z);
            hilt.add(x + tx * width * 1.6f, y + length * 0.28f, z + tz * width * 1.6f);
        }
    }

    private static void fracture(float h, float r, float t, float hit, int quality, int segments) {
        int shards = 4 + quality * 2;
        float spread = 1.0f + hit * 0.34f;
        for (int i = 0; i < shards; i++) {
            float a = TAU * i / shards + t * 0.28f;
            float rr = r * (1.08f + 0.10f * (float) Math.sin(t + i)) * spread;
            float y = h * (0.33f + 0.40f * ((i % 3) / 2.0f)) + (float) Math.sin(t * 1.6f + i) * 0.11f;
            crystal((float) Math.cos(a) * rr, y, (float) Math.sin(a) * rr,
                    a, 0.23f + h * (0.10f + (i % 2) * 0.06f), 0.12f + r * 0.08f, hit);
        }
        // Broken portal hoops interleave instead of forming one solid luminous cage.
        for (int tier = 0; tier < 3; tier++) {
            int pieces = 4 + quality;
            for (int piece = 0; piece < pieces; piece++) {
                LightningFx.Line arc = SECONDARY.line(0.32f, 0.48f);
                if (arc == null) continue;
                for (int j = 0; j <= 6; j++) {
                    float a = TAU * (piece + j / 9.0f) / pieces + t * (tier % 2 == 0 ? .24f : -.20f);
                    arc.add((float) Math.cos(a) * r * 1.13f, h * tier * 0.5f + .025f, (float) Math.sin(a) * r * 1.13f);
                }
                NeonKit.fadeEnds(arc, 0.65f);
            }
        }
        // Stable procedural floor cracks: no per-frame random number regeneration.
        for (int crack = 0; crack < 4 + quality * 2; crack++) {
            LightningFx.Line line = PRIMARY.line(0.42f + hit * 0.15f, 0.57f);
            if (line == null) continue;
            float a = crack * 2.399963f + t * 0.035f;
            for (int j = 0; j <= 7; j++) {
                float u = j / 7.0f;
                float bend = (float) Math.sin(j * 2.1f + crack * 1.7f) * 0.16f * u;
                float rr = r * (0.30f + 1.35f * u) * spread;
                line.add((float) Math.cos(a + bend) * rr, 0.04f, (float) Math.sin(a + bend) * rr);
                line.a[j] = 1.0f - u * 0.80f;
            }
        }
    }

    private static void crystal(float x, float y, float z, float a, float height, float width, float hit) {
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float ax = ca * 0.25f, ay = 0.968246f, az = sa * 0.25f;
        float bx = -sa, bz = ca;
        float cx = -ca * ay, cy = .25f, cz = -sa * ay;
        LightningFx.Line waist = SECONDARY.line(0.35f, 0.57f);
        if (waist != null) {
            for (int i = 0; i <= 4; i++) {
                float p = TAU * i / 4.0f;
                float u = (float) Math.cos(p) * width, v = (float) Math.sin(p) * width;
                waist.add(x + bx * u + cx * v, y + cy * v, z + bz * u + cz * v);
            }
        }
        for (int i = 0; i < 4; i++) {
            float p = TAU * i / 4.0f;
            float u = (float) Math.cos(p) * width, v = (float) Math.sin(p) * width;
            LightningFx.Line edge = PRIMARY.line(0.55f + hit * 0.20f, 0.84f);
            if (edge == null) continue;
            edge.add(x + ax * height, y + ay * height, z + az * height);
            edge.add(x + bx * u + cx * v, y + cy * v, z + bz * u + cz * v);
            edge.add(x - ax * height * 0.86f, y - ay * height * 0.86f, z - az * height * 0.86f);
        }
        PRIMARY.spark(x + ax * height, y + ay * height, z + az * height, .72f);
    }

    private static void shockwave(float radius, float progress, int segments) {
        float fade = (1.0f - progress) * (1.0f - progress);
        float eased = TargetEspMath.easeOutCubic(progress);
        NeonKit.circle(PRIMARY.line(0.70f * fade + 0.15f, fade), .045f, radius * (0.90f + eased * 1.75f), segments, 0.0f);
        NeonKit.circle(SECONDARY.line(0.38f, fade * .65f), .065f, radius * (0.70f + eased * 1.38f), segments, 0.0f);
    }

    private static void rotated(LightningFx.Line line, float x, float y, float z, float yaw) {
        float c = (float) Math.cos(yaw), s = (float) Math.sin(yaw);
        line.add(x * c - z * s, y, x * s + z * c);
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.WORLD_PARTICLES_QUADS);
    }
}
