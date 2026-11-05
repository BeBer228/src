package releon.ru.api.drag.impl;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.repository.staff.StaffUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Staff extends HudPanel {
    private static final float HEADER_HEIGHT = 18.0F;
    private static final float ROW_HEIGHT = 16.0F;
    private static final float ROW_GAP = 2.0F;
    private static final float PANEL_ANIMATION = 0.24F;
    private static final float ROW_ANIMATION = 0.22F;

    private static final Comparator<RowState> ROW_COMPARATOR =
            Comparator.comparingDouble(RowState::offset);

    private final List<RowEntry> entries = new ArrayList<>();
    private final List<StaffEntry> cachedStaff = new ArrayList<>();
    private final List<RowState> rows = new ArrayList<>();

    private final Map<String, PlayerInfo> tabPlayers = new HashMap<>();
    private final Map<String, Player> worldPlayers = new HashMap<>();

    private final SmoothAnimation panelAnimation =
            new SmoothAnimation();

    private final SmoothAnimation iconAnimation =
            new SmoothAnimation();

    private boolean iconForward = true;

    public Staff() {
        super(
                "staff",
                "Staff",
                140.0F,
                70.0F,
                112.0F,
                28.0F
        );
    }

    @Override
    protected void renderHud() {
        StaffState state = updateState();

        if (state == null) {
            return;
        }

        renderStaff(state);
    }

    private StaffState updateState() {
        List<StaffEntry> staff = collectStaff();

        boolean preview = staff.isEmpty() && editPreview();
        boolean shouldShow = !staff.isEmpty() || preview;

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

        int index = 0;

        if (preview) {
            StaffEntry previewStaff = new StaffEntry(
                    "Moderator",
                    "Active",
                    defaultSkin("Moderator"),
                    ColorUtil.rgba(HudStyle.GOOD_R, HudStyle.GOOD_G, HudStyle.GOOD_B, 255)
            );

            activate(
                    row(
                            "__preview",
                            previewStaff,
                            0.0F
                    ),
                    0.0F
            );

            index = 1;
        } else {
            for (StaffEntry staffEntry : staff) {
                float targetY = index * (ROW_HEIGHT + ROW_GAP);

                activate(
                        row(
                                normalize(staffEntry.name),
                                staffEntry,
                                targetY
                        ),
                        targetY
                );

                index++;
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
            if (entry.alpha.get() <= 0.01F && !entry.active) {
                continue;
            }

            float nameWidth = Render2D.textWidth(
                    TEXT_FONT,
                    entry.name,
                    6.0F
            );

            float statusWidth = Render2D.textWidth(
                    TEXT_FONT,
                    entry.status,
                    5.5F
            );

            width = Math.max(
                    width,
                    nameWidth + statusWidth + 40.0F
            );
        }

        float height =
                Math.max(
                        ROW_HEIGHT,
                        index * (ROW_HEIGHT + ROW_GAP)
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
                                entry.status,
                                entry.skin,
                                entry.dotColor,
                                entry.y.get(),
                                alpha
                        )
                );
            }
        }

        rows.sort(ROW_COMPARATOR);

        return new StaffState(
                List.copyOf(rows),
                panelAlpha,
                drag.x(),
                drag.y(),
                drag.width(),
                drag.height(),
                staff.size()
        );
    }

    private void renderStaff(StaffState state) {
        if (state == null || state.alpha <= 0.015F) {
            return;
        }

        float alpha = state.alpha;
        float headerY = state.y - HEADER_HEIGHT;

        HudStyle.glass(state.x, headerY, state.width, state.height + HEADER_HEIGHT, alpha);
        HudStyle.header(state.x, headerY, state.width, HEADER_HEIGHT, "c", 8.0F, "Staff",
                state.count > 0 ? Integer.toString(state.count) : null, alpha);

        renderRows(state);
    }

    private void renderRows(StaffState state) {
        float rowX = state.x + 3.0F;
        float rowWidth = state.width - 6.0F;
        float rowY = state.y + 3.0F;

        for (RowState row : state.rows) {
            float alpha = state.alpha * row.alpha;
            if (alpha <= 0.01F) {
                continue;
            }
            float y = rowY + row.offset;
            int status = HudStyle.withAlpha(row.dotColor, 255.0F, alpha);

            HudStyle.surface(rowX, y, rowWidth, ROW_HEIGHT, HudStyle.RADIUS_SM, alpha);

            float avatar = ROW_HEIGHT - 4.0F;
            float avatarX = rowX + 2.0F;
            float avatarY = y + 2.0F;
            Render2D.rect(avatarX - 0.5F, avatarY - 0.5F, avatar + 1.0F, avatar + 1.0F, 3.0F,
                    HudStyle.withAlpha(row.dotColor, 120.0F, alpha));
            renderHead(row, avatarX, avatarY, avatar, alpha);
            // online-индикатор на углу аватарки
            Render2D.rect(avatarX + avatar - 3.0F, avatarY + avatar - 3.0F, 4.0F, 4.0F, 2.0F, HudStyle.glassColor(255.0F, alpha));
            Render2D.rect(avatarX + avatar - 2.4F, avatarY + avatar - 2.4F, 2.8F, 2.8F, 1.4F, status);

            float pillH = ROW_HEIGHT - 6.0F;
            float pillW = HudStyle.pillWidth(row.status, HudStyle.NUM, HudStyle.TINY_SIZE);
            float pillX = rowX + rowWidth - pillW - 3.0F;
            HudStyle.pill(pillX, y + 3.0F, pillH, row.status, HudStyle.NUM, HudStyle.TINY_SIZE,
                    HudStyle.withAlpha(row.dotColor, 38.0F, alpha), status);

            float nameX = avatarX + avatar + 5.0F;
            String name = trimToWidth(row.name, TEXT_FONT, HudStyle.BODY_SIZE, pillX - nameX - 4.0F);
            Render2D.text(TEXT_FONT, name, nameX, y + (ROW_HEIGHT - HudStyle.BODY_SIZE) * 0.5F,
                    HudStyle.BODY_SIZE, HudStyle.text(alpha));
        }
    }

    private void renderHead(
            RowState row,
            float x,
            float y,
            float size,
            float alpha
    ) {
        String texture = row.skin;

        if (texture == null || texture.isBlank()) {
            texture = defaultSkin(row.name);
        }

        int color = ColorUtil.rgba(
                255,
                255,
                255,
                Math.round(255.0F * alpha)
        );

        Render2D.imageUvNearest(
                texture,
                x,
                y,
                size,
                size,
                2.5F,
                1.0F,
                8.0F / 64.0F,
                8.0F / 64.0F,
                16.0F / 64.0F,
                16.0F / 64.0F,
                color
        );

        Render2D.imageUvNearest(
                texture,
                x,
                y,
                size,
                size,
                2.5F,
                1.0F,
                40.0F / 64.0F,
                8.0F / 64.0F,
                48.0F / 64.0F,
                16.0F / 64.0F,
                color
        );
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
            StaffEntry staff,
            float targetY
    ) {
        for (RowEntry entry : entries) {
            if (!entry.key.equals(key)) {
                continue;
            }

            entry.name = staff.name;
            entry.status = staff.status;
            entry.skin = staff.skin;
            entry.dotColor = staff.dotColor;

            return entry;
        }

        RowEntry entry = new RowEntry(
                key,
                staff
        );

        entry.alpha.set(0.0);
        entry.y.set(targetY + 4.0F);
        entries.add(entry);

        return entry;
    }

    private List<StaffEntry> collectStaff() {
        cachedStaff.clear();

        if (mc.level == null || mc.getConnection() == null) {
            return cachedStaff;
        }

        tabPlayers.clear();

        for (PlayerInfo info :
                mc.getConnection().getOnlinePlayers()) {
            String name = normalize(
                    info.getProfile().name()
            );

            if (!name.isEmpty()) {
                tabPlayers.put(
                        name,
                        info
                );
            }
        }

        worldPlayers.clear();

        for (Player player : mc.level.players()) {
            String name = normalize(
                    player.getName().getString()
            );

            if (!name.isEmpty()) {
                worldPlayers.put(
                        name,
                        player
                );
            }
        }

        for (String staffName : StaffUtils.getStaffNames()) {
            String key = normalize(staffName);

            if (key.isEmpty()) {
                continue;
            }

            Player player = worldPlayers.get(key);
            PlayerInfo info = tabPlayers.get(key);

            if (player != null) {
                cachedStaff.add(
                        new StaffEntry(
                                staffName,
                                "Near",
                                skin(player, info, staffName),
                                ColorUtil.rgba(HudStyle.WARN_R, HudStyle.WARN_G, HudStyle.WARN_B, 255)
                        )
                );
            } else if (info != null) {
                cachedStaff.add(
                        new StaffEntry(
                                staffName,
                                "Active",
                                skin(null, info, staffName),
                                ColorUtil.rgba(HudStyle.GOOD_R, HudStyle.GOOD_G, HudStyle.GOOD_B, 255)
                        )
                );
            } else {
                cachedStaff.add(
                        new StaffEntry(
                                staffName,
                                "Spec",
                                defaultSkin(staffName),
                                ColorUtil.rgba(HudStyle.BAD_R, HudStyle.BAD_G, HudStyle.BAD_B, 255)
                        )
                );
            }
        }

        return cachedStaff;
    }

    private String skin(
            Player player,
            PlayerInfo info,
            String name
    ) {
        try {
            if (player instanceof AbstractClientPlayer clientPlayer) {
                return clientPlayer
                        .getSkin()
                        .body()
                        .texturePath()
                        .toString();
            }

            if (info != null) {
                return info
                        .getSkin()
                        .body()
                        .texturePath()
                        .toString();
            }
        } catch (RuntimeException ignored) {
        }

        return defaultSkin(name);
    }

    private String defaultSkin(String name) {
        return ((name == null ? 0 : name.hashCode()) & 1) == 0
                ? "minecraft:textures/entity/player/wide/steve.png"
                : "minecraft:textures/entity/player/slim/alex.png";
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return StaffUtils
                .normalizeName(value)
                .toLowerCase();
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
        private String status;
        private String skin;
        private int dotColor;
        private boolean active;

        private RowEntry(
                String key,
                StaffEntry staff
        ) {
            this.key = key;
            this.name = staff.name;
            this.status = staff.status;
            this.skin = staff.skin;
            this.dotColor = staff.dotColor;
        }
    }

    private record StaffEntry(
            String name,
            String status,
            String skin,
            int dotColor
    ) {
    }

    private record RowState(
            String name,
            String status,
            String skin,
            int dotColor,
            float offset,
            float alpha
    ) {
    }

    private record StaffState(
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