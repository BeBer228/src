package releon.ru.screens.clickgui.impl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Темы ClickGUI.
 * <p>
 * Каждая тема задаётся всего тремя цветами: акцент, второй цвет (для градиентов) и оттенок фона.
 * Остальные 11 цветов палитры (окно, карточки, поля, рамки, текст) вычисляются из них по одной формуле,
 * поэтому все темы одинаково аккуратные и "сочетаются" сами с собой.
 * <p>
 * Смена темы — плавное перетекание цветов за {@link #TRANSITION_MS} мс.
 * Выбранная тема сохраняется в {@code titanium/clickgui_theme.txt}.
 */
public final class ClickGuiThemes {
    public static final long TRANSITION_MS = 520L;
    private static final Path SAVE = Path.of("titanium", "clickgui_theme.txt");

    /** Порядок слотов палитры. */
    private static final int ACCENT = 0, ACCENT_DIM = 1, WINDOW = 2, WINDOW_TOP = 3, SURFACE = 4, SURFACE_HI = 5,
            FIELD = 6, FIELD_HI = 7, BORDER = 8, DIVIDER = 9, TEXT = 10, TEXT_DIM = 11, TEXT_MUTED = 12, SECONDARY = 13;
    private static final int SLOTS = 14;

    public static final class Theme {
        private final String name;
        private final String description;
        private final int[][] palette;

        /**
         * @param accent    главный цвет (кнопки, тумблеры, выделение)
         * @param secondary второй цвет (градиенты, TargetESP и т.п.)
         * @param base      цвет фона окна (очень тёмный, с лёгким оттенком)
         */
        Theme(String name, String description, int accent, int secondary, int base) {
            this.name = name;
            this.description = description;
            int[] hex = new int[SLOTS];
            hex[ACCENT] = accent;
            hex[SECONDARY] = secondary;
            hex[ACCENT_DIM] = shade(accent, 0.72f);
            hex[WINDOW] = base;
            hex[WINDOW_TOP] = lighten(base, 0.025f);
            hex[SURFACE] = lighten(base, 0.05f);
            hex[SURFACE_HI] = lighten(base, 0.085f);
            hex[FIELD] = lighten(base, 0.095f);
            hex[FIELD_HI] = lighten(base, 0.14f);
            hex[BORDER] = blend(lighten(base, 0.12f), accent, 0.06f);
            hex[DIVIDER] = lighten(base, 0.075f);
            hex[TEXT] = blend(0xF1F1F5, accent, 0.05f);
            hex[TEXT_DIM] = blend(0x9C9CA8, accent, 0.10f);
            hex[TEXT_MUTED] = blend(0x6C6C78, accent, 0.10f);
            palette = new int[SLOTS][];
            for (int i = 0; i < SLOTS; i++) {
                palette[i] = new int[]{(hex[i] >> 16) & 255, (hex[i] >> 8) & 255, hex[i] & 255};
            }
        }

        public String name() {
            return name;
        }

        public String description() {
            return description;
        }

        public int[] accent() {
            return palette[ACCENT];
        }

        /** Второй цвет темы — для градиентов модулей (TargetESP, DashTrail, Hands...). */
        public int[] secondary() {
            return palette[SECONDARY];
        }

        public int[] accentDim() {
            return palette[ACCENT_DIM];
        }

        public int[] window() {
            return palette[WINDOW];
        }

        public int[] windowTop() {
            return palette[WINDOW_TOP];
        }

        public int[] surface() {
            return palette[SURFACE];
        }

        public int[] surfaceHi() {
            return palette[SURFACE_HI];
        }

        public int[] field() {
            return palette[FIELD];
        }

        public int[] fieldHi() {
            return palette[FIELD_HI];
        }

        public int[] border() {
            return palette[BORDER];
        }

        public int[] text() {
            return palette[TEXT];
        }

        public int[] textDim() {
            return palette[TEXT_DIM];
        }

        public int[] textMuted() {
            return palette[TEXT_MUTED];
        }
    }

    public static final List<Theme> THEMES = List.of(
            new Theme("Sakura", "Нежно-розовый и голубой", 0xFF7AA8, 0x8FD3FF, 0x121014),
            new Theme("Aurora", "Северное сияние", 0x2DD4BF, 0xA78BFA, 0x0C1314),
            new Theme("Ocean", "Глубокий синий океан", 0x4FA3FF, 0x3DE0C8, 0x0C1118),
            new Theme("Synthwave", "Неон восьмидесятых", 0xFF3CAC, 0x2BD2FF, 0x120C16),
            new Theme("Sunset", "Тёплый закат", 0xFF8A4C, 0xFF4F8B, 0x141010),
            new Theme("Violet", "Фиолетовая ночь", 0xA67CFF, 0x5CC8FF, 0x100E17),
            new Theme("Galaxy", "Космос и туманности", 0x7F5AF0, 0x2CB67D, 0x0E0D15),
            new Theme("Cyberpunk", "Розовый неон и жёлтый", 0xFF2E88, 0xFFD000, 0x120E12),
            new Theme("Crimson", "Агрессивный красный", 0xFF4D5E, 0xFF9A4D, 0x140E0F),
            new Theme("Lava", "Раскалённая лава", 0xFF5A1F, 0xFFC24B, 0x140D0B),
            new Theme("Gold", "Золото на чёрном", 0xF2B544, 0xFFE8A3, 0x12110D),
            new Theme("Mint", "Свежая мята", 0x3DDC97, 0x7FD8FF, 0x0D1311),
            new Theme("Toxic", "Кислотный лайм", 0xA3E635, 0x22D3EE, 0x0E120C),
            new Theme("Matrix", "Зелёный код", 0x39FF88, 0x00C2FF, 0x0A110C),
            new Theme("Emerald", "Изумруд и золото", 0x2ECC71, 0xF5D76E, 0x0C130F),
            new Theme("Ice", "Холодный лёд", 0x7DD3FC, 0xC4B5FD, 0x0D1216),
            new Theme("Deep Sea", "Бездна морская", 0x06B6D4, 0x3B82F6, 0x0A1114),
            new Theme("Midnight", "Полночный город", 0x4A6CF7, 0x9D7BFF, 0x0B0D15),
            new Theme("Royal", "Королевский индиго", 0x6C63FF, 0xFF6CB5, 0x0E0E16),
            new Theme("Bubblegum", "Сладкая жвачка", 0xFF6EC7, 0x7AD7FF, 0x140E13),
            new Theme("Peach", "Персик и малина", 0xFFA585, 0xFF6FA5, 0x15100F),
            new Theme("Rose Gold", "Розовое золото", 0xE8A3A3, 0xF5D0A9, 0x141011),
            new Theme("Blood Moon", "Кровавая луна", 0xD02435, 0xFF6A3D, 0x120B0C),
            new Theme("Mono", "Строгий графит", 0xD4D4DC, 0x8A8A96, 0x0F0F11)
    );

    private static final int[][] FROM = new int[SLOTS][3];
    private static int current;
    private static long transitionStart;
    private static boolean transitioning;
    private static boolean loaded;

    private ClickGuiThemes() {
    }

    private static int[][] targets() {
        return new int[][]{ClickGuiTheme.ACCENT, ClickGuiTheme.ACCENT_DIM, ClickGuiTheme.WINDOW, ClickGuiTheme.WINDOW_TOP,
                ClickGuiTheme.SURFACE, ClickGuiTheme.SURFACE_HI, ClickGuiTheme.FIELD, ClickGuiTheme.FIELD_HI,
                ClickGuiTheme.BORDER, ClickGuiTheme.DIVIDER, ClickGuiTheme.TEXT, ClickGuiTheme.TEXT_DIM, ClickGuiTheme.TEXT_MUTED,
                ClickGuiTheme.SECONDARY};
    }

    private static final int[][] LIVE = targets();

    public static int current() {
        return current;
    }

    public static Theme currentTheme() {
        return THEMES.get(current);
    }

    /** Загрузить сохранённую тему (один раз, без анимации). */
    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        int index = 0;
        try {
            if (Files.exists(SAVE)) {
                String name = Files.readString(SAVE, StandardCharsets.UTF_8).trim();
                for (int i = 0; i < THEMES.size(); i++) {
                    if (THEMES.get(i).name().equalsIgnoreCase(name)) {
                        index = i;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        current = index;
        for (int i = 0; i < SLOTS; i++) {
            System.arraycopy(THEMES.get(index).palette[i], 0, LIVE[i], 0, 3);
        }
    }

    /** Сменить тему с плавным перетеканием цветов. */
    public static void apply(int index) {
        load();
        if (index < 0 || index >= THEMES.size() || index == current) {
            return;
        }
        for (int i = 0; i < SLOTS; i++) {
            System.arraycopy(LIVE[i], 0, FROM[i], 0, 3);
        }
        current = index;
        transitionStart = System.currentTimeMillis();
        transitioning = true;
        try {
            Files.createDirectories(SAVE.getParent());
            Files.writeString(SAVE, THEMES.get(index).name(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Раз в кадр. Пока перехода нет — мгновенный выход. */
    public static void update() {
        load();
        if (!transitioning) {
            return;
        }
        float t = Math.min(1.0f, (System.currentTimeMillis() - transitionStart) / (float) TRANSITION_MS);
        float e = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        int[][] to = THEMES.get(current).palette;
        for (int i = 0; i < SLOTS; i++) {
            for (int c = 0; c < 3; c++) {
                LIVE[i][c] = Math.round(FROM[i][c] + (to[i][c] - FROM[i][c]) * e);
            }
        }
        if (t >= 1.0f) {
            transitioning = false;
        }
    }

    /** 0..1 — прогресс текущего перехода (1, если перехода нет). */
    public static float transition() {
        return transitioning ? Math.min(1.0f, (System.currentTimeMillis() - transitionStart) / (float) TRANSITION_MS) : 1.0f;
    }

    // ---------------------------------------------------------------- color math

    private static int lighten(int rgb, float k) {
        return blend(rgb, 0xFFFFFF, k);
    }

    private static int shade(int rgb, float k) {
        int r = Math.round(((rgb >> 16) & 255) * k);
        int g = Math.round(((rgb >> 8) & 255) * k);
        int b = Math.round((rgb & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    private static int blend(int from, int to, float k) {
        int fr = (from >> 16) & 255, fg = (from >> 8) & 255, fb = from & 255;
        int tr = (to >> 16) & 255, tg = (to >> 8) & 255, tb = to & 255;
        int r = Math.round(fr + (tr - fr) * k);
        int g = Math.round(fg + (tg - fg) * k);
        int b = Math.round(fb + (tb - fb) * k);
        return (r << 16) | (g << 8) | b;
    }
}
