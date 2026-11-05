package releon.ru.api.drag.impl;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import releon.ru.api.drag.core.ElementScreen;
import releon.ru.api.module.ModuleManager;
import releon.ru.api.module.impl.misc.ServerHelper;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.manager.Manager;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Горизонтальный список забинденных предметов: иконка, количество под ней,
 * клавиша справа и тонкий разделитель между строками.
 */
public final class Binds extends HudPanel {
    private static final float ROW_HEIGHT = 20.0F;
    private static final float ROW_GAP = 2.0F;
    private static final float ICON_SIZE = 12.0F;
    private static final float ROW_PAD = 5.0F;
    private static final float KEY_PAD = 5.0F;
    private static final float MIN_WIDTH = 82.0F;
    private static final float COUNT_SIZE = 4.2F;
    private static final float KEY_SIZE = 5.2F;
    private static final String[] PREVIEW_KEYS = {"A", "B", "C"};
    private static final ItemStack[] PREVIEW_STACKS = {
            Items.ENDER_EYE.getDefaultInstance(),
            Items.SUGAR.getDefaultInstance(),
            Items.DRIED_KELP.getDefaultInstance()
    };
    private static final Map<String, String> SEARCH_NAMES = new HashMap<>();
    private static final Map<String, Integer> BIND_COLORS = new HashMap<>();

    static {
        SEARCH_NAMES.put("Зелье отрыжки", "отрыжки");
        SEARCH_NAMES.put("Зелье серной кислоты", "серная");
        SEARCH_NAMES.put("Зелье вспышки", "вспышка");
        SEARCH_NAMES.put("Зелье мочи Флеша", "моча флеша");
        SEARCH_NAMES.put("Зелье победителя", "победителя");
        SEARCH_NAMES.put("Зелье агента", "агента");
        SEARCH_NAMES.put("Зелье медика", "медика");
        SEARCH_NAMES.put("Зелье киллера", "киллера");

        BIND_COLORS.put("Зелье отрыжки", 0xFF9900);
        BIND_COLORS.put("Зелье серной кислоты", 0x00C200);
        BIND_COLORS.put("Зелье вспышки", 0xFFFFFF);
        BIND_COLORS.put("Зелье мочи Флеша", 0x5D00FF);
        BIND_COLORS.put("Зелье победителя", 0x00FF00);
        BIND_COLORS.put("Зелье агента", 0xFFFB00);
        BIND_COLORS.put("Зелье медика", 0xFF00DE);
        BIND_COLORS.put("Зелье киллера", 0xFF0000);
    }

    private final List<BindEntry> bindEntries = new ArrayList<>();
    private final Map<String, SmoothAnimation> rowAnimations = new LinkedHashMap<>();
    private List<BindEntry> lastBinds = List.of();

    public Binds() {
        super("binds", "Binds", 10.0F, 180.0F, MIN_WIDTH, ROW_HEIGHT);
    }

    @Override
    protected void renderHud() {
        BindsState state = logics();
        if (state == null) {
            return;
        }
        renderBinds(state);
    }

    private BindsState logics() {
        List<BindEntry> binds = hudBinds();
        boolean preview = binds.isEmpty() && editPreview();
        boolean visible = !binds.isEmpty() || preview;
        float alpha = contentAlpha(visible);
        if (alpha <= 0.001F) {
            return null;
        }

        List<BindEntry> render = visible ? (preview ? previewEntries() : binds) : lastBinds;
        if (visible) {
            lastBinds = new ArrayList<>(render);
        }
        if (render.isEmpty()) {
            return null;
        }

        updateRowAnimations(render);

        float width = MIN_WIDTH;
        for (BindEntry entry : render) {
            width = Math.max(width, rowWidth(entry));
        }
        float height = render.size() * ROW_HEIGHT + (render.size() - 1) * ROW_GAP;
        size(width, height);

        return new BindsState(render, preview, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void updateRowAnimations(List<BindEntry> entries) {
        Set<String> present = new HashSet<>();
        for (BindEntry entry : entries) {
            present.add(entry.id());
            SmoothAnimation animation = rowAnimations.get(entry.id());
            if (animation == null) {
                animation = new SmoothAnimation();
                animation.set(0.0);
                rowAnimations.put(entry.id(), animation);
            }
            animation.update();
            animation.run(1.0, 0.22, Easings.EXPO_OUT, true);
        }

        for (Map.Entry<String, SmoothAnimation> entry : rowAnimations.entrySet()) {
            if (present.contains(entry.getKey())) {
                continue;
            }
            SmoothAnimation animation = entry.getValue();
            animation.update();
            animation.run(0.0, 0.16, Easings.EXPO_IN, true);
        }
        rowAnimations.entrySet().removeIf(entry ->
                !present.contains(entry.getKey()) && entry.getValue().get() <= 0.01F);
    }

    private float rowWidth(BindEntry entry) {
        float keyWidth = Render2D.textWidth(HudStyle.NUM, entry.key(), KEY_SIZE);
        return ROW_PAD + ICON_SIZE + 8.0F + keyWidth + KEY_PAD + ROW_PAD;
    }

    private void renderBinds(BindsState state) {
        float rowY = state.y;
        for (int index = 0; index < state.binds.size(); index++) {
            BindEntry entry = state.binds.get(index);
            SmoothAnimation animation = rowAnimations.get(entry.id());
            float progress = animation == null ? 1.0F : animation.get();
            float rowAlpha = state.alpha * progress;
            if (rowAlpha > 0.001F) {
                renderRow(state.x + (1.0F - progress) * -7.0F, rowY, state.width, entry, rowAlpha,
                        index < state.binds.size() - 1);
            }
            rowY += ROW_HEIGHT + ROW_GAP;
        }
    }

    private void renderRow(float x, float y, float width, BindEntry entry, float alpha, boolean divider) {
        ItemStack stack = entry.stack();
        boolean hasStack = stack != null && !stack.isEmpty();
        int cooldownSeconds = entry.cooldownTicks() <= 0
                ? 0
                : Math.max(1, (int) Math.ceil(entry.cooldownTicks() / 20.0D));
        boolean cooling = cooldownSeconds > 0;
        boolean empty = entry.inventoryCount() <= 0;

        float iconX = x + ROW_PAD;
        float iconY = y + 2.0F;
        float iconAlpha = cooling ? alpha * 0.45F : empty ? alpha * 0.4F : alpha;
        if (hasStack) {
            RenderItem.item(stack, iconX, iconY, ICON_SIZE, RenderItemOptions.noDecorations(iconAlpha));
        }

        String countText;
        int countColor;
        if (cooling) {
            countText = cooldownSeconds + "с";
            countColor = HudStyle.gold(255.0F, alpha);
        } else {
            int count = Math.max(0, entry.inventoryCount());
            countText = count > 99 ? "99+" : Integer.toString(count);
            countColor = empty ? HudStyle.bad(255.0F, alpha) : HudStyle.white(238.0F, alpha);
        }
        HudStyle.textCentered(HudStyle.NUM, countText, iconX + ICON_SIZE * 0.5F,
                iconY + ICON_SIZE + 1.4F, COUNT_SIZE, countColor);

        String key = trimToWidth(entry.key(), HudStyle.NUM, KEY_SIZE, width - ICON_SIZE - 20.0F);
        float keyWidth = Render2D.textWidth(HudStyle.NUM, key, KEY_SIZE);
        float keyX = x + width - KEY_PAD - keyWidth;
        float keyY = y + (ROW_HEIGHT - KEY_SIZE) * 0.5F - 0.4F;
        Render2D.rect(keyX - 3.0F, keyY - 1.6F, keyWidth + 6.0F, KEY_SIZE + 3.2F, 2.0F,
                HudStyle.white(cooling ? 8.0F : 16.0F, alpha));
        Render2D.text(HudStyle.NUM, key, keyX, keyY, KEY_SIZE,
                cooling ? HudStyle.muted(alpha) : HudStyle.text(alpha));

        if (divider) {
            HudStyle.divider(x + ROW_PAD + 1.0F, y + ROW_HEIGHT + ROW_GAP * 0.5F,
                    width - (ROW_PAD + 1.0F) * 2.0F, alpha);
        }
    }

    private List<BindEntry> previewEntries() {
        List<BindEntry> entries = new ArrayList<>(PREVIEW_STACKS.length);
        for (int i = 0; i < PREVIEW_STACKS.length; i++) {
            entries.add(new BindEntry("preview_" + i, PREVIEW_STACKS[i], PREVIEW_KEYS[i], 64, 0));
        }
        return entries;
    }

    private List<BindEntry> hudBinds() {
        ModuleManager manager = Manager.getModules();
        if (manager == null) {
            bindEntries.clear();
            return List.of();
        }
        return manager.getByType(ServerHelper.class)
                .map(this::readHudBinds)
                .orElse(List.of());
    }

    private List<BindEntry> readHudBinds(ServerHelper helper) {
        List<ServerHelper.HudBind> hudBinds = helper.getHudBinds(0.0F);
        bindEntries.clear();
        if (hudBinds == null || hudBinds.isEmpty()) {
            return bindEntries;
        }

        for (ServerHelper.HudBind hudBind : hudBinds) {
            if (hudBind == null) {
                continue;
            }
            ItemStack stack = hudBind.stack() == null ? ItemStack.EMPTY : hudBind.stack().copy();
            KeyBind bind = hudBind.setting() == null ? KeyBind.NONE : hudBind.setting().getValue();

            String bindName = hudBind.displayName();
            if (bindName != null && BIND_COLORS.containsKey(bindName) && stack.getItem() == Items.SPLASH_POTION) {
                int color = BIND_COLORS.get(bindName);
                stack = createColoredPotion(color);
            }

            String key = bind == null || !bind.isBound() ? "None" : shortBind(bind);
            bindEntries.add(new BindEntry(hudBind.key(), stack, key, hudBind.inventoryCount(), hudBind.cooldownTicks()));
        }
        return bindEntries;
    }

    private ItemStack createColoredPotion(int color) {
        ItemStack stack = Items.SPLASH_POTION.getDefaultInstance();
        try {
            PotionContents contents = new PotionContents(
                    Potions.WATER
            );
            stack.set(DataComponents.POTION_CONTENTS, contents);
        } catch (Exception ignored) {
        }
        return stack;
    }

    private record BindEntry(String id, ItemStack stack, String key, int inventoryCount, int cooldownTicks) {
    }

    private record BindsState(List<BindEntry> binds, boolean preview, float alpha,
                              float x, float y, float width, float height) {
    }
}
