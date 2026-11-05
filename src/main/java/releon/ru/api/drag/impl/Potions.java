package releon.ru.api.drag.impl;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class Potions extends HudPanel {
    private static final float HEADER_HEIGHT = 18.0F;
    private static final float ROW_HEIGHT = 15.0F;
    private static final float ROW_GAP = 2.0F;
    private static final float SECTION_HEIGHT = 10.0F;
    private static final float GROUP_GAP = 4.0F;

    private static final float PANEL_ANIMATION = 0.24F;
    private static final float ROW_ANIMATION = 0.22F;
    private static final int POSITIVE_R = HudStyle.GOOD_R;
    private static final int POSITIVE_G = HudStyle.GOOD_G;
    private static final int POSITIVE_B = HudStyle.GOOD_B;

    private static final int NEGATIVE_R = HudStyle.BAD_R;
    private static final int NEGATIVE_G = HudStyle.BAD_G;
    private static final int NEGATIVE_B = HudStyle.BAD_B;

    private static final List<Holder<MobEffect>> PREVIEW_POSITIVE =
            List.of(MobEffects.SPEED);

    private static final List<Holder<MobEffect>> PREVIEW_NEGATIVE =
            List.of(MobEffects.POISON);

    private static final Comparator<MobEffectInstance> EFFECT_COMPARATOR =
            Comparator.comparing(effect ->
                    effect.getEffect().value().getDisplayName().getString());

    private static final Comparator<RowState> ROW_COMPARATOR =
            Comparator.comparingDouble(RowState::offset);

    private final List<MobEffectInstance> effects = new ArrayList<>();
    private final List<MobEffectInstance> positiveEffects = new ArrayList<>();
    private final List<MobEffectInstance> negativeEffects = new ArrayList<>();
    private final List<RowEntry> entries = new ArrayList<>();
    private final List<RowState> rows = new ArrayList<>();

    private final SmoothAnimation panelAnimation = new SmoothAnimation();

    private long previewSecond = -1L;

    private Holder<MobEffect> previewPositive = MobEffects.SPEED;
    private Holder<MobEffect> previewNegative = MobEffects.POISON;

    public Potions() {
        super(
                "potions",
                "Potions",
                300.0F,
                100.0F,
                100.0F,
                25.0F
        );
    }

    @Override
    protected void renderHud() {
        PotionsState state = updateState();

        if (state == null) {
            return;
        }

        renderPotions(state);
    }

    private PotionsState updateState() {
        collectEffects();

        boolean preview = effects.isEmpty() && editPreview();
        boolean shouldShow = !effects.isEmpty() || preview;

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
                shouldShow ? Easings.EXPO_OUT : Easings.EXPO_IN,
                true
        );

        for (RowEntry entry : entries) {
            entry.active = false;
            entry.alpha.update();
            entry.y.update();
        }

        float targetY = 0.0F;

        if (preview) {
            updatePreview();

            targetY = activate(
                    section(
                            "__positive_section",
                            "Buffs",
                            targetY,
                            false
                    ),
                    targetY,
                    SECTION_HEIGHT
            );

            targetY = activate(
                    row(
                            "__positive_preview",
                            "Example",
                            "",
                            "**:**",
                            6.0F,
                            targetY,
                            previewPositive,
                            -1,
                            false,
                            false
                    ),
                    targetY,
                    ROW_HEIGHT + GROUP_GAP
            );

            targetY = activate(
                    section(
                            "__negative_section",
                            "Debuffs",
                            targetY,
                            true
                    ),
                    targetY,
                    SECTION_HEIGHT
            );

            targetY = activate(
                    row(
                            "__negative_preview",
                            "Example",
                            "",
                            "**:**",
                            6.0F,
                            targetY,
                            previewNegative,
                            -1,
                            false,
                            true
                    ),
                    targetY,
                    ROW_HEIGHT + ROW_GAP
            );
        } else {
            if (!positiveEffects.isEmpty()) {
                targetY = activate(
                        section(
                                "__positive_section",
                                "Buffs",
                                targetY,
                                false
                        ),
                        targetY,
                        SECTION_HEIGHT
                );
            }

            for (MobEffectInstance effect : positiveEffects) {
                targetY = activate(
                        effectRow(effect, false, targetY),
                        targetY,
                        ROW_HEIGHT + ROW_GAP
                );
            }

            if (!positiveEffects.isEmpty() && !negativeEffects.isEmpty()) {
                targetY += GROUP_GAP;
            }

            if (!negativeEffects.isEmpty()) {
                targetY = activate(
                        section(
                                "__negative_section",
                                "Debuffs",
                                targetY,
                                true
                        ),
                        targetY,
                        SECTION_HEIGHT
                );
            }

            for (MobEffectInstance effect : negativeEffects) {
                targetY = activate(
                        effectRow(effect, true, targetY),
                        targetY,
                        ROW_HEIGHT + ROW_GAP
                );
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

        float panelAlpha = panelAnimation.get();

        boolean visible =
                shouldShow
                        || panelAlpha > 0.01F
                        || !entries.isEmpty();

        contentVisible(visible);

        if (!visible) {
            return null;
        }

        float width = 100.0F;
        float height = Math.max(25.0F, targetY + 7.0F);

        for (RowEntry entry : entries) {
            if (entry.alpha.get() <= 0.01F && !entry.active) {
                continue;
            }

            if (entry.section) {
                width = Math.max(
                        width,
                        Render2D.textWidth(
                                TEXT_FONT,
                                entry.name,
                                5.5F
                        ) + 30.0F
                );
            } else {
                width = Math.max(
                        width,
                        Render2D.textWidth(
                                TEXT_FONT,
                                entry.name,
                                6.0F
                        )
                                + Render2D.textWidth(
                                TEXT_FONT,
                                entry.level,
                                6.0F
                        )
                                + 76.0F
                );
            }
        }

        size(width, height);
        drag.size(
                drag.width(),
                (float) Math.ceil(height)
        );

        rows.clear();

        for (RowEntry entry : entries) {
            float alpha = entry.alpha.get();

            if (alpha > 0.01F || entry.active) {
                rows.add(
                        new RowState(
                                entry.section,
                                entry.negative,
                                entry.effect,
                                entry.name,
                                entry.level,
                                entry.value,
                                entry.valueSize,
                                entry.remainingTicks,
                                entry.maxTicks,
                                entry.y.get(),
                                alpha
                        )
                );
            }
        }

        rows.sort(ROW_COMPARATOR);

        return new PotionsState(
                List.copyOf(rows),
                panelAlpha,
                drag.x(),
                drag.y(),
                drag.width(),
                drag.height(),
                effects.size()
        );
    }

    private void collectEffects() {
        effects.clear();

        if (mc.player != null) {
            for (MobEffectInstance effect : mc.player.getActiveEffects()) {
                if (effect.showIcon()) {
                    effects.add(effect);
                }
            }
        }

        effects.sort(EFFECT_COMPARATOR);

        positiveEffects.clear();
        negativeEffects.clear();

        for (MobEffectInstance effect : effects) {
            if (isNegative(effect)) {
                negativeEffects.add(effect);
            } else {
                positiveEffects.add(effect);
            }
        }
    }

    private void renderPotions(PotionsState state) {
        if (state == null || state.panelAlpha() <= 0.015F) {
            return;
        }

        float alpha = state.panelAlpha();
        float headerY = state.y() - HEADER_HEIGHT;

        HudStyle.glass(state.x(), headerY, state.width(), state.height() + HEADER_HEIGHT, alpha);
        HudStyle.header(state.x(), headerY, state.width(), HEADER_HEIGHT, "q", 7.0F, "Effects",
                state.count() > 0 ? Integer.toString(state.count()) : null, alpha);

        renderRows(state);
    }

    private void renderRows(PotionsState state) {
        float rowY = state.y + 3.0F;
        float x = state.x + 3.0F;
        float width = state.width - 6.0F;

        for (RowState row : state.rows) {
            float rowAlpha = state.panelAlpha * row.alpha * expiringAlpha(row.remainingTicks);
            if (rowAlpha <= 0.01F) {
                continue;
            }
            float y = rowY + row.offset;
            int tint = row.negative
                    ? ColorUtil.rgba(NEGATIVE_R, NEGATIVE_G, NEGATIVE_B, 255)
                    : ColorUtil.rgba(POSITIVE_R, POSITIVE_G, POSITIVE_B, 255);

            if (row.section) {
                renderSection(state, row, y, tint, rowAlpha);
                continue;
            }

            HudStyle.surface(x, y, width, ROW_HEIGHT, HudStyle.RADIUS_SM, rowAlpha);
            renderPotionRow(row, x, y, width, tint, rowAlpha);
        }
    }

    private void renderSection(PotionsState state, RowState row, float y, int tint, float alpha) {
        float textX = state.x + 9.5F;
        float textY = y + (SECTION_HEIGHT - HudStyle.TINY_SIZE) * 0.5F - 1.0F;
        HudStyle.dot(state.x + 6.0F, textY + HudStyle.TINY_SIZE * 0.5F, 2.5F, HudStyle.withAlpha(tint, 255.0F, alpha));
        String label = row.name.toUpperCase();
        Render2D.text(HudStyle.NUM, label, textX, textY, HudStyle.TINY_SIZE, HudStyle.withAlpha(tint, 220.0F, alpha));
        float lineX = textX + Render2D.textWidth(HudStyle.NUM, label, HudStyle.TINY_SIZE) + 4.0F;
        float lineW = state.x + state.width - 6.0F - lineX;
        if (lineW > 2.0F) {
            Render2D.gradientRect(lineX, textY + HudStyle.TINY_SIZE * 0.5F, lineW, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F,
                    HudStyle.withAlpha(tint, 70.0F, alpha), HudStyle.white(0.0F, alpha),
                    HudStyle.white(0.0F, alpha), HudStyle.withAlpha(tint, 70.0F, alpha));
        }
    }

    private void renderPotionRow(RowState row, float x, float y, float width, int tint, float alpha) {
        float iconBox = ROW_HEIGHT - 4.0F;
        float iconX = x + 2.0F;
        float iconY = y + 2.0F;
        HudStyle.tintChip(iconX, iconY, iconBox, tint, alpha);
        if (row.effect != null) {
            Render2D.effectIcon(row.effect, iconX + 1.0F, iconY + 1.0F, iconBox - 2.0F, HudStyle.white(255.0F, alpha));
        }

        float cell = 3.4F;
        float timerW = HudStyle.stableWidth(row.value, cell);
        float timerX = x + width - timerW - 5.0F;
        float textY = y + (ROW_HEIGHT - HudStyle.BODY_SIZE) * 0.5F - 1.0F;
        HudStyle.stableText(row.value, timerX, textY + 0.3F, row.valueSize, cell, HudStyle.text(alpha));

        float nameX = iconX + iconBox + 5.0F;
        float levelW = row.level.isBlank() ? 0.0F : HudStyle.pillWidth(row.level, HudStyle.NUM, HudStyle.TINY_SIZE) + 3.0F;
        String name = trimToWidth(row.name, TEXT_FONT, HudStyle.BODY_SIZE, timerX - nameX - levelW - 5.0F);
        Render2D.text(TEXT_FONT, name, nameX, textY, HudStyle.BODY_SIZE, HudStyle.text(alpha));

        if (!row.level.isBlank()) {
            float levelX = nameX + Render2D.textWidth(TEXT_FONT, name, HudStyle.BODY_SIZE) + 3.0F;
            HudStyle.pill(levelX, textY - 1.2F, 8.0F, row.level, HudStyle.NUM, HudStyle.TINY_SIZE,
                    HudStyle.withAlpha(tint, 45.0F, alpha), HudStyle.withAlpha(tint, 255.0F, alpha));
        }

        float progress;
        if (row.remainingTicks < 0 || row.maxTicks <= 0) {
            progress = 1.0F;
        } else {
            progress = HudStyle.clamp01(row.remainingTicks / (float) row.maxTicks);
        }
        float barW = x + width - 5.0F - nameX;
        HudStyle.progress(nameX, y + ROW_HEIGHT - 3.4F, barW, 1.2F, progress,
                HudStyle.withAlpha(tint, 140.0F, alpha), HudStyle.withAlpha(tint, 255.0F, alpha), alpha);
    }

    private RowEntry effectRow(
            MobEffectInstance effect,
            boolean negative,
            float targetY
    ) {
        String name = effect
                .getEffect()
                .value()
                .getDisplayName()
                .getString();

        String key = (negative ? "negative:" : "positive:")
                + name
                + ":"
                + effect.getAmplifier();

        return row(
                key,
                name,
                level(effect),
                formatDuration(effect.getDuration()),
                6.0F,
                targetY,
                effect.getEffect(),
                effect.getDuration(),
                false,
                negative
        );
    }

    private RowEntry section(
            String key,
            String name,
            float targetY,
            boolean negative
    ) {
        return row(
                key,
                name,
                "",
                "",
                5.5F,
                targetY,
                null,
                -1,
                true,
                negative
        );
    }

    private RowEntry row(
            String key,
            String name,
            String level,
            String value,
            float valueSize,
            float targetY,
            Holder<MobEffect> effect,
            int remainingTicks,
            boolean section,
            boolean negative
    ) {
        for (RowEntry entry : entries) {
            if (!entry.key.equals(key)) {
                continue;
            }

            entry.section = section;
            entry.negative = negative;
            entry.effect = effect;
            entry.name = name;
            entry.level = level;
            entry.value = value;
            entry.valueSize = valueSize;
            entry.remainingTicks = remainingTicks;
            entry.maxTicks = Math.max(
                    entry.maxTicks,
                    remainingTicks
            );

            return entry;
        }

        RowEntry entry = new RowEntry(
                key,
                section,
                negative,
                effect,
                name,
                level,
                value,
                valueSize,
                remainingTicks
        );

        entry.alpha.set(0.0);
        entry.y.set(targetY + 4.0F);
        entries.add(entry);

        return entry;
    }

    private float activate(
            RowEntry entry,
            float targetY,
            float step
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

        return targetY + step;
    }

    private float expiringAlpha(int ticks) {
        if (ticks < 0 || ticks > 100) {
            return 1.0F;
        }

        float pulse = (float) (
                (Math.sin(
                        System.currentTimeMillis()
                                / 135.0D
                ) + 1.0D) * 0.5D
        );

        return (155.0F + pulse * 100.0F) / 255.0F;
    }

    private void updatePreview() {
        long second = System.currentTimeMillis() / 1000L;

        if (second == previewSecond) {
            return;
        }

        previewSecond = second;

        previewPositive =
                PREVIEW_POSITIVE.get(
                        ThreadLocalRandom.current().nextInt(
                                PREVIEW_POSITIVE.size()
                        )
                );

        previewNegative =
                PREVIEW_NEGATIVE.get(
                        ThreadLocalRandom.current().nextInt(
                                PREVIEW_NEGATIVE.size()
                        )
                );
    }

    private String formatDuration(int ticks) {
        if (ticks < 0) {
            return "**:**";
        }

        int totalSeconds = Math.max(
                0,
                ticks / 20
        );

        int minutes = Math.min(
                99,
                totalSeconds / 60
        );

        int seconds = totalSeconds % 60;

        return twoDigits(minutes)
                + ":"
                + twoDigits(seconds);
    }

    private String level(MobEffectInstance effect) {
        int level = effect.getAmplifier() + 1;

        return level <= 1 ? "" : HudStyle.roman(level);
    }

    private boolean isNegative(MobEffectInstance effect) {
        return MobEffects.SLOW_FALLING.equals(effect.getEffect())
                || effect.getEffect()
                .value()
                .getCategory()
                == MobEffectCategory.HARMFUL;
    }

    public static String twoDigits(int value) {
        return value < 10
                ? "0" + value
                : Integer.toString(value);
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

    private static final class RowEntry {
        private final String key;

        private final SmoothAnimation alpha =
                new SmoothAnimation();

        private final SmoothAnimation y =
                new SmoothAnimation();

        private boolean section;
        private boolean negative;
        private boolean active;

        private Holder<MobEffect> effect;
        private String name;
        private String level;
        private String value;
        private float valueSize;
        private int remainingTicks;
        private int maxTicks;

        private RowEntry(
                String key,
                boolean section,
                boolean negative,
                Holder<MobEffect> effect,
                String name,
                String level,
                String value,
                float valueSize,
                int remainingTicks
        ) {
            this.key = key;
            this.section = section;
            this.negative = negative;
            this.effect = effect;
            this.name = name;
            this.level = level;
            this.value = value;
            this.valueSize = valueSize;
            this.remainingTicks = remainingTicks;
            this.maxTicks = Math.max(1, remainingTicks);
        }
    }

    private record RowState(
            boolean section,
            boolean negative,
            Holder<MobEffect> effect,
            String name,
            String level,
            String value,
            float valueSize,
            int remainingTicks,
            int maxTicks,
            float offset,
            float alpha
    ) {
    }

    private record PotionsState(
            List<RowState> rows,
            float panelAlpha,
            float x,
            float y,
            float width,
            float height,
            int count
    ) {
    }
}