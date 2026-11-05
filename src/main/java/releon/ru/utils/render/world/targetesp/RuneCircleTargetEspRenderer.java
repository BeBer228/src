package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.pipeline.ClientPipelines;

import java.util.ArrayList;
import java.util.List;

/**
 * "Магический круг": под ногами цели вращается рунный круг с гексаграммой,
 * над головой парит нимб. Каждый удар выпускает ударную волну по земле
 * и короткий столб света вверх.
 */
public final class RuneCircleTargetEspRenderer {
    private static final NeonKit RINGS = new NeonKit(24);
    private static final NeonKit STAR = new NeonKit(8);
    private static final NeonKit WAVES = new NeonKit(16);
    private static final List<long[]> SHOCKS = new ArrayList<>(); // [spawnMs]
    private static final long SHOCK_MS = 650L;

    private static float spin;
    private static long lastMs;
    private static float lastHurt;

    private RuneCircleTargetEspRenderer() {
    }

    public static void reset() {
        spin = 0.0f;
        lastMs = 0L;
        lastHurt = 0.0f;
        SHOCKS.clear();
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
        if (hurt > lastHurt + 0.25f && SHOCKS.size() < 6) {
            SHOCKS.add(new long[]{now});
        }
        lastHurt = hurt;
        SHOCKS.removeIf(s -> now - s[0] > SHOCK_MS);
        spin += dt * (0.9f + 4.0f * hurt);

        float height = Math.max(target.getBbHeight(), 0.6f);
        float R = (Math.max(target.getBbWidth() * 0.5f, 0.2f) + 0.55f) * (1.0f + 0.12f * impact);
        float y0 = 0.03f;
        float time = now / 1000.0f;

        RINGS.begin();
        STAR.begin();
        WAVES.begin();

        // внешнее и внутреннее кольца
        NeonKit.circle(RINGS.line(0.9f, 1.0f), y0, R, 64, spin);
        NeonKit.circle(RINGS.line(0.45f, 0.75f), y0, R * 0.86f, 64, -spin);
        NeonKit.circle(RINGS.line(0.35f, 0.55f), y0, R * 0.38f, 40, spin * 1.5f);

        // "руны" — засечки между кольцами, крутятся вместе с внешним кольцом
        int runes = 12;
        for (int i = 0; i < runes; i++) {
            float a = spin + (float) (Math.PI * 2.0 * i / runes);
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            float flick = 0.55f + 0.45f * (float) Math.sin(time * 5.0f + i * 1.7f);
            LightningFx.Line rune = RINGS.line(0.5f, flick);
            if (rune == null) {
                break;
            }
            float r1 = R * 0.88f, r2 = R * 0.98f;
            if (i % 3 == 0) {
                // ромбик
                float rm = (r1 + r2) * 0.5f, hw = 0.06f;
                float tx = -sn * hw, tz = cs * hw;
                rune.add(cs * r1, y0, sn * r1);
                rune.add(cs * rm + tx, y0, sn * rm + tz);
                rune.add(cs * r2, y0, sn * r2);
                rune.add(cs * rm - tx, y0, sn * rm - tz);
                rune.add(cs * r1, y0, sn * r1);
            } else {
                rune.add(cs * r1, y0, sn * r1);
                rune.add(cs * r2, y0, sn * r2);
            }
        }

        // гексаграмма (два треугольника), вращается в обратную сторону
        float starR = R * 0.84f;
        for (int tri = 0; tri < 2; tri++) {
            LightningFx.Line l = STAR.line(0.75f, 0.95f);
            for (int i = 0; i <= 3; i++) {
                float a = -spin * 0.6f + tri * (float) (Math.PI / 3.0) + (float) (Math.PI * 2.0 * i / 3.0);
                l.add((float) Math.cos(a) * starR, y0, (float) Math.sin(a) * starR);
                if (i < 3) {
                    STAR.spark((float) Math.cos(a) * starR, y0, (float) Math.sin(a) * starR, 0.85f);
                }
            }
        }

        // нимб над головой
        float bob = 0.05f * (float) Math.sin(time * 2.4f);
        NeonKit.circle(RINGS.line(0.7f, 0.9f), height + 0.28f + bob, 0.26f + 0.06f * impact, 36, spin * 2.0f);

        // ударные волны + столб света
        for (long[] s : SHOCKS) {
            float p = (now - s[0]) / (float) SHOCK_MS;
            float ease = TargetEspMath.easeOutCubic(p);
            float fade = 1.0f - p;
            NeonKit.circle(WAVES.line(1.3f * fade + 0.2f, fade), y0 + 0.01f, R * (1.0f + 1.4f * ease), 72, 0.0f);
            NeonKit.circle(WAVES.line(0.6f, fade * 0.6f), y0 + 0.01f, R * (1.0f + 0.9f * ease), 72, 0.0f);
            LightningFx.Line pillar = WAVES.line(2.2f * fade, fade);
            if (pillar != null) {
                float top = height * 1.6f * ease + 0.2f;
                for (int i = 0; i <= 10; i++) {
                    pillar.add(0.0f, top * i / 10.0f, 0.0f);
                }
                NeonKit.fadeEnds(pillar, 0.6f);
            }
        }

        VertexConsumer consumer = provider.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS);
        PoseStack.Pose entry = stack.last();
        float px = NeonKit.px(cam, 0.0f);
        int hit = LightningFx.hitColor(context);
        float alpha = context.alpha();
        RINGS.flush(consumer, entry, cam, px, hit, alpha);
        STAR.flush(consumer, entry, cam, px, context.secondaryColor(), alpha * 0.9f);
        WAVES.flush(consumer, entry, cam, px, LightningFx.mix(context.secondaryColor(), 0xFFFFFF, 0.25f), alpha);
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.WORLD_PARTICLES_QUADS);
    }
}
