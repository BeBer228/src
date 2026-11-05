package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.pipeline.ClientPipelines;

/**
 * "Атом": три наклонные орбиты вокруг цели, по каждой летит комета с хвостом.
 * При ударе кометы резко разгоняются, орбиты "вздуваются" и цвет уходит во второй.
 */
public final class AtomTargetEspRenderer {
    private static final NeonKit ORBITS = new NeonKit(8);
    private static final NeonKit COMETS = new NeonKit(16);
    private static final int ORBIT_COUNT = 3;
    private static final int TAIL_POINTS = 18;
    private static final float TAIL_LENGTH = 1.35f; // радианы

    private static float phase;
    private static float spin;
    private static long lastMs;

    private AtomTargetEspRenderer() {
    }

    public static void reset() {
        phase = 0.0f;
        spin = 0.0f;
        lastMs = 0L;
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider,
                              TargetEspRenderContext context, Vec3 cam) {
        LivingEntity target = context.target();
        if (target == null || context.alpha() <= 0.01f) {
            return;
        }
        long now = context.frameTimeMs();
        float dt = lastMs == 0L ? 0.0f : Math.min((now - lastMs) / 1000.0f, 0.1f);
        lastMs = now;

        float hurt = LightningFx.clamp(context.hurtProgress(), 0.0f, 1.0f);
        float impact = context.chainImpactProgress();
        phase += dt * (2.6f + 7.0f * hurt);
        spin += dt * (0.35f + 1.2f * hurt);

        float height = Math.max(target.getBbHeight(), 0.6f);
        float cy = height * 0.55f;
        float radius = (Math.max(target.getBbWidth() * 0.5f, 0.2f) + 0.42f) * (1.0f + 0.18f * impact);

        ORBITS.begin();
        COMETS.begin();

        for (int k = 0; k < ORBIT_COUNT; k++) {
            float tilt = (float) Math.toRadians(62.0);
            float yaw = spin + k * (float) (Math.PI * 2.0 / ORBIT_COUNT);
            float tiltSin = (float) Math.sin(tilt), tiltCos = (float) Math.cos(tilt);
            float yawSin = (float) Math.sin(yaw), yawCos = (float) Math.cos(yaw);

            // сама орбита — тонкая и полупрозрачная
            LightningFx.Line ring = ORBITS.line(0.32f, 0.35f + 0.3f * impact);
            if (ring != null) {
                for (int i = 0; i <= 56; i++) {
                    float a = (float) (Math.PI * 2.0 * i / 56);
                    addOrbitPoint(ring, a, radius, cy, tiltSin, tiltCos, yawSin, yawCos);
                }
            }

            // комета
            float head = phase * (k % 2 == 0 ? 1.0f : -1.0f) + k * 2.1f;
            float dir = k % 2 == 0 ? 1.0f : -1.0f;
            LightningFx.Line tail = COMETS.line(1.15f + 0.4f * hurt, 1.0f);
            if (tail != null) {
                for (int i = 0; i < TAIL_POINTS; i++) {
                    float t = (float) i / (TAIL_POINTS - 1);
                    float a = head - dir * TAIL_LENGTH * (1.0f - t);
                    addOrbitPoint(tail, a, radius, cy, tiltSin, tiltCos, yawSin, yawCos);
                }
                NeonKit.cometTail(tail);
                int last = tail.n - 1;
                COMETS.spark(tail.x[last], tail.y[last], tail.z[last], 1.0f);
            }
        }

        VertexConsumer consumer = provider.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS);
        PoseStack.Pose entry = stack.last();
        float px = NeonKit.px(cam, cy);
        int color = LightningFx.hitColor(context);
        ORBITS.flush(consumer, entry, cam, px, context.secondaryColor(), context.alpha() * 0.8f);
        COMETS.flush(consumer, entry, cam, px, color, context.alpha());
    }

    private static void addOrbitPoint(LightningFx.Line l, float a, float r, float cy,
                                      float tiltSin, float tiltCos, float yawSin, float yawCos) {
        // круг в плоскости XZ
        float x = (float) Math.cos(a) * r;
        float z = (float) Math.sin(a) * r;
        // наклон вокруг X
        float y = -z * tiltSin;
        z = z * tiltCos;
        // поворот вокруг Y
        float rx = x * yawCos - z * yawSin;
        float rz = x * yawSin + z * yawCos;
        l.add(rx, cy + y, rz);
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.WORLD_PARTICLES_QUADS);
    }
}
