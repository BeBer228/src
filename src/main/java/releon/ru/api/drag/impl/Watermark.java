package releon.ru.api.drag.impl;

import com.adl.nativeprotect.User;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Watermark extends HudPanel {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final String BRAND = "Titanium";
    private static final String VERSION = "1.21.11";

    private static final float ROW_HEIGHT = 20.0F;
    private static final float ROW_GAP = 3.0F;
    private static final float BLOCK_GAP = 3.0F;
    private static final float BADGE = 20.0F;
    private static final float RADIUS = HudStyle.RADIUS;
    private static final float TEXT_SIZE = 6.0F;
    private static final float BRAND_SIZE = 7.5F;
    private static final float VERSION_SIZE = 5.5F;
    private static final float ICON_GAP = 3.5F;
    private static final float CELL_PADDING = 6.0F;
    private static final float ANIMATION_SECONDS = 0.24F;

    private static final int ACCENT_R = HudStyle.ACCENT_R;
    private static final int ACCENT_G = HudStyle.ACCENT_G;
    private static final int ACCENT_B = HudStyle.ACCENT_B;


    private final SmoothAnimation topWidthAnimation = new SmoothAnimation();
    private final SmoothAnimation bottomWidthAnimation = new SmoothAnimation();
    private final SmoothAnimation totalWidthAnimation = new SmoothAnimation();

    private long lastNanos = System.nanoTime();
    private float pulse;

    public Watermark() {
        super("watermark", "Watermark", 10.0F, 10.0F, 232.0F, ROW_HEIGHT * 2.0F + ROW_GAP);
        topWidthAnimation.set(150.0F);
        bottomWidthAnimation.set(232.0F);
        totalWidthAnimation.set(232.0F);
    }

    @Override
    protected void renderHud() {
        WatermarkState state = logics();
        if (state == null) {
            return;
        }
        renderWatermark(state);
    }

    private WatermarkState logics() {
        float alpha = contentAlpha(true);
        if (alpha <= 0.0F) {
            return null;
        }

        long now = System.nanoTime();
        float delta = Math.min(0.05F, Math.max(0.0F, (now - lastNanos) / 1_000_000_000.0F));
        lastNanos = now;
        pulse = (pulse + delta * 1.4F) % 6.2831855F;

        int ping = ping();
        Cell[] topCells = {
                new Cell("D", 9.0F, mc.getFps() + " fps", Accent.NEUTRAL),
                new Cell("B", 8.5F, coords(), Accent.NEUTRAL)
        };
        Cell[] bottomCells = {
                new Cell("y", 8.5F, ping + " ms", pingAccent(ping)),
                new Cell("R", 9.0F, server(), Accent.NEUTRAL),
                new Cell("z", 7.0F, "20 tps", Accent.NEUTRAL),
                new Cell("t", 9.0F, resolveUsername(), Accent.NEUTRAL),
                new Cell("h", 9.0F, LocalTime.now().format(TIME_FORMATTER), Accent.NEUTRAL)
        };

        float brandWidth = BADGE
                + Render2D.textWidth(FontType.BOLD, BRAND, BRAND_SIZE)
                + Render2D.textWidth(FontType.BOLD, VERSION, VERSION_SIZE)
                + 18.0F;
        float topStatsWidth = naturalWidth(topCells);
        float bottomWidth = naturalWidth(bottomCells);
        float totalWidth = Math.max(brandWidth + BLOCK_GAP + topStatsWidth, bottomWidth);

        float animatedTotal = animate(totalWidthAnimation, totalWidth);
        float animatedTop = animate(topWidthAnimation, Math.max(topStatsWidth, animatedTotal - brandWidth - BLOCK_GAP));
        float animatedBottom = animate(bottomWidthAnimation, animatedTotal);

        distribute(topCells, animatedTop);
        distribute(bottomCells, animatedBottom);

        float height = ROW_HEIGHT * 2.0F + ROW_GAP;
        size(animatedTotal, height);

        float x = drag.x();
        float y = drag.y();

        return new WatermarkState(topCells, bottomCells, alpha, x, y, animatedTotal, brandWidth, animatedTop, animatedBottom);
    }

    private void renderWatermark(WatermarkState state) {
        float x = state.x;
        float y = state.y;
        float alpha = state.alpha;
        float bottomY = y + ROW_HEIGHT + ROW_GAP;

        // мягкий акцентный ореол за логотипом
        float breathe = HudStyle.pulse(3200L);
        HudStyle.glow(x - 16.0F, y - 14.0F, BADGE + 32.0F, ROW_HEIGHT + 28.0F, HudStyle.accent(70.0F + 40.0F * breathe, alpha));

        HudStyle.glass(x, y, state.brandWidth, ROW_HEIGHT, RADIUS, alpha);
        HudStyle.glass(x + state.brandWidth + BLOCK_GAP, y, state.topWidth, ROW_HEIGHT, RADIUS, alpha);
        HudStyle.glass(x, bottomY, state.bottomWidth, ROW_HEIGHT, RADIUS, alpha);

        renderBrand(x, y, state.brandWidth, alpha, breathe);
        renderCells(x + state.brandWidth + BLOCK_GAP, y, state.topCells, alpha);
        renderCells(x, bottomY, state.bottomCells, alpha);
    }

    private void renderBrand(float x, float y, float width, float alpha, float breathe) {
        // логотип в градиентном чипе
        float chip = 13.0F;
        float chipX = x + (BADGE - chip) * 0.5F + 1.0F;
        float chipY = y + (ROW_HEIGHT - chip) * 0.5F;
        HudStyle.glow(chipX - 7.0F, chipY - 7.0F, chip + 14.0F, chip + 14.0F, HudStyle.accent2(90.0F + 60.0F * breathe, alpha));
        HudStyle.iconChip(chipX, chipY, chip, "c", 10.0F, alpha);

        float cursor = x + BADGE + 3.0F;
        Render2D.text(FontType.BOLD, BRAND, cursor, centered(y, BRAND_SIZE), BRAND_SIZE, HudStyle.text(alpha));
        cursor += Render2D.textWidth(FontType.BOLD, BRAND, BRAND_SIZE) + 3.5F;

        float pillH = 9.0F;
        float pillW = Render2D.textWidth(FontType.BOLD, VERSION, VERSION_SIZE) + 6.0F;
        float pillY = y + (ROW_HEIGHT - pillH) * 0.5F;
        Render2D.rect(cursor, pillY, pillW, pillH, pillH * 0.5F, HudStyle.accent(40.0F, alpha));
        Render2D.outline(cursor, pillY, pillW, pillH, pillH * 0.5F, HudStyle.STROKE, HudStyle.accent(90.0F, alpha));
        Render2D.text(FontType.BOLD, VERSION, cursor + 3.0F, pillY + (pillH - VERSION_SIZE) * 0.5F, VERSION_SIZE, HudStyle.accent(255.0F, alpha));

        // бегущий блик по плашке бренда
        float period = 4200.0F;
        float t = (System.currentTimeMillis() % (long) period) / period;
        float band = 26.0F;
        float bandX = x - band + t * (width + band * 2.0F);
        float left = Math.max(x + RADIUS, bandX);
        float rightEdge = Math.min(x + width - RADIUS, bandX + band);
        if (rightEdge - left > 1.0F) {
            float mid = (left + rightEdge) * 0.5F;
            int edge = HudStyle.white(0.0F, alpha);
            int peak = HudStyle.white(22.0F, alpha);
            Render2D.gradientRect(left, y + 1.0F, mid - left, ROW_HEIGHT - 2.0F, 0.0F, 0.0F, 0.0F, 0.0F, edge, peak, peak, edge);
            Render2D.gradientRect(mid, y + 1.0F, rightEdge - mid, ROW_HEIGHT - 2.0F, 0.0F, 0.0F, 0.0F, 0.0F, peak, edge, edge, peak);
        }
    }

    private void renderCells(float x, float y, Cell[] cells, float alpha) {
        int textColor = HudStyle.text(alpha);
        float cursor = x;
        for (int i = 0; i < cells.length; i++) {
            Cell cell = cells[i];
            boolean last = i == cells.length - 1;

            float iconX = cursor + CELL_PADDING;
            int iconColor = cell.accent.color(alpha);
            if (cell.accent != Accent.NEUTRAL) {
                HudStyle.glow(iconX - 5.0F, y - 1.0F, 18.0F, ROW_HEIGHT + 2.0F, cell.accent.glow(alpha));
            }
            Render2D.text(FontType.MAINMENUSCREEN, cell.icon, iconX, centered(y, cell.iconSize), cell.iconSize, iconColor);

            float textX = iconX + Render2D.textWidth(FontType.MAINMENUSCREEN, cell.icon, cell.iconSize) + ICON_GAP;
            Render2D.text(FontType.SEMIBOLD, cell.text, textX, centered(y, TEXT_SIZE), TEXT_SIZE, textColor);

            cursor += cell.width;
            if (!last) {
                Render2D.rect(cursor - 0.75F, y + ROW_HEIGHT * 0.5F - 0.75F, 1.5F, 1.5F, 0.75F, HudStyle.white(60.0F, alpha));
            }
        }
    }

    private float naturalWidth(Cell[] cells) {
        float width = 0.0F;
        for (Cell cell : cells) {
            cell.width = CELL_PADDING * 2.0F
                    + Render2D.textWidth(FontType.MAINMENUSCREEN, cell.icon, cell.iconSize)
                    + ICON_GAP
                    + Render2D.textWidth(FontType.SEMIBOLD, cell.text, TEXT_SIZE);
            width += cell.width;
        }
        return width;
    }

    private void distribute(Cell[] cells, float targetWidth) {
        float natural = 0.0F;
        for (Cell cell : cells) {
            natural += cell.width;
        }
        float extra = (targetWidth - natural) / cells.length;
        for (Cell cell : cells) {
            cell.width += extra;
        }
    }

    private float animate(SmoothAnimation animation, float target) {
        animation.run(target, ANIMATION_SECONDS, Easings.CUBIC_OUT, true);
        animation.update();
        return animation.get();
    }

    private static float centered(float y, float size) {
        return y + (ROW_HEIGHT - size) * 0.5F;
    }

    private static int color(int r, int g, int b, float a, float alpha) {
        return ColorUtil.rgba(r, g, b, Math.max(0, Math.min(255, Math.round(a * alpha))));
    }

    private int ping() {
        if (mc.player == null || mc.getConnection() == null || mc.getConnection().getPlayerInfo(mc.player.getUUID()) == null) {
            return 0;
        }
        return Math.max(0, mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency());
    }

    private String coords() {
        if (mc.player == null) {
            return "0, 0, 0";
        }
        return Math.round(mc.player.getX()) + ", " + Math.round(mc.player.getY()) + ", " + Math.round(mc.player.getZ());
    }

    private static Accent pingAccent(int ping) {
        if (ping <= 60) {
            return Accent.GOOD;
        }
        return ping <= 140 ? Accent.WARN : Accent.BAD;
    }

    private String server() {
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null && !mc.getCurrentServer().ip.isBlank()) {
            return mc.getCurrentServer().ip;
        }
        return "singleplayer";
    }

    private String resolveUsername() {
        String username = "";
        try {
            username = User.getInstance().profile("username");
        } catch (Throwable ignored) {
        }
        if (isBlank(username) && mc.getUser() != null) {
            username = mc.getUser().getName();
        }
        if (isBlank(username) && mc.player != null && mc.player.getGameProfile() != null) {
            username = mc.player.getGameProfile().name();
        }
        return isBlank(username) ? "User" : username.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum Accent {
        NEUTRAL(ACCENT_R, ACCENT_G, ACCENT_B),
        GOOD(HudStyle.GOOD_R, HudStyle.GOOD_G, HudStyle.GOOD_B),
        WARN(HudStyle.WARN_R, HudStyle.WARN_G, HudStyle.WARN_B),
        BAD(HudStyle.BAD_R, HudStyle.BAD_G, HudStyle.BAD_B);

        private final int r;
        private final int g;
        private final int b;

        Accent(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        private int color(float alpha) {
            if (this == NEUTRAL) {
                return HudStyle.accent(255.0F, alpha);
            }
            return Watermark.color(r, g, b, 255.0F, alpha);
        }

        private int glow(float alpha) {
            if (this == NEUTRAL) {
                return HudStyle.accent(60.0F, alpha);
            }
            return Watermark.color(r, g, b, 60.0F, alpha);
        }
    }

    private static final class Cell {
        private final String icon;
        private final float iconSize;
        private final String text;
        private final Accent accent;
        private float width;

        private Cell(String icon, float iconSize, String text, Accent accent) {
            this.icon = icon;
            this.iconSize = iconSize;
            this.text = text;
            this.accent = accent;
        }
    }

    private record WatermarkState(
            Cell[] topCells,
            Cell[] bottomCells,
            float alpha,
            float x,
            float y,
            float width,
            float brandWidth,
            float topWidth,
            float bottomWidth
    ) {
    }
}
