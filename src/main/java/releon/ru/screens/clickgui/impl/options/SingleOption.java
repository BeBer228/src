package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.screens.clickgui.impl.ClickGuiController;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

/** Выпадающий список ("Style: Rounded ⌄"). */
public class SingleOption extends ClickGuiOption {
    private static final float ITEM_HEIGHT = 13.0f;
    private static final float CONTROL_HEIGHT = 12.0f;

    private final String[] values;
    private final SmoothAnimation openAnimation = new SmoothAnimation();
    private final ModeSetting setting;
    private String value;
    private boolean open;
    private float controlX;
    private float controlY;
    private float controlWidth;

    public SingleOption(ModeSetting setting) {
        super(setting.getName());
        this.setting = setting;
        this.value = setting.getValue();
        this.values = setting.getModes().toArray(String[]::new);
        openAnimation.set(0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        value = setting.getValue();
        controlWidth = Math.min(Math.max(46.0f, width * 0.46f), 74.0f);
        controlX = x + width - controlWidth;
        controlY = firstLineCenter() - CONTROL_HEIGHT * 0.5f;
        openAnimation.update();
        float openP = openAnimation.get();

        boolean hover = hovered(ClickGuiController.mouseX(), ClickGuiController.mouseY(), controlX, controlY, controlWidth, CONTROL_HEIGHT);
        ClickGuiTheme.field(controlX, controlY, controlWidth, CONTROL_HEIGHT, hover ? 1.0f : 0.0f, openP, alpha);
        String chevron = openP > 0.5f ? "K" : "J";
        Render2D.text(FontType.GUI_ICONS, chevron, controlX + controlWidth - 9.5f, ClickGuiTheme.textTop(FontType.GUI_ICONS, controlY + CONTROL_HEIGHT * 0.5f, 6.6f), 6.6f,
                ClickGuiTheme.mix(ClickGuiTheme.TEXT_MUTED, ClickGuiTheme.ACCENT, openP, alpha));
        renderScrollingText(graphics, "single:value", value, controlX + 5.0f, top(controlY + CONTROL_HEIGHT * 0.5f, ClickGuiTheme.FS_VALUE), ClickGuiTheme.FS_VALUE,
                valueColor(), controlX + 4.0f, controlY, controlWidth - 15.0f, CONTROL_HEIGHT,
                controlX, controlY, controlWidth, CONTROL_HEIGHT);
        renderLabelBlock(graphics, "single", controlX - 6.0f);
    }

    @Override
    public void renderOverlay(GuiGraphics graphics) {
        float progress = openAnimation.get();
        if (progress <= 0.01f) {
            return;
        }
        float popupAlpha = alpha * progress;
        float popupX = controlX;
        float popupY = controlY + CONTROL_HEIGHT + 2.0f;
        float popupW = controlWidth;
        float fullH = values.length * ITEM_HEIGHT + 4.0f;
        float renderH = fullH * (0.6f + 0.4f * progress);

        renderPopupSurface(popupX, popupY, popupW, renderH, popupAlpha);
        double mx = ClickGuiController.mouseX();
        double my = ClickGuiController.mouseY();
        Render2D.pushScissor(graphics, popupX, popupY, popupW, renderH);
        for (int i = 0; i < values.length; i++) {
            float itemY = popupY + 2.0f + i * ITEM_HEIGHT;
            boolean selected = values[i].equals(value);
            boolean hov = progress > 0.85f && hovered(mx, my, popupX, itemY, popupW, ITEM_HEIGHT);
            if (selected || hov) {
                Render2D.rect(popupX + 2.0f, itemY + 0.5f, popupW - 4.0f, ITEM_HEIGHT - 1.0f, 3.0f,
                        selected ? ClickGuiTheme.c(ClickGuiTheme.ACCENT, 34, popupAlpha) : ClickGuiTheme.c(ClickGuiTheme.FIELD_HI, popupAlpha));
            }
            int tc = selected ? ClickGuiTheme.c(ClickGuiTheme.ACCENT, popupAlpha)
                    : ClickGuiTheme.mix(ClickGuiTheme.TEXT_DIM, ClickGuiTheme.TEXT, hov ? 1.0f : 0.0f, popupAlpha);
            renderScrollingText(graphics, "single:popup:" + i, values[i], popupX + 6.0f, top(itemY + ITEM_HEIGHT * 0.5f, ClickGuiTheme.FS_VALUE), ClickGuiTheme.FS_VALUE,
                    tc, popupX + 5.0f, itemY, popupW - 14.0f, ITEM_HEIGHT, popupX, itemY, popupW, ITEM_HEIGHT);
            if (selected) {
                Render2D.rect(popupX + popupW - 7.5f, itemY + ITEM_HEIGHT * 0.5f - 1.5f, 3.0f, 3.0f, 1.5f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, popupAlpha));
            }
        }
        Render2D.popScissor(graphics);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) {
            return false;
        }
        if (isPopupVisible() && popupHovered(event.x(), event.y())) {
            int index = (int) ((event.y() - (controlY + CONTROL_HEIGHT + 4.0f)) / ITEM_HEIGHT);
            index = Math.max(0, Math.min(values.length - 1, index));
            value = values[index];
            setting.setValue(value);
            setOpen(false);
            return true;
        }
        if (hovered(event.x(), event.y(), controlX, controlY, controlWidth, CONTROL_HEIGHT)) {
            setOpen(!open);
            return true;
        }
        if (open) {
            setOpen(false);
        }
        return false;
    }

    @Override
    public boolean wantsMousePriority(MouseButtonEvent event) {
        return isPopupVisible() && popupHovered(event.x(), event.y());
    }

    @Override
    public boolean hasOpenPopup() {
        return isPopupVisible();
    }

    @Override
    public void closePopup() {
        if (open) {
            setOpen(false);
        }
    }

    private boolean popupHovered(double mx, double my) {
        return hovered(mx, my, controlX, controlY + CONTROL_HEIGHT + 2.0f, controlWidth, values.length * ITEM_HEIGHT + 4.0f);
    }

    private boolean isPopupVisible() {
        return open || openAnimation.get() > 0.01f || openAnimation.isAlive();
    }

    private void setOpen(boolean open) {
        if (this.open == open) {
            return;
        }
        this.open = open;
        openAnimation.run(open ? 1.0 : 0.0, 0.16, Easings.CUBIC_OUT);
    }
}
