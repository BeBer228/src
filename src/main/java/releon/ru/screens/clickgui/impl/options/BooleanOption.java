package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;

/** Чекбокс справа, как на макете ("Use refresh rate scaling" ✓). */
public class BooleanOption extends ClickGuiOption {
    private static final float BOX = 8.5f;

    private final SmoothAnimation toggleAnimation = new SmoothAnimation();
    private final BooleanSetting setting;
    private float boxX;
    private float boxY;

    public BooleanOption(BooleanSetting setting) {
        super(setting.getName());
        this.setting = setting;
        toggleAnimation.set(Boolean.TRUE.equals(setting.getValue()) ? 1.0 : 0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        boolean enabled = Boolean.TRUE.equals(setting.getValue());
        toggleAnimation.run(enabled ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT, true);
        toggleAnimation.update();

        boxX = x + width - BOX;
        boxY = firstLineCenter() - BOX * 0.5f;
        ClickGuiTheme.checkbox(boxX, boxY, BOX, toggleAnimation.get(), hoverProgress, alpha);
        renderLabelBlock(graphics, "boolean", boxX - 6.0f);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0 || !hovered(event.x(), event.y(), x - 2.0f, y, width + 4.0f, height)) {
            return false;
        }
        boolean next = !Boolean.TRUE.equals(setting.getValue());
        setting.setValue(next);
        toggleAnimation.run(next ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT);
        return true;
    }
}
