package releon.ru.api.drag.impl;

import releon.ru.api.module.impl.visual.Hud;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.blur.BuiltBlur;

final class HudRenderCompat {
    private static final float NO_BLUR_RECT_ALPHA_SCALE = 200.0F / 255.0F;

    private HudRenderCompat() {
    }

    static void background(float x, float y, float width, float height, float radius, float blurRadius, float smoothness, int color) {
        if (Hud.isBlurEnabled()) {
            Render2D.blur(x, y, width, height, radius, blurRadius, smoothness, color);
            return;
        }
        Render2D.rect(x, y, width, height, radius, noBlurRectColor(color));
    }

    static void background(BuiltBlur blur) {
        if (blur == null) {
            return;
        }
        if (Hud.isBlurEnabled()) {
            Render2D.blur(blur);
            return;
        }
        Render2D.rect(
                blur.x(), blur.y(), blur.width(), blur.height(),
                blur.radiusTopLeft(), blur.radiusTopRight(), blur.radiusBottomRight(), blur.radiusBottomLeft(),
                noBlurRectColor(blur.color())
        );
    }

    static void glow(String texture, float x, float y, float width, float height, float radius, int color) {
        if (Hud.isGlowEnabled()) {
            Render2D.image(texture, x, y, width, height, radius, color);
        }
    }

    static boolean glowEnabled() {
        return Hud.isGlowEnabled();
    }

    private static int noBlurRectColor(int color) {
        return ColorUtil.multAlpha(color, NO_BLUR_RECT_ALPHA_SCALE);
    }
}
