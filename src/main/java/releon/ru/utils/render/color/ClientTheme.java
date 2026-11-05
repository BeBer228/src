package releon.ru.utils.render.color;

import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.screens.clickgui.impl.ClickGuiThemes;

/**
 * Единая точка доступа к цветам текущей темы для ВСЕГО клиента (HUD, хотбар, ESP, эффекты...).
 * Тема выбирается во вкладке ClickGUI → Themes. При смене темы цвета плавно перетекают —
 * модули, которые берут цвет отсюда каждый кадр, меняются вместе с меню.
 *
 * <pre>
 * ClientTheme.primary()          // основной цвет темы, ARGB, alpha 255
 * ClientTheme.primary(140)       // с прозрачностью 0..255
 * ClientTheme.secondary(255)     // второй цвет (для градиентов)
 * ClientTheme.wave(520, 230)     // плавный перелив primary ↔ secondary (период в мс)
 * ClientTheme.gradient(0.3f, 255) // точка градиента primary → secondary (0..1)
 * </pre>
 * Все методы дешёвые: никаких аллокаций, только чтение 3 чисел.
 */
public final class ClientTheme {
    private ClientTheme() {
    }

    private static int argb(int[] rgb, int alpha) {
        ClickGuiThemes.update(); // мгновенный выход, если тема не меняется
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
    }

    /** Основной цвет темы (акцент). */
    public static int primary() {
        return argb(ClickGuiTheme.ACCENT, 255);
    }

    public static int primary(int alpha) {
        return argb(ClickGuiTheme.ACCENT, alpha);
    }

    /** Второй цвет темы. */
    public static int secondary() {
        return argb(ClickGuiTheme.SECONDARY, 255);
    }

    public static int secondary(int alpha) {
        return argb(ClickGuiTheme.SECONDARY, alpha);
    }

    /** Приглушённый акцент (обводки, второстепенные элементы). */
    public static int dim(int alpha) {
        return argb(ClickGuiTheme.ACCENT_DIM, alpha);
    }

    /** Цвет фона панелей темы (подложки HUD и т.п.). */
    public static int background(int alpha) {
        return argb(ClickGuiTheme.WINDOW, alpha);
    }

    /** Основной цвет текста темы. */
    public static int text(int alpha) {
        return argb(ClickGuiTheme.TEXT, alpha);
    }

    /** Точка градиента primary → secondary, t = 0..1. */
    public static int gradient(float t, int alpha) {
        ClickGuiThemes.update();
        float p = Math.max(0.0f, Math.min(1.0f, t));
        int[] a = ClickGuiTheme.ACCENT;
        int[] b = ClickGuiTheme.SECONDARY;
        int r = Math.round(a[0] + (b[0] - a[0]) * p);
        int g = Math.round(a[1] + (b[1] - a[1]) * p);
        int bl = Math.round(a[2] + (b[2] - a[2]) * p);
        int al = Math.max(0, Math.min(255, alpha));
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    /** Плавный перелив primary ↔ secondary во времени. periodMs — скорость (как 520 в старом коде). */
    public static int wave(long periodMs, int alpha) {
        return gradient((float) ((Math.sin(System.currentTimeMillis() / (double) Math.max(1L, periodMs)) + 1.0) * 0.5), alpha);
    }

    /** То же, но со сдвигом фазы (offset 0..1) — для градиентов вдоль полоски/следа. */
    public static int wave(long periodMs, float offset, int alpha) {
        double phase = System.currentTimeMillis() / (double) Math.max(1L, periodMs) + offset * Math.PI * 2.0;
        return gradient((float) ((Math.sin(phase) + 1.0) * 0.5), alpha);
    }

    /** Название текущей темы. */
    public static String name() {
        ClickGuiThemes.load();
        return ClickGuiThemes.currentTheme().name();
    }
}
