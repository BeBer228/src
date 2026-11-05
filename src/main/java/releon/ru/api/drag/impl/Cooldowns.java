package releon.ru.api.drag.impl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Cooldowns extends HudPanel {
    private static final float HEADER_HEIGHT = 18.0F;
    private static final float ROW_HEIGHT = 16.0F;
    private static final float ROW_GAP = 2.0F;
    private static final float PANEL_ANIMATION = 0.24F;
    private static final float ROW_ANIMATION = 0.22F;

    private static final ItemStack[] PREVIEW_STACKS = {
            Items.SUGAR.getDefaultInstance(),
            Items.MACE.getDefaultInstance(),
            Items.GOLDEN_APPLE.getDefaultInstance()
    };

    private static final Comparator<RowState> ROW_COMPARATOR =
            Comparator.comparingDouble(RowState::offset);

    private final Map<String, CooldownInfo> cooldownCache =
            new LinkedHashMap<>();

    private final List<CooldownInfo> activeCooldowns =
            new ArrayList<>();

    private final List<RowEntry> entries =
            new ArrayList<>();

    private final List<RowState> rows =
            new ArrayList<>();

    private final SmoothAnimation panelAnimation =
            new SmoothAnimation();

    private final SmoothAnimation iconAnimation =
            new SmoothAnimation();

    private boolean iconForward = true;

    public Cooldowns() {
        super(
                "cooldowns",
                "Cooldowns",
                10.0F,
                40.0F,
                112.0F,
                28.0F
        );
    }

    @Override
    protected void renderHud() {
        CooldownsState state = updateState();

        if (state == null) {
            return;
        }

        renderCooldowns(state);
    }

    private CooldownsState updateState() {
        List<CooldownInfo> active = collectActive();

        boolean preview = active.isEmpty() && editPreview();
        boolean shouldShow = !active.isEmpty() || preview;

        drag.hitExpansion(
                0.0F,
                HEADER_HEIGHT,
                0.0F,
                0.0F
        );

        panelAnimation.update();
        panelAnimation.run(
                shouldShow ? 1.0 : 0.0,
                PANEL_ANIMATION,
                shouldShow
                        ? Easings.EXPO_OUT
                        : Easings.EXPO_IN,
                true
        );

        iconAnimation.update();
        if (!iconAnimation.isAlive()) {
            iconAnimation.run(
                    iconForward ? 1.0 : 0.0,
                    1.55,
                    Easings.EXPO_IN_OUT
            );
            iconForward = !iconForward;
        }

        for (RowEntry entry : entries) {
            entry.active = false;
            entry.alpha.update();
            entry.y.update();
        }

        int targetRows = 0;

        if (preview) {
            ItemStack stack = PREVIEW_STACKS[
                    (int) ((System.currentTimeMillis() / 1800L)
                            % PREVIEW_STACKS.length)
                    ];

            RowEntry entry = row(
                    "__preview",
                    stack,
                    displayName(stack),
                    "**:**",
                    6.0F,
                    0.0F
            );

            activate(entry, 0.0F);
            targetRows = 1;
        } else {
            for (CooldownInfo info : active) {
                float targetY =
                        targetRows * (ROW_HEIGHT + ROW_GAP);

                RowEntry entry = row(
                        info.key,
                        info.stack,
                        info.name,
                        info.timeText(),
                        6.0F,
                        targetY
                );

                entry.progress = info.progress;
                activate(entry, targetY);
                targetRows++;
            }
        }

        for (RowEntry entry : entries) {
            if (!entry.active) {
                entry.alpha.run(
                        0.0,
                        ROW_ANIMATION,
                        Easings.EXPO_IN,
                        true
                );
            }
        }

        entries.removeIf(entry ->
                !entry.active
                        && entry.alpha.get() <= 0.01F
                        && !entry.alpha.isAlive()
        );

        float alpha = panelAnimation.get();

        boolean visible =
                shouldShow
                        || alpha > 0.01F
                        || !entries.isEmpty();

        contentVisible(visible);

        if (!visible) {
            return null;
        }

        float width = 112.0F;

        for (RowEntry entry : entries) {
            if (entry.alpha.get() <= 0.01F && !entry.active) {
                continue;
            }

            float nameWidth = Render2D.textWidth(
                    TEXT_FONT,
                    entry.name,
                    6.0F
            );

            width = Math.max(
                    width,
                    nameWidth + 76.0F
            );
        }

        float height =
                Math.max(
                        ROW_HEIGHT,
                        targetRows * (ROW_HEIGHT + ROW_GAP)
                ) + 6.0F;

        size(width, height);

        drag.size(
                drag.width(),
                (float) Math.ceil(height)
        );

        rows.clear();

        for (RowEntry entry : entries) {
            float rowAlpha = entry.alpha.get();

            if (rowAlpha > 0.01F || entry.active) {
                rows.add(
                        new RowState(
                                entry.stack,
                                entry.name,
                                entry.time,
                                entry.timeSize,
                                entry.progress,
                                entry.y.get(),
                                rowAlpha
                        )
                );
            }
        }

        rows.sort(ROW_COMPARATOR);

        return new CooldownsState(
                List.copyOf(rows),
                alpha,
                drag.x(),
                drag.y(),
                drag.width(),
                drag.height(),
                active.size()
        );
    }

    private void renderCooldowns(CooldownsState state) {
        if (state == null || state.alpha <= 0.015F) {
            return;
        }

        float alpha = state.alpha;
        float headerY = state.y - HEADER_HEIGHT;

        HudStyle.glass(state.x, headerY, state.width, state.height + HEADER_HEIGHT, alpha);
        HudStyle.header(state.x, headerY, state.width, HEADER_HEIGHT, "l", 7.5F, "Cooldowns",
                state.count > 0 ? Integer.toString(state.count) : null, alpha);

        renderRows(state);
    }

    private void renderRows(CooldownsState state) {
        float rowX = state.x + 3.0F;
        float rowWidth = state.width - 6.0F;
        float rowY = state.y + 3.0F;

        for (RowState row : state.rows) {
            float alpha = state.alpha * row.alpha;
            if (alpha <= 0.01F) {
                continue;
            }
            float y = rowY + row.offset;
            float progress = HudStyle.clamp01(row.progress);
            boolean almostReady = progress > 0.0F && progress < 0.15F;
            int tint = almostReady ? HudStyle.good(255.0F, 1.0F) : HudStyle.accent(255.0F, 1.0F);

            HudStyle.surface(rowX, y, rowWidth, ROW_HEIGHT, HudStyle.RADIUS_SM, alpha);

            float itemBox = ROW_HEIGHT - 4.0F;
            float itemX = rowX + 2.0F;
            float itemY = y + 2.0F;
            HudStyle.tintChip(itemX, itemY, itemBox, tint, alpha);
            RenderItem.item(row.stack, itemX + 1.0F, itemY + 1.0F, itemBox - 2.0F, RenderItemOptions.noDecorations(alpha));

            float cell = 3.4F;
            float pillW = HudStyle.stableWidth(row.time, cell) + 6.0F;
            float pillH = ROW_HEIGHT - 6.0F;
            float pillX = rowX + rowWidth - pillW - 3.0F;
            float pillY = y + 3.0F;
            Render2D.rect(pillX, pillY, pillW, pillH, pillH * 0.5F, HudStyle.withAlpha(tint, 34.0F, alpha));
            HudStyle.stableText(row.time, pillX + 3.0F, pillY + (pillH - row.timeSize) * 0.5F, row.timeSize, cell,
                    HudStyle.withAlpha(tint, 255.0F, alpha));

            float nameX = itemX + itemBox + 5.0F;
            String name = trimToWidth(row.name, TEXT_FONT, HudStyle.BODY_SIZE, pillX - nameX - 4.0F);
            Render2D.text(TEXT_FONT, name, nameX, y + (ROW_HEIGHT - HudStyle.BODY_SIZE) * 0.5F - 1.0F,
                    HudStyle.BODY_SIZE, HudStyle.text(alpha));

            float barX = nameX;
            float barW = pillX - nameX - 5.0F;
            if (barW > 4.0F) {
                HudStyle.progress(barX, y + ROW_HEIGHT - 3.5F, barW, 1.2F, 1.0F - progress,
                        HudStyle.withAlpha(tint, 150.0F, alpha), HudStyle.withAlpha(tint, 255.0F, alpha), alpha);
            }
        }
    }

    private RowEntry row(
            String key,
            ItemStack stack,
            String name,
            String time,
            float timeSize,
            float targetY
    ) {
        for (RowEntry entry : entries) {
            if (!entry.key.equals(key)) {
                continue;
            }

            entry.stack = stack == null
                    ? ItemStack.EMPTY
                    : stack.copy();

            entry.name = name;
            entry.time = time;
            entry.timeSize = timeSize;

            return entry;
        }

        RowEntry entry = new RowEntry(
                key,
                stack,
                name,
                time,
                timeSize
        );

        entry.alpha.set(0.0);
        entry.y.set(targetY + 4.0F);
        entries.add(entry);

        return entry;
    }

    private void activate(
            RowEntry entry,
            float targetY
    ) {
        entry.active = true;

        entry.alpha.run(
                1.0,
                ROW_ANIMATION,
                Easings.EXPO_OUT,
                true
        );

        entry.y.run(
                targetY,
                ROW_ANIMATION,
                Easings.EXPO_OUT,
                true
        );
    }

    private List<CooldownInfo> collectActive() {
        activeCooldowns.clear();

        if (mc.player == null) {
            cooldownCache.clear();
            return activeCooldowns;
        }

        for (int slot = 0;
             slot < mc.player.getInventory().getContainerSize();
             slot++) {

            ItemStack stack =
                    mc.player.getInventory().getItem(slot);

            if (stack.isEmpty()) {
                continue;
            }

            if (!mc.player.getCooldowns().isOnCooldown(stack)) {
                continue;
            }

            String key = itemKey(stack);

            float progress =
                    mc.player.getCooldowns()
                            .getCooldownPercent(
                                    stack,
                                    0.0F
                            );

            CooldownInfo info =
                    cooldownCache.computeIfAbsent(
                            key,
                            ignored -> new CooldownInfo()
                    );

            info.key = key;
            info.stack = stack.copy();
            info.name = displayName(stack);
            info.update(
                    progress,
                    mc.player.tickCount
            );

            if (activeCooldowns.stream()
                    .noneMatch(existing ->
                            existing.key.equals(key))) {
                activeCooldowns.add(info);
            }
        }

        cooldownCache.keySet().removeIf(key ->
                activeCooldowns.stream()
                        .noneMatch(info ->
                                info.key.equals(key))
        );

        return activeCooldowns;
    }

    private String itemKey(ItemStack stack) {
        return BuiltInRegistries.ITEM
                .getKey(stack.getItem())
                .toString()
                + ":"
                + stack.getHoverName().getString();
    }

    private String displayName(ItemStack stack) {
        String id = BuiltInRegistries.ITEM
                .getKey(stack.getItem())
                .toString();

        return switch (id) {
            case "minecraft:ender_eye" ->
                    "Дезка";
            case "minecraft:sugar" ->
                    "Явная пыль";
            case "minecraft:netherite_scrap" ->
                    "Трапка";
            case "minecraft:dried_kelp" ->
                    "Пласт";
            case "minecraft:trident" ->
                    "Трезубец";
            case "minecraft:mace" ->
                    "Булава";
            case "minecraft:wind_charge" ->
                    "Заряд ветра";
            case "minecraft:enchanted_golden_apple" ->
                    "Чарка";
            case "minecraft:golden_apple" ->
                    "Золотое яблоко";
            case "minecraft:firework_rocket" ->
                    "Фейерверк";
            case "minecraft:lava_bucket" ->
                    "Ведро лавы";
            case "minecraft:water_bucket" ->
                    "Ведро воды";
            case "minecraft:powder_snow_bucket" ->
                    "Ведро снега";
            case "minecraft:fire_charge" ->
                    "Огненный смерч";
            default ->
                    stack.getHoverName().getString();
        };
    }

    private static int color(
            int r,
            int g,
            int b,
            float alpha,
            float multiplier
    ) {
        return ColorUtil.rgba(
                r,
                g,
                b,
                Math.max(
                        0,
                        Math.min(
                                255,
                                Math.round(alpha * multiplier)
                        )
                )
        );
    }

    private static String formatTicks(int ticks) {
        if (ticks < 0) {
            return "**:**";
        }

        int seconds = Math.max(
                0,
                ticks / 20
        );

        int minutes = Math.min(
                99,
                seconds / 60
        );

        seconds %= 60;

        return twoDigits(minutes)
                + ":"
                + twoDigits(seconds);
    }

    public static String twoDigits(int value) {
        return value < 10
                ? "0" + value
                : Integer.toString(value);
    }

    private static final class RowEntry {
        private final String key;

        private final SmoothAnimation alpha =
                new SmoothAnimation();

        private final SmoothAnimation y =
                new SmoothAnimation();

        private ItemStack stack;
        private String name;
        private String time;
        private float timeSize;
        private float progress;
        private boolean active;

        private RowEntry(
                String key,
                ItemStack stack,
                String name,
                String time,
                float timeSize
        ) {
            this.key = key;
            this.stack = stack == null
                    ? ItemStack.EMPTY
                    : stack.copy();
            this.name = name;
            this.time = time;
            this.timeSize = timeSize;
        }
    }

    private static final class CooldownInfo {
        private String key;
        private ItemStack stack = ItemStack.EMPTY;
        private String name = "";

        private float progress;
        private float previousProgress = -1.0F;
        private long previousTick = -1L;
        private int totalTicks = -1;
        private int remainingTicks;

        private void update(
                float nextProgress,
                long tick
        ) {
            progress = Math.max(
                    0.0F,
                    Math.min(
                            1.0F,
                            nextProgress
                    )
            );

            if (previousTick >= 0L
                    && tick > previousTick
                    && previousProgress >= progress) {

                float difference =
                        previousProgress - progress;

                if (difference > 0.00001F) {
                    int estimate = Math.round(
                            (tick - previousTick)
                                    / difference
                    );

                    if (estimate > 0 && estimate < 12000) {
                        totalTicks = totalTicks <= 0
                                ? estimate
                                : Math.round(
                                totalTicks * 0.75F
                                        + estimate * 0.25F
                        );
                    }
                }
            }

            previousProgress = progress;
            previousTick = tick;

            remainingTicks = totalTicks <= 0
                    ? Math.max(
                    1,
                    Math.round(progress * 20.0F)
            )
                    : Math.max(
                    1,
                    Math.round(progress * totalTicks)
            );
        }

        private String timeText() {
            return formatTicks(remainingTicks);
        }
    }

    private record RowState(
            ItemStack stack,
            String name,
            String time,
            float timeSize,
            float progress,
            float offset,
            float alpha
    ) {
    }

    private record CooldownsState(
            List<RowState> rows,
            float alpha,
            float x,
            float y,
            float width,
            float height,
            int count
    ) {
    }
}