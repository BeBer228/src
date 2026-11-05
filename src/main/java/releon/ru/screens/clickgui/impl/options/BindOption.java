package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;

/** Бинд-чип: показывает клавишу, по клику ждёт новую (Esc/Backspace/Delete — сброс). */
public class BindOption extends ClickGuiOption {
    private static final float CHIP_HEIGHT = 12.0f;

    private final BindSetting setting;
    private final SmoothAnimation listenAnimation = new SmoothAnimation();
    private boolean listening;
    private float boxX;
    private float boxY;
    private float boxWidth;

    public BindOption(BindSetting setting) {
        super(setting.getName());
        this.setting = setting;
        listenAnimation.set(0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        listenAnimation.run(listening ? 1.0 : 0.0, 0.16, Easings.CUBIC_OUT, true);
        listenAnimation.update();
        float listenP = listenAnimation.get();

        KeyBind bind = setting.getValue();
        String text = listening ? "Press a key" : (bind != null && bind.isBound() ? bind.getDisplayName() : "None");
        float tw = textWidth(text, ClickGuiTheme.FS_VALUE);
        boxWidth = Math.max(26.0f, Math.min(width * 0.5f, tw + 12.0f));
        boxX = x + width - boxWidth;
        boxY = firstLineCenter() - CHIP_HEIGHT * 0.5f;

        ClickGuiTheme.field(boxX, boxY, boxWidth, CHIP_HEIGHT, hoverProgress, listenP, alpha);
        int tc;
        if (listening) {
            float pulse = 0.55f + 0.45f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 260.0));
            tc = ClickGuiTheme.c(ClickGuiTheme.ACCENT, pulse * alpha);
        } else {
            tc = bind != null && bind.isBound() ? valueColor() : dimLabelColor();
        }
        renderScrollingText(graphics, "bind:value", text, boxX + Math.max(5.0f, (boxWidth - tw) * 0.5f), top(boxY + CHIP_HEIGHT * 0.5f, ClickGuiTheme.FS_VALUE),
                ClickGuiTheme.FS_VALUE, tc, boxX + 3.0f, boxY, boxWidth - 6.0f, CHIP_HEIGHT, boxX, boxY, boxWidth, CHIP_HEIGHT);
        renderLabelBlock(graphics, "bind", boxX - 6.0f);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (listening) {
            setting.setValue(KeyBind.mouse(event.button()));
            listening = false;
            return true;
        }
        if (event.button() == 0 && hovered(event.x(), event.y(), boxX - 2.0f, boxY - 2.0f, boxWidth + 4.0f, CHIP_HEIGHT + 4.0f)) {
            listening = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean wantsMousePriority(MouseButtonEvent event) {
        return listening;
    }

    @Override
    public boolean capturesKeyboard() {
        return listening;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!listening) {
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
            setting.setValue(KeyBind.NONE);
        } else {
            setting.setValue(KeyBind.keyboard(event.key()));
        }
        listening = false;
        return true;
    }

    @Override
    public void closePopup() {
        listening = false;
    }
}
