package releon.ru.screens.clickgui.impl;

import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Дизайн-система ClickGUI (стиль "Lite"): тёмные графитовые поверхности, один розовый акцент,
 * тонкие обводки, мягкие тени. Все цвета и общие элементы (тумблер, чекбокс, чипы) — здесь,
 * чтобы менять внешний вид в одном месте.
 */
public final class ClickGuiTheme {
    // ---------------------------------------------------------------- palette
    public static final int[] ACCENT = {255, 122, 168};
    public static final int[] ACCENT_DIM = {196, 84, 128};
    /** Второй цвет темы (градиенты в модулях). */
    public static final int[] SECONDARY = {127, 242, 255};

    public static final int[] WINDOW = {17, 17, 20};
    public static final int[] WINDOW_TOP = {21, 21, 25};
    public static final int[] SURFACE = {25, 25, 29};
    public static final int[] SURFACE_HI = {30, 30, 35};
    public static final int[] FIELD = {33, 33, 38};
    public static final int[] FIELD_HI = {41, 41, 47};
    public static final int[] BORDER = {40, 40, 46};
    public static final int[] DIVIDER = {34, 34, 39};

    public static final int[] TEXT = {236, 236, 240};
    public static final int[] TEXT_DIM = {150, 150, 160};
    public static final int[] TEXT_MUTED = {104, 104, 114};

    // ---------------------------------------------------------------- type scale
    public static final float FS_TITLE = 11.0f;
    public static final float FS_TAB = 7.0f;
    public static final float FS_CARD = 8.0f;
    public static final float FS_LABEL = 6.9f;
    public static final float FS_DESC = 5.6f;
    public static final float FS_VALUE = 6.2f;
    public static final float FS_BADGE = 5.4f;
    public static final float FS_DESC_CARD = 5.9f;

    /** Избранные модули (хранятся до перезапуска; можно подключить к конфигу через {@link #favorites()}). */
    private static final Set<Module> FAVORITES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Class<?>, Method> DESCRIPTION_METHODS = new HashMap<>();
    private static final Method NO_METHOD;

    static {
        Method none = null;
        try {
            none = Object.class.getMethod("toString");
        } catch (NoSuchMethodException ignored) {
        }
        NO_METHOD = none;
    }

    private ClickGuiTheme() {
    }

    // ---------------------------------------------------------------- colors

    public static int c(int[] rgb, float alpha) {
        return ColorUtil.rgba(rgb[0], rgb[1], rgb[2], a(alpha));
    }

    public static int c(int[] rgb, int alpha255, float alpha) {
        return ColorUtil.rgba(rgb[0], rgb[1], rgb[2], a(alpha255 / 255.0f * alpha));
    }

    public static int mix(int[] from, int[] to, float t, float alpha) {
        float p = Math.max(0.0f, Math.min(1.0f, t));
        return ColorUtil.rgba(
                Math.round(from[0] + (to[0] - from[0]) * p),
                Math.round(from[1] + (to[1] - from[1]) * p),
                Math.round(from[2] + (to[2] - from[2]) * p),
                a(alpha));
    }

    public static int white(float alpha) {
        return ColorUtil.rgba(255, 255, 255, a(alpha));
    }

    public static int black(float alpha) {
        return ColorUtil.rgba(0, 0, 0, a(alpha));
    }

    public static int a(float alpha) {
        return Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
    }

    // ---------------------------------------------------------------- pixel grid
    /*
     * Всё "неровное" было из-за дробных координат: рамки 0.6 px, текст на 0.3 px выше/ниже,
     * карточки на половине пикселя. Теперь каждая координата прилипает к реальному пикселю экрана,
     * линии всегда ровно 1 физический пиксель, а текст центрируется одной формулой.
     */
    /** Подстройка вертикального центра текста (доля от размера шрифта). Если текст "сидит" выше — увеличь. */
    public static final float TEXT_NUDGE = 0.05f;
    /** То же для иконочных шрифтов. */
    public static final float ICON_NUDGE = 0.05f;

    private static float px = 0.5f;

    /** Вызывается контроллером раз в кадр: размер одного физического пикселя в координатах меню. */
    public static void setPixelSize(float size) {
        px = size > 0.0001f && Float.isFinite(size) ? size : 0.5f;
    }

    public static float px() {
        return px;
    }

    /** Прилипание к пиксельной сетке. */
    public static float snap(float v) {
        return Math.round(v / px) * px;
    }

    /** Толщина линий/обводок: ровно N целых пикселей (~0.6 ед. дизайна, минимум 1 px). */
    public static float line() {
        return Math.max(1, Math.round(0.6f / px)) * px;
    }

    // ---------------------------------------------------------------- easing (без аллокаций)

    public static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    public static float easeOutCubic(float t) {
        t = clamp01(t);
        float i = 1.0f - t;
        return 1.0f - i * i * i;
    }

    /** С лёгким "перелётом" — для появления карточек. */
    public static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.45f;
        float c3 = c1 + 1.0f;
        float i = t - 1.0f;
        return 1.0f + c3 * i * i * i + c1 * i * i;
    }

    /** Экспоненциальное сглаживание, не зависящее от FPS. */
    public static float approach(float current, float target, float speed, float dt) {
        return current + (target - current) * (1.0f - (float) Math.exp(-speed * dt));
    }

    /** Круг (заливка). */
    public static void circle(float cx, float cy, float r, int color) {
        if (r <= 0.05f) {
            return;
        }
        Render2D.rect(cx - r, cy - r, r * 2.0f, r * 2.0f, r, color);
    }

    /** Кольцо. */
    public static void ring(float cx, float cy, float r, float thickness, int color) {
        if (r <= 0.05f) {
            return;
        }
        Render2D.outline(cx - r, cy - r, r * 2.0f, r * 2.0f, r, thickness, color);
    }

    // ---------------------------------------------------------------- text (one formula everywhere)

    /** Текст, отцентрованный по вертикали относительно cy. */
    public static void text(FontType font, String s, float x, float cy, float size, int color) {
        if (s == null || s.isEmpty()) {
            return;
        }
        Render2D.text(font, s, snap(x), textTop(font, cy, size), size, color);
    }

    /** Текст, прижатый правым краем к right. Возвращает x начала. */
    public static float textRight(FontType font, String s, float right, float cy, float size, int color) {
        float x = right - Render2D.textWidth(font, s, size);
        text(font, s, x, cy, size, color);
        return x;
    }

    /** Текст по центру точки (cx, cy). */
    public static void textCenter(FontType font, String s, float cx, float cy, float size, int color) {
        text(font, s, cx - Render2D.textWidth(font, s, size) * 0.5f, cy, size, color);
    }

    /** Верх строки текста для заданного центра (для мест, где нужен сырой Render2D.text). */
    public static float textTop(FontType font, float cy, float size) {
        float nudge = font == FontType.SEMIBOLD || font == FontType.BOLD ? TEXT_NUDGE : ICON_NUDGE;
        return snap(cy - size * 0.5f + size * nudge);
    }

    // ---------------------------------------------------------------- primitives (без теней и свечений — они и съедали FPS)

    public static void rect(float x, float y, float w, float h, float r, int color) {
        float sx = snap(x);
        float sy = snap(y);
        Render2D.rect(sx, sy, snap(x + w) - sx, snap(y + h) - sy, r, color);
    }

    public static void border(float x, float y, float w, float h, float r, int color) {
        float sx = snap(x);
        float sy = snap(y);
        Render2D.outline(sx, sy, snap(x + w) - sx, snap(y + h) - sy, r, line(), color);
    }

    /** Карточка/панель: сплошная заливка + обводка в 1 px. */
    public static void surface(float x, float y, float w, float h, float radius, int[] fill, float hover, float alpha) {
        rect(x, y, w, h, radius, mix(fill, SURFACE_HI, hover * 0.5f, alpha));
        border(x, y, w, h, radius, c(BORDER, alpha));
    }

    /** Поле ввода/выпадающий список. */
    public static void field(float x, float y, float w, float h, float hover, float focus, float alpha) {
        rect(x, y, w, h, 3.5f, mix(FIELD, FIELD_HI, hover, alpha));
        border(x, y, w, h, 3.5f, focus > 0.01f ? mix(BORDER, ACCENT, focus, alpha) : c(BORDER, alpha));
    }

    public static void divider(float x, float y, float w, float alpha) {
        float sx = snap(x);
        Render2D.rect(sx, snap(y), snap(x + w) - sx, line(), 0.0f, c(DIVIDER, alpha));
    }

    /** Тумблер: трек + ручка. progress 0..1. */
    public static void toggle(float x, float y, float w, float h, float progress, float hover, float alpha) {
        x = snap(x);
        y = snap(y);
        float r = h * 0.5f;
        Render2D.rect(x, y, w, h, r, mix(FIELD_HI, ACCENT, progress, alpha));
        float pad = snap(1.6f);
        float knob = h - pad * 2.0f;
        float kx = x + pad + (w - knob - pad * 2.0f) * progress;
        int knobColor = progress > 0.5f ? white(alpha) : mix(TEXT_DIM, TEXT, hover, alpha);
        Render2D.rect(kx, y + pad, knob, knob, knob * 0.5f, knobColor);
    }

    /** Чекбокс: розовый квадрат с галочкой. */
    public static void checkbox(float x, float y, float size, float progress, float hover, float alpha) {
        x = snap(x);
        y = snap(y);
        Render2D.rect(x, y, size, size, 2.4f, mix(FIELD_HI, ACCENT, progress, alpha));
        if (progress < 0.99f) {
            Render2D.outline(x, y, size, size, 2.4f, line(), mix(BORDER, TEXT_MUTED, hover, (1.0f - progress) * alpha));
        }
        if (progress > 0.05f) {
            textCenter(FontType.GUI_ICONS, "P", x + size * 0.5f, y + size * 0.5f, size * 0.95f, white(progress * alpha));
        }
    }

    /** Бейдж-таблетка ("5 Active"), центр по cy. Возвращает ширину. */
    public static float badge(String text, float x, float cy, boolean accent, float alpha) {
        float w = snap(Render2D.textWidth(FontType.SEMIBOLD, text, FS_BADGE) + 8.0f);
        float h = 10.0f;
        float y = cy - h * 0.5f;
        rect(x, y, w, h, 2.5f, accent ? c(ACCENT, 38, alpha) : c(FIELD, alpha));
        border(x, y, w, h, 2.5f, accent ? c(ACCENT, 90, alpha) : c(BORDER, alpha));
        textCenter(FontType.SEMIBOLD, text, x + w * 0.5f, cy, FS_BADGE, accent ? c(ACCENT, alpha) : c(TEXT_DIM, alpha));
        return w;
    }

    /** Квадратная иконка-кнопка (верхняя панель). */
    public static void iconButton(float x, float y, float size, FontType font, String icon, float iconSize,
                                  boolean active, float hover, float alpha) {
        if (active) {
            rect(x, y, size, size, 4.0f, mix(ACCENT, ACCENT_DIM, hover * 0.35f, alpha));
        } else {
            rect(x, y, size, size, 4.0f, mix(SURFACE, FIELD_HI, hover, alpha));
            border(x, y, size, size, 4.0f, c(BORDER, alpha));
        }
        int color = active ? white(alpha) : mix(TEXT_DIM, TEXT, hover, alpha);
        textCenter(font, icon, x + size * 0.5f, y + size * 0.5f, iconSize, color);
    }

    /** @deprecated тени убраны ради FPS; оставлено для совместимости. */
    @Deprecated
    public static void shadow(float x, float y, float w, float h, float radius, float strength, float alpha) {
    }

    /** Кеш обрезанной строки: пересчитывается только когда меняется текст/ширина (раньше — каждый кадр). */
    public static final class Ellipsis {
        private String text;
        private float size;
        private float width = -1.0f;
        private String result = "";

        public String get(FontType font, String text, float size, float maxWidth) {
            if (text == null) {
                return "";
            }
            if (text != this.text || size != this.size || Math.abs(maxWidth - width) > 0.25f) {
                if (!text.equals(this.text) || size != this.size || Math.abs(maxWidth - width) > 0.25f) {
                    result = ellipsize(font, text, size, maxWidth);
                }
                this.text = text;
                this.size = size;
                this.width = maxWidth;
            }
            return result;
        }
    }

    // ---------------------------------------------------------------- text helpers

    public static String ellipsize(FontType font, String text, float size, float maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0.0f) {
            return "";
        }
        if (Render2D.textWidth(font, text, size) <= maxWidth) {
            return text;
        }
        String dots = "...";
        float dotsW = Render2D.textWidth(font, dots, size);
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (Render2D.textWidth(font, text.substring(0, mid), size) + dotsW <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo).stripTrailing() + dots;
    }

    // ---------------------------------------------------------------- data helpers

    public static Set<Module> favorites() {
        return FAVORITES;
    }

    public static boolean isFavorite(Module module) {
        return FAVORITES.contains(module);
    }

    public static void toggleFavorite(Module module) {
        if (!FAVORITES.remove(module)) {
            FAVORITES.add(module);
        }
    }

    /**
     * Описание модуля/настройки. В конструкторах есть описание ("Highlights the current Aura target."),
     * но геттер мне не прислали — поэтому ищем его по типичным именам один раз и кешируем.
     * Если знаешь точное имя — замени на прямой вызов, например {@code module.getDescription()}.
     */
    public static String description(Object target) {
        if (target == null) {
            return "";
        }
        Method method;
        synchronized (DESCRIPTION_METHODS) {
            method = DESCRIPTION_METHODS.computeIfAbsent(target.getClass(), ClickGuiTheme::findDescriptionMethod);
        }
        if (method == NO_METHOD || method == null) {
            return "";
        }
        try {
            Object value = method.invoke(target);
            return value == null ? "" : value.toString().trim();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }

    private static Method findDescriptionMethod(Class<?> type) {
        for (String name : new String[]{"getDescription", "description", "getDesc", "desc", "getInfo"}) {
            Class<?> c = type;
            while (c != null && c != Object.class) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                        m.setAccessible(true);
                        return m;
                    }
                } catch (NoSuchMethodException | RuntimeException ignored) {
                }
                c = c.getSuperclass();
            }
        }
        return NO_METHOD;
    }

    public static String categoryIcon(ModuleCategory category) {
        return switch (category) {
            case COMBAT -> "r";
            case MOVEMENT -> "j";
            case VISUAL -> "a";
            case PLAYER -> "t";
            case MISC -> "d";
        };
    }

    public static String categoryDescription(ModuleCategory category) {
        return switch (category) {
            case COMBAT -> "Aura, velocity and everything for the fight.";
            case MOVEMENT -> "Speed, flight and smarter movement.";
            case VISUAL -> "Explore materials, effects and visual rendering.";
            case PLAYER -> "Automation and quality-of-life for your player.";
            case MISC -> "Utilities, interface and everything else.";
        };
    }
}
