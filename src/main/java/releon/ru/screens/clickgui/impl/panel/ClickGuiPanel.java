package releon.ru.screens.clickgui.impl.panel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.ModuleManager;
import releon.ru.api.settings.Setting;
import releon.ru.manager.Manager;
import releon.ru.screens.clickgui.impl.ClickGuiController;
import releon.ru.screens.clickgui.impl.ClickGuiTheme;
import releon.ru.screens.clickgui.impl.ClickGuiThemes;
import releon.ru.screens.clickgui.impl.module.ModuleOption;
import releon.ru.screens.clickgui.impl.module.ModuleOptionFactory;
import releon.ru.screens.clickgui.impl.search.ClickGuiSearchBar;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Главное окно ClickGUI в стиле макета:
 * <pre>
 * ┌───────────────────────────────────────────────────────────────┐
 * │ [T]     ( Combat | Movement | Visual | Player | Misc )   ● ⌄ ✕ │  ← верхняя панель
 * ├───────────────────────────────────────────────────────────────┤
 * │ (●) Visual  [5 Active] [11 Total]      [Default order⌄][Search]│  ← шапка категории
 * │     Explore materials, effects and visual rendering.           │
 * │ ┌─card─┐ ┌─card─┐ ┌─card─┐                                    ▐│  ← сетка карточек
 * │ └──────┘ │      │ └──────┘                                     │    (masonry, 2–3 колонки)
 * └───────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class ClickGuiPanel {
    private static final float MAX_WIDTH = 660.0f;
    private static final float MAX_HEIGHT = 410.0f;
    private static final float RADIUS = 8.0f;
    private static final float TOP_BAR = 36.0f;
    private static final float HEADER = 40.0f;
    private static final float PAD = 10.0f;
    private static final float GAP = 6.0f;
    private static final float SCROLL_STEP = 34.0f;
    private static final String[] SORT_NAMES = {"Default order", "Name A-Z", "Enabled first"};
    private static final float BTN = 18.0f;
    private static final float TAB_PAD_X = 9.0f;
    private static final float TAB_ICON = 8.0f;
    private static final float TAB_ICON_GAP = 5.0f;
    private static final long REFRESH_MS = 250L;
    /** Задержка между появлением соседних карточек модулей и длительность появления. */
    private static final long CARD_STAGGER_MS = 38L;
    private static final float CARD_IN_MS = 360.0f;
    private static final int STAGGER_CAP = 14;
    private static final int[] WHITE = {255, 255, 255};

    private final ModuleCategory[] categories = ModuleCategory.values();
    private final Map<Module, ModuleOption> cards = new IdentityHashMap<>();
    private final ClickGuiSearchBar search = new ClickGuiSearchBar();
    private final List<ClickGuiPanelSlot> slots = new ArrayList<>();
    private final List<ClickGuiPanelSlot> slotPool = new ArrayList<>();
    private final List<Module> visibleModules = new ArrayList<>();

    private final SmoothAnimation tabX = new SmoothAnimation();
    private final SmoothAnimation tabW = new SmoothAnimation();
    private final SmoothAnimation pageAnimation = new SmoothAnimation();
    private final SmoothAnimation scrollAnimation = new SmoothAnimation();
    private final SmoothAnimation[] tabHover;

    private ModuleCategory selected;
    private int sortMode;
    private float scrollTarget;
    private float scrollLimit;
    private boolean tabInitialized;
    private Module pendingReveal;
    private boolean dirty = true;
    private boolean themesOpen;
    private final ClickGuiThemesPage themesPage = new ClickGuiThemesPage();
    private final int tabCount;
    private long introStart;
    private boolean introPending = true;
    private long lastRefresh;
    private ModuleCategory lastSelected;
    private int lastSort;
    private String lastQuery = "";
    private boolean initializedFilter;
    private int activeCount;
    private boolean tabsMeasured;
    private final String[] tabLabels;
    private final String[] tabIcons;
    private final float[] columnHeights = new float[3];
    private final ClickGuiTheme.Ellipsis subtitleCache = new ClickGuiTheme.Ellipsis();

    // раскладка последнего кадра
    private float wx;
    private float wy;
    private float ww;
    private float wh;
    private float gridX;
    private float gridY;
    private float gridW;
    private float gridH;
    private final float[] tabXs;
    private final float[] tabWs;
    private float tabsX;
    private float tabsY;
    private float tabsW;
    private float expandBtnX;
    private float closeBtnX;
    private float btnY;
    private float sortX;
    private float sortY;
    private float sortW;
    private float searchX;
    private float searchW;

    public ClickGuiPanel() {
        selected = categories.length > 0 ? categories[0] : null;
        tabCount = categories.length + 1; // + вкладка "Themes"
        tabHover = new SmoothAnimation[tabCount];
        for (int i = 0; i < tabCount; i++) {
            tabHover[i] = new SmoothAnimation();
            tabHover[i].set(0.0);
        }
        tabXs = new float[tabCount];
        tabWs = new float[tabCount];
        tabLabels = new String[tabCount];
        tabIcons = new String[tabCount];
        pageAnimation.set(1.0);
        scrollAnimation.set(0.0);
    }

    // ================================================================ render

    public void render(GuiGraphics graphics, int screenWidth, int screenHeight, float open) {
        float ease = open * open * (3.0f - 2.0f * open);
        ww = Math.min(MAX_WIDTH, screenWidth - 40.0f);
        wh = Math.min(MAX_HEIGHT, screenHeight - 40.0f);
        ww = ClickGuiTheme.snap(ww);
        wh = ClickGuiTheme.snap(wh);
        wx = ClickGuiTheme.snap((screenWidth - ww) * 0.5f);
        wy = ClickGuiTheme.snap((screenHeight - wh) * 0.5f + (1.0f - ease) * 14.0f);
        float alpha = ease;
        if (alpha <= 0.01f) {
            return;
        }

        gridX = wx + PAD;
        gridY = wy + TOP_BAR + HEADER + 4.0f;
        gridW = ww - PAD * 2.0f - 5.0f;
        gridH = wh - TOP_BAR - HEADER - 4.0f - PAD;

        pageAnimation.update();
        float page = clamp(pageAnimation.get(), 0.0f, 1.0f);
        float pageAlpha = alpha * page;

        if (themesOpen) {
            renderThemesPage(graphics, alpha, pageAlpha);
            return;
        }

        refreshVisibleModules();
        if (introPending) {
            introPending = false;
            playCardIntro();
        }
        float pageOffset = 0.0f;

        for (Module module : visibleModules) {
            card(module).updateAnimation();
        }
        layoutGrid(pageOffset);
        updateScroll();
        if (pendingReveal != null) {
            revealNow();
        }

        // ---------- проход 1: подложки
        renderFrame(alpha);
        Render2D.pushScissor(graphics, gridX - 4.0f, gridY, gridW + 8.0f, gridH);
        try {
            for (ClickGuiPanelSlot slot : slots) {
                ModuleOption card = slot.card();
                card.beginInteractionFrame();
                if (slot.y() + slot.height() > gridY - 4.0f && slot.y() < gridY + gridH + 4.0f) {
                    card.renderShell(slot.x(), slot.y(), slot.width(), pageAlpha * slot.alpha(), gridY, gridY + gridH);
                }
            }
        } finally {
            Render2D.popScissor(graphics);
        }
        graphics.nextStratum();

        // ---------- проход 2: текст и контролы
        renderTopBar(graphics, alpha);
        renderHeader(graphics, alpha, pageAlpha);
        Render2D.pushScissor(graphics, gridX - 4.0f, gridY, gridW + 8.0f, gridH);
        try {
            for (ClickGuiPanelSlot slot : slots) {
                if (slot.y() + slot.height() > gridY && slot.y() < gridY + gridH) {
                    slot.card().renderContent(graphics, pageAlpha * slot.alpha(), gridY, gridY + gridH);
                }
            }
        } finally {
            Render2D.popScissor(graphics);
        }
        if (slots.isEmpty()) {
            renderEmptyState(pageAlpha);
        }
        renderScrollbar(alpha);
        graphics.nextStratum();

        // ---------- проход 3: всплывающие окна
        for (ClickGuiPanelSlot slot : slots) {
            slot.card().renderOverlay(graphics);
        }
    }

    private void renderThemesPage(GuiGraphics graphics, float alpha, float pageAlpha) {
        renderFrame(alpha);
        Render2D.pushScissor(graphics, gridX - 4.0f, gridY - 2.0f, gridW + 8.0f, gridH + 2.0f);
        try {
            themesPage.renderShapes(gridX, gridY, gridW, gridH, pageAlpha);
        } finally {
            Render2D.popScissor(graphics);
        }
        graphics.nextStratum();
        renderTopBar(graphics, alpha);
        renderHeader(graphics, alpha, pageAlpha);
        Render2D.pushScissor(graphics, gridX - 4.0f, gridY - 2.0f, gridW + 8.0f, gridH + 2.0f);
        try {
            themesPage.renderText(pageAlpha);
        } finally {
            Render2D.popScissor(graphics);
        }
    }

    /** Иконка вкладки "Themes": три точки-палитра. */
    private static void paletteIcon(float cx, float cy, float size, int color) {
        float r = size * 0.2f;
        float o = size * 0.3f;
        ClickGuiTheme.circle(cx, cy - o, r, color);
        ClickGuiTheme.circle(cx - o, cy + o * 0.6f, r, color);
        ClickGuiTheme.circle(cx + o, cy + o * 0.6f, r, color);
    }

    private void openThemes() {
        if (themesOpen) {
            return;
        }
        themesOpen = true;
        search.clear();
        search.unfocus();
        closeAllPopups();
        slots.clear();
        themesPage.open();
        pageAnimation.set(0.0);
        pageAnimation.run(1.0, 0.26, Easings.CUBIC_OUT);
    }

    private void closeThemes() {
        if (!themesOpen) {
            return;
        }
        themesOpen = false;
        restartIntro();
        pageAnimation.set(0.0);
        pageAnimation.run(1.0, 0.26, Easings.CUBIC_OUT);
    }

    /** Перезапустить "прогрузку": карточки модулей появляются по очереди, затем их настройки. */
    public void restartIntro() {
        introStart = System.currentTimeMillis();
        introPending = true;
        dirty = true;
    }

    private void playCardIntro() {
        int i = 0;
        for (Module module : visibleModules) {
            card(module).playIntro(introStart + Math.min(i, STAGGER_CAP) * CARD_STAGGER_MS + 110L);
            i++;
        }
    }

    /** Вызывается при каждом открытии меню. */
    public void onOpen() {
        restartIntro();
        if (themesOpen) {
            themesPage.open();
        }
    }

    private void renderFrame(float alpha) {
        // без свечений и теней на весь экран: одна заливка + обводка 1 px
        ClickGuiTheme.rect(wx, wy, ww, wh, RADIUS, ClickGuiTheme.c(ClickGuiTheme.WINDOW, 250, alpha));
        Render2D.rect(wx, wy, ww, TOP_BAR, RADIUS, RADIUS, 0.0f, 0.0f, ClickGuiTheme.c(ClickGuiTheme.WINDOW_TOP, 250, alpha));
        ClickGuiTheme.border(wx, wy, ww, wh, RADIUS, ClickGuiTheme.c(ClickGuiTheme.BORDER, alpha));
        ClickGuiTheme.divider(wx + 1.0f, wy + TOP_BAR, ww - 2.0f, alpha);
    }

    private void measureTabs() {
        if (tabsMeasured) {
            return;
        }
        for (int i = 0; i < tabCount; i++) {
            boolean themes = i == categories.length;
            tabLabels[i] = themes ? "Themes" : categories[i].getDisplayName();
            tabIcons[i] = themes ? null : ClickGuiTheme.categoryIcon(categories[i]);
            tabWs[i] = ClickGuiTheme.snap(TAB_ICON + TAB_ICON_GAP + Render2D.textWidth(FontType.SEMIBOLD, tabLabels[i], ClickGuiTheme.FS_TAB) + TAB_PAD_X * 2.0f);
        }
        tabsMeasured = true;
    }

    private void renderTopBar(GuiGraphics graphics, float alpha) {
        double mx = ClickGuiController.mouseX();
        double my = ClickGuiController.mouseY();
        float barMid = wy + TOP_BAR * 0.5f;

        // логотип
        float logo = 20.0f;
        float logoX = wx + PAD;
        float logoY = barMid - logo * 0.5f;
        ClickGuiTheme.rect(logoX, logoY, logo, logo, 5.0f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, alpha));
        ClickGuiTheme.textCenter(FontType.BOLD, "T", logoX + logo * 0.5f, barMid, 11.0f, ClickGuiTheme.white(alpha));

        // вкладки категорий
        measureTabs();
        float tabH = 18.0f;
        float tabPad = 2.0f;
        float total = 0.0f;
        for (int i = 0; i < tabCount; i++) {
            total += tabWs[i];
        }
        tabsW = total + tabPad * 2.0f;
        tabsX = ClickGuiTheme.snap(wx + (ww - tabsW) * 0.5f);
        tabsY = barMid - (tabH + tabPad * 2.0f) * 0.5f;
        ClickGuiTheme.rect(tabsX, tabsY, tabsW, tabH + tabPad * 2.0f, 6.0f, ClickGuiTheme.c(ClickGuiTheme.SURFACE, alpha));
        ClickGuiTheme.border(tabsX, tabsY, tabsW, tabH + tabPad * 2.0f, 6.0f, ClickGuiTheme.c(ClickGuiTheme.BORDER, alpha));

        float cx = tabsX + tabPad;
        int selectedIndex = -1;
        for (int i = 0; i < tabCount; i++) {
            tabXs[i] = cx;
            if (themesOpen ? i == categories.length : i < categories.length && categories[i] == selected && !search.isActive()) {
                selectedIndex = i;
            }
            cx += tabWs[i];
        }
        if (selectedIndex >= 0) {
            if (!tabInitialized) {
                tabX.set(tabXs[selectedIndex]);
                tabW.set(tabWs[selectedIndex]);
                tabInitialized = true;
            }
            tabX.run(tabXs[selectedIndex], 0.28, Easings.CUBIC_OUT, true);
            tabW.run(tabWs[selectedIndex], 0.28, Easings.CUBIC_OUT, true);
        }
        tabX.update();
        tabW.update();
        if (selectedIndex >= 0) {
            ClickGuiTheme.rect(tabX.get(), tabsY + tabPad, tabW.get(), tabH, 4.5f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, alpha));
        }
        for (int i = 0; i < tabCount; i++) {
            boolean hov = inside(mx, my, tabXs[i], tabsY, tabWs[i], tabH + tabPad * 2.0f);
            tabHover[i].run(hov ? 1.0 : 0.0, 0.14, Easings.CUBIC_OUT, true);
            tabHover[i].update();
            boolean active = i == selectedIndex;
            float activeP = active ? overlap(tabX.get(), tabW.get(), tabXs[i], tabWs[i]) : 0.0f;
            int color = active
                    ? ClickGuiTheme.mix(ClickGuiTheme.TEXT_DIM, WHITE, activeP, alpha)
                    : ClickGuiTheme.mix(ClickGuiTheme.TEXT_DIM, ClickGuiTheme.TEXT, tabHover[i].get(), alpha);
            float ix = tabXs[i] + TAB_PAD_X;
            if (tabIcons[i] == null) {
                paletteIcon(ix + TAB_ICON * 0.5f, barMid, 7.5f, color);
            } else {
                ClickGuiTheme.textCenter(FontType.MAINMENUSCREEN, tabIcons[i], ix + TAB_ICON * 0.5f, barMid, 7.5f, color);
            }
            ClickGuiTheme.text(FontType.SEMIBOLD, tabLabels[i], ix + TAB_ICON + TAB_ICON_GAP, barMid, ClickGuiTheme.FS_TAB, color);
        }

        // кнопки справа: свернуть/развернуть всё, закрыть
        float btn = BTN;
        btnY = barMid - btn * 0.5f;
        closeBtnX = wx + ww - PAD - btn;
        expandBtnX = closeBtnX - btn - 4.0f;

        boolean anyExpanded = anyExpanded();
        ClickGuiTheme.iconButton(expandBtnX, btnY, btn, FontType.GUI_ICONS, anyExpanded ? "N" : "M", 8.0f, false,
                inside(mx, my, expandBtnX, btnY, btn, btn) ? 1.0f : 0.0f, alpha);
        ClickGuiTheme.iconButton(closeBtnX, btnY, btn, FontType.ICONS, "C", 7.0f, true,
                inside(mx, my, closeBtnX, btnY, btn, btn) ? 1.0f : 0.0f, alpha);
    }

    private void renderHeader(GuiGraphics graphics, float alpha, float pageAlpha) {
        double mx = ClickGuiController.mouseX();
        double my = ClickGuiController.mouseY();
        float hy = wy + TOP_BAR;
        float mid = hy + HEADER * 0.5f;

        String title;
        String subtitle;
        String icon = null;
        boolean paletteGlyph = false;
        if (themesOpen) {
            title = "Themes";
            subtitle = "Pick a look. Click anywhere to make waves.";
            paletteGlyph = true;
        } else if (search.isActive()) {
            title = "Search";
            subtitle = "Results for \"" + search.query().trim() + "\" across all categories.";
        } else if (selected != null) {
            title = selected.getDisplayName();
            subtitle = ClickGuiTheme.categoryDescription(selected);
            icon = ClickGuiTheme.categoryIcon(selected);
        } else {
            title = "Modules";
            subtitle = "";
        }

        // иконка-кружок категории
        float dot = 18.0f;
        float dotX = wx + PAD;
        float dotCx = dotX + dot * 0.5f;
        ClickGuiTheme.rect(dotX, mid - dot * 0.5f, dot, dot, dot * 0.5f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, alpha));
        if (paletteGlyph) {
            paletteIcon(dotCx, mid, 9.0f, ClickGuiTheme.white(alpha));
        } else if (icon != null) {
            ClickGuiTheme.textCenter(FontType.MAINMENUSCREEN, icon, dotCx, mid, 8.5f, ClickGuiTheme.white(alpha));
        } else {
            ClickGuiTheme.textCenter(FontType.ICONS, "U", dotCx, mid, 8.0f, ClickGuiTheme.white(alpha));
        }

        // заголовок + бейджи на одной линии, описание под ними
        float textX = dotX + dot + 8.0f;
        float titleCy = mid - 4.5f;
        float subCy = mid + 6.5f;
        ClickGuiTheme.text(FontType.BOLD, title, textX, titleCy, ClickGuiTheme.FS_TITLE, ClickGuiTheme.c(ClickGuiTheme.TEXT, pageAlpha));
        float bx = ClickGuiTheme.snap(textX + Render2D.textWidth(FontType.BOLD, title, ClickGuiTheme.FS_TITLE) + 7.0f);
        if (themesOpen) {
            bx += ClickGuiTheme.badge(ClickGuiThemes.currentTheme().name(), bx, titleCy, true, pageAlpha) + 3.0f;
            ClickGuiTheme.badge(ClickGuiThemes.THEMES.size() + " Themes", bx, titleCy, false, pageAlpha);
        } else {
            bx += ClickGuiTheme.badge(activeCount + " Active", bx, titleCy, true, pageAlpha) + 3.0f;
            ClickGuiTheme.badge(visibleModules.size() + " Total", bx, titleCy, false, pageAlpha);
        }

        // справа: сортировка и поиск
        float fieldH = 15.0f;
        searchW = ClickGuiTheme.snap(Math.min(130.0f, ww * 0.22f));
        searchX = wx + ww - PAD - searchW;
        sortW = 76.0f;
        sortX = searchX - sortW - 5.0f;
        sortY = mid - fieldH * 0.5f;
        String sub = subtitleCache.get(FontType.SEMIBOLD, subtitle, 6.0f, (themesOpen ? wx + ww - PAD : sortX - 10.0f) - textX);
        ClickGuiTheme.text(FontType.SEMIBOLD, sub, textX, subCy, 6.0f, ClickGuiTheme.c(ClickGuiTheme.TEXT_MUTED, pageAlpha));
        if (themesOpen) {
            return; // на вкладке тем нет сортировки и поиска
        }

        boolean sortHover = inside(mx, my, sortX, sortY, sortW, fieldH);
        ClickGuiTheme.field(sortX, sortY, sortW, fieldH, sortHover ? 1.0f : 0.0f, 0.0f, alpha);
        ClickGuiTheme.text(FontType.SEMIBOLD, SORT_NAMES[sortMode], sortX + 6.0f, mid, 6.0f, ClickGuiTheme.c(ClickGuiTheme.TEXT, alpha));
        ClickGuiTheme.textCenter(FontType.GUI_ICONS, "J", sortX + sortW - 7.0f, mid, 6.6f, ClickGuiTheme.c(ClickGuiTheme.TEXT_MUTED, alpha));

        search.render(graphics, searchX, sortY, searchW, fieldH, alpha);
    }

    private void renderEmptyState(float alpha) {
        float cx = gridX + gridW * 0.5f;
        float cy = gridY + gridH * 0.42f;
        ClickGuiTheme.textCenter(FontType.SEMIBOLD, "No modules found", cx, cy, 8.0f, ClickGuiTheme.c(ClickGuiTheme.TEXT_DIM, alpha));
        ClickGuiTheme.textCenter(FontType.SEMIBOLD, "Try another name or setting.", cx, cy + 12.0f, 6.0f, ClickGuiTheme.c(ClickGuiTheme.TEXT_MUTED, alpha));
    }

    private void renderScrollbar(float alpha) {
        if (scrollLimit <= 0.5f) {
            return;
        }
        float trackX = wx + ww - PAD + 2.0f;
        float total = gridH + scrollLimit;
        float thumbH = Math.max(24.0f, gridH * (gridH / total));
        float thumbY = gridY + (scrollAnimation.get() / scrollLimit) * (gridH - thumbH);
        ClickGuiTheme.rect(trackX, gridY, 2.0f, gridH, 1.0f, ClickGuiTheme.c(ClickGuiTheme.FIELD, alpha));
        ClickGuiTheme.rect(trackX, thumbY, 2.0f, thumbH, 1.0f, ClickGuiTheme.c(ClickGuiTheme.ACCENT, 200, alpha));
    }

    // ================================================================ data / layout

    /** Пересобирать список не каждый кадр, а только при изменениях (и раз в 250 мс — на случай бинда с клавиатуры). */
    public void markDirty() {
        dirty = true;
    }

    private void refreshVisibleModules() {
        long now = System.currentTimeMillis();
        String query = search.isActive() ? search.query() : "";
        boolean keyChanged = selected != lastSelected || sortMode != lastSort || !query.equals(lastQuery);
        if (!dirty && !keyChanged && now - lastRefresh < REFRESH_MS) {
            return;
        }
        dirty = false;
        lastRefresh = now;

        visibleModules.clear();
        ModuleManager manager = Manager.getModules();
        if (manager != null) {
            Collection<Module> source;
            if (search.isActive()) {
                source = manager.getModules();
            } else {
                source = selected == null ? List.of() : manager.getByCategory(selected);
            }
            for (Module module : source) {
                if (search.isActive() && search.score(module) <= 0) {
                    continue;
                }
                visibleModules.add(module);
            }
            if (search.isActive()) {
                visibleModules.sort(Comparator.comparingInt((Module m) -> -search.score(m))
                        .thenComparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
            } else if (sortMode == 1) {
                visibleModules.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
            } else if (sortMode == 2) {
                visibleModules.sort(Comparator.comparing((Module m) -> !m.isEnabled())
                        .thenComparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
            }
        }
        activeCount = 0;
        for (Module module : visibleModules) {
            if (module.isEnabled()) {
                activeCount++;
            }
        }

        if (keyChanged) {
            boolean pageChanged = initializedFilter;
            initializedFilter = true;
            lastSelected = selected;
            lastSort = sortMode;
            lastQuery = query;
            scrollTarget = 0.0f;
            scrollAnimation.run(0.0, 0.22, Easings.CUBIC_OUT, true);
            if (pageChanged) {
                pageAnimation.set(0.0);
                pageAnimation.run(1.0, 0.26, Easings.CUBIC_OUT);
            }
            introStart = now;
            introPending = true;
            closeAllPopups();
        }
    }

    private void layoutGrid(float pageOffset) {
        slots.clear();
        long now = System.currentTimeMillis();
        int columns = gridW >= 520.0f ? 3 : (gridW >= 300.0f ? 2 : 1);
        float colW = (float) Math.floor((gridW - GAP * (columns - 1)) / columns / ClickGuiTheme.px()) * ClickGuiTheme.px();
        float[] colH = columnHeights;
        java.util.Arrays.fill(colH, 0.0f);
        float baseY = ClickGuiTheme.snap(gridY - scrollAnimation.get() + pageOffset);
        int index = 0;
        for (Module module : visibleModules) {
            ModuleOption card = card(module);
            int col = 0;
            for (int c = 1; c < columns; c++) {
                if (colH[c] < colH[col] - 0.5f) {
                    col = c;
                }
            }
            float h = card.height();
            float x = ClickGuiTheme.snap(gridX + col * (colW + GAP));
            float t = (now - introStart - Math.min(index, STAGGER_CAP) * CARD_STAGGER_MS) / CARD_IN_MS;
            float e = ClickGuiTheme.easeOutCubic(t);
            float y = ClickGuiTheme.snap(baseY + colH[col] + (1.0f - e) * 14.0f);
            slots.add(slot(index++).set(card, x, y, colW, h).alpha(ClickGuiTheme.clamp01(t * 1.6f)));
            colH[col] += h + GAP;
        }
        float content = 0.0f;
        for (int c = 0; c < columns; c++) {
            content = Math.max(content, colH[c] - GAP);
        }
        scrollLimit = Math.max(0.0f, content - gridH + 2.0f);
    }

    private void updateScroll() {
        scrollTarget = clamp(scrollTarget, 0.0f, scrollLimit);
        scrollAnimation.run(scrollTarget, 0.22, Easings.CUBIC_OUT, true);
        scrollAnimation.update();
    }

    private ClickGuiPanelSlot slot(int index) {
        while (slotPool.size() <= index) {
            slotPool.add(new ClickGuiPanelSlot());
        }
        return slotPool.get(index);
    }

    private ModuleOption card(Module module) {
        return cards.computeIfAbsent(module, ModuleOptionFactory::create);
    }

    private boolean anyExpanded() {
        for (Module module : visibleModules) {
            ModuleOption card = card(module);
            if (card.hasSettings() && card.isExpanded()) {
                return true;
            }
        }
        return false;
    }

    private void closeAllPopups() {
        for (ModuleOption card : cards.values()) {
            card.closeAll();
        }
    }

    private void revealNow() {
        for (ClickGuiPanelSlot slot : slots) {
            if (slot.card().module() == pendingReveal) {
                slot.card().revealSettings();
                float y = slot.y() + scrollAnimation.get() - gridY;
                scrollTarget = clamp(y - 4.0f, 0.0f, scrollLimit);
                break;
            }
        }
        pendingReveal = null;
    }

    /** Открыть категорию модуля и прокрутить к нему (например, из поиска/команды). */
    public void reveal(Module module) {
        if (module == null) {
            return;
        }
        search.clear();
        closeThemes();
        selected = module.getCategory();
        pendingReveal = module;
    }

    public void warmupText() {
        for (ModuleCategory category : categories) {
            Render2D.warmupText(FontType.SEMIBOLD, category.getDisplayName(), ClickGuiTheme.FS_TAB);
            Render2D.warmupText(FontType.BOLD, category.getDisplayName(), ClickGuiTheme.FS_TITLE);
        }
        ModuleManager manager = Manager.getModules();
        if (manager == null) {
            return;
        }
        for (Module module : manager.getModules()) {
            Render2D.warmupText(FontType.SEMIBOLD, module.getName(), ClickGuiTheme.FS_CARD);
            for (Setting<?> setting : module.getSettings()) {
                Render2D.warmupText(FontType.SEMIBOLD, setting.getName(), ClickGuiTheme.FS_LABEL);
            }
        }
    }

    public boolean isSearchFocused() {
        return search.isFocused();
    }

    // ================================================================ input

    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        dirty = true;
        double mx = event.x();
        double my = event.y();

        // 1. открытые всплывающие окна
        for (int i = slots.size() - 1; i >= 0; i--) {
            if (slots.get(i).card().mouseClickedPopup(event, doubled)) {
                return true;
            }
        }
        // 2. ожидание бинда модуля
        for (ClickGuiPanelSlot slot : slots) {
            if (slot.card().isBinding()) {
                return slot.card().mouseClickedCard(event, doubled);
            }
        }
        // 3. поиск
        if (!themesOpen && search.mouseClicked(event, doubled)) {
            return true;
        }
        // 4. верхняя панель
        if (my >= wy && my <= wy + TOP_BAR && event.button() == 0) {
            for (int i = 0; i < tabCount; i++) {
                if (inside(mx, my, tabXs[i], tabsY, tabWs[i], 24.0f)) {
                    if (i == categories.length) {
                        openThemes();
                    } else {
                        if (selected != categories[i]) {
                            closeAllPopups();
                        }
                        selected = categories[i];
                        search.clear();
                        closeThemes();
                    }
                    return true;
                }
            }
            float btn = BTN;
            if (!themesOpen && inside(mx, my, expandBtnX, btnY, btn, btn)) {
                boolean expand = !anyExpanded();
                for (Module module : visibleModules) {
                    card(module).setExpanded(expand);
                }
                return true;
            }
            if (inside(mx, my, closeBtnX, btnY, btn, btn)) {
                ClickGuiController.startClosing();
                return true;
            }
        }
        // вкладка тем: волны + выбор
        if (themesOpen) {
            return themesPage.mouseClicked(mx, my, event.button()) || inside(mx, my, wx, wy, ww, wh);
        }
        // 5. сортировка
        if (inside(mx, my, sortX, sortY, sortW, 15.0f)) {
            if (event.button() == 0) {
                sortMode = (sortMode + 1) % SORT_NAMES.length;
            } else if (event.button() == 1) {
                sortMode = (sortMode + SORT_NAMES.length - 1) % SORT_NAMES.length;
            }
            return true;
        }
        // 6. сетка
        boolean inGrid = inside(mx, my, gridX - 4.0f, gridY, gridW + 8.0f, gridH);
        for (int i = slots.size() - 1; i >= 0; i--) {
            ModuleOption card = slots.get(i).card();
            if ((inGrid || card.capturesKeyboard()) && card.mouseClickedSettings(event, doubled)) {
                return true;
            }
        }
        if (inGrid) {
            for (int i = slots.size() - 1; i >= 0; i--) {
                if (slots.get(i).card().mouseClickedCard(event, doubled)) {
                    return true;
                }
            }
        }
        return inside(mx, my, wx, wy, ww, wh);
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        dirty = true;
        boolean handled = false;
        for (ModuleOption card : cards.values()) {
            handled |= card.mouseReleased(event);
        }
        return handled;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            if (slots.get(i).card().mouseDragged(event, dragX, dragY)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!inside(mouseX, mouseY, wx, wy, ww, wh)) {
            return false;
        }
        if (themesOpen) {
            themesPage.scroll(mouseX, mouseY, scrollY);
            return true;
        }
        // открытые списки закрываем при прокрутке, чтобы не "отрывались" от поля
        closeAllPopups();
        scrollTarget = clamp(scrollTarget - (float) scrollY * SCROLL_STEP, 0.0f, scrollLimit);
        return true;
    }

    public boolean keyPressed(KeyEvent event) {
        dirty = true;
        if (!themesOpen && search.keyPressed(event)) {
            return true;
        }
        for (ClickGuiPanelSlot slot : slots) {
            if (slot.card().capturesKeyboard() && slot.card().keyPressed(event)) {
                return true;
            }
        }
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && event.key() == GLFW.GLFW_KEY_F) {
            closeThemes();
            search.focus();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && categories.length > 0) {
            int index = 0;
            for (int i = 0; i < categories.length; i++) {
                if (categories[i] == selected) {
                    index = i;
                }
            }
            boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
            closeAllPopups();
            selected = categories[(index + (shift ? categories.length - 1 : 1)) % categories.length];
            search.clear();
            closeThemes();
            return true;
        }
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        dirty = true;
        if (!themesOpen && search.charTyped(event)) {
            return true;
        }
        for (ClickGuiPanelSlot slot : slots) {
            if (slot.card().capturesKeyboard() && slot.card().charTyped(event)) {
                return true;
            }
        }
        return false;
    }

    /** Клавиатуру сейчас занимает поле ввода или бинд — ESC/RShift не должны закрывать меню. */
    public boolean capturesKeyboard() {
        if (!themesOpen && search.isFocused()) {
            return true;
        }
        // только видимые карточки: поле, оставшееся в фокусе на другой вкладке, больше не блокирует ESC
        for (ClickGuiPanelSlot slot : slots) {
            if (slot.card().capturesKeyboard()) {
                return true;
            }
        }
        return false;
    }

    public void onClose() {
        closeAllPopups();
        search.unfocus();
    }

    // ================================================================ utils

    private static float overlap(float ax, float aw, float bx, float bw) {
        float l = Math.max(ax, bx);
        float r = Math.min(ax + aw, bx + bw);
        return clamp((r - l) / Math.max(1.0f, bw), 0.0f, 1.0f);
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
