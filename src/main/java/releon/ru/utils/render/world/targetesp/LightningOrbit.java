package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.pipeline.ClientPipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class LightningOrbit {

    private static final int MAX_BOLTS = 9;
    private static final long CRACKLE_MS = 50L;
    private static final float ORBIT_SPEED = 0.85f; // рад/с

    private static final Random RNG = new Random();
    private static final List<Bolt> BOLTS = new ArrayList<>();

    private static final LightningFx.Line[] POOL = new LightningFx.Line[96];
    private static float[] sparks = new float[4 * 64];
    private static final float[] CX = new float[8];
    private static final float[] CY = new float[8];
    private static final float[] CZ = new float[8];

    private static long nextSpawnMs = 0L;
    private static float lastHurt = 0.0f;

    static {
        for (int i = 0; i < POOL.length; i++) {
            POOL[i] = new LightningFx.Line();
        }
    }

    private LightningOrbit() {
    }

    public static void reset() {
        BOLTS.clear();
        nextSpawnMs = 0L;
        lastHurt = 0.0f;
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider,
                              TargetEspRenderContext context, Vec3 cameraLocalPos) {
        LivingEntity target = context.target();
        if (target == null) {
            return;
        }
        long now = System.currentTimeMillis();
        float height = Math.max(target.getBbHeight(), 0.6f);
        float radius = Math.max(target.getBbWidth() * 0.5f, 0.2f) + 0.2f;

        if (target.isAlive()) {
            if (nextSpawnMs == 0L || now >= nextSpawnMs) {
                if (BOLTS.size() < MAX_BOLTS) {
                    BOLTS.add(spawn(now, height, radius, 1.0f));
                }
                nextSpawnMs = now + 45L + RNG.nextInt(70);
            }
            float hurt = context.hurtProgress();
            if (hurt > lastHurt + 0.25f) {
                for (int i = 0; i < 4; i++) {
                    BOLTS.add(spawn(now, height, radius, 1.45f));
                }
            }
            lastHurt = hurt;
        }
        BOLTS.removeIf(b -> now - b.spawnMs > b.lifeMs);
        if (BOLTS.isEmpty() || context.alpha() <= 0.01f) {
            return;
        }

        float spin = (float) ((now % 600_000L) / 1000.0 * ORBIT_SPEED);
        int lineCount = 0;
        int sparkCount = 0;

        for (Bolt b : BOLTS) {
            float age = (now - b.spawnMs) / (float) b.lifeMs;
            float grow = LightningFx.smooth(age / 0.16f);
            float fadeOut = 1.0f - LightningFx.smooth((age - 0.45f) / 0.55f);
            int frame = (int) ((now - b.spawnMs) / CRACKLE_MS);

            LightningFx.seed(b.seed * 7919 + frame * 131);
            float flicker = 0.72f + 0.28f * LightningFx.rnd01();
            if (LightningFx.rnd01() < 0.12f) {
                flicker *= 0.4f;
            }
            float boltAlpha = fadeOut * flicker * b.power;
            if (boltAlpha <= 0.01f || lineCount >= POOL.length - 4) {
                continue;
            }

            int ctrl = 4;
            for (int k = 0; k < ctrl; k++) {
                float t = k / (float) (ctrl - 1);
                float ang = b.angle + spin * b.dir + b.sweep * t;
                float r = radius * (1.0f + 0.12f * (float) Math.sin(Math.PI * t)) * b.radiusMul;
                CX[k] = (float) Math.cos(ang) * r;
                CY[k] = b.y0 + (b.y1 - b.y0) * t;
                CZ[k] = (float) Math.sin(ang) * r;
            }

            LightningFx.Line main = POOL[lineCount++];
            main.reset();
            LightningFx.jagged(main, CX, CY, CZ, ctrl, 3, 0.36f);
            LightningFx.taper(main, 0.18f);
            main.width = b.power;
            main.alpha = boltAlpha;
            int fullN = main.n;

            int branches = b.power > 1.0f ? 3 : (LightningFx.rnd01() < 0.65f ? 1 + (LightningFx.rnd01() < 0.4f ? 1 : 0) : 0);
            for (int j = 0; j < branches && lineCount < POOL.length; j++) {
                int idx = 4 + (int) (LightningFx.rnd01() * (fullN - 9));
                float originT = idx / (float) (fullN - 1);
                if (originT > grow) {
                    continue;
                }
                LightningFx.Line br = POOL[lineCount++];
                br.reset();
                float len = 0.12f + 0.16f * LightningFx.rnd01();
                LightningFx.branch(br, main, idx, len * b.power, 0.55f);
                br.width = 0.55f * b.power * main.w[idx];
                br.alpha = boltAlpha * 0.8f;
                br.truncate(LightningFx.clamp((grow - originT) / 0.2f, 0.0f, 1.0f));
            }

            main.truncate(grow);
            if (main.n < 2) {
                continue;
            }

            sparks = ensure(sparks, sparkCount + 2);
            sparkCount = putSpark(sparks, sparkCount, main.x[0], main.y[0], main.z[0], boltAlpha * 0.9f);
            int last = main.n - 1;
            sparkCount = putSpark(sparks, sparkCount, main.x[last], main.y[last], main.z[last],
                    boltAlpha * (grow < 1.0f ? 1.0f : 0.7f));
        }

        if (lineCount == 0) {
            return;
        }

        VertexConsumer consumer = provider.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS);
        PoseStack.Pose entry = stack.last();
        float px = LightningFx.pxScale(cameraLocalPos, height * 0.5f);
        int color = LightningFx.hitColor(context);
        LightningFx.draw(consumer, entry, cameraLocalPos, POOL, lineCount, sparks, sparkCount,
                px, color, context.alpha());
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.WORLD_PARTICLES_QUADS);
    }

    private static Bolt spawn(long now, float height, float radius, float power) {
        Bolt b = new Bolt();
        b.seed = RNG.nextInt();
        b.spawnMs = now;
        b.power = power;
        b.dir = RNG.nextBoolean() ? 1 : -1;
        b.angle = RNG.nextFloat() * (float) (Math.PI * 2.0);
        float sweep = (0.9f + RNG.nextFloat() * 1.55f) * (power > 1.0f ? 1.35f : 1.0f);
        b.sweep = sweep * (RNG.nextBoolean() ? 1.0f : -1.0f);
        float yA = height * (0.05f + RNG.nextFloat() * 0.9f);
        float dy = height * (RNG.nextFloat() - 0.5f) * (power > 1.0f ? 1.1f : 0.75f);
        b.y0 = yA;
        b.y1 = LightningFx.clamp(yA + dy, height * 0.02f, height * 1.02f);
        b.radiusMul = 0.95f + RNG.nextFloat() * 0.15f;
        b.lifeMs = (power > 1.0f ? 420L : 260L) + RNG.nextInt(220);
        return b;
    }

    private static int putSpark(float[] arr, int count, float x, float y, float z, float a) {
        int k = count * 4;
        arr[k] = x;
        arr[k + 1] = y;
        arr[k + 2] = z;
        arr[k + 3] = a;
        return count + 1;
    }

    private static float[] ensure(float[] arr, int count) {
        return count * 4 <= arr.length ? arr : java.util.Arrays.copyOf(arr, Math.max(arr.length * 2, count * 4));
    }

    private static final class Bolt {
        int seed;
        long spawnMs;
        long lifeMs;
        float power;
        int dir;
        float angle;
        float sweep;
        float y0;
        float y1;
        float radiusMul;
    }
}
