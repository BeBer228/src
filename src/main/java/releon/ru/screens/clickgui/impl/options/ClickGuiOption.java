package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.Setting;
import releon.ru.screens.clickgui.impl.ClickGuiController;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Базовая строка настройки внутри карточки модуля.
 * Слева — название (+ описание серым под ним), справа — контрол.
 */
public abstract class ClickGuiOption {
    protected static final float HEIGHT = 19.0f;
    protected static final float HEIGHT_WITH_DESC = 25.0f;
    protected static final float PAD_X = 0.0f;
    /** Расстояние от центра названия до центра описания под ним. */
    protected static final float DESC_LINE_OFFSET = 9.0f;
    private final ClickGuiTheme.Ellipsis descCache = new ClickGuiTheme.Ellipsis();

    private final String name;
    private String description = "";
    protected float x;
    protected float y;
    protected float width;
    protected float height;
    protected float scale = 1.0f;
    protected float alpha;
    protected float animationAlpha;
    protected float hoverProgress;
    private final SmoothAnimation hoverAnimation = new SmoothAnimation();
    private final SmoothAnimation visibilityAnimation = new SmoothAnimation();
    private final Map<String, SmoothAnimation> textScrollAnimations = new HashMap<>();
    private BooleanSupplier visibleSupplier = () -> true;
    private float fadeMultiplier = 1.0f;
    private boolean interactiveThisFrame;

    protected ClickGuiOption(String name) {
        this.name = name == null ? "" : name;
        hoverAnimation.set(0.0);
        visibilityAnimation.set(1.0);
    }

    public float getHeight() {
        return hasDescription() ? HEIGHT_WITH_DESC : HEIGHT;
    }

    public final void render(GuiGraphics graphics, float x, float y, float width, float scale, float alpha) {
        float sw = width * scale;
        float sh = getHeight() * scale;
        this.x = x;
        this.y = y;
        this.width = sw;
        this.height = sh;
        this.scale = scale;
        this.animationAlpha = alpha;
        this.alpha = alpha * fadeMultiplier;
        this.interactiveThisFrame = this.alpha > 0.01f && sw > 0.0f && sh > 0.0f;

        boolean hover = interactiveThisFrame && hovered(ClickGuiController.mouseX(), ClickGuiController.mouseY(), x - 4.0f, y, sw + 8.0f, sh);
        hoverAnimation.run(hover ? 1.0 : 0.0, 0.12, Easings.CUBIC_OUT, true);
        hoverAnimation.update();
        hoverProgress = hoverAnimation.get();

        renderControl(graphics, x, y, sw, sh, scale, this.alpha);
    }

    public void resetInteractionFrame() {
        interactiveThisFrame = false;
    }

    public void setSetting(Setting<?> setting) {
        if (setting == null) {
            setVisibleSupplier(null);
            description = "";
            return;
        }
        description = ClickGuiTheme.description(setting);
        if (description.equalsIgnoreCase(name)) {
            description = "";
        }
        setVisibleSupplier(setting::isVisible);
    }

    public void setVisibleSupplier(BooleanSupplier visibleSupplier) {
        this.visibleSupplier = visibleSupplier == null ? () -> true : visibleSupplier;
        visibilityAnimation.set(this.visibleSupplier.getAsBoolean() ? 1.0 : 0.0);
    }

    public void updateVisibility() {
        boolean visible = visibleSupplier.getAsBoolean();
        visibilityAnimation.run(visible ? 1.0 : 0.0, 0.20, Easings.CUBIC_OUT, true);
        visibilityAnimation.update();
        if (!visible) {
            closePopup();
        }
    }

    public float visibilityProgress() {
        return clamp(visibilityAnimation.get(), 0.0f, 1.0f);
    }

    public boolean isVisibleForRender() {
        return visibilityProgress() > 0.01f;
    }

    public boolean isVisibleForInteraction() {
        return interactiveThisFrame && visibleSupplier.getAsBoolean() && visibilityProgress() > 0.85f;
    }

    public void renderOverlay(GuiGraphics graphics) {
    }

    public void setFadeMultiplier(float fadeMultiplier) {
        this.fadeMultiplier = clamp(fadeMultiplier, 0.0f, 1.0f);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return false;
    }

    public boolean wantsMousePriority(MouseButtonEvent event) {
        return false;
    }

    public boolean hasOpenPopup() {
        return false;
    }

    /** true, если строка сейчас перехватывает клавиатуру (ввод текста, ожидание бинда). */
    public boolean capturesKeyboard() {
        return false;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        return false;
    }

    public void closePopup() {
    }

    protected abstract void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha);

    protected String name() {
        return name;
    }

    protected String description() {
        return description;
    }

    protected boolean hasDescription() {
        return !description.isEmpty();
    }

    // ---------------------------------------------------------------- layout helpers

    /** Y центра "первой строки" (там, где название). Контролы выравниваются по ней. */
    protected float firstLineCenter() {
        return y + (hasDescription() ? 8.0f : height * 0.5f);
    }

    /** Название + описание слева. clipRight — правая граница текста (до контрола). */
    protected void renderLabelBlock(GuiGraphics graphics, String key, float clipRight) {
        renderLabelBlock(graphics, key, clipRight, clipRight);
    }

    protected void renderLabelBlock(GuiGraphics graphics, String key, float clipRight, float descClipRight) {
        float textX = x;
        float clipWidth = Math.max(0.0f, clipRight - textX);
        float descWidth = Math.max(0.0f, descClipRight - textX);
        float labelCy = firstLineCenter();
        renderScrollingText(graphics, "label:" + key, name, textX, top(labelCy, ClickGuiTheme.FS_LABEL), ClickGuiTheme.FS_LABEL, labelColor(),
                textX, y, clipWidth, height, textX, y, clipWidth, height);
        if (hasDescription()) {
            String desc = descCache.get(FontType.SEMIBOLD, description, ClickGuiTheme.FS_DESC, descWidth);
            ClickGuiTheme.text(FontType.SEMIBOLD, desc, textX, labelCy + DESC_LINE_OFFSET, ClickGuiTheme.FS_DESC, dimLabelColor());
        }
    }

    /** Верх текста по центру строки (одна формула для всех контролов). */
    protected static float top(float centerY, float size) {
        return ClickGuiTheme.textTop(FontType.SEMIBOLD, centerY, size);
    }

    protected boolean hovered(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    protected float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    protected float textWidth(String text, float size) {
        return Render2D.textWidth(FontType.SEMIBOLD, text, size);
    }

    protected float visibleAlpha() {
        return alpha >= 0.98f ? 1.0f : alpha;
    }

    protected int labelColor() {
        return ClickGuiTheme.c(ClickGuiTheme.TEXT, visibleAlpha());
    }

    protected int dimLabelColor() {
        return ClickGuiTheme.c(ClickGuiTheme.TEXT_MUTED, visibleAlpha());
    }

    protected int valueColor() {
        return ClickGuiTheme.c(ClickGuiTheme.TEXT, visibleAlpha());
    }

    protected int accentColor() {
        return ClickGuiTheme.c(ClickGuiTheme.ACCENT, visibleAlpha());
    }

    protected int iconColor() {
        return ClickGuiTheme.mix(ClickGuiTheme.TEXT_MUTED, ClickGuiTheme.TEXT, hoverProgress, visibleAlpha());
    }

    protected int color(int red, int green, int blue, int alpha, float alphaMultiplier) {
        return releon.ru.utils.render.color.ColorUtil.rgba(red, green, blue, Math.max(0, Math.min(255, (int) (alpha * alphaMultiplier))));
    }

    protected void renderScrollingText(
            GuiGraphics graphics,
            String key,
            String text,
            float textX,
            float textY,
            float textSize,
            int textColor,
            float clipX,
            float clipY,
            float clipWidth,
            float clipHeight,
            float hoverX,
            float hoverY,
            float hoverWidth,
            float hoverHeight
    ) {
        if (text == null || text.isEmpty() || clipWidth <= 0.25f || clipHeight <= 0.25f) {
            return;
        }

        float textWidth = textWidth(text, textSize);
        float overflow = Math.max(0.0f, textWidth - clipWidth + 3.0f);
        if (overflow <= 0.25f) {
            Render2D.text(FontType.SEMIBOLD, text, ClickGuiTheme.snap(textX), textY, textSize, textColor);
            return;
        }
        SmoothAnimation scrollAnimation = textScrollAnimations.computeIfAbsent(key, ignored -> {
            SmoothAnimation animation = new SmoothAnimation();
            animation.set(0.0);
            return animation;
        });

        boolean hover = hovered(ClickGuiController.mouseX(), ClickGuiController.mouseY(), hoverX, hoverY, hoverWidth, hoverHeight);
        float target = 0.0f;
        if (hover) {
            long segment = Math.max(950L, 1200L + Math.round(overflow * 18.0f));
            target = ((System.currentTimeMillis() / segment) & 1L) == 0L ? -overflow : 0.0f;
        }
        scrollAnimation.run(target, hover ? 0.55 : 0.22, Easings.CUBIC_OUT, true);
        scrollAnimation.update();

        float scroll = scrollAnimation.get();
        float fadeWidth = Math.min(10.0f, Math.max(2.0f, clipWidth * 0.35f));
        float leftFade = scroll < -0.4f ? 0.78f : 0.0f;
        float rightFade = scroll > -overflow + 0.4f ? 0.88f : 0.0f;

        Render2D.pushScissor(graphics, clipX, clipY, clipWidth, clipHeight);
        if (leftFade > 0.0f || rightFade > 0.0f) {
            Render2D.textFade(FontType.SEMIBOLD, text, textX + scroll, textY, textSize, textColor, clipX, clipX + clipWidth, fadeWidth, leftFade, rightFade);
        } else {
            Render2D.text(FontType.SEMIBOLD, text, textX + scroll, textY, textSize, textColor);
        }
        Render2D.popScissor(graphics);
    }

    /** Всплывающее окно (выпадающий список, пикер цвета). */
    protected void renderPopupSurface(float x, float y, float width, float height, float alphaMultiplier) {
        ClickGuiTheme.rect(x, y, width, height, 5.0f, ClickGuiTheme.c(ClickGuiTheme.SURFACE_HI, alphaMultiplier));
        ClickGuiTheme.border(x, y, width, height, 5.0f, ClickGuiTheme.c(ClickGuiTheme.BORDER, alphaMultiplier));
    }
}
