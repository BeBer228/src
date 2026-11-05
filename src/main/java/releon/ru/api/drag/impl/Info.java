package releon.ru.api.drag.impl;

import releon.ru.utils.render.ui.Render2D;

import java.util.ArrayList;
import java.util.List;

public final class Info extends HudPanel {
    private final List<String> rows = new ArrayList<>(3);
    private double lastX;
    private double lastZ;
    private long lastSampleMs;
    private double blocksPerSecond;

    public Info() {
        super("info", "Info", 10.0F, 70.0F, 92.0F, 50.0F);
    }

    @Override
    protected void renderHud() {
        InfoState state = logics();
        if (state == null) {
            return;
        }
        renderInfo(state);
    }

    private static final float ROW_HEIGHT = 11.0F;
    private static final String[] LABELS = {"FPS", "XYZ", "BPS"};

    private InfoState logics() {
        contentVisible(selected());
        if (mc.player == null) {
            return null;
        }

        updateSpeed();
        rows.clear();
        rows.add(Integer.toString(mc.getFps()));
        rows.add(Math.round(mc.player.getX()) + " " + Math.round(mc.player.getY()) + " " + Math.round(mc.player.getZ()));
        rows.add(formatBps(blocksPerSecond));

        float width = 92.0F;
        for (int i = 0; i < rows.size(); i++) {
            float label = Render2D.textWidth(HudStyle.NUM, LABELS[i], HudStyle.TINY_SIZE);
            float value = Render2D.textWidth(TEXT_FONT, rows.get(i), HudStyle.BODY_SIZE);
            width = Math.max(width, 7.0F + label + 12.0F + value + 7.0F);
        }
        size(width, rows.size() * ROW_HEIGHT + 8.0F);

        return new InfoState(rows, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderInfo(InfoState state) {
        panelBackground(state.x, state.y, state.width, state.height, 1.0F);

        float rowY = state.y + 4.0F;
        for (int i = 0; i < state.rows.size(); i++) {
            float textY = rowY + (ROW_HEIGHT - HudStyle.BODY_SIZE) * 0.5F;
            HudStyle.dot(state.x + 6.5F, rowY + ROW_HEIGHT * 0.5F, 2.0F, HudStyle.accent(255.0F, 1.0F));
            Render2D.text(HudStyle.NUM, LABELS[i], state.x + 10.0F, textY + 0.5F, HudStyle.TINY_SIZE, HudStyle.muted(1.0F));
            HudStyle.textRight(TEXT_FONT, state.rows.get(i), state.x + state.width - 7.0F, textY, HudStyle.BODY_SIZE, HudStyle.text(1.0F));
            if (i < state.rows.size() - 1) {
                HudStyle.divider(state.x + 6.0F, rowY + ROW_HEIGHT, state.width - 12.0F, 1.0F);
            }
            rowY += ROW_HEIGHT;
        }
    }

    private void updateSpeed() {
        long now = System.currentTimeMillis();
        if (lastSampleMs == 0L) {
            lastSampleMs = now;
            lastX = mc.player.getX();
            lastZ = mc.player.getZ();
            return;
        }
        long deltaMs = now - lastSampleMs;
        if (deltaMs < 200L) {
            return;
        }

        double dx = mc.player.getX() - lastX;
        double dz = mc.player.getZ() - lastZ;
        double instant = Math.sqrt(dx * dx + dz * dz) / Math.max(0.001D, deltaMs / 1000.0D);
        blocksPerSecond = blocksPerSecond * 0.65D + instant * 0.35D;
        lastSampleMs = now;
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
    }

    private String formatBps(double value) {
        int scaled = Math.max(0, (int) Math.round(value * 100.0D));
        int whole = scaled / 100;
        int fraction = scaled % 100;
        return whole + "." + twoDigits(fraction);
    }

    private record InfoState(List<String> rows, float x, float y, float width, float height) {
    }
}
