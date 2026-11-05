package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;

public class ColorOption extends ClickGuiOption {
    private static final int[] HUE_COLORS = {
            Color.HSBtoRGB(0.0f, 1.0f, 1.0f),
            Color.HSBtoRGB(1.0f / 6.0f, 1.0f, 1.0f),
            Color.HSBtoRGB(2.0f / 6.0f, 1.0f, 1.0f),
            Color.HSBtoRGB(3.0f / 6.0f, 1.0f, 1.0f),
            Color.HSBtoRGB(4.0f / 6.0f, 1.0f, 1.0f),
            Color.HSBtoRGB(5.0f / 6.0f, 1.0f, 1.0f),
            Color.HSBtoRGB(1.0f, 1.0f, 1.0f)
    };

    private final SmoothAnimation openAnimation = new SmoothAnimation();
    private int hexColor;
    private String hexText;
    private final ColorSetting setting;
    private float hue;
    private float saturation;
    private float brightness;
    private float alphaValue;
    private boolean open;
    private int dragMode;
    private float swatchX;
    private float swatchY;
    private float swatchSize;
    private float popupX;
    private float popupY;
    private float popupWidth;
    private float popupHeight;
    private float paletteX;
    private float paletteY;
    private float paletteSize;
    private float hueX;
    private float hueY;
    private float hueWidth;
    private float hueHeight;
    private float alphaX;
    private float alphaY;
    private float alphaWidth;
    private float alphaHeight;
    private boolean editSecondary;

    public ColorOption(ColorSetting setting) {
        super(setting.getName());
        this.setting = setting;
        applyColor(setting.getCustomColor());
        openAnimation.set(0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        if (dragMode == 0) {
            applyColor(editSecondary ? setting.getSecondary() : setting.getCustomColor());
        }
        swatchSize = 10.0f;
        swatchX = x + width - swatchSize;
        swatchY = firstLineCenter() - swatchSize * 0.5f;
        openAnimation.update();
        float openP = openAnimation.get();

        int current = setting.getValue().getRGB();
        if (current != hexColor || hexText == null) {
            hexColor = current;
            hexText = String.format("#%08X", current);
        }
        String hex = hexText;
        float hexW = textWidth(hex, ClickGuiTheme.FS_VALUE);
        float hexX = swatchX - hexW - 5.0f;
        ClickGuiTheme.text(FontType.SEMIBOLD, hex, hexX, firstLineCenter(), ClickGuiTheme.FS_VALUE,
                ClickGuiTheme.mix(ClickGuiTheme.TEXT_MUTED, ClickGuiTheme.TEXT_DIM, hoverProgress, alpha));

        if (openP > 0.01f) {
            Render2D.outline(swatchX - 1.6f, swatchY - 1.6f, swatchSize + 3.2f, swatchSize + 3.2f, 3.6f, 0.8f,
                    ClickGuiTheme.c(ClickGuiTheme.ACCENT, openP * alpha));
        }
        checker(swatchX, swatchY, swatchSize, swatchSize, alpha);
        Render2D.rect(swatchX, swatchY, swatchSize, swatchSize, 2.6f,
                color(red(current), green(current), blue(current), current >>> 24, alpha));
        ClickGuiTheme.border(swatchX, swatchY, swatchSize, swatchSize, 2.6f, ClickGuiTheme.white(0.16f * alpha));
        renderLabelBlock(graphics, "color", hexX - 6.0f, swatchX - 6.0f);
    }

    @Override
    public void renderOverlay(GuiGraphics graphics) {
        float progress = openAnimation.get();
        if (progress <= 0.01f) {
            return;
        }

        float popupAlpha = alpha * progress;
        popupWidth = 132.0f;
        popupHeight = 146.0f;
        popupX = x + width - popupWidth;
        popupY = swatchY + swatchSize + 4.0f - (1.0f - progress) * 4.0f;
        paletteX = popupX + 7.0f;
        paletteY = popupY + 26.0f;
        paletteSize = 66.0f;
        hueX = paletteX + paletteSize + 6.0f;
        hueY = paletteY;
        hueWidth = 7.0f;
        hueHeight = paletteSize;
        alphaX = paletteX;
        alphaY = paletteY + paletteSize + 6.0f;
        alphaWidth = paletteSize + hueWidth + 6.0f;
        alphaHeight = 6.0f;

        renderPopupSurface(popupX, popupY, popupWidth, popupHeight, popupAlpha);
        String[] names = {"Свой", "Тема", "Радуга", "Градиент"};
        for (int i = 0; i < names.length; i++) {
            float bx = popupX + 5 + i * 31;
            Render2D.rect(bx, popupY + 6, 29, 14, 3,
                    ClickGuiTheme.c(i == setting.getMode().ordinal() ? ClickGuiTheme.ACCENT_DIM : ClickGuiTheme.FIELD, popupAlpha));
            ClickGuiTheme.textCenter(FontType.SEMIBOLD, names[i], bx + 14.5f, popupY + 13, 4.5f,
                    ClickGuiTheme.c(ClickGuiTheme.TEXT, popupAlpha));
        }
        drawPalette(popupAlpha);
        drawHueSlider(popupAlpha);
        drawAlphaSlider(popupAlpha);
        ClickGuiTheme.text(FontType.SEMIBOLD, "Непрозрачность " + Math.round(alphaValue * 100) + "%",
                paletteX, alphaY + 15, 5.5f, ClickGuiTheme.c(ClickGuiTheme.TEXT_DIM, popupAlpha));
        String detail = switch (setting.getMode()) {
            case GRADIENT -> editSecondary ? "Цвет 2  ·  переключить" : "Цвет 1  ·  переключить";
            case RAINBOW -> ColorSetting.PRESETS[setting.getPreset()];
            case THEME -> "Цвет текущей темы";
            default -> "Собственный цвет";
        };
        ClickGuiTheme.text(FontType.SEMIBOLD, detail, paletteX, popupY + 124, 5.5f,
                ClickGuiTheme.c(ClickGuiTheme.TEXT, popupAlpha));
        if (setting.getMode() == ColorSetting.Mode.GRADIENT || setting.getMode() == ColorSetting.Mode.RAINBOW) {
            ClickGuiTheme.text(FontType.SEMIBOLD, String.format(java.util.Locale.ROOT, "Скорость %.1fx  − / +", setting.getSpeed()),
                    paletteX, popupY + 138, 5.5f, ClickGuiTheme.c(ClickGuiTheme.TEXT_DIM, popupAlpha));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) {
            return false;
        }

        double mouseX = event.x();
        double mouseY = event.y();
        if (isPopupVisible()) {
            if (hovered(mouseX, mouseY, popupX + 5, popupY + 6, 124, 14)) {
                int index = Math.min(3, (int) ((mouseX - popupX - 5) / 31));
                setting.setMode(ColorSetting.Mode.values()[index]);
                editSecondary = false;
                applyColor(setting.getCustomColor());
                return true;
            }
            if (hovered(mouseX, mouseY, popupX + 5, popupY + 116, 122, 14)) {
                if (setting.getMode() == ColorSetting.Mode.GRADIENT) editSecondary = !editSecondary;
                if (setting.getMode() == ColorSetting.Mode.RAINBOW) setting.setPreset((setting.getPreset() + 1) % ColorSetting.PRESETS.length);
                applyColor(editSecondary ? setting.getSecondary() : setting.getCustomColor());
                return true;
            }
            if (hovered(mouseX, mouseY, popupX + 5, popupY + 131, 122, 14)) {
                setting.setSpeed(setting.getSpeed() + (mouseX < popupX + popupWidth / 2 ? -0.1f : 0.1f));
                return true;
            }
            if (hovered(mouseX, mouseY, paletteX, paletteY, paletteSize, paletteSize)) {
                dragMode = 1;
                updatePalette(mouseX, mouseY);
                return true;
            }
            if (hovered(mouseX, mouseY, hueX, hueY, hueWidth, hueHeight)) {
                dragMode = 2;
                updateHue(mouseY);
                return true;
            }
            if (hovered(mouseX, mouseY, alphaX, alphaY, alphaWidth, alphaHeight)) {
                dragMode = 3;
                updateAlpha(mouseX);
                return true;
            }
        }

        if (hovered(mouseX, mouseY, swatchX - 40.0f, swatchY - 4.0f, swatchSize + 44.0f, swatchSize + 8.0f)) {
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
        return isPopupVisible() && hovered(event.x(), event.y(), popupX - 3.0f, popupY - 3.0f, popupWidth + 6.0f, popupHeight + 6.0f);
    }

    @Override
    public boolean hasOpenPopup() {
        return isPopupVisible();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragMode == 0) {
            return false;
        }
        dragMode = 0;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragMode == 0) {
            return false;
        }
        if (dragMode == 1) {
            updatePalette(event.x(), event.y());
        } else if (dragMode == 2) {
            updateHue(event.y());
        } else if (dragMode == 3) {
            updateAlpha(event.x());
        }
        return true;
    }

    @Override
    public void closePopup() {
        if (open) {
            setOpen(false);
        }
    }

    private void drawPalette(float popupAlpha) {
        int hueColor = Color.HSBtoRGB(hue, 1.0f, 1.0f);
        int topLeft = color(255, 255, 255, 255, popupAlpha);
        int topRight = color(red(hueColor), green(hueColor), blue(hueColor), 255, popupAlpha);
        int bottom = color(0, 0, 0, 255, popupAlpha);
        Render2D.rect(paletteX, paletteY, paletteSize, paletteSize, 5.0f * scale, topLeft, topRight, bottom, bottom);
        float selectorX = paletteX + saturation * paletteSize;
        float selectorY = paletteY + (1.0f - brightness) * paletteSize;
        Render2D.outline(selectorX - 3.0f * scale, selectorY - 3.0f * scale, 6.0f * scale, 6.0f * scale, 3.0f * scale, 1.4f * scale,
                color(255, 255, 255, Math.round(245 * popupAlpha), 1.0f));
        Render2D.rect(selectorX - 1.2f * scale, selectorY - 1.2f * scale, 2.4f * scale, 2.4f * scale, 1.0f * scale,
                color(20, 24, 32, Math.round(200 * popupAlpha), 1.0f));
    }

    private void drawHueSlider(float popupAlpha) {
        float segmentHeight = hueHeight / (HUE_COLORS.length - 1);
        for (int i = 0; i < HUE_COLORS.length - 1; i++) {
            int top = HUE_COLORS[i];
            int bottom = HUE_COLORS[i + 1];
            float overlap = 0.8f * scale;
            float segmentY = hueY + i * segmentHeight - (i == 0 ? 0.0f : overlap);
            float segmentBottom = hueY + (i + 1) * segmentHeight + (i == HUE_COLORS.length - 2 ? 0.0f : overlap);
            float radius = 1.8f * scale;
            Render2D.rect(hueX, segmentY, hueWidth, segmentBottom - segmentY,
                    i == 0 ? radius : 0.0f,
                    i == 0 ? radius : 0.0f,
                    i == HUE_COLORS.length - 2 ? radius : 0.0f,
                    i == HUE_COLORS.length - 2 ? radius : 0.0f,
                    color(red(top), green(top), blue(top), 255, popupAlpha),
                    color(red(top), green(top), blue(top), 255, popupAlpha),
                    color(red(bottom), green(bottom), blue(bottom), 255, popupAlpha),
                    color(red(bottom), green(bottom), blue(bottom), 255, popupAlpha));
        }
        Render2D.outline(hueX - 0.5f * scale, hueY - 0.5f * scale, hueWidth + 1.0f * scale, hueHeight + 1.0f * scale,
                2.0f * scale, ClickGuiTheme.line(),
                color(200, 200, 220, Math.round(140 * popupAlpha), 1.0f));
        float selectorY = hueY + hue * hueHeight;
        Render2D.rect(hueX - 1.2f * scale, selectorY - 1.8f * scale, hueWidth + 2.4f * scale, 3.6f * scale, 1.5f * scale,
                color(255, 255, 255, Math.round(245 * popupAlpha), 1.0f));
    }

    private void drawAlphaSlider(float popupAlpha) {
        int curr = currentRgb();
        int r = red(curr);
        int g = green(curr);
        int b = blue(curr);
        checker(alphaX, alphaY, alphaWidth, alphaHeight, popupAlpha);
        Render2D.gradientRect(alphaX, alphaY, (int) alphaWidth, alphaHeight, (int) (2.5f * scale),
                color(r, g, b, 0, popupAlpha),
                color(r, g, b, 255, popupAlpha),
                color(r, g, b, 0, popupAlpha),
                color(r, g, b, 255, popupAlpha));
        Render2D.outline(alphaX - 0.5f * scale, alphaY - 0.5f * scale, alphaWidth + 1.0f * scale, alphaHeight + 1.0f * scale,
                2.5f * scale, ClickGuiTheme.line(), color(200, 200, 220, Math.round(140 * popupAlpha), 1.0f));
        float selectorX = alphaX + alphaValue * alphaWidth;
        Render2D.rect(selectorX - 1.8f * scale, alphaY - 1.0f * scale, 3.6f * scale, alphaHeight + 2.0f * scale, 1.5f * scale,
                color(255, 255, 255, Math.round(245 * popupAlpha), 1.0f));
    }

    private void updatePalette(double mouseX, double mouseY) {
        saturation = clamp((float) ((mouseX - paletteX) / paletteSize), 0.0f, 1.0f);
        brightness = 1.0f - clamp((float) ((mouseY - paletteY) / paletteSize), 0.0f, 1.0f);
        syncSetting();
    }

    private void updateHue(double mouseY) {
        hue = clamp((float) ((mouseY - hueY) / hueHeight), 0.0f, 1.0f);
        syncSetting();
    }

    private void updateAlpha(double mouseX) {
        alphaValue = clamp((float) ((mouseX - alphaX) / alphaWidth), 0.0f, 1.0f);
        syncSetting();
    }

    private void applyColor(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        this.hue = hsb[0];
        this.saturation = hsb[1];
        this.brightness = hsb[2];
        this.alphaValue = color.getAlpha() / 255.0f;
    }

    private int currentRgb() {
        return Color.HSBtoRGB(hue, saturation, brightness);
    }

    private void syncSetting() {
        int color = currentRgb();
        Color value = new Color(red(color), green(color), blue(color), Math.round(alphaValue * 255.0f));
        if (editSecondary) setting.setSecondary(value);
        else setting.setValue(value);
    }

    private void checker(float x, float y, float w, float h, float opacity) {
        float cell = 3f;
        for (int cx = 0; cx * cell < w; cx++) {
            for (int cy = 0; cy * cell < h; cy++) {
                int shade = ((cx + cy) & 1) == 0 ? 95 : 170;
                Render2D.rect(x + cx * cell, y + cy * cell, Math.min(cell, w - cx * cell),
                        Math.min(cell, h - cy * cell), 0, color(shade, shade, shade, 255, opacity));
            }
        }
    }

    private boolean isPopupVisible() {
        return open || openAnimation.get() > 0.01f || openAnimation.isAlive();
    }

    private void setOpen(boolean open) {
        if (this.open == open) {
            return;
        }
        this.open = open;
        openAnimation.run(open ? 1.0 : 0.0, 0.20, Easings.CUBIC_OUT);
    }

    private static int red(int color) {
        return (color >>> 16) & 0xFF;
    }

    private static int green(int color) {
        return (color >>> 8) & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    @Override
    protected int color(int red, int green, int blue, int alpha, float alphaMultiplier) {
        return ColorUtil.rgba(red, green, blue, (int) (alpha * alphaMultiplier));
    }
}
