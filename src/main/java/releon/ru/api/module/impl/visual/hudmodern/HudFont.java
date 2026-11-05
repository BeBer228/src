package releon.ru.api.module.impl.visual.hudmodern;
import net.minecraft.client.gui.GuiGraphics;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;

public record HudFont(FontType type, int size) {
    private static final HudFont[][] CACHE = new HudFont[FontType.values().length][129];
    public static HudFont of(int size, FontType type) {
        int value = Math.max(1, Math.min(128, size));
        HudFont font = CACHE[type.ordinal()][value];
        if (font == null) CACHE[type.ordinal()][value] = font = new HudFont(type, value);
        return font;
    }
    public float getStringWidth(String text) { return Render2D.textWidth(type, text, size); }
    public float centeredY(float top, float height) { return top + (height - size) * .5f; }
    public void drawString(GuiGraphics g, String text, float x, float y, int color) { PrismDraw.text(g, type, text, x, y, size, color); }
}
