package releon.ru.api.module.impl.visual.hudmodern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.Mth;
import releon.ru.api.settings.impl.NumberSetting;

public abstract class NewHudPanel {
    protected final String name;
    private final NumberSetting posX, posY;
    protected float x, y, width = 60, height = 2.5f, screenW = 100, screenH = 100;
    protected float renderScale = 1f;
    protected NewHudPanel(String name, NumberSetting x, NumberSetting y) { this.name = name; posX = x; posY = y; }
    protected boolean chatOpen() { return Minecraft.getInstance().screen instanceof ChatScreen; }
    public abstract void tick();
    public abstract void frame(float dt);
    public abstract void render(GuiGraphics g, float dt);
    public abstract boolean onClick(float mx, float my);
    public void setRenderScale(float value) { renderScale = value <= 0 ? 1 : value; }
    public void setBounds(float x, float y, float width, float height) { this.x = x; this.y = y; this.width = width; this.height = height; }
    public void setViewport(float w, float h) { screenW = w; screenH = h; }
    protected void resolvePosition() { x = posX.getFloat() * Math.max(0, screenW - width); y = posY.getFloat() * Math.max(0, screenH - height); }
    public boolean hitTest(float mx, float my) { return width > 0 && height > 0 && mx >= x - 2 && mx <= x + width + 2 && my >= y - 2 && my <= y + height + 2; }
    public void move(float mx, float my) {
        posX.setValue((double) Mth.clamp(mx / Math.max(1, screenW - width), 0, 1));
        posY.setValue((double) Mth.clamp(my / Math.max(1, screenH - height), 0, 1));
        resolvePosition();
    }
}
