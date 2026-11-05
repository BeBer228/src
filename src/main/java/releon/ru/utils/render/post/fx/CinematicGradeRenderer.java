package releon.ru.utils.render.post.fx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.resources.Identifier;

/** Кинематографичная цветокоррекция мира (вызывается из Ambience.onAfterTranslucent). */
public final class CinematicGradeRenderer {
    private static final int UBO_SIZE = 64;
    private static final PostShaderPass PASS = new PostShaderPass(
            "cinematic_grade",
            Identifier.fromNamespaceAndPath("releon", "post/fx/cinematic_grade"),
            "GradeConfig",
            UBO_SIZE
    );

    private CinematicGradeRenderer() {
    }

    public static boolean isDisabledAfterError() {
        return PASS.isDisabled();
    }

    public static void clear() {
        PASS.close();
    }

    /**
     * @param shadows    цвет теней ARGB (alpha = сила тонирования)
     * @param highlights цвет светов ARGB (alpha = сила тонирования)
     */
    public static void render(RenderTarget target, float exposure, float saturation, int shadows, int highlights,
                              float vignette, float bloom) {
        PASS.apply(target, data -> {
            data.putFloat(0, target.width);
            data.putFloat(4, target.height);
            data.putFloat(8, exposure);
            data.putFloat(12, saturation);
            putColor(data, 16, shadows);
            putColor(data, 32, highlights);
            data.putFloat(48, vignette);
            data.putFloat(52, bloom);
        });
    }

    private static void putColor(java.nio.ByteBuffer data, int offset, int argb) {
        data.putFloat(offset, ((argb >> 16) & 0xFF) / 255.0F);
        data.putFloat(offset + 4, ((argb >> 8) & 0xFF) / 255.0F);
        data.putFloat(offset + 8, (argb & 0xFF) / 255.0F);
        data.putFloat(offset + 12, ((argb >>> 24) & 0xFF) / 255.0F);
    }
}
