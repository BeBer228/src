package releon.ru.api.module.impl.visual.hudmodern;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;
public final class HudPaint {
    private static float alpha = 1;
    private HudPaint() {}
    public static float opacity() { return alpha; }
    public static void beginElement(float value) { alpha = value; }
    public static void multiplyElement(float value) { alpha *= Math.max(0, Math.min(1, value)); }
    public static int tint(int color) { return PrismDraw.withAlpha(color, alpha); }
}
