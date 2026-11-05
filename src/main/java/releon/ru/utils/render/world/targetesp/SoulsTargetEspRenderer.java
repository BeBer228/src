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

/**
 * "Души": из-под ног цели по спирали поднимаются светящиеся огоньки с хвостами.
 * При ударе из тела вырывается вспышка душ, разлетающихся в стороны.
 */
public final class SoulsTargetEspRenderer {
    private static final int MAX_SOULS = 34;
    private static final int TRAIL = 12;
    private static final float TRAIL_TIME = 0.28f; // сек
    private static final Random RNG = new Random();
    private static final List<Soul> SOULS = new ArrayList<>();
    private static final NeonKit CALM = new NeonKit(MAX_SOULS + 4);
    private static final NeonKit BURST = new NeonKit(MAX_SOULS + 4);

    private static long nextSpawnMs;
    private static float lastHurt;

    private SoulsTargetEspRenderer() {
    }

    public static void reset() {
        SOULS.clear();
        nextSpawnMs = 0L;
        lastHurt = 0.0f;
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider,
                              TargetEspRenderContext context, Vec3 cam) {
        LivingEntity target = context.target();
        if (target == null) {
            return;
        }
        long now = context.frameTimeMs();
        float height = Math.max(target.getBbHeight(), 0.6f);
        float radius = Math.max(target.getBbWidth() * 0.5f, 0.2f) + 0.3f;

        if (target.isAlive()) {
            if (now >= nextSpawnMs && SOULS.size() < MAX_SOULS) {
                SOULS.add(spawn(now, height, radius, false));
                nextSpawnMs = now + 70L + RNG.nextInt(60);
            }
            float hurt = context.hurtProgress();
            if (hurt > lastHurt + 0.25f) {
                for (int i = 0; i < 9 && SOULS.size() < MAX_SOULS + 2; i++) {
                    SOULS.add(spawn(now, height, radius, true));
                }
            }
            lastHurt = hurt;
        }
        SOULS.removeIf(s -> now - s.spawnMs > s.lifeMs);
        if (SOULS.isEmpty() || context.alpha() <= 0.01f) {
            return;
        }

        CALM.begin();
        BURST.begin();
        float[] p = new float[3];
        for (Soul s : SOULS) {
            float age = (now - s.spawnMs) / 1000.0f;
            float life = s.lifeMs / 1000.0f;
            float k = age / life;
            float fade = LightningFx.smooth(k / 0.15f) * (1.0f - LightningFx.smooth((k - 0.6f) / 0.4f));
            if (fade <= 0.01f) {
                continue;
            }
            NeonKit kit = s.burst ? BURST : CALM;
            LightningFx.Line l = kit.line(s.size, fade);
            if (l == null) {
                continue;
            }
            for (int i = 0; i < TRAIL; i++) {
                float t = (float) i / (TRAIL - 1);
                float sampleAge = Math.max(0.0f, age - TRAIL_TIME * (1.0f - t));
                position(s, sampleAge, p);
                l.add(p[0], p[1], p[2]);
            }
            NeonKit.cometTail(l);
            int last = l.n - 1;
            kit.spark(l.x[last], l.y[last], l.z[last], fade);
        }

        VertexConsumer consumer = provider.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS);
        PoseStack.Pose entry = stack.last();
        float px = NeonKit.px(cam, height * 0.5f);
        float alpha = context.alpha();
        CALM.flush(consumer, entry, cam, px, LightningFx.hitColor(context), alpha);
        BURST.flush(consumer, entry, cam, px, context.secondaryColor(), alpha);
    }

    private static void position(Soul s, float age, float[] out) {
        float ang = s.angle + s.dir * age * s.spinSpeed;
        float r = s.radius * (1.0f + 0.22f * (float) Math.sin(age * 4.0f + s.wobble)) + s.outSpeed * age;
        out[0] = (float) Math.cos(ang) * r;
        out[1] = s.y0 + s.rise * age + 0.06f * (float) Math.sin(age * 7.0f + s.wobble);
        out[2] = (float) Math.sin(ang) * r;
    }

    private static Soul spawn(long now, float height, float radius, boolean burst) {
        Soul s = new Soul();
        s.spawnMs = now;
        s.burst = burst;
        s.angle = RNG.nextFloat() * (float) (Math.PI * 2.0);
        s.dir = RNG.nextBoolean() ? 1.0f : -1.0f;
        s.wobble = RNG.nextFloat() * 6.28f;
        if (burst) {
            s.radius = radius * 0.4f;
            s.y0 = height * (0.3f + RNG.nextFloat() * 0.5f);
            s.rise = 0.4f + RNG.nextFloat() * 0.8f;
            s.outSpeed = 1.4f + RNG.nextFloat() * 1.2f;
            s.spinSpeed = 1.5f + RNG.nextFloat() * 1.5f;
            s.lifeMs = 450L + RNG.nextInt(250);
            s.size = 1.1f;
        } else {
            s.radius = radius * (0.85f + RNG.nextFloat() * 0.3f);
            s.y0 = RNG.nextFloat() * 0.15f;
            s.rise = height * (0.55f + RNG.nextFloat() * 0.35f);
            s.outSpeed = 0.0f;
            s.spinSpeed = 1.8f + RNG.nextFloat() * 1.6f;
            s.lifeMs = 1300L + RNG.nextInt(600);
            s.size = 0.65f + RNG.nextFloat() * 0.35f;
        }
        return s;
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.WORLD_PARTICLES_QUADS);
    }

    private static final class Soul {
        long spawnMs;
        long lifeMs;
        boolean burst;
        float angle, dir, wobble, radius, y0, rise, outSpeed, spinSpeed, size;
    }
}
