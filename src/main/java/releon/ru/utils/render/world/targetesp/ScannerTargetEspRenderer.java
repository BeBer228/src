package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.pipeline.ClientPipelines;

/**
 * "Сканер": два волнистых кольца сканируют цель вверх-вниз, оставляя за собой
 * затухающие "эхо"-кольца. По углам — неоновые скобки прицела.
 * При ударе кольца сильнее рябят и вспыхивают вторым цветом.
 */
public final class ScannerTargetEspRenderer {
    private static final NeonKit RINGS = new NeonKit(16);
    private static final NeonKit BRACKETS = new NeonKit(12);
    private static final int ECHOES = 4;

    private ScannerTargetEspRenderer() {
    }

    public static void reset() {
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider,
                              TargetEspRenderContext context, Vec3 cam) {
        LivingEntity target = context.target();
        if (target == null || context.alpha() <= 0.01f) {
            return;
        }
        float time = context.frameTimeMs() / 1000.0f;
        float hurt = LightningFx.clamp(context.hurtProgress(), 0.0f, 1.0f);
        float impact = context.chainImpactProgress();

        float height = Math.max(target.getBbHeight(), 0.6f);
        float halfW = Math.max(target.getBbWidth() * 0.5f, 0.2f);
        float R = halfW + 0.25f;

        RINGS.begin();
        BRACKETS.begin();

        for (int ring = 0; ring < 2; ring++) {
            float offset = ring * (float) Math.PI;
            for (int e = 0; e <= ECHOES; e++) {
                float lag = e * 0.09f;
                float s = 0.5f + 0.5f * (float) Math.sin(time * 1.9f + offset - lag * 3.0f);
                float y = 0.05f + (height - 0.05f) * s;
                float echoFade = 1.0f - e / (float) (ECHOES + 1);
                LightningFx.Line l = RINGS.line(e == 0 ? 1.0f : 0.45f, echoFade * echoFade);
                if (l == null) {
                    continue;
                }
                float ripple = 0.05f + 0.16f * impact;
                int seg = 72;
                for (int i = 0; i <= seg; i++) {
                    double a = Math.PI * 2.0 * i / seg;
                    float r = R * (1.0f + ripple * (float) Math.sin(6.0 * a + time * 5.0f + ring * 2.0f))
                            * (1.0f - 0.08f * e);
                    l.add((float) Math.cos(a) * r, y, (float) Math.sin(a) * r);
                }
            }
        }

        // скобки прицела: 4 угла сверху и 4 снизу, повёрнуты вместе со временем
        float yaw = time * 0.6f;
        float br = R + 0.18f + 0.12f * impact;
        float arm = 0.22f;
        for (int c = 0; c < 4; c++) {
            float a = yaw + (float) (Math.PI * 0.5 * c + Math.PI * 0.25);
            float cx = (float) Math.cos(a) * br, cz = (float) Math.sin(a) * br;
            float tx = -(float) Math.sin(a), tz = (float) Math.cos(a);
            for (int v = 0; v < 2; v++) {
                float y = v == 0 ? -0.02f : height + 0.08f;
                float dy = v == 0 ? arm : -arm;
                LightningFx.Line l = BRACKETS.line(0.8f, 0.9f);
                if (l == null) {
                    continue;
                }
                l.add(cx - tx * arm, y, cz - tz * arm);
                l.add(cx, y, cz);
                l.add(cx + tx * arm, y, cz + tz * arm);
                LightningFx.Line up = BRACKETS.line(0.8f, 0.9f);
                if (up != null) {
                    up.add(cx, y, cz);
                    up.add(cx, y + dy, cz);
                }
                BRACKETS.spark(cx, y, cz, 0.7f + 0.3f * hurt);
            }
        }

        VertexConsumer consumer = provider.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS);
        PoseStack.Pose entry = stack.last();
        float px = NeonKit.px(cam, height * 0.5f);
        float alpha = context.alpha();
        RINGS.flush(consumer, entry, cam, px, LightningFx.hitColor(context), alpha);
        BRACKETS.flush(consumer, entry, cam, px, context.secondaryColor(), alpha * 0.9f);
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.WORLD_PARTICLES_QUADS);
    }
}
