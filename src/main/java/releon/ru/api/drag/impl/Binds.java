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
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class Binds extends HudPanel {
    private static final int PER_ROW = 5;
    private static final float CARD_WIDTH = 21.0F;
    private static final float CARD_HEIGHT = 21.0F;
    private static final float ROW_STEP = 24.0F;
    private static final float CARD_GAP = 3.0F;
    private static final float ITEM_SIZE = 10.0F;
    private static final ItemStack[] PREVIEW_STACKS = {
            Items.ENDER_EYE.getDefaultInstance(),
            Items.SUGAR.getDefaultInstance(),
            Items.DRIED_KELP.getDefaultInstance()
    };
    private static final Map<String, String> SEARCH_NAMES = new HashMap<>();
    private static final Map<String, Integer> BIND_COLORS = new HashMap<>();
    private static final Random RANDOM = new Random();

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

    private final SmoothAnimation panelAnimation = new SmoothAnimation();
    private final List<BindEntry> bindEntries = new ArrayList<>();
    private final List<BindEntry> lastBinds = new ArrayList<>();
    private boolean lastPreview;
    private int lastCount;
    private int lastRows = 1;
    private float lastWidth = 22.0F;
    private float lastHeight = ROW_STEP;
    private long lastBindChange = 0L;
    private String currentRandomKey1 = "None";
    private String currentRandomKey2 = "None";
    private String currentRandomKey3 = "None";
    private int currentItemIndex1 = 0;
    private int currentItemIndex2 = 1;
    private int currentItemIndex3 = 2;

    public Binds() {
        super("binds", "Binds", 10.0F, 180.0F, 48.0F, 18.0F);
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
        boolean targetVisible = !binds.isEmpty() || preview;

        updatePreviewAnimation(binds, preview);

        if (targetVisible) {
            rememberLayout(binds, preview);
        }

        panelAnimation.update();
        panelAnimation.run(targetVisible ? 1.0 : 0.0, 0.24F, targetVisible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);
        float alpha = panelAnimation.get();
        boolean fading = !targetVisible && lastCount > 0 && (alpha > 0.01F || panelAnimation.isAlive());
        boolean renderVisible = targetVisible || fading;
        contentVisible(renderVisible);
        if (!renderVisible) {
            return null;
        }

        List<BindEntry> renderBinds = targetVisible ? binds : lastBinds;
        boolean renderPreview = targetVisible ? preview : lastPreview;
        int count = targetVisible ? (preview ? PREVIEW_STACKS.length : binds.size()) : lastCount;
        int rows = targetVisible ? Math.max(1, (int) Math.ceil(count / (double) PER_ROW)) : lastRows;
        float width = targetVisible ? layoutWidth(binds, preview, count, rows) : lastWidth;
        float height = targetVisible ? rows * ROW_STEP : lastHeight;
        sizeInstant(width, height);

        return new BindsState(renderBinds, renderPreview, count, rows, alpha, drag.x(), drag.y(), drag.width());
    }

    private void updatePreviewAnimation(List<BindEntry> binds, boolean preview) {
        long currentTime = System.currentTimeMillis();
        if (binds.isEmpty() && preview && currentTime - lastBindChange >= 1000L) {
            String[] availableKeys = {"A", "B", "C", "D", "E"};
            currentRandomKey1 = availableKeys[RANDOM.nextInt(availableKeys.length)];
            currentRandomKey2 = availableKeys[RANDOM.nextInt(availableKeys.length)];
            currentRandomKey3 = availableKeys[RANDOM.nextInt(availableKeys.length)];
            currentItemIndex1 = (currentItemIndex1 + 1) % PREVIEW_STACKS.length;
            currentItemIndex2 = (currentItemIndex2 + 1) % PREVIEW_STACKS.length;
            currentItemIndex3 = (currentItemIndex3 + 1) % PREVIEW_STACKS.length;
            lastBindChange = currentTime;
        }
    }

    private void rememberLayout(List<BindEntry> binds, boolean preview) {
        int count = preview ? PREVIEW_STACKS.length : binds.size();
        int rows = Math.max(1, (int) Math.ceil(count / (double) PER_ROW));
        float width = layoutWidth(binds, preview, count, rows);

        lastBinds.clear();
        if (!preview) {
            lastBinds.addAll(binds);
        }
        lastPreview = preview;
        lastCount = count;
        lastRows = rows;
        lastWidth = width;
        lastHeight = rows * ROW_STEP;
    }

    private float layoutWidth(List<BindEntry> binds, boolean preview, int count, int rows) {
        float width = 0.0F;
        for (int row = 0; row < rows; row++) {
            int start = row * PER_ROW;
            int end = Math.min(count, start + PER_ROW);
            float rowWidth = 0.0F;
            for (int i = start; i < end; i++) {
                rowWidth += cardWidth();
                if (i + 1 < end) {
                    rowWidth += CARD_GAP;
                }
            }
            width = Math.max(width, rowWidth);
        }
        return Math.max(22.0F, width + 6.0F);
    }

    private void sizeInstant(float width, float height) {
        drag.size((float) Math.ceil(width), (float) Math.ceil(height));
        drag.clamp(ElementScreen.current());
    }

    private void renderBinds(BindsState state) {
        int index = 0;
        for (int row = 0; row < state.rows; row++) {
            int start = row * PER_ROW;
            int end = Math.min(state.count, start + PER_ROW);
            float rowWidth = 0.0F;
            for (int i = start; i < end; i++) {
                rowWidth += cardWidth();
                if (i + 1 < end) {
                    rowWidth += CARD_GAP;
                }
            }

            float cardX = state.x + (state.width - rowWidth) * 0.5F;
            float cardY = state.y + row * ROW_STEP + 1.5F;
            for (int i = start; i < end && index < state.count; i++) {
                ItemStack stack;
                String key;
                int itemCount;
                float cooldownProgress;

                if (state.preview) {
                    if (index == 0) {
                        stack = PREVIEW_STACKS[currentItemIndex1];
                        key = currentRandomKey1;
                    } else if (index == 1) {
                        stack = PREVIEW_STACKS[currentItemIndex2];
                        key = currentRandomKey2;
                    } else {
                        stack = PREVIEW_STACKS[currentItemIndex3];
                        key = currentRandomKey3;
                    }
                    itemCount = 64;
                    cooldownProgress = 0.0F;
                } else {
                    BindEntry bind = state.binds.get(index);
                    stack = bind.stack();
                    key = shortBind(bind.bind());
                    itemCount = bind.inventoryCount();
                    cooldownProgress = bind.cooldownProgress();
                }

                float cw = cardWidth();
                boolean hasStack = stack != null && !stack.isEmpty();
                String countText = itemCount > 99 ? "99+" : Integer.toString(Math.max(0, itemCount));
                float progress = clamp(cooldownProgress, 0.0F, 1.0F);
                float alpha = state.alpha;
                boolean empty = !state.preview && itemCount <= 0;

                HudStyle.glass(cardX, cardY, cw, CARD_HEIGHT, 4.0F, alpha);
                if (progress <= 0.0F && !empty) {
                    Render2D.gradientRect(cardX, cardY, cw, CARD_HEIGHT, 4.0F, 4.0F, 4.0F, 4.0F,
                            HudStyle.accent(30.0F, alpha), HudStyle.accent2(16.0F, alpha),
                            HudStyle.accent2(16.0F, alpha), HudStyle.accent(30.0F, alpha));
                }

                float itemX = cardX + (cw - ITEM_SIZE) * 0.5F;
                float itemY = cardY + 2.5F;
                if (hasStack) {
                    RenderItem.item(stack, itemX, itemY, ITEM_SIZE, RenderItemOptions.noDecorations(empty ? alpha * 0.4F : alpha));
                }

                // кулдаун: затемнение сверху вниз + акцентная кромка
                if (progress > 0.0F) {
                    float overlayHeight = CARD_HEIGHT * progress;
                    float oy = cardY + CARD_HEIGHT - overlayHeight;
                    Render2D.rect(cardX, oy, cw, overlayHeight, 4.0F, HudStyle.glassColor(165.0F, alpha));
                    Render2D.rect(cardX + 2.0F, oy, cw - 4.0F, 0.7F, 0.35F, HudStyle.accent(230.0F, alpha));
                }

                // счётчик (правый верхний угол)
                int countColor = empty ? HudStyle.bad(255.0F, alpha) : HudStyle.white(235.0F, alpha);
                HudStyle.textRight(HudStyle.NUM, countText, cardX + cw - 1.8F, cardY + 1.6F, 4.2F, countColor);

                // клавиша (снизу по центру)
                String visibleKey = trimToWidth(key, HudStyle.NUM, 4.5F, cw - 4.0F);
                float keyW = Render2D.textWidth(HudStyle.NUM, visibleKey, 4.5F) + 4.0F;
                float keyX = cardX + (cw - keyW) * 0.5F;
                float keyY = cardY + CARD_HEIGHT - 7.0F;
                Render2D.rect(keyX, keyY, keyW, 6.0F, 2.0F, HudStyle.white(22.0F, alpha));
                Render2D.text(HudStyle.NUM, visibleKey, keyX + 2.0F, keyY + 0.8F, 4.5F, HudStyle.text(alpha));

                cardX += cw + CARD_GAP;
                index++;
            }
        }
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

            bindEntries.add(new BindEntry(stack, bind == null ? KeyBind.NONE : bind, hudBind.inventoryCount(), hudBind.cooldownProgress()));
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

    private float cardWidth() {
        return CARD_WIDTH;
    }

    private record BindEntry(ItemStack stack, KeyBind bind, int inventoryCount, float cooldownProgress) {
    }

    private record BindsState(List<BindEntry> binds, boolean preview, int count, int rows, float alpha, float x, float y, float width) {
    }
}
