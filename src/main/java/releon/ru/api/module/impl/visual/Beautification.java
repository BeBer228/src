package releon.ru.api.module.impl.visual;

import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.Module;
import releon.ru.api.settings.impl.BooleanSetting;
import net.minecraft.client.gui.screens.Screen;

import java.util.WeakHashMap;

/**
 * Port of QuickClient's Beautification (donor: pasta, sg.ec.Beautification) — the pieces of its
 * "Украшения" group that map onto Include's hooks: containers scale in over 100 ms with the
 * donor's EASE_OUT_EXPO curve from ~0.01 around the screen center. Tab list and new chat messages
 * have their own opt-in animations (off by default so the current picture does not change).
 */
public class Beautification extends Module {

    public final BooleanSetting inventoryOpen = register(new BooleanSetting(
            "Открытие инвентаря", "Анимация появления инвентаря и контейнеров.", true));
    public final BooleanSetting menus = register(new BooleanSetting(
            "Анимации меню", "Плавное появление игровых меню.", true));
    public final BooleanSetting perspective = register(new BooleanSetting(
            "Смена перспективы", "Плавный переход камеры между первым и третьим лицом.", true));
    public final BooleanSetting slide = register(new BooleanSetting(
            "Сдвиг окон", "Лёгкий подъём интерфейса при открытии.", true));
    public final BooleanSetting menuDim = register(new BooleanSetting(
            "Фон меню", "Тёмный фон за меню клиента (без плавного проявления).", true));

    /** Per-animation-type durations in milliseconds; each slider drives only its own animation. */
    public final releon.ru.api.settings.impl.NumberSetting durationInventory = register(new releon.ru.api.settings.impl.NumberSetting(
            "Длительность: инвентарь", "Скорость анимации открытия инвентаря и контейнеров.", 220, 40, 800, 20));
    public final releon.ru.api.settings.impl.NumberSetting durationMenus = register(new releon.ru.api.settings.impl.NumberSetting(
            "Длительность: меню", "Скорость анимации появления игровых меню.", 220, 40, 800, 20));
    public final releon.ru.api.settings.impl.NumberSetting durationPerspective = register(new releon.ru.api.settings.impl.NumberSetting(
            "Длительность: перспектива", "Скорость перехода камеры между видами.", 220, 40, 800, 20));

    public final BooleanSetting tabAnimation = register(new BooleanSetting(
            "Плавный Tab", "Список игроков раскрывается и сворачивается от верхнего центра.", false));
    public final releon.ru.api.settings.impl.NumberSetting durationTab = register(new releon.ru.api.settings.impl.NumberSetting(
            "Длительность: Tab", "Время раскрытия и сворачивания списка игроков.", 180, 40, 800, 20));
    public final releon.ru.api.settings.impl.NumberSetting strengthTab = register(new releon.ru.api.settings.impl.NumberSetting(
            "Сила: Tab", "Начальное уменьшение списка игроков, %. 100 — из точки.", 100, 5, 100, 5));
    public final BooleanSetting chatMessages = register(new BooleanSetting(
            "Новые сообщения чата", "Новые строки чата плавно проявляются; позиции и клики не смещаются.", false));
    public final releon.ru.api.settings.impl.NumberSetting durationChat = register(new releon.ru.api.settings.impl.NumberSetting(
            "Длительность: чат", "Время проявления нового сообщения.", 250, 40, 1000, 20));
    public final releon.ru.api.settings.impl.NumberSetting strengthChat = register(new releon.ru.api.settings.impl.NumberSetting(
            "Сила: чат", "Начальная прозрачность нового сообщения, %.", 100, 5, 100, 5));

    private static final java.util.Map<Object, Long> chatAdded = new java.util.LinkedHashMap<>();
    private static float tabProgress;
    private static long tabFrame;

    public static Beautification getInstance() { return instance; }

    private static boolean on(java.util.function.Function<Beautification, BooleanSetting> setting) {
        return instance != null && instance.isEnabled() && setting.apply(instance).getValue();
    }

    /** Called by Hud every frame with the Tab key state; keeps the list drawn until it has folded away. */
    public static boolean tabVisible(boolean keyDown) {
        long now = System.nanoTime();
        float dt = tabFrame == 0 ? 0 : Math.min(.1f, (now - tabFrame) / 1e9f);
        tabFrame = now;
        if (!on(b -> b.tabAnimation)) {
            tabProgress = keyDown ? 1 : 0;
            return keyDown;
        }
        float step = dt * 1000f / Math.max(1f, instance.durationTab.getFloat());
        tabProgress = keyDown ? Math.min(1, tabProgress + step) : Math.max(0, tabProgress - step);
        return keyDown || tabProgress > 0;
    }

    /** Uniform scale for the tab list, 1 when idle or disabled. */
    public static float tabScale() {
        if (tabProgress >= 1 || !on(b -> b.tabAnimation)) return 1;
        float eased = 1 - (1 - tabProgress) * (1 - tabProgress) * (1 - tabProgress);
        return 1 - instance.strengthTab.getFloat() / 100f * (1 - eased);
    }

    public static void chatMessageAdded(Object message) {
        if (on(b -> b.chatMessages)) { if(chatAdded.size()>256)chatAdded.clear();chatAdded.put(message, System.nanoTime()); }
    }

    /** Alpha multiplier for a chat message; finished or untracked messages cost one map miss. */
    public static float chatAlpha(Object message) {
        if (chatAdded.isEmpty()) return 1;
        Long added = chatAdded.get(message);
        if (added == null) return 1;
        float progress = (System.nanoTime() - added) / 1e6f / Math.max(1f, instance.durationChat.getFloat());
        if (progress >= 1 || !on(b -> b.chatMessages)) {
            chatAdded.remove(message);
            return 1;
        }
        float eased = 1 - (1 - progress) * (1 - progress);
        return 1 - instance.strengthChat.getFloat() / 100f * (1 - eased);
    }
    public static void opened(Screen screen) { openTimes.remove(screen); }
    private static final long FINISHED = Long.MIN_VALUE;
    public static boolean animate(Screen screen) {
        if (screen instanceof releon.ru.screens.cosmetics.CosmeticsScreen) return false;
        if(instance==null||!instance.isEnabled())return false;
        return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                ? instance.inventoryOpen.getValue()
                : instance.menus.getValue() && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen)
                    && !(screen instanceof releon.ru.screens.clickgui.ClickGui);
    }

    /** True unless the user disabled the dark backdrop behind the client menu via this module. */
    public static boolean menuDimEnabled() {
        Beautification beauty = instance;
        return beauty == null || !beauty.isEnabled() || beauty.menuDim.getValue();
    }

    private static final WeakHashMap<Screen, Long> openTimes = new WeakHashMap<>();
    private static Beautification instance;

    public Beautification() {
        super("Beautification", "Анимации интерфейса: плавное открытие инвентаря, меню и смена перспективы.", ModuleCategory.VISUAL);
        instance = this;
        releon.ru.utils.render.IncludeDefaults.apply("Beautification",getSettings(),"");
    }

    /**
     * Scale factor for a freshly-opened screen; 1.0 when the animation finished or is off.
     * Called from {@code ScreenMixin} around the screen's render-state extraction.
     */
    public static float beginScale(Screen screen) {
        if (screen == null) return 1.0f;
        Beautification beautification = instance;
        if (!animate(screen)) {
            return 1.0f;
        }
        long now = System.currentTimeMillis();
        Long opened = openTimes.get(screen);
        if (opened != null && opened == FINISHED) return 1.0f;
        if (opened == null) {
            opened = now;
            openTimes.put(screen, opened);
        }
        float duration = screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                ? beautification.durationInventory.getFloat()
                : beautification.durationMenus.getFloat();
        float progress = Math.min(1.0f, (now - opened) / Math.max(1.0f, duration));
        if (progress >= 1.0f) {
            openTimes.put(screen, FINISHED);
            return 1.0f;
        }
        // Donor: EASE_OUT_EXPO, min 0.01.
        return .9f + .1f * (1.0f - (float)Math.pow(1.0f-progress,3));
    }
}
