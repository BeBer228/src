package releon.ru.screens.clickgui.impl.module;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.screens.clickgui.impl.ClickGuiController;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.screens.clickgui.impl.options.ClickGuiOption;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.List;

/**
 * Карточка модуля (как на макете):
 * <pre>
 * ┌──────────────────────────────────────┐
 * │ Motion Blur                ⌄  [━━●]  │  ← имя, свернуть, тумблер
 * ├──────────────────────────────────────┤
 * │ Depth-aware camera motion blur  [⌨]  │  ← описание + бинд
 * ├──────────────────────────────────────┤
 * │ Strength                       -0.8  │  ← настройки (всегда раскрыты,
 * │ Camera motion sample   ───●──────    │     ПКМ по шапке — свернуть)
 * └──────────────────────────────────────┘
 * </pre>
 * ЛКМ по шапке — вкл/выкл, ПКМ — свернуть/развернуть, СКМ или клик по чипу — бинд.
 */
public class ModuleOption {
    public static final float HEADER_HEIGHT = 22.0f;
    public static final float DESC_HEIGHT = 19.0f;
    private static final float PAD = 8.0f;
    private static final float OPTION_GAP = 3.0f;
    private static final float SETTINGS_PAD_TOP = 6.0f;
    private static final float SETTINGS_PAD_BOTTOM = 5.0f;
    private static final float RADIUS = 5.0f;
    private static final float TOGGLE_W = 16.0f;
    private static final float TOGGLE_H = 9.0f;
    private static final float CHEVRON_W = 9.0f;

    /** Каскад строк настроек: задержка между строками и длительность появления строки. */
    private static final long OPTION_STAGGER_MS = 30L;
    private static final float OPTION_IN_MS = 280.0f;
    private static final float FLASH_MS = 520.0f;
    private long optionsIntro;
    private boolean optionsIntroDone = true;
    private long flashStart;

    private final ClickGuiTheme.Ellipsis nameCache = new ClickGuiTheme.Ellipsis();
    private final ClickGuiTheme.Ellipsis descCache = new ClickGuiTheme.Ellipsis();
    private final ClickGuiTheme.Ellipsis chipCache = new ClickGuiTheme.Ellipsis();

    private final Module module;
    private final List<ClickGuiOption> settings;
    private final String description;
    private final SmoothAnimation settingsAnimation = new SmoothAnimation();
    private final SmoothAnimation bindAnimation = new SmoothAnimation();
    private final SmoothAnimation enabledAnimation = new SmoothAnimation();
    private final SmoothAnimation hoverAnimation = new SmoothAnimation();
    private final BindSetting bindSetting;
    private boolean settingsExpanded = true;
    private boolean binding;
    private float settingsProgress;

    // последняя раскладка
    private float cardX;
    private float cardY;
    private float cardW;
    private float cardH;
    private boolean interactive;
    private boolean hovered;
    private float chipX;
    private float chipY;
    private float chipW;
    private float chipH;
    private float toggleX;
    private float toggleY;

    public ModuleOption(Module module, List<ClickGuiOption> settings) {
        this.module = module;
        this.settings = settings == null ? List.of() : settings;
        this.bindSetting = module.getBindSetting();
        String desc = ClickGuiTheme.description(module);
        this.description = desc.isEmpty() ? "No description" : desc;
        settingsAnimation.set(settingsExpanded && hasSettings() ? 1.0 : 0.0);
        settingsProgress = settingsAnimation.get();
        bindAnimation.set(0.0);
        hoverAnimation.set(0.0);
        enabledAnimation.set(module.isEnabled() ? 1.0 : 0.0);
    }

    public Module module() {
        return module;
    }

    public void updateAnimation() {
        settingsAnimation.run(settingsExpanded ? 1.0 : 0.0, 0.26, Easings.CUBIC_OUT, true);
        settingsAnimation.update();
        settingsProgress = hasSettings() ? clamp(settingsAnimation.get(), 0.0f, 1.0f) : 0.0f;
        bindAnimation.run(binding ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT, true);
        bindAnimation.update();
        enabledAnimation.run(module.isEnabled() ? 1.0 : 0.0, 0.22, Easings.CUBIC_OUT, true);
        enabledAnimation.update();
        hoverAnimation.run(hovered ? 1.0 : 0.0, 0.14, Easings.CUBIC_OUT, true);
        hoverAnimation.update();
        for (ClickGuiOption option : settings) {
            option.updateVisibility();
        }
    }

    // ---------------------------------------------------------------- layout

    /** Полная анимированная высота карточки. */
    public float height() {
        return HEADER_HEIGHT + DESC_HEIGHT + settingsBlockHeight() * settingsProgress;
    }

    private float settingsBlockHeight() {
        float h = settingsContentHeight();
        return h <= 0.01f ? 0.0f : SETTINGS_PAD_TOP + h + SETTINGS_PAD_BOTTOM;
    }

    private float settingsContentHeight() {
        float height = 0.0f;
        for (ClickGuiOption option : settings) {
            float visibility = option.visibilityProgress();
            if (visibility > 0.01f) {
                height += (option.getHeight() + OPTION_GAP) * visibility;
            }
        }
        return Math.max(0.0f, height - OPTION_GAP);
    }

    // ---------------------------------------------------------------- render

    /** Первый проход: фон карточки (до текста). */
    public void renderShell(float x, float y, float w, float alpha, float clipTop, float clipBottom) {
        cardX = x;
        cardY = y;
        cardW = w;
        cardH = height();
        interactive = alpha > 0.01f && y + cardH > clipTop && y < clipBottom;

        double mx = ClickGuiController.mouseX();
        double my = ClickGuiController.mouseY();
        hovered = interactive && my >= clipTop && my <= clipBottom && inside(mx, my, x, y, w, cardH);

        float hover = hoverAnimation.get();
        float enabled = enabledAnimation.get();

        ClickGuiTheme.rect(x, y, w, cardH, RADIUS, ClickGuiTheme.mix(ClickGuiTheme.SURFACE, ClickGuiTheme.SURFACE_HI, hover * 0.5f, alpha));
        ClickGuiTheme.border(x, y, w, cardH, RADIUS, enabled > 0.01f
                ? ClickGuiTheme.mix(ClickGuiTheme.BORDER, ClickGuiTheme.ACCENT_DIM, enabled * 0.55f, alpha)
                : ClickGuiTheme.c(ClickGuiTheme.BORDER, alpha));

        // вспышка при включении/выключении: акцентная рамка расходится и гаснет
        if (flashStart != 0L) {
            float ft = (System.currentTimeMillis() - flashStart) / FLASH_MS;
            if (ft >= 1.0f) {
                flashStart = 0L;
            } else {
                float spread = ClickGuiTheme.easeOutCubic(ft) * 3.5f;
                float fa = (1.0f - ft) * (1.0f - ft);
                ClickGuiTheme.border(x - spread, y - spread, w + spread * 2.0f, cardH + spread * 2.0f, RADIUS + spread,
                        ClickGuiTheme.c(ClickGuiTheme.ACCENT, 200, fa * alpha));
                ClickGuiTheme.rect(x, y, w, HEADER_HEIGHT, RADIUS, ClickGuiTheme.c(ClickGuiTheme.ACCENT, 22, fa * alpha));
            }
        }

        ClickGuiTheme.divider(x + PAD, y + HEADER_HEIGHT, w - PAD * 2.0f, alpha);
        if (settingsProgress > 0.01f && settingsBlockHeight() > 0.0f) {
            ClickGuiTheme.divider(x + PAD, y + HEADER_HEIGHT + DESC_HEIGHT, w - PAD * 2.0f, alpha * settingsProgress);
        }
    }

    /** Второй проход: текст и контролы. */
    public void renderContent(GuiGraphics graphics, float alpha, float clipTop, float clipBottom) {
        float x = cardX;
        float y = cardY;
        float w = cardW;
        float hover = hoverAnimation.get();
        float enabled = enabledAnimation.get();
        float bindP = bindAnimation.get();
        double mx = ClickGuiController.mouseX();
        double my = ClickGuiController.mouseY();

        // --- шапка: имя слева, [шеврон] [тумблер] справа — всё на одной линии headerMid
        float headerMid = y + HEADER_HEIGHT * 0.5f;
        toggleX = x + w - PAD - TOGGLE_W;
        toggleY = headerMid - TOGGLE_H * 0.5f;
        ClickGuiTheme.toggle(toggleX, toggleY, TOGGLE_W, TOGGLE_H, enabled, hover, alpha);

        float rightEdge = toggleX - 6.0f;
        if (hasSettings()) {
            float chevronSize = 7.5f;
            ClickGuiTheme.textCenter(FontType.GUI_ICONS, settingsExpanded ? "N" : "M", rightEdge - CHEVRON_W * 0.5f, headerMid, chevronSize,
                    ClickGuiTheme.mix(ClickGuiTheme.TEXT_MUTED, ClickGuiTheme.TEXT_DIM, hover, alpha));
            rightEdge -= CHEVRON_W + 4.0f;
        }

        float nameX = x + PAD;
        String name = nameCache.get(FontType.SEMIBOLD, module.getName(), ClickGuiTheme.FS_CARD, rightEdge - nameX);
        int nameColor = ClickGuiTheme.mix(ClickGuiTheme.TEXT_DIM, ClickGuiTheme.TEXT, Math.max(enabled, hover * 0.6f), alpha);
        ClickGuiTheme.text(FontType.SEMIBOLD, name, nameX, headerMid, ClickGuiTheme.FS_CARD, nameColor);

        // --- описание + бинд на линии descMid
        float descMid = y + HEADER_HEIGHT + DESC_HEIGHT * 0.5f;
        chipH = 10.0f;
        String chipText;
        boolean bound = bindSetting != null && bindSetting.getValue() != null && bindSetting.getValue().isBound();
        if (binding) {
            chipText = "...";
        } else if (bound) {
            chipText = bindSetting.getValue().getDisplayName();
        } else {
            chipText = null; // иконка клавиатуры
        }
        if (bindSetting != null) {
            float chipTextW = chipText == null ? 7.0f : Render2D.textWidth(FontType.SEMIBOLD, chipText, ClickGuiTheme.FS_BADGE);
            chipW = ClickGuiTheme.snap(Math.max(18.0f, Math.min(48.0f, chipTextW + 8.0f)));
            chipX = x + w - PAD - chipW;
            chipY = descMid - chipH * 0.5f;
            boolean chipHover = interactive && inside(mx, my, chipX, chipY, chipW, chipH);
            ClickGuiTheme.field(chipX, chipY, chipW, chipH, chipHover ? 1.0f : 0.0f, bindP, alpha);
            float chipCx = chipX + chipW * 0.5f;
            if (chipText == null) {
                ClickGuiTheme.textCenter(FontType.GUI_ICONS, "L", chipCx, descMid, 6.5f,
                        ClickGuiTheme.mix(ClickGuiTheme.TEXT_MUTED, ClickGuiTheme.TEXT_DIM, chipHover ? 1.0f : 0.0f, alpha));
            } else {
                String shown = chipCache.get(FontType.SEMIBOLD, chipText, ClickGuiTheme.FS_BADGE, chipW - 6.0f);
                int tc = binding ? ClickGuiTheme.c(ClickGuiTheme.ACCENT, alpha) : ClickGuiTheme.c(ClickGuiTheme.TEXT_DIM, alpha);
                ClickGuiTheme.textCenter(FontType.SEMIBOLD, shown, chipCx, descMid, ClickGuiTheme.FS_BADGE, tc);
            }
        }
        float descRight = (bindSetting != null ? chipX : x + w - PAD) - 6.0f;
        String descText = binding ? "Press a key  ·  Esc to clear" : description;
        String desc = descCache.get(FontType.SEMIBOLD, descText, ClickGuiTheme.FS_DESC_CARD, descRight - (x + PAD));
        int descColor = binding ? ClickGuiTheme.mix(ClickGuiTheme.TEXT_MUTED, ClickGuiTheme.ACCENT, bindP, alpha)
                : ClickGuiTheme.c(ClickGuiTheme.TEXT_MUTED, alpha);
        ClickGuiTheme.text(FontType.SEMIBOLD, desc, x + PAD, descMid, ClickGuiTheme.FS_DESC_CARD, descColor);

        // --- настройки
        if (settingsProgress <= 0.01f || settingsBlockHeight() <= 0.0f) {
            return;
        }
        float settingsTop = y + HEADER_HEIGHT + DESC_HEIGHT;
        float settingsVisible = settingsBlockHeight() * settingsProgress;
        float scTop = Math.max(settingsTop, clipTop);
        float scBottom = Math.min(settingsTop + settingsVisible, clipBottom);
        if (scBottom - scTop <= 0.5f) {
            return;
        }
        // Свой scissor только пока идёт анимация раскрытия/появления строк.
        // Каждый scissor = сброс батча рендера, раньше он был у КАЖДОЙ карточки КАЖДЫЙ кадр.
        long now = System.currentTimeMillis();
        boolean cascading = !optionsIntroDone;
        boolean clip = settingsProgress < 0.995f || cascading || anyOptionAnimating();
        if (clip) {
            Render2D.pushScissor(graphics, x, scTop, w, scBottom - scTop);
        }
        try {
            float optionY = settingsTop + SETTINGS_PAD_TOP;
            float innerX = x + PAD;
            float innerW = w - PAD * 2.0f;
            boolean allDone = true;
            int row = 0;
            for (ClickGuiOption option : settings) {
                float visibility = option.visibilityProgress();
                float slot = (option.getHeight() + OPTION_GAP) * visibility;
                if (visibility > 0.01f) {
                    // каскад: строки выезжают слева по очереди
                    float cascade = 1.0f;
                    if (cascading) {
                        float ct = (now - optionsIntro - row * OPTION_STAGGER_MS) / OPTION_IN_MS;
                        cascade = ClickGuiTheme.easeOutCubic(ct);
                        if (ct < 1.0f) {
                            allDone = false;
                        }
                    }
                    boolean onScreen = optionY + option.getHeight() > scTop && optionY < scBottom;
                    option.setFadeMultiplier(settingsProgress * visibility * cascade * (onScreen ? 1.0f : 0.0f));
                    if (onScreen && cascade > 0.01f) {
                        float renderY = ClickGuiTheme.snap(optionY - (1.0f - visibility) * 4.0f);
                        float renderX = ClickGuiTheme.snap(innerX - (1.0f - cascade) * 10.0f);
                        option.render(graphics, renderX, renderY, innerW, 1.0f, alpha);
                    }
                    row++;
                }
                optionY += slot;
            }
            if (cascading && allDone) {
                optionsIntroDone = true;
            }
        } finally {
            if (clip) {
                Render2D.popScissor(graphics);
            }
        }
    }

    private boolean anyOptionAnimating() {
        for (ClickGuiOption option : settings) {
            float v = option.visibilityProgress();
            if (v > 0.01f && v < 0.99f) {
                return true;
            }
        }
        return false;
    }

    public void renderOverlay(GuiGraphics graphics) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return;
        }
        for (ClickGuiOption option : settings) {
            float visibility = option.visibilityProgress();
            if (visibility <= 0.01f) {
                continue;
            }
            option.renderOverlay(graphics);
        }
    }

    /** Запустить каскадное появление строк настроек (при прогрузке страницы / раскрытии). */
    public void playIntro(long startMs) {
        optionsIntro = startMs;
        optionsIntroDone = !hasSettings();
    }

    public void beginInteractionFrame() {
        interactive = false;
        for (ClickGuiOption option : settings) {
            option.resetInteractionFrame();
        }
    }

    // ---------------------------------------------------------------- input

    /** Клик по шапке/описанию карточки. Вызывать только если курсор внутри видимой области сетки. */
    public boolean mouseClickedCard(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        if (binding) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                stopBinding();
            } else {
                setBind(KeyBind.mouse(event.button()));
            }
            return true;
        }
        if (!interactive || !inside(mx, my, cardX, cardY, cardW, HEADER_HEIGHT + DESC_HEIGHT)) {
            return false;
        }
        float headerMid = cardY + HEADER_HEIGHT * 0.5f;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            startBinding();
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            toggleExpanded();
            return true;
        }
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (bindSetting != null && inside(mx, my, chipX - 2.0f, chipY - 2.0f, chipW + 4.0f, chipH + 4.0f)) {
            startBinding();
            return true;
        }
        if (hasSettings()) {
            float chevronRight = toggleX - 6.0f;
            if (inside(mx, my, chevronRight - CHEVRON_W - 2.0f, cardY, CHEVRON_W + 4.0f, HEADER_HEIGHT)) {
                toggleExpanded();
                return true;
            }
        }
        if (my <= cardY + HEADER_HEIGHT || inside(mx, my, toggleX - 3.0f, toggleY - 3.0f, 22.0f, 15.0f)) {
            module.toggle();
            flashStart = System.currentTimeMillis();
            return true;
        }
        return true; // клик по описанию просто поглощаем
    }

    public boolean mouseClickedSettings(MouseButtonEvent event, boolean doubled) {
        if (!hasSettings() || settingsProgress <= 0.85f) {
            return false;
        }
        ClickGuiOption handled = null;
        for (ClickGuiOption option : settings) {
            if (option.isVisibleForInteraction() && option.wantsMousePriority(event) && option.mouseClicked(event, doubled)) {
                handled = option;
                break;
            }
        }
        if (handled == null) {
            for (int i = settings.size() - 1; i >= 0; i--) {
                ClickGuiOption option = settings.get(i);
                if (option.isVisibleForInteraction() && option.mouseClicked(event, doubled)) {
                    handled = option;
                    break;
                }
            }
        }
        if (handled != null) {
            closePopupsExcept(handled);
            return true;
        }
        closePopupsExcept(null);
        return false;
    }

    public boolean mouseClickedPopup(MouseButtonEvent event, boolean doubled) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }
        boolean hasOpenPopup = false;
        ClickGuiOption handled = null;
        for (int i = settings.size() - 1; i >= 0; i--) {
            ClickGuiOption option = settings.get(i);
            if (!option.isVisibleForRender() || !option.hasOpenPopup()) {
                continue;
            }
            hasOpenPopup = true;
            if (option.wantsMousePriority(event) && option.mouseClicked(event, doubled)) {
                handled = option;
                break;
            }
        }
        if (handled != null) {
            closePopupsExcept(handled);
            return true;
        }
        if (hasOpenPopup) {
            closePopupsExcept(null);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (!hasSettings()) {
            return false;
        }
        for (int i = settings.size() - 1; i >= 0; i--) {
            if (settings.get(i).mouseReleased(event)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }
        for (int i = settings.size() - 1; i >= 0; i--) {
            if (settings.get(i).mouseDragged(event, dragX, dragY)) {
                return true;
            }
        }
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        if (binding) {
            if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_ESCAPE) {
                setBind(KeyBind.NONE);
            } else {
                setBind(KeyBind.keyboard(event.key()));
            }
            return true;
        }
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }
        for (int i = settings.size() - 1; i >= 0; i--) {
            ClickGuiOption option = settings.get(i);
            if (option.capturesKeyboard() && option.keyPressed(event)) {
                return true;
            }
        }
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }
        for (int i = settings.size() - 1; i >= 0; i--) {
            ClickGuiOption option = settings.get(i);
            if (option.capturesKeyboard() && option.charTyped(event)) {
                return true;
            }
        }
        return false;
    }

    public boolean capturesKeyboard() {
        if (binding) {
            return true;
        }
        for (ClickGuiOption option : settings) {
            if (option.capturesKeyboard()) {
                return true;
            }
        }
        return false;
    }

    public void closeAll() {
        binding = false;
        closePopupsExcept(null);
    }

    public boolean hasSettings() {
        return !settings.isEmpty();
    }

    public boolean isBinding() {
        return binding;
    }

    /** Есть ли совпадение по названию настройки (для поиска). */
    public List<ClickGuiOption> settings() {
        return settings;
    }

    public boolean isExpanded() {
        return settingsExpanded;
    }

    public void setExpanded(boolean expanded) {
        if (!hasSettings() || settingsExpanded == expanded) {
            return;
        }
        settingsExpanded = expanded;
        settingsAnimation.run(expanded ? 1.0 : 0.0, 0.26, Easings.CUBIC_OUT);
        if (expanded) {
            playIntro(System.currentTimeMillis() + 40L);
        }
        if (!expanded) {
            closePopupsExcept(null);
        }
    }

    public void revealSettings() {
        setExpanded(true);
    }

    public float cardY() {
        return cardY;
    }

    public boolean isHovered() {
        return hovered;
    }

    // ---------------------------------------------------------------- internals

    private void toggleExpanded() {
        setExpanded(!settingsExpanded);
    }

    private void startBinding() {
        if (bindSetting == null) {
            return;
        }
        binding = true;
        bindAnimation.run(1.0, 0.18, Easings.CUBIC_OUT);
        closePopupsExcept(null);
    }

    private void stopBinding() {
        binding = false;
        bindAnimation.run(0.0, 0.18, Easings.CUBIC_OUT);
    }

    private void setBind(KeyBind bind) {
        if (bindSetting != null) {
            bindSetting.setValue(bind);
        }
        stopBinding();
    }

    private void closePopupsExcept(ClickGuiOption keepOpen) {
        for (ClickGuiOption option : settings) {
            if (option != keepOpen) {
                option.closePopup();
            }
        }
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
