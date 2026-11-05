package releon.ru.utils.render.post.fx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.resources.Identifier;
import releon.ru.utils.render.RenderSampler;

/**
 * Shader ESP: берёт ванильный entity outline буфер (силуэты сущностей, которым мы
 * форсим glowing через MinecraftMixin) как маску и рисует поверх сцены
 * мягкое свечение, контур и голографическую заливку с бегущим градиентом.
 */
public final class EspGlowRenderer {
    private static final int UBO_SIZE = 64;
    private static final PostShaderPass PASS = new PostShaderPass(
            "esp_glow",
            Identifier.fromNamespaceAndPath("releon", "post/fx/esp_glow"),
            "EspGlowConfig",
            UBO_SIZE,
            "MaskSampler"
    );
    private static final long START_MS = System.currentTimeMillis();

    private EspGlowRenderer() {
    }

    public static boolean isDisabledAfterError() {
        return PASS.isDisabled();
    }

    public static void clear() {
        PASS.close();
    }

    public static void render(RenderTarget target, RenderTarget mask, float radius, float fill, int colorA, int colorB) {
        if (target == null || mask == null || mask.getColorTextureView() == null
                || mask.width != target.width || mask.height != target.height) {
            return;
        }
        float time = (System.currentTimeMillis() - START_MS) / 1000.0F;
        PASS.apply(target, data -> {
            data.putFloat(0, target.width);
            data.putFloat(4, target.height);
            data.putFloat(8, time);
            data.putFloat(12, radius);
            putColor(data, 16, colorA);
            putColor(data, 32, colorB);
            data.putFloat(48, fill);
            data.putFloat(52, 1.3F);   // толщина контура, px
            data.putFloat(56, 1.15F);  // сила свечения
            data.putFloat(60, 0.0F);
        }, pass -> pass.bindTexture("MaskSampler", mask.getColorTextureView(), RenderSampler.linear()));
    }

    private static void putColor(java.nio.ByteBuffer data, int offset, int argb) {
        data.putFloat(offset, ((argb >> 16) & 0xFF) / 255.0F);
        data.putFloat(offset + 4, ((argb >> 8) & 0xFF) / 255.0F);
        data.putFloat(offset + 8, (argb & 0xFF) / 255.0F);
        data.putFloat(offset + 12, 1.0F);
    }
}
