package releon.ru.api.module.impl.visual.esp.prism;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.FontType;

/** Adapts Include's GUI coordinates to the existing batched renderer. */
public final class PrismDraw {
    private static long previousFrame;
    private static float delta = 1f / 60;
    private PrismDraw() {}

    public static float dt() {
        long now = System.nanoTime();
        delta = previousFrame == 0 ? 1f / 60 : Math.min(.1f, Math.max(.001f, (now - previousFrame) / 1e9f));
        previousFrame = now;
        return delta;
    }
    public static float time() { return (System.nanoTime() % 1_000_000_000_000L) / 1e9f; }
    public static void begin(GuiGraphics g) { Render2D.beginFrame(g); }
    public static void flush(GuiGraphics g) { Render2D.flush(); g.nextStratum(); Render2D.beginFrame(g); }
    public static void end(GuiGraphics g) { flush(g); }

    private static void enter(GuiGraphics g) {
        g.pose().pushMatrix();
        float inverse = 1f / Render2DCoordinateSpace.guiIndependentScale();
        g.pose().scale(inverse, inverse);
    }
    public static void roundRect(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        if (w <= 0 || h <= 0 || color >>> 24 == 0) return;
        enter(g);
        Render2D.rect(x, y, w, h, r, color);
        g.pose().popMatrix();
    }
    public static void rect(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
        roundRect(g, x0, y0, x1 - x0, y1 - y0, 0, color);
    }
    public static void vgrad(GuiGraphics g, float x0, float y0, float x1, float y1, int top, int bottom) {
        if (x1 <= x0 || y1 <= y0) return;
        enter(g);
        Render2D.gradientRect(x0, y0, x1 - x0, y1 - y0, 0, top, top, bottom, bottom);
        g.pose().popMatrix();
    }
    public static void glow(GuiGraphics g, float cx, float cy, float rx, float ry, int color) {
        for (int i = 4; i >= 1; i--) {
            float k = i / 4f;
            roundRect(g, cx - rx * k, cy - ry * k, rx * 2 * k, ry * 2 * k,
                    Math.min(rx, ry) * k, setAlpha(color, Math.round((color >>> 24) * .08f)));
        }
    }
    public static void blur(GuiGraphics g, float x, float y, float w, float h, float r) {
        enter(g);
        Render2D.blur(x, y, w, h, r, 5f);
        g.pose().popMatrix();
    }
    public static int interWidth(String text, int size) { return Math.round(Render2D.textWidth(FontType.SEMIBOLD, text, size)); }
    public static int interBoldWidth(String text, int size) { return Math.round(Render2D.textWidth(FontType.BOLD, text, size)); }
    public static float interCenteredY(float y, float h, int size) { return y + (h - size) * .5f; }
    public static float interBoldCenteredY(float y, float h, int size) { return interCenteredY(y, h, size); }
    public static void drawInter(GuiGraphics g, String text, float x, float y, int size, int color) { text(g, FontType.SEMIBOLD, text, x, y, size, color); }
    public static void drawInterBold(GuiGraphics g, String text, float x, float y, int size, int color) { text(g, FontType.BOLD, text, x, y, size, color); }
    public static void text(GuiGraphics g, FontType font, String text, float x, float y, int size, int color) {
        enter(g);
        Render2D.text(font, text, x, y, size, color);
        g.pose().popMatrix();
    }
    public static void imageRegionRaw(GuiGraphics g, float x, float y, float size, Identifier texture,
                                      int color, float radius, float u0, float v0, float u1, float v1) {
        enter(g);
        Render2D.imageUvNearest(texture.toString(), x, y, size, size, radius, 1, u0, v0, u1, v1, color);
        g.pose().popMatrix();
    }
    public static void image(GuiGraphics g, Identifier texture, float x, float y, float size, int color) {
        enter(g);
        Render2D.image(texture.toString(), x, y, size, 0, color);
        g.pose().popMatrix();
    }
    public static void drawInterCentered(GuiGraphics g, String text, float cx, float y, int size, int color) {
        drawInter(g, text, cx - interWidth(text, size) * .5f, y, size, color);
    }
    public static void drawInterBoldCentered(GuiGraphics g, String text, float cx, float y, int size, int color) {
        drawInterBold(g, text, cx - interBoldWidth(text, size) * .5f, y, size, color);
    }
    public static void circle(GuiGraphics g, float cx, float cy, float radius, int color) {
        roundRect(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
    }
    public static void outline(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        enter(g);
        Render2D.outline(x, y, w, h, radius, .8f, color);
        g.pose().popMatrix();
    }
    public static int setAlpha(int color, int alpha) { return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF); }
    public static int withAlpha(int color, float alpha) { return setAlpha(color, Math.round((color >>> 24) * Math.max(0, Math.min(1, alpha)))); }
    public static int lerpColor(int a, int b, float p) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int from = a >>> shift & 255, to = b >>> shift & 255;
            result |= Math.round(from + (to - from) * p) << shift;
        }
        return result;
    }
}
