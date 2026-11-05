package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

/**
 * Слайдер как на макете: название и описание слева, значение справа сверху,
 * тонкий трек с розовой заливкой и круглой ручкой — справа снизу.
 */
public class SliderOption extends ClickGuiOption {
    private final SmoothAnimation progressAnimation = new SmoothAnimation();
    private final SmoothAnimation dragAnimation = new SmoothAnimation();
    private final NumberSetting setting;
    private final String suffix;
    private float value;
    private float min;
    private float max;
    private boolean dragging;
    private float trackX;
    private float trackY;
    private float trackWidth;

    public SliderOption(NumberSetting setting, String suffix) {
        super(setting.getName());
        this.setting = setting;
        this.value = setting.getFloat();
        this.min = (float) setting.getMin();
        this.max = (float) setting.getMax();
        this.suffix = suffix == null ? "" : suffix;
        progressAnimation.set(progress());
        dragAnimation.set(0.0);
    }

    @Override
    public float getHeight() {
        return HEIGHT_WITH_DESC;
    }

    @Override
    protected float firstLineCenter() {
        return y + 8.0f;
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        value = setting.getFloat();
        min = (float) setting.getMin();
        max = (float) setting.getMax();

        progressAnimation.run(progress(), dragging ? 0.06 : 0.18, Easings.CUBIC_OUT, true);
        progressAnimation.update();
        dragAnimation.run(dragging ? 1.0 : 0.0, 0.14, Easings.CUBIC_OUT, true);
        dragAnimation.update();
        float p = clamp(progressAnimation.get(), 0.0f, 1.0f);
        float drag = dragAnimation.get();

        trackWidth = Math.max(40.0f, width * 0.5f);
        trackX = x + width - trackWidth;
        trackY = y + 18.5f;

        // значение
        String valueText = formatValue(value);
        float vw = textWidth(valueText, ClickGuiTheme.FS_VALUE);
        float valueX = x + width - vw;
        ClickGuiTheme.text(FontType.SEMIBOLD, valueText, valueX, firstLineCenter(), ClickGuiTheme.FS_VALUE,
                ClickGuiTheme.mix(ClickGuiTheme.TEXT, ClickGuiTheme.ACCENT, drag, alpha));

        // трек (2 px) + круглая ручка, всё по пиксельной сетке
        float th = 2.0f;
        float ty = ClickGuiTheme.snap(trackY - th * 0.5f);
        ClickGuiTheme.rect(trackX, ty, trackWidth, th, th * 0.5f, ClickGuiTheme.c(ClickGuiTheme.FIELD_HI, alpha));
        float fillW = trackWidth * p;
        if (fillW > 0.5f) {
            ClickGuiTheme.rect(trackX, ty, fillW, th, th * 0.5f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, alpha));
        }
        float knob = 6.0f + 1.0f * Math.max(drag, hoverProgress * 0.6f);
        float kx = ClickGuiTheme.snap(trackX + fillW - knob * 0.5f);
        float ky = ClickGuiTheme.snap(ty + th * 0.5f - knob * 0.5f);
        Render2D.rect(kx, ky, knob, knob, knob * 0.5f, ClickGuiTheme.mix(new int[]{255, 255, 255}, ClickGuiTheme.ACCENT, 0.15f + 0.25f * drag, alpha));

        renderLabelBlock(graphics, "slider", valueX - 6.0f, trackX - 6.0f);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) {
            return false;
        }
        if (hovered(event.x(), event.y(), trackX - 4.0f, trackY - 6.0f, trackWidth + 8.0f, 12.0f)) {
            dragging = true;
            updateValue(event.x());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!dragging) {
            return false;
        }
        updateValue(event.x());
        return true;
    }

    private void updateValue(double mouseX) {
        float nextProgress = clamp((float) ((mouseX - trackX) / trackWidth), 0.0f, 1.0f);
        float raw = min + (max - min) * nextProgress;
        value = Math.round(raw * 10.0f) / 10.0f;
        setting.setValue((double) value);
    }

    private float progress() {
        if (max == min) {
            return 0.0f;
        }
        return clamp((value - min) / (max - min), 0.0f, 1.0f);
    }

    private String formatValue(float displayValue) {
        int scaled = Math.round(displayValue * 10.0f);
        String sign = scaled < 0 ? "-" : "";
        int absolute = Math.abs(scaled);
        String number = absolute % 10 == 0 ? String.valueOf(absolute / 10) : (absolute / 10) + "." + (absolute % 10);
        return sign + number + suffix;
    }
}
