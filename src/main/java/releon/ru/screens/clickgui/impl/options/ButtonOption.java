package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.ButtonSetting;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

/** Кнопка действия: тонкая акцентная обводка, при нажатии — заливка и "пружинка". */
public class ButtonOption extends ClickGuiOption {
    private static final float CONTROL_HEIGHT = 12.0f;

    private final String action;
    private final SmoothAnimation pressAnimation = new SmoothAnimation();
    private final ButtonSetting setting;
    private float controlX;
    private float controlY;
    private float controlWidth;

    public ButtonOption(ButtonSetting setting, String action) {
        super(setting.getName());
        this.setting = setting;
        this.action = action == null ? "Apply" : action;
        pressAnimation.set(0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        pressAnimation.update();
        float press = pressAnimation.get();
        float tw = textWidth(action, ClickGuiTheme.FS_VALUE);
        controlWidth = Math.max(36.0f, tw + 14.0f);
        controlX = x + width - controlWidth;
        controlY = firstLineCenter() - CONTROL_HEIGHT * 0.5f;

        float shrink = press * 1.2f;
        float bx = controlX + shrink;
        float by = controlY + shrink * 0.5f;
        float bw = controlWidth - shrink * 2.0f;
        float bh = CONTROL_HEIGHT - shrink;
        float fill = Math.max(press, hoverProgress * 0.35f);
        Render2D.rect(bx, by, bw, bh, 3.5f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, Math.round(30 + 200 * fill), alpha));
        ClickGuiTheme.border(bx, by, bw, bh, 3.5f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, 150, alpha));
        ClickGuiTheme.textCenter(FontType.SEMIBOLD, action, bx + bw * 0.5f, by + bh * 0.5f, ClickGuiTheme.FS_VALUE,
                ClickGuiTheme.mix(ClickGuiTheme.ACCENT, ClickGuiTheme.TEXT, Math.max(press, hoverProgress * 0.6f), alpha));
        renderLabelBlock(graphics, "button", controlX - 6.0f);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) {
            return false;
        }
        if (hovered(event.x(), event.y(), controlX - 2.0f, controlY - 2.0f, controlWidth + 4.0f, CONTROL_HEIGHT + 4.0f)) {
            pressAnimation.set(1.0);
            pressAnimation.run(0.0, 0.32, Easings.CUBIC_OUT);
            setting.press();
            return true;
        }
        return false;
    }
}
