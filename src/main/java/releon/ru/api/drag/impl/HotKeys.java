package releon.ru.api.drag.impl;

import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleManager;
import releon.ru.manager.Manager;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class HotKeys extends HudPanel {
    private static final float HEADER_HEIGHT = 18.0F;
    private static final float ROW_HEIGHT = 16.0F;
    private static final float ROW_GAP = 2.0F;

    private static final float PANEL_ANIMATION = 0.24F;
    private static final float ROW_ANIMATION = 0.22F;

    private static final Comparator<Module> MODULE_COMPARATOR =
            Comparator.comparing(Module::getName);

    private static final Comparator<RowState> ROW_COMPARATOR =
            Comparator.comparingDouble(RowState::offset);

    private final List<Module> activeModules = new ArrayList<>();
    private final List<RowEntry> entries = new ArrayList<>();
    private final List<RowState> rows = new ArrayList<>();

    private final SmoothAnimation panelAnimation =
            new SmoothAnimation();

    private final SmoothAnimation iconAnimation =
            new SmoothAnimation();

    private boolean iconForward = true;

    public HotKeys() {
        super(
                "hotkeys",
                "HotKeys",
                300.0F,
                40.0F,
                112.0F,
                28.0F
        );
    }

    @Override
    protected void renderHud() {
        HotKeysState state = updateState();

        if (state == null) {
            return;
        }

        renderPanel(state);
    }

    private HotKeysState updateState() {
        collectActiveModules();

        boolean preview =
                activeModules.isEmpty() && editPreview();

        boolean shouldShow =
                !activeModules.isEmpty() || preview;

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
            RowEntry entry = row(
                    "__preview",
                    "Aura",
                    "R",
                    5.5F,
                    0.0F
            );

            activate(entry, 0.0F);
            targetRows = 1;
        } else {
            for (Module module : activeModules) {
                float targetY =
                        targetRows * (ROW_HEIGHT + ROW_GAP);

                String bind =
                        shortBind(module.getBind());

                RowEntry entry = row(
                        module.getName(),
                        module.getName(),
                        bind,
                        5.5F,
                        targetY
                );

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

        float panelAlpha = panelAnimation.get();

        boolean visible =
                shouldShow
                        || panelAlpha > 0.01F
                        || !entries.isEmpty();

        contentVisible(visible);

        if (!visible) {
            return null;
        }

        float width = 112.0F;

        for (RowEntry entry : entries) {
            if (entry.alpha.get() <= 0.01F
                    && !entry.active) {
                continue;
            }

            float nameWidth = Render2D.textWidth(
                    TEXT_FONT,
                    entry.name,
                    6.0F
            );

            float bindWidth = Render2D.textWidth(
                    TEXT_FONT,
                    entry.bind,
                    entry.bindSize
            );

            width = Math.max(
                    width,
                    nameWidth + bindWidth + 34.0F
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
            float alpha = entry.alpha.get();

            if (alpha > 0.01F || entry.active) {
                rows.add(
                        new RowState(
                                entry.name,
                                entry.bind,
                                entry.bindSize,
                                entry.y.get(),
                                alpha
                        )
                );
            }
        }

        rows.sort(ROW_COMPARATOR);

        return new HotKeysState(
                List.copyOf(rows),
                panelAlpha,
                drag.x(),
                drag.y(),
                drag.width(),
                drag.height(),
                activeModules.size()
        );
    }

    private void collectActiveModules() {
        activeModules.clear();

        ModuleManager manager =
                Manager.getModules();

        if (manager == null) {
            return;
        }

        for (Module module : manager.getModules()) {
            if (module == null) {
                continue;
            }

            if (!module.isEnabled()) {
                continue;
            }

            if (module.isHidden()) {
                continue;
            }

            if (module.getBind() == null) {
                continue;
            }

            if (!module.getBind().isBound()) {
                continue;
            }

            activeModules.add(module);
        }

        if (activeModules.size() > 1) {
            activeModules.sort(MODULE_COMPARATOR);
        }
    }

    private void renderPanel(HotKeysState state) {
        if (state == null || state.alpha <= 0.015F) {
            return;
        }

        float alpha = state.alpha;
        float headerY = state.y - HEADER_HEIGHT;

        HudStyle.glass(state.x, headerY, state.width, state.height + HEADER_HEIGHT, alpha);
        HudStyle.header(state.x, headerY, state.width, HEADER_HEIGHT, "m", 8.0F, "Hotkeys",
                state.count > 0 ? Integer.toString(state.count) : null, alpha);

        renderRows(state);
    }

    private void renderRows(HotKeysState state) {
        float rowX = state.x + 3.0F;
        float rowWidth = state.width - 6.0F;
        float rowY = state.y + 3.0F;

        for (RowState row : state.rows) {
            float alpha = state.alpha * row.alpha;
            if (alpha <= 0.01F) {
                continue;
            }
            float y = rowY + row.offset;

            HudStyle.surface(rowX, y, rowWidth, ROW_HEIGHT, HudStyle.RADIUS_SM, alpha);
            HudStyle.dot(rowX + 6.0F, y + ROW_HEIGHT * 0.5F, 3.0F, HudStyle.accent(255.0F, alpha));

            // keycap
            float capH = ROW_HEIGHT - 5.0F;
            float capW = Math.max(capH, Render2D.textWidth(HudStyle.NUM, row.bind, row.bindSize) + 7.0F);
            float capX = rowX + rowWidth - capW - 2.5F;
            float capY = y + 2.0F;
            Render2D.rect(capX, capY + 0.8F, capW, capH, 2.5F, HudStyle.black(90.0F, alpha));
            Render2D.gradientRect(capX, capY, capW, capH, 2.5F, 2.5F, 2.5F, 2.5F,
                    HudStyle.white(30.0F, alpha), HudStyle.white(30.0F, alpha),
                    HudStyle.white(14.0F, alpha), HudStyle.white(14.0F, alpha));
            Render2D.outline(capX, capY, capW, capH, 2.5F, HudStyle.STROKE, HudStyle.white(40.0F, alpha));
            HudStyle.textCentered(HudStyle.NUM, row.bind, capX + capW * 0.5F, capY + (capH - row.bindSize) * 0.5F,
                    row.bindSize, HudStyle.text(alpha));

            float nameX = rowX + 12.0F;
            String name = trimToWidth(row.name, TEXT_FONT, HudStyle.BODY_SIZE, capX - nameX - 5.0F);
            Render2D.text(TEXT_FONT, name, nameX, y + (ROW_HEIGHT - HudStyle.BODY_SIZE) * 0.5F,
                    HudStyle.BODY_SIZE, HudStyle.text(alpha));
        }
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

    private RowEntry row(
            String key,
            String name,
            String bind,
            float bindSize,
            float targetY
    ) {
        for (RowEntry entry : entries) {
            if (!entry.key.equals(key)) {
                continue;
            }

            entry.name = name;
            entry.bind = bind;
            entry.bindSize = bindSize;

            return entry;
        }

        RowEntry entry = new RowEntry(
                key,
                name,
                bind,
                bindSize
        );

        entry.alpha.set(0.0);
        entry.y.set(targetY + 4.0F);
        entries.add(entry);

        return entry;
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

        private String name;
        private String bind;
        private float bindSize;
        private boolean active;

        private RowEntry(
                String key,
                String name,
                String bind,
                float bindSize
        ) {
            this.key = key;
            this.name = name;
            this.bind = bind;
            this.bindSize = bindSize;
        }
    }

    private record RowState(
            String name,
            String bind,
            float bindSize,
            float offset,
            float alpha
    ) {
    }

    private record HotKeysState(
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