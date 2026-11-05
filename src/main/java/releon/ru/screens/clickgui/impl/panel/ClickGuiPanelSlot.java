package releon.ru.screens.clickgui.impl.panel;

import releon.ru.screens.clickgui.impl.module.ModuleOption;

/** Позиция карточки модуля в сетке (masonry) на текущем кадре. */
public final class ClickGuiPanelSlot {
    private ModuleOption card;
    private float x;
    private float y;
    private float width;
    private float height;
    private float alpha = 1.0f;

    public ClickGuiPanelSlot set(ModuleOption card, float x, float y, float width, float height) {
        this.card = card;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.alpha = 1.0f;
        return this;
    }

    /** Прозрачность карточки во время анимации появления. */
    public ClickGuiPanelSlot alpha(float alpha) {
        this.alpha = alpha;
        return this;
    }

    public float alpha() {
        return alpha;
    }

    public ModuleOption card() {
        return card;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }
}
