package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.pipeline.ClientPipelines;

/**
 * "ДНК": двойная спираль из двух цветов обвивает цель снизу доверху,
 * между нитями — светящиеся "перекладины". При ударе спираль раздувается и крутится быстрее.
 */
public final class HelixTargetEspRenderer {
    private static final NeonKit STRAND_A = new NeonKit(2);
    private static final NeonKit STRAND_B = new NeonKit(2);
    private static final NeonKit RUNGS = new NeonKit(24);
    private static final int POINTS = 48;
    private static final int RUNG_COUNT = 9;

    private static float phase;
    private static long lastMs;

    private HelixTargetEspRenderer() {
    }

    public static void reset() {
        phase = 0.0f;
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
        phase += dt * (1.8f + 6.0f * hurt);

        float height = Math.max(target.getBbHeight(), 0.6f) + 0.15f;
        float baseR = Math.max(target.getBbWidth() * 0.5f, 0.2f) + 0.28f;
        float twist = (float) (Math.PI * 2.0 * 1.25 / height); // ~1.25 оборота на рост
        float time = now / 1000.0f;

        STRAND_A.begin();
        STRAND_B.begin();
        RUNGS.begin();

        LightningFx.Line a = STRAND_A.line(1.0f, 1.0f);
        LightningFx.Line b = STRAND_B.line(1.0f, 1.0f);
        for (int i = 0; i < POINTS; i++) {
            float t = (float) i / (POINTS - 1);
            float y = height * t;
            float r = radius(baseR, t, time, impact);
            float ang = y * twist + phase;
            a.add((float) Math.cos(ang) * r, y, (float) Math.sin(ang) * r);
            b.add((float) Math.cos(ang + Math.PI) * r, y, (float) Math.sin(ang + Math.PI) * r);
        }
        NeonKit.fadeEnds(a, 0.45f);
        NeonKit.fadeEnds(b, 0.45f);

        // перекладины ползут вверх
        float scroll = (time * 0.35f) % (1.0f / RUNG_COUNT);
        for (int k = 0; k < RUNG_COUNT; k++) {
            float t = k / (float) RUNG_COUNT + scroll;
            if (t <= 0.04f || t >= 0.96f) {
                continue;
            }
            float y = height * t;
            float r = radius(baseR, t, time, impact) * 0.97f;
            float ang = y * twist + phase;
            float edgeFade = (float) Math.pow(Math.sin(Math.PI * t), 0.6);
            float x1 = (float) Math.cos(ang) * r, z1 = (float) Math.sin(ang) * r;
            LightningFx.Line rung = RUNGS.line(0.42f, 0.75f * edgeFade);
            if (rung != null) {
                for (int s = 0; s <= 6; s++) {
                    float u = s / 6.0f;
                    rung.add(x1 * (1.0f - 2.0f * u), y, z1 * (1.0f - 2.0f * u));
                }
                NeonKit.fadeEnds(rung, 0.3f);
            }
            RUNGS.spark(x1, y, z1, 0.9f * edgeFade);
            RUNGS.spark(-x1, y, -z1, 0.9f * edgeFade);
        }

        VertexConsumer consumer = provider.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS);
        PoseStack.Pose entry = stack.last();
        float px = NeonKit.px(cam, height * 0.5f);
        int hit = LightningFx.hitColor(context);
        float alpha = context.alpha();
        RUNGS.flush(consumer, entry, cam, px, LightningFx.mix(context.primaryColor(), context.secondaryColor(), 0.5f), alpha * 0.85f);
        STRAND_A.flush(consumer, entry, cam, px, hit, alpha);
        STRAND_B.flush(consumer, entry, cam, px, LightningFx.mix(context.secondaryColor(), 0xFFFFFF, 0.15f * hurt), alpha);
    }

    private static float radius(float base, float t, float time, float impact) {
        // лёгкое "дыхание" + выпуклость в середине при ударе
        float breathe = 1.0f + 0.06f * (float) Math.sin(time * 3.0f + t * 6.0f);
        float bulge = 1.0f + 0.35f * impact * (float) Math.sin(Math.PI * t);
        return base * breathe * bulge;
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.WORLD_PARTICLES_QUADS);
    }
}
