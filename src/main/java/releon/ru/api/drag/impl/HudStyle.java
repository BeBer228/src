package releon.ru.api.drag.impl;

import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.screens.clickgui.impl.ClickGuiThemes;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

/**
 * Titanium HUD design system.
 * <p>
 * Единая точка правды для всего HUD: палитра, радиусы, типографика и
 * переиспользуемые примитивы (стеклянная карточка, шапка, чипы, пилюли,
 * прогресс-бары). Чтобы перекрасить весь клиент, достаточно поменять
 * значения ACCENT / ACCENT_2 ниже.
 */
final class HudStyle {
    static final String GLOW = "releon:textures/particles/ghost-glow.png";

    // ---------------------------------------------------------------- palette
    // Акцент: холодный индиго -> фиолетовый (градиент)
    static final int ACCENT_R = 118, ACCENT_G = 146, ACCENT_B = 255;
    static final int ACCENT2_R = 178, ACCENT2_G = 128, ACCENT2_B = 255;

    // Стекло
    static final int GLASS_R = 11, GLASS_G = 12, GLASS_B = 18;
    static final float GLASS_A = 196.0F;

    // Текст
    static final int TEXT_R = 242, TEXT_G = 244, TEXT_B = 250;
    static final int MUTED_R = 146, MUTED_G = 152, MUTED_B = 172;

    // Семантика
    static final int GOOD_R = 86, GOOD_G = 222, GOOD_B = 150;
    static final int WARN_R = 255, WARN_G = 196, WARN_B = 92;
    static final int BAD_R = 255, BAD_G = 94, BAD_B = 112;
    static final int GOLD_R = 255, GOLD_G = 204, GOLD_B = 64;

    // ------------------------------------------------------------ metrics
    static final float RADIUS = 6.0F;
    static final float RADIUS_SM = 3.5F;
    static final float STROKE = 0.5F;
    static final float PAD = 5.0F;
    static final float HEADER = 18.0F;
    static final float BLUR = 18.0F;
    static final float BLUR_SMOOTH = 1.25F;

    // ------------------------------------------------------------ typography
    static final FontType TITLE = FontType.BOLD;
    static final FontType BODY = FontType.SEMIBOLD;
    static final FontType NUM = FontType.BOLD;
    static final FontType GLYPH = FontType.MAINMENUSCREEN;
    static final float TITLE_SIZE = 6.5F;
    static final float BODY_SIZE = 6.0F;
    static final float SMALL_SIZE = 5.5F;
    static final float TINY_SIZE = 5.0F;

    private HudStyle() {
    }

    // ================================================================ colors

    static int rgba(int r, int g, int b, float a, float alpha) {
        return ColorUtil.rgba(r, g, b, Math.max(0, Math.min(255, Math.round(a * clamp01(alpha)))));
    }

    // ---------------------------------------------------- theme bridge
    // Цвета HUD берутся из текущей темы ClickGUI (ClickGuiThemes) и плавно
    // перетекают при её смене.

    static int[] themeAccent() {
        ClickGuiThemes.update();
        return ClickGuiTheme.ACCENT;
    }

    static int[] themeAccent2() {
        ClickGuiThemes.update();
        return ClickGuiTheme.SECONDARY;
    }

    static int accent(float a, float alpha) {
        int[] c = themeAccent();
        return rgba(c[0], c[1], c[2], a, alpha);
    }

    static int accent2(float a, float alpha) {
        int[] c = themeAccent2();
        return rgba(c[0], c[1], c[2], a, alpha);
    }

    /** Цвет стекла: базовый тёмный + лёгкий оттенок фона темы. */
    static int glassColor(float a, float alpha) {
        ClickGuiThemes.update();
        int[] w = ClickGuiTheme.WINDOW;
        return rgba(lerp(GLASS_R, w[0], 0.55F), lerp(GLASS_G, w[1], 0.55F), lerp(GLASS_B, w[2], 0.55F), a, alpha);
    }

    static int white(float a, float alpha) {
        return rgba(255, 255, 255, a, alpha);
    }

    static int black(float a, float alpha) {
        return rgba(0, 0, 0, a, alpha);
    }

    static int text(float alpha) {
        return rgba(TEXT_R, TEXT_G, TEXT_B, 255.0F, alpha);
    }

    static int muted(float alpha) {
        return rgba(MUTED_R, MUTED_G, MUTED_B, 240.0F, alpha);
    }

    static int good(float a, float alpha) {
        return rgba(GOOD_R, GOOD_G, GOOD_B, a, alpha);
    }

    static int warn(float a, float alpha) {
        return rgba(WARN_R, WARN_G, WARN_B, a, alpha);
    }

    static int bad(float a, float alpha) {
        return rgba(BAD_R, BAD_G, BAD_B, a, alpha);
    }

    static int gold(float a, float alpha) {
        return rgba(GOLD_R, GOLD_G, GOLD_B, a, alpha);
    }

    /** Цвет состояния 0..1: красный -> жёлтый -> зелёный. */
    static int health(float value, float a, float alpha) {
        float v = clamp01(value);
        int r;
        int g;
        int b;
        if (v < 0.5F) {
            float t = v / 0.5F;
            r = lerp(BAD_R, WARN_R, t);
            g = lerp(BAD_G, WARN_G, t);
            b = lerp(BAD_B, WARN_B, t);
        } else {
            float t = (v - 0.5F) / 0.5F;
            r = lerp(WARN_R, GOOD_R, t);
            g = lerp(WARN_G, GOOD_G, t);
            b = lerp(WARN_B, GOOD_B, t);
        }
        return rgba(r, g, b, a, alpha);
    }

    /** Смешивает два ARGB-цвета. */
    static int mix(int from, int to, float t) {
        float k = clamp01(t);
        int a = lerp((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, k);
        int r = lerp((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, k);
        int g = lerp((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, k);
        int b = lerp(from & 0xFF, to & 0xFF, k);
        return ColorUtil.rgba(r, g, b, a);
    }

    /** Подменяет альфу ARGB-цвета: a (0..255) * alpha. */
    static int withAlpha(int color, float a, float alpha) {
        return rgba((color >>> 16) & 0xFF, (color >>> 8) & 0xFF, color & 0xFF, a, alpha);
    }

    // ============================================================= surfaces

    /** Мягкая тень под карточкой (через glow-текстуру, уважает настройку glow). */
    static void shadow(float x, float y, float w, float h, float alpha) {
        HudRenderCompat.glow(GLOW, x - 9.0F, y - 6.0F, w + 18.0F, h + 16.0F, 0.0F, black(120.0F, alpha));
    }

    /** Цветное свечение позади элемента. */
    static void glow(float x, float y, float w, float h, int color) {
        if ((color >>> 24) > 2) {
            HudRenderCompat.glow(GLOW, x, y, w, h, 0.0F, color);
        }
    }

    /** Главная поверхность: матовое стекло + тонкая обводка + блик сверху. */
    static void glass(float x, float y, float w, float h, float r, float alpha) {
        if (alpha <= 0.01F || w <= 0.5F || h <= 0.5F) {
            return;
        }
        shadow(x, y, w, h, alpha);
        HudRenderCompat.background(x, y, w, h, r, BLUR, BLUR_SMOOTH, glassColor(GLASS_A, alpha));
        // едва заметная акцентная подкраска слева
        Render2D.gradientRect(x, y, w, h, r, r, r, r,
                accent(16.0F, alpha), white(3.0F, alpha), white(3.0F, alpha), accent(16.0F, alpha));
        Render2D.outline(x, y, w, h, r, STROKE, white(20.0F, alpha));
        sheen(x, y, w, r, alpha);
        shimmer(x, y, w, h, r, alpha);
    }

    /**
     * Бегущий блик по стеклу: раз в несколько секунд мягкая полоса света
     * проходит слева направо. Фаза зависит от позиции — элементы не мигают
     * синхронно.
     */
    static void shimmer(float x, float y, float w, float h, float r, float alpha) {
        if (alpha <= 0.05F || w < 16.0F) {
            return;
        }
        long period = 6200L;
        long offset = (long) (Math.abs(x * 13.0F + y * 7.0F)) % period;
        float phase = ((System.currentTimeMillis() + offset) % period) / (float) period;
        float run = 0.32F;
        if (phase > run) {
            return;
        }
        float t = easeInOut(phase / run);
        float half = Math.min(22.0F, w * 0.22F);
        float cx = x - half + (w + half * 2.0F) * t;
        float left = x + r;
        float right = x + w - r;
        float peak = 13.0F * (float) Math.sin(t * Math.PI);
        band(cx, half, left, right, y + 0.5F, h - 1.0F, peak, alpha);
        // искра на верхней кромке
        band(cx, half * 0.6F, left, right, y + 0.3F, 0.6F, peak * 5.0F, alpha);
    }

    /** Горизонтальная полоса света с треугольным профилем, обрезанная по [minX, maxX]. */
    static void band(float cx, float half, float minX, float maxX, float y, float h, float peak, float alpha) {
        float l = Math.max(minX, cx - half);
        float m = Math.min(maxX, Math.max(minX, cx));
        float rr = Math.min(maxX, cx + half);
        if (m > l + 0.1F) {
            int c0 = white(peak * (1.0F - (cx - l) / half), alpha);
            int c1 = white(peak * (1.0F - (cx - m) / half), alpha);
            Render2D.gradientRect(l, y, m - l, h, 0.0F, 0.0F, 0.0F, 0.0F, c0, c1, c1, c0);
        }
        float m2 = Math.max(l, m);
        if (rr > m2 + 0.1F) {
            int c0 = white(peak * (1.0F - (m2 - cx) / half), alpha);
            int c1 = white(peak * (1.0F - (rr - cx) / half), alpha);
            Render2D.gradientRect(m2, y, rr - m2, h, 0.0F, 0.0F, 0.0F, 0.0F, c0, c1, c1, c0);
        }
    }

    /** Уголки-скобки (рамка выделения как в редакторах). */
    static void corners(float x, float y, float w, float h, float len, float t, int color) {
        float l = Math.min(len, Math.min(w, h) * 0.5F);
        Render2D.rect(x, y, l, t, 0.0F, color);
        Render2D.rect(x, y, t, l, 0.0F, color);
        Render2D.rect(x + w - l, y, l, t, 0.0F, color);
        Render2D.rect(x + w - t, y, t, l, 0.0F, color);
        Render2D.rect(x, y + h - t, l, t, 0.0F, color);
        Render2D.rect(x, y + h - l, t, l, 0.0F, color);
        Render2D.rect(x + w - l, y + h - t, l, t, 0.0F, color);
        Render2D.rect(x + w - t, y + h - l, t, l, 0.0F, color);
    }

    /** Пунктир по вертикали (бегущий). */
    static void dashedV(float x, float y, float h, int color) {
        float dash = 3.0F;
        float gap = 2.5F;
        float shift = (System.currentTimeMillis() % 600L) / 600.0F * (dash + gap);
        for (float p = -dash + shift; p < h; p += dash + gap) {
            float a = Math.max(0.0F, p);
            float b = Math.min(h, p + dash);
            if (b > a) {
                Render2D.rect(x - 0.25F, y + a, 0.5F, b - a, 0.0F, color);
            }
        }
    }

    /** Пунктир по горизонтали (бегущий). */
    static void dashedH(float x, float y, float w, int color) {
        float dash = 3.0F;
        float gap = 2.5F;
        float shift = (System.currentTimeMillis() % 600L) / 600.0F * (dash + gap);
        for (float p = -dash + shift; p < w; p += dash + gap) {
            float a = Math.max(0.0F, p);
            float b = Math.min(w, p + dash);
            if (b > a) {
                Render2D.rect(x + a, y - 0.25F, b - a, 0.5F, 0.0F, color);
            }
        }
    }

    static void glass(float x, float y, float w, float h, float alpha) {
        glass(x, y, w, h, RADIUS, alpha);
    }

    /** Блик по верхней кромке (стекло «ловит свет»). */
    static void sheen(float x, float y, float w, float r, float alpha) {
        float inset = Math.max(r, 3.0F);
        float half = (w - inset * 2.0F) * 0.5F;
        if (half <= 1.0F) {
            return;
        }
        int edge = white(0.0F, alpha);
        int mid = white(46.0F, alpha);
        Render2D.gradientRect(x + inset, y + 0.35F, half, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, edge, mid, mid, edge);
        Render2D.gradientRect(x + inset + half, y + 0.35F, half, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, mid, edge, edge, mid);
    }

    /** Вложенная поверхность (строки, слоты, плашки). */
    static void surface(float x, float y, float w, float h, float r, float alpha) {
        Render2D.rect(x, y, w, h, r, white(10.0F, alpha));
    }

    /** Тонкий разделитель, растворяющийся к краям. */
    static void divider(float x, float y, float w, float alpha) {
        float half = w * 0.5F;
        if (half <= 0.5F) {
            return;
        }
        int edge = white(0.0F, alpha);
        int mid = white(26.0F, alpha);
        Render2D.gradientRect(x, y, half, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, edge, mid, mid, edge);
        Render2D.gradientRect(x + half, y, half, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, mid, edge, edge, mid);
    }

    static void verticalDivider(float x, float y, float h, float alpha) {
        Render2D.rect(x, y, 0.5F, h, 0.0F, white(22.0F, alpha));
    }

    // ================================================================ blocks

    /**
     * Чип с иконкой: скруглённый квадрат с акцентным градиентом и свечением.
     */
    static void iconChip(float x, float y, float size, String glyph, float glyphSize, float alpha) {
        iconChip(x, y, size, glyph, glyphSize, accent(255.0F, alpha), accent2(255.0F, alpha), alpha);
    }

    static void iconChip(float x, float y, float size, String glyph, float glyphSize, int left, int right, float alpha) {
        // градиент медленно «переливается» между цветами
        float flow = pulse(3600L);
        int a = mix(left, right, flow * 0.6F);
        int b = mix(right, left, flow * 0.6F);
        float breathe = 0.8F + 0.2F * pulse(2400L);
        glow(x - size * 0.6F, y - size * 0.6F, size * 2.2F, size * 2.2F, withAlpha(a, 70.0F * breathe, alpha));
        Render2D.gradientRect(x, y, size, size, RADIUS_SM, RADIUS_SM, RADIUS_SM, RADIUS_SM,
                withAlpha(a, 235.0F, alpha), withAlpha(b, 235.0F, alpha),
                withAlpha(b, 235.0F, alpha), withAlpha(a, 235.0F, alpha));
        // стеклянный блик в верхней половине
        Render2D.gradientRect(x + 0.5F, y + 0.5F, size - 1.0F, size * 0.5F, RADIUS_SM, RADIUS_SM, 0.0F, 0.0F,
                white(46.0F, alpha), white(46.0F, alpha), white(0.0F, alpha), white(0.0F, alpha));
        Render2D.outline(x, y, size, size, RADIUS_SM, STROKE, white(40.0F, alpha));
        if (glyph != null && !glyph.isEmpty()) {
            float gw = Render2D.textWidth(GLYPH, glyph, glyphSize);
            Render2D.text(GLYPH, glyph, x + (size - gw) * 0.5F, y + (size - glyphSize) * 0.5F, glyphSize, white(255.0F, alpha));
        }
    }

    /** Тонированный чип (иконка цветом, фон — полупрозрачный тон). */
    static void tintChip(float x, float y, float size, int tint, float alpha) {
        Render2D.rect(x, y, size, size, RADIUS_SM, withAlpha(tint, 40.0F, alpha));
        Render2D.outline(x, y, size, size, RADIUS_SM, STROKE, withAlpha(tint, 70.0F, alpha));
    }

    /**
     * Шапка карточки: чип-иконка, заголовок, бейдж-счётчик справа, разделитель снизу.
     */
    static void header(float x, float y, float w, float h, String glyph, float glyphSize, String title, String badge, float alpha) {
        float chip = 11.0F;
        float chipX = x + PAD;
        float chipY = y + (h - chip) * 0.5F;
        iconChip(chipX, chipY, chip, glyph, glyphSize, alpha);

        float titleX = chipX + chip + 5.0F;
        Render2D.text(TITLE, title, titleX, y + (h - TITLE_SIZE) * 0.5F, TITLE_SIZE, text(alpha));

        if (badge != null && !badge.isEmpty()) {
            float bw = Render2D.textWidth(NUM, badge, TINY_SIZE) + 7.0F;
            float bh = 9.0F;
            float bx = x + w - PAD - bw;
            float by = y + (h - bh) * 0.5F;
            Render2D.rect(bx, by, bw, bh, bh * 0.5F, accent(38.0F, alpha));
            Render2D.outline(bx, by, bw, bh, bh * 0.5F, STROKE, accent(80.0F, alpha));
            Render2D.text(NUM, badge, bx + 3.5F, by + (bh - TINY_SIZE) * 0.5F, TINY_SIZE, accent(255.0F, alpha));
        }
        divider(x + PAD, y + h - 0.5F, w - PAD * 2.0F, alpha);
    }

    /** Ширина, которую займёт шапка (для авто-размера). */
    static float headerWidth(String title, String badge) {
        float w = PAD + 11.0F + 5.0F + Render2D.textWidth(TITLE, title, TITLE_SIZE) + 10.0F;
        if (badge != null && !badge.isEmpty()) {
            w += Render2D.textWidth(NUM, badge, TINY_SIZE) + 7.0F;
        }
        return w + PAD;
    }

    /** Пилюля с текстом. Возвращает её ширину. */
    static float pill(float x, float y, float h, String label, FontType font, float size, int bg, int fg) {
        float w = Render2D.textWidth(font, label, size) + 6.0F;
        Render2D.rect(x, y, w, h, h * 0.5F, bg);
        Render2D.text(font, label, x + 3.0F, y + (h - size) * 0.5F, size, fg);
        return w;
    }

    static float pillWidth(String label, FontType font, float size) {
        return Render2D.textWidth(font, label, size) + 6.0F;
    }

    /** Точка-индикатор со свечением. */
    static void dot(float cx, float cy, float size, int color) {
        glow(cx - size * 2.0F, cy - size * 2.0F, size * 4.0F, size * 4.0F, withAlpha(color, 90.0F, ((color >>> 24) & 0xFF) / 255.0F));
        Render2D.rect(cx - size * 0.5F, cy - size * 0.5F, size, size, size * 0.5F, color);
    }

    /** Прогресс-бар: трек + градиентная заливка + светящийся кончик. */
    static void progress(float x, float y, float w, float h, float value, int left, int right, float alpha) {
        float r = h * 0.5F;
        Render2D.rect(x, y, w, h, r, white(16.0F, alpha));
        float p = clamp01(value);
        if (p <= 0.001F) {
            return;
        }
        float fw = Math.max(h, w * p);
        Render2D.rect(x, y, fw, h, r, left, right, right, left);
        // бегущий блик по заливке
        if (fw > h * 3.0F) {
            float phase = (System.currentTimeMillis() % 2600L) / 2600.0F;
            if (phase < 0.55F) {
                float t = phase / 0.55F;
                float half = Math.min(9.0F, fw * 0.25F);
                band(x - half + (fw + half * 2.0F) * t, half, x + r, x + fw - r, y, h, 90.0F * (float) Math.sin(t * Math.PI), alpha);
            }
        }
        if (p > 0.03F && p < 0.995F) {
            glow(x + fw - h * 3.0F, y - h * 2.5F, h * 6.0F, h * 6.0F, withAlpha(right, 110.0F, alpha));
        }
    }

    static void progress(float x, float y, float w, float h, float value, float alpha) {
        progress(x, y, w, h, value, accent(255.0F, alpha), accent2(255.0F, alpha), alpha);
    }

    // ================================================================ text

    static float textWidth(FontType font, String text, float size) {
        return Render2D.textWidth(font, text, size);
    }

    static void textRight(FontType font, String text, float right, float y, float size, int color) {
        Render2D.text(font, text, right - Render2D.textWidth(font, text, size), y, size, color);
    }

    static void textCentered(FontType font, String text, float cx, float y, float size, int color) {
        Render2D.text(font, text, cx - Render2D.textWidth(font, text, size) * 0.5F, y, size, color);
    }

    /** Моноширинная отрисовка цифр — значения не «прыгают» при смене. */
    static float stableWidth(String text, float cell) {
        return text.length() * cell;
    }

    static void stableText(String text, float x, float y, float size, float cell, int color) {
        for (int i = 0; i < text.length(); i++) {
            String c = Character.toString(text.charAt(i));
            float cw = Render2D.textWidth(NUM, c, size);
            Render2D.text(NUM, c, x + i * cell + (cell - cw) * 0.5F, y, size, color);
        }
    }

    static void stableTextRight(String text, float right, float y, float size, float cell, int color) {
        stableText(text, right - text.length() * cell, y, size, cell, color);
    }

    static String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(value);
        };
    }

    // ================================================================ math

    static float clamp01(float v) {
        return v < 0.0F ? 0.0F : Math.min(1.0F, v);
    }

    static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float easeOut(float t) {
        float k = 1.0F - clamp01(t);
        return 1.0F - k * k * k;
    }

    static float easeInOut(float t) {
        float k = clamp01(t);
        return k < 0.5F ? 4.0F * k * k * k : 1.0F - (float) Math.pow(-2.0F * k + 2.0F, 3.0D) / 2.0F;
    }

    static float easeOutBack(float t) {
        float k = clamp01(t) - 1.0F;
        float c = 1.70158F;
        return 1.0F + (c + 1.0F) * k * k * k + c * k * k;
    }

    /** 0..1..0 пульсация с периодом periodMs. */
    static float pulse(long periodMs) {
        double phase = (System.currentTimeMillis() % periodMs) / (double) periodMs;
        return (float) (0.5D - 0.5D * Math.cos(phase * Math.PI * 2.0D));
    }
}
