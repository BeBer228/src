package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.pipeline.ClientPipelines;

/**
 * Тип 2 — длинные электрические дуги, которые по спирали обвивают цель и вращаются вокруг неё.
 * <p>
 * Каждая дуга живёт по циклу: прорастает сверху вниз → трещит и мерцает → гаснет, затем появляется
 * в новом месте. Короткие ветки "цепляются" за тело цели, на концах — искры.
 */
public final class LightningArcs {

    private static final int ARCS = 3;
    private static final float CYCLE_SPEED = 0.95f; // циклов в секунду
    private static final long CRACKLE_MS = 55L;
    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private static final LightningFx.Line[] POOL = new LightningFx.Line[ARCS * 5];
    private static final float[] SPARKS = new float[4 * ARCS * 2];
    private static final float[] CX = new float[10];
    private static final float[] CY = new float[10];
    private static final float[] CZ = new float[10];

    private static long lastNanos = 0L;
    private static float time = 0.0f;

    static {
        for (int i = 0; i < POOL.length; i++) {
            POOL[i] = new LightningFx.Line();
        }
    }

    private LightningArcs() {
    }

    public static void reset() {
        lastNanos = 0L;
        time = 0.0f;
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider,
                              TargetEspRenderContext context, Vec3 cameraLocalPos) {
        if (context.target() == null || context.alpha() <= 0.01f) {
            return;
        }
        long nowNs = System.nanoTime();
        float dt = lastNanos == 0L ? 1.0f / 60.0f : Math.min((nowNs - lastNanos) / 1.0E9f, 0.05f);
        lastNanos = nowNs;
        time += dt * (1.0f + context.hurtProgress() * 0.8f);

        float height = Math.max(context.target().getBbHeight(), 0.6f);
        float radius = Math.max(context.target().getBbWidth() * 0.5f, 0.2f) + 0.24f;
        float power = 1.0f + context.chainImpactProgress() * 0.35f;
        int crackle = (int) (System.currentTimeMillis() / CRACKLE_MS);

        int lineCount = 0;
        int sparkCount = 0;

        for (int i = 0; i < ARCS; i++) {
            float phaseT = time * CYCLE_SPEED + (float) i / ARCS;
            int cycle = (int) Math.floor(phaseT);
            float ph = phaseT - cycle;

            float grow = LightningFx.smooth(ph / 0.14f);
            float fadeOut = 1.0f - LightningFx.smooth((ph - 0.55f) / 0.35f);
            LightningFx.seed(i * 92821 + cycle * 68917);
            float startAngle = LightningFx.rnd01() * TWO_PI;
            float sweep = (0.55f + 0.35f * LightningFx.rnd01()) * TWO_PI * (LightningFx.rnd() >= 0.0f ? 1.0f : -1.0f);
            float topY = height * (0.82f + 0.14f * LightningFx.rnd01());
            float botY = height * (0.06f + 0.22f * LightningFx.rnd01());
            float rMul = 0.95f + 0.12f * LightningFx.rnd01();
            int arcSeed = (int) (LightningFx.rnd() * 1.0E6f);
            LightningFx.seed(arcSeed + crackle * 31337);
            float flicker = 0.75f + 0.25f * LightningFx.rnd01();
            if (LightningFx.rnd01() < 0.1f) {
                flicker *= 0.45f;
            }
            float arcAlpha = fadeOut * flicker;
            if (arcAlpha <= 0.01f) {
                continue;
            }

            float spin = time * 0.9f;
            int ctrl = 7;
            for (int k = 0; k < ctrl; k++) {
                float t = k / (float) (ctrl - 1);
                float ang = startAngle + spin + sweep * t;
                float r = radius * rMul * (1.0f + 0.06f * (float) Math.sin(t * 9.0f + time * 3.0f));
                CX[k] = (float) Math.cos(ang) * r;
                CY[k] = topY + (botY - topY) * t + height * 0.035f * (float) Math.sin(time * 2.3f + k * 1.7f + i);
                CZ[k] = (float) Math.sin(ang) * r;
            }

            LightningFx.Line main = POOL[lineCount++];
            main.reset();
            LightningFx.jagged(main, CX, CY, CZ, ctrl, 3, 0.32f);
            LightningFx.taper(main, 0.12f);
            main.width = power;
            main.alpha = arcAlpha;
            int fullN = main.n;

            int branches = 2 + (LightningFx.rnd01() < 0.5f ? 1 : 0);
            for (int j = 0; j < branches && lineCount < POOL.length; j++) {
                int idx = 6 + (int) (LightningFx.rnd01() * (fullN - 13));
                float originT = idx / (float) (fullN - 1);
                if (originT > grow) {
                    continue;
                }
                LightningFx.Line br = POOL[lineCount++];
                br.reset();
                float outward = LightningFx.rnd01() < 0.6f ? -0.9f : 0.6f;
                LightningFx.branch(br, main, idx, (0.14f + 0.14f * LightningFx.rnd01()) * power, outward);
                br.width = 0.55f * power * main.w[idx];
                br.alpha = arcAlpha * 0.75f;
                br.truncate(LightningFx.clamp((grow - originT) / 0.18f, 0.0f, 1.0f));
            }

            main.truncate(grow);
            if (main.n < 2) {
                continue;
            }
            int last = main.n - 1;
            sparkCount = put(sparkCount, main.x[0], main.y[0], main.z[0], arcAlpha * 0.85f);
            sparkCount = put(sparkCount, main.x[last], main.y[last], main.z[last], arcAlpha);
        }

        if (lineCount == 0) {
            return;
        }
        VertexConsumer consumer = provider.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS);
        PoseStack.Pose entry = stack.last();
        float px = LightningFx.pxScale(cameraLocalPos, height * 0.5f);
        LightningFx.draw(consumer, entry, cameraLocalPos, POOL, lineCount, SPARKS, sparkCount,
                px, LightningFx.hitColor(context), context.alpha());
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.WORLD_PARTICLES_QUADS);
    }

    private static int put(int count, float x, float y, float z, float a) {
        int k = count * 4;
        SPARKS[k] = x;
        SPARKS[k + 1] = y;
        SPARKS[k + 2] = z;
        SPARKS[k + 3] = a;
        return count + 1;
    }
}
