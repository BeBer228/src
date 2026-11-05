package releon.ru.screens.clickgui.impl.panel;

import releon.ru.screens.clickgui.impl.ClickGuiController;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.screens.clickgui.impl.ClickGuiThemes;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.List;

/**
 * Вкладка "Темы".
 * <ul>
 *   <li>сверху — карточка текущей темы: большой градиент, название и палитра (живо перетекает при смене);</li>
 *   <li>ниже — сетка карточек: каждая нарисована в СВОИХ цветах (фон, градиент, текст), так что видно тему сразу;</li>
 *   <li>наведение меняет только цвет рамки/подсветку — карточки не двигаются, поэтому ничего не дёргается и не мерцает;</li>
 *   <li>выбранная тема — двойная рамка акцентом и галочка; клик даёт короткую вспышку.</li>
 * </ul>
 * Почему раньше мерцало: под курсором рисовались светящиеся круги/кольцо и волны, а карточки
 * подпрыгивали при наведении на дробные пиксели — всё это менялось каждый кадр при движении мыши.
 */
public final class ClickGuiThemesPage {
    private static final float GAP = 8.0f;
    private static final float HERO_H = 58.0f;
    private static final float RADIUS = 7.0f;
    private static final long CARD_DELAY_MS = 30L;
    private static final int STAGGER_CAP = 16;
    private static final float SCROLL_STEP = 34.0f;
    private static final float CARD_IN_MS = 380.0f;
    private static final float FLASH_MS = 420.0f;

    private final List<ClickGuiThemes.Theme> themes = ClickGuiThemes.THEMES;
    private final int count = themes.size();
    private final float[] cardX = new float[count];
    private final float[] cardY = new float[count];
    private final float[] hover = new float[count];
    private final long[] pressTime = new long[count];

    private long openTime;
    private long lastNanos;
    private float dt;
    private float cardW;
    private float cardH;
    private float heroY;
    private float px;
    private float py;
    private float pw;
    private float ph;
    private float scroll;
    private float scrollTarget;
    private float scrollLimit;
    private float contentH;

    // геометрия карточки в текущем кадре (общая для прохода фигур и текста)
    private float gx;
    private float gy;
    private float ga;

    /** Вызывать при открытии вкладки — перезапускает анимацию появления. */
    public void open() {
        openTime = System.currentTimeMillis();
        lastNanos = 0L;
        scroll = scrollTarget = 0.0f;
        for (int i = 0; i < count; i++) {
            hover[i] = 0.0f;
            pressTime[i] = 0L;
        }
    }

    // ---------------------------------------------------------------- layout / update

    private void layout(float x, float y, float w, float h) {
        px = x;
        py = y;
        pw = w;
        ph = h;
        int cols = w >= 640.0f ? 5 : (w >= 470.0f ? 4 : (w >= 300.0f ? 3 : 2));
        int rows = (count + cols - 1) / cols;
        float pixel = ClickGuiTheme.px();
        cardW = (float) Math.floor((w - GAP * (cols - 1)) / cols / pixel) * pixel;
        cardH = ClickGuiTheme.snap(Math.max(74.0f, Math.min(98.0f, cardW * 0.74f)));
        float gridTop = HERO_H + GAP + 2.0f;
        contentH = gridTop + rows * cardH + (rows - 1) * GAP + 6.0f;
        scrollLimit = Math.max(0.0f, contentH - h);
        scrollTarget = Math.max(0.0f, Math.min(scrollLimit, scrollTarget));
        scroll = Math.max(0.0f, Math.min(scrollLimit, scroll));
        float sy = ClickGuiTheme.snap(scroll);
        heroY = ClickGuiTheme.snap(y + 2.0f - sy);
        for (int i = 0; i < count; i++) {
            int c = i % cols;
            int r = i / cols;
            cardX[i] = ClickGuiTheme.snap(x + c * (cardW + GAP));
            cardY[i] = ClickGuiTheme.snap(y + gridTop + r * (cardH + GAP) - sy);
        }
    }

    private void tick(double mx, double my) {
        long now = System.nanoTime();
        dt = lastNanos == 0L ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        boolean in = insidePage(mx, my);
        scroll = ClickGuiTheme.approach(scroll, scrollTarget, 14.0f, dt);
        if (Math.abs(scroll - scrollTarget) < 0.05f) {
            scroll = scrollTarget;
        }
        for (int i = 0; i < count; i++) {
            boolean h = in && insideCard(i, mx, my);
            hover[i] = ClickGuiTheme.approach(hover[i], h ? 1.0f : 0.0f, 16.0f, dt);
            if (hover[i] < 0.002f) {
                hover[i] = 0.0f;
            } else if (hover[i] > 0.998f) {
                hover[i] = 1.0f;
            }
        }
    }

    private boolean insidePage(double mx, double my) {
        return mx >= px && mx <= px + pw && my >= py && my <= py + ph;
    }

    private boolean insideCard(int i, double mx, double my) {
        return mx >= cardX[i] && mx <= cardX[i] + cardW && my >= cardY[i] && my <= cardY[i] + cardH;
    }

    private boolean visible(int i) {
        return cardY[i] + cardH >= py - 4.0f && cardY[i] <= py + ph + 4.0f;
    }

    /** Считает позицию/прозрачность карточки. false — карточку не рисуем. */
    private boolean geometry(int i, long now, float alpha) {
        float t = (now - openTime - Math.min(i, STAGGER_CAP) * CARD_DELAY_MS) / CARD_IN_MS;
        if (t <= 0.0f || !visible(i)) {
            return false;
        }
        float e = ClickGuiTheme.easeOutCubic(t);
        gx = cardX[i];
        gy = ClickGuiTheme.snap(cardY[i] + (1.0f - e) * 8.0f);
        ga = ClickGuiTheme.clamp01(t * 1.6f) * alpha;
        return ga > 0.01f;
    }

    /** Прокрутка списка тем. */
    public boolean scroll(double mx, double my, double amount) {
        if (!insidePage(mx, my)) {
            return false;
        }
        scrollTarget = Math.max(0.0f, Math.min(scrollLimit, scrollTarget - (float) amount * SCROLL_STEP));
        return true;
    }

    // ---------------------------------------------------------------- render

    /** Проход 1: все фигуры. */
    public void renderShapes(float x, float y, float w, float h, float alpha) {
        layout(x, y, w, h);
        tick(ClickGuiController.mouseX(), ClickGuiController.mouseY());
        long now = System.currentTimeMillis();
        int active = ClickGuiThemes.current();

        renderHeroShapes(alpha);

        for (int i = 0; i < count; i++) {
            if (!geometry(i, now, alpha)) {
                continue;
            }
            ClickGuiThemes.Theme theme = themes.get(i);
            boolean selected = i == active;
            float hv = hover[i];
            float ca = ga;
            float x0 = gx;
            float y0 = gy;
            float cw = cardW;
            float ch = cardH;
            float bannerH = ClickGuiTheme.snap(ch * 0.48f);

            // внешнее свечение выбранной
            if (selected) {
                ClickGuiTheme.border(x0 - 2.0f, y0 - 2.0f, cw + 4.0f, ch + 4.0f, RADIUS + 2.0f, ClickGuiTheme.c(theme.accent(), 90, ca));
            }

            // фон карточки — цветом окна самой темы
            ClickGuiTheme.rect(x0, y0, cw, ch, RADIUS, ClickGuiTheme.c(theme.window(), ca));

            // градиентный баннер: акцент -> второй цвет
            int tl = ClickGuiTheme.c(theme.accent(), ca);
            int tr = ClickGuiTheme.c(theme.secondary(), ca);
            int br = ClickGuiTheme.mix(theme.secondary(), theme.window(), 0.35f, ca);
            int bl = ClickGuiTheme.mix(theme.accent(), theme.window(), 0.35f, ca);
            float sx = ClickGuiTheme.snap(x0);
            float sy = ClickGuiTheme.snap(y0);
            Render2D.gradientRect(sx, sy, ClickGuiTheme.snap(x0 + cw) - sx, ClickGuiTheme.snap(y0 + bannerH) - sy,
                    RADIUS, RADIUS, 0.0f, 0.0f, tl, tr, br, bl);

            // мини-окно поверх баннера (превью интерфейса в цветах темы)
            float mw = Math.min(cw * 0.52f, 62.0f);
            float mh = bannerH - 12.0f;
            if (mh > 12.0f) {
                float mx0 = x0 + 8.0f;
                float my0 = y0 + 7.0f;
                ClickGuiTheme.rect(mx0, my0, mw, mh, 3.5f, ClickGuiTheme.c(theme.window(), 225, ca));
                ClickGuiTheme.rect(mx0 + 4.0f, my0 + 4.0f, mw * 0.42f, 2.5f, 1.25f, ClickGuiTheme.c(theme.text(), 200, ca));
                ClickGuiTheme.rect(mx0 + 4.0f, my0 + 9.0f, mw * 0.62f, 2.0f, 1.0f, ClickGuiTheme.c(theme.textMuted(), ca));
                // тумблер
                float tgw = 9.0f;
                float tgx = mx0 + mw - tgw - 4.0f;
                ClickGuiTheme.rect(tgx, my0 + 3.5f, tgw, 5.0f, 2.5f, ClickGuiTheme.c(theme.accent(), ca));
                ClickGuiTheme.circle(tgx + tgw - 2.5f, my0 + 6.0f, 1.7f, ClickGuiTheme.white(ca));
                // слайдер
                if (mh > 18.0f) {
                    float sly = my0 + mh - 5.0f;
                    ClickGuiTheme.rect(mx0 + 4.0f, sly, mw - 8.0f, 2.0f, 1.0f, ClickGuiTheme.c(theme.fieldHi(), ca));
                    ClickGuiTheme.rect(mx0 + 4.0f, sly, (mw - 8.0f) * 0.6f, 2.0f, 1.0f, ClickGuiTheme.c(theme.accent(), ca));
                    ClickGuiTheme.circle(mx0 + 4.0f + (mw - 8.0f) * 0.6f, sly + 1.0f, 2.0f, ClickGuiTheme.white(ca));
                }
            }

            // галочка выбранной темы
            if (selected) {
                float bx = x0 + cw - 11.0f;
                float by = y0 + 11.0f;
                ClickGuiTheme.circle(bx, by, 6.5f, ClickGuiTheme.c(theme.window(), 230, ca));
                ClickGuiTheme.circle(bx, by, 5.0f, ClickGuiTheme.c(theme.accent(), ca));
            }

            // палитра-точки внизу справа
            float dotY = y0 + ch - 9.0f;
            float dx = x0 + cw - 9.0f;
            ClickGuiTheme.circle(dx, dotY, 2.6f, ClickGuiTheme.c(theme.secondary(), ca));
            ClickGuiTheme.circle(dx - 7.0f, dotY, 2.6f, ClickGuiTheme.c(theme.accent(), ca));
            ClickGuiTheme.circle(dx - 14.0f, dotY, 2.6f, ClickGuiTheme.c(theme.fieldHi(), ca));

            // подсветка наведения (только цвет, без движения)
            if (hv > 0.0f) {
                ClickGuiTheme.rect(x0, y0, cw, ch, RADIUS, ClickGuiTheme.white(0.035f * hv * ca));
            }

            // вспышка при клике
            float flash = pressTime[i] == 0L ? 0.0f : 1.0f - ClickGuiTheme.clamp01((now - pressTime[i]) / FLASH_MS);
            if (flash > 0.0f) {
                ClickGuiTheme.rect(x0, y0, cw, ch, RADIUS, ClickGuiTheme.c(theme.accent(), Math.round(70 * flash * flash), ca));
            } else if (pressTime[i] != 0L) {
                pressTime[i] = 0L;
            }

            // рамка
            float edge = selected ? 1.0f : hv * 0.85f;
            ClickGuiTheme.border(x0, y0, cw, ch, RADIUS, ClickGuiTheme.mix(theme.border(), theme.accent(), edge, ca));
        }
        renderScrollbar(alpha);
    }

    /** Карточка текущей темы (цвета берутся из "живой" палитры — красиво перетекают при смене). */
    private void renderHeroShapes(float alpha) {
        if (heroY + HERO_H < py - 4.0f) {
            return;
        }
        float t = ClickGuiTheme.clamp01((System.currentTimeMillis() - openTime) / CARD_IN_MS);
        float a = ClickGuiTheme.easeOutCubic(t) * alpha;
        float x0 = px;
        float y0 = heroY;
        float w = pw;
        float h = HERO_H;
        ClickGuiTheme.rect(x0, y0, w, h, RADIUS + 1.0f, ClickGuiTheme.c(ClickGuiTheme.SURFACE, a));

        // градиентная плитка слева
        float gw = ClickGuiTheme.snap(Math.min(110.0f, w * 0.24f));
        float inset = 6.0f;
        float sx = ClickGuiTheme.snap(x0 + inset);
        float sy = ClickGuiTheme.snap(y0 + inset);
        Render2D.gradientRect(sx, sy, ClickGuiTheme.snap(x0 + gw) - sx, ClickGuiTheme.snap(y0 + h - inset) - sy, RADIUS - 1.0f,
                ClickGuiTheme.c(ClickGuiTheme.ACCENT, a), ClickGuiTheme.c(ClickGuiTheme.SECONDARY, a),
                ClickGuiTheme.mix(ClickGuiTheme.SECONDARY, ClickGuiTheme.SURFACE, 0.3f, a),
                ClickGuiTheme.mix(ClickGuiTheme.ACCENT, ClickGuiTheme.SURFACE, 0.3f, a));

        // палитра справа
        int[][] swatches = {ClickGuiTheme.ACCENT, ClickGuiTheme.SECONDARY, ClickGuiTheme.ACCENT_DIM,
                ClickGuiTheme.FIELD_HI, ClickGuiTheme.TEXT_DIM, ClickGuiTheme.TEXT};
        float r = 5.0f;
        float cx = x0 + w - 12.0f;
        float cy = y0 + h * 0.5f;
        for (int i = 0; i < swatches.length; i++) {
            ClickGuiTheme.circle(cx - i * (r * 2.0f + 4.0f), cy, r + 1.0f, ClickGuiTheme.c(ClickGuiTheme.WINDOW, a));
            ClickGuiTheme.circle(cx - i * (r * 2.0f + 4.0f), cy, r, ClickGuiTheme.c(swatches[i], a));
        }
        ClickGuiTheme.border(x0, y0, w, h, RADIUS + 1.0f, ClickGuiTheme.c(ClickGuiTheme.BORDER, a));
    }

    /** Тонкий скроллбар справа. */
    private void renderScrollbar(float alpha) {
        if (scrollLimit <= 0.5f) {
            return;
        }
        float trackH = ph - 4.0f;
        float thumbH = Math.max(18.0f, trackH * (ph / contentH));
        float ty = py + 2.0f + (trackH - thumbH) * (scroll / scrollLimit);
        float tx = px + pw + 2.0f;
        Render2D.rect(ClickGuiTheme.snap(tx), ClickGuiTheme.snap(py + 2.0f), 2.0f, trackH, 1.0f, ClickGuiTheme.c(ClickGuiTheme.FIELD, alpha));
        Render2D.rect(ClickGuiTheme.snap(tx), ClickGuiTheme.snap(ty), 2.0f, thumbH, 1.0f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, 200, alpha));
    }

    /** Проход 2: подписи. */
    public void renderText(float alpha) {
        long now = System.currentTimeMillis();
        int active = ClickGuiThemes.current();

        // текст карточки текущей темы
        if (heroY + HERO_H >= py - 4.0f) {
            float t = ClickGuiTheme.clamp01((now - openTime) / CARD_IN_MS);
            float a = ClickGuiTheme.easeOutCubic(t) * alpha;
            float gw = ClickGuiTheme.snap(Math.min(110.0f, pw * 0.24f));
            float tx = px + gw + 14.0f;
            ClickGuiThemes.Theme cur = ClickGuiThemes.currentTheme();
            ClickGuiTheme.text(FontType.SEMIBOLD, "ТЕКУЩАЯ ТЕМА", tx, heroY + 15.0f, 5.0f, ClickGuiTheme.c(ClickGuiTheme.TEXT_MUTED, a));
            float maxW = Math.max(20.0f, px + pw - 100.0f - tx);
            ClickGuiTheme.text(FontType.BOLD, ClickGuiTheme.ellipsize(FontType.BOLD, cur.name(), 11.0f, maxW), tx, heroY + 28.0f, 11.0f, ClickGuiTheme.c(ClickGuiTheme.TEXT, a));
            ClickGuiTheme.text(FontType.SEMIBOLD, ClickGuiTheme.ellipsize(FontType.SEMIBOLD, cur.description(), 6.0f, maxW), tx, heroY + 42.0f, 6.0f, ClickGuiTheme.c(ClickGuiTheme.TEXT_DIM, a));
        }

        for (int i = 0; i < count; i++) {
            if (!geometry(i, now, alpha)) {
                continue;
            }
            ClickGuiThemes.Theme theme = themes.get(i);
            boolean selected = i == active;
            float x0 = gx;
            float y0 = gy;
            float bannerH = ClickGuiTheme.snap(cardH * 0.48f);
            float nameCy = y0 + bannerH + (cardH - bannerH) * 0.34f;
            float maxW = cardW - 34.0f;
            ClickGuiTheme.text(FontType.SEMIBOLD, ClickGuiTheme.ellipsize(FontType.SEMIBOLD, theme.name(), 7.4f, maxW), x0 + 8.0f, nameCy, 7.4f,
                    selected ? ClickGuiTheme.c(theme.accent(), ga) : ClickGuiTheme.mix(theme.textDim(), theme.text(), hover[i], ga));
            ClickGuiTheme.text(FontType.SEMIBOLD, ClickGuiTheme.ellipsize(FontType.SEMIBOLD, theme.description(), 5.2f, maxW), x0 + 8.0f, nameCy + 9.0f, 5.2f,
                    ClickGuiTheme.c(theme.textMuted(), ga));
            if (selected) {
                ClickGuiTheme.textCenter(FontType.GUI_ICONS, "P", x0 + cardW - 11.0f, y0 + 11.0f, 5.5f, ClickGuiTheme.white(ga));
            }
        }
    }

    // ---------------------------------------------------------------- input

    public boolean mouseClicked(double mx, double my, int button) {
        if (!insidePage(mx, my)) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        for (int i = 0; i < count; i++) {
            if (visible(i) && insideCard(i, mx, my)) {
                pressTime[i] = System.currentTimeMillis();
                ClickGuiThemes.apply(i);
                return true;
            }
        }
        return true;
    }
}
