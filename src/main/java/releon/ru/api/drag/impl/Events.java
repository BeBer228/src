package releon.ru.api.drag.impl;

import releon.ru.api.module.ModuleManager;
import releon.ru.api.module.impl.misc.ServerHelper;
import releon.ru.manager.Manager;
import releon.ru.utils.render.ui.Render2D;

import java.util.List;

public final class Events extends HudPanel {
    public Events() {
        super("events", "Events", 140.0F, 200.0F, 104.0F, 23.0F);
    }

    @Override
    protected void renderHud() {
        EventsState state = logics();
        if (state == null) {
            return;
        }
        renderEvents(state);
    }

    private static final float ROW_HEIGHT = 14.0F;
    private static final float ROW_GAP = 2.0F;

    private EventsState logics() {
        List<ServerHelper.HudEvent> events = events();
        boolean preview = events.isEmpty() && editPreview();
        boolean visible = !events.isEmpty() || preview;
        float alpha = contentAlpha(visible);
        if (alpha <= 0.0F) {
            return null;
        }

        int rows = preview ? 1 : Math.max(1, events.size());
        String badge = preview ? "1" : Integer.toString(events.size());
        float width = Math.max(120.0F, HudStyle.headerWidth("Events", badge));
        if (preview) {
            width = Math.max(width, rowWidth("Не скоро", distanceText(128.0D), "1:30"));
        } else {
            for (ServerHelper.HudEvent event : events) {
                width = Math.max(width, rowWidth(event.name(), distanceText(event.distance()), event.timer()));
            }
        }
        size(width, HudStyle.HEADER + 3.0F + rows * (ROW_HEIGHT + ROW_GAP) + 1.0F);

        return new EventsState(events, preview, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private float rowWidth(String name, String distance, String timer) {
        return 6.0F + Render2D.textWidth(TEXT_FONT, name, HudStyle.BODY_SIZE) + 4.0F
                + HudStyle.pillWidth(distance, HudStyle.NUM, HudStyle.TINY_SIZE) + 8.0F
                + HudStyle.pillWidth(timer, HudStyle.NUM, HudStyle.SMALL_SIZE) + 9.0F;
    }

    private void renderEvents(EventsState state) {
        float alpha = state.alpha;
        String badge = state.preview ? "1" : Integer.toString(state.events.size());
        card(state.x, state.y, state.width, state.height, "h", 7.5F, "Events", badge, alpha);

        float rowX = state.x + 3.0F;
        float rowW = state.width - 6.0F;
        float rowY = state.y + HudStyle.HEADER + 3.0F;

        if (state.preview) {
            renderRow(rowX, rowY, rowW, "Не скоро", distanceText(128.0D), "1:30", alpha);
            return;
        }
        for (ServerHelper.HudEvent event : state.events) {
            renderRow(rowX, rowY, rowW, event.name(), distanceText(event.distance()), event.timer(), alpha);
            rowY += ROW_HEIGHT + ROW_GAP;
        }
    }

    private void renderRow(float x, float y, float w, String name, String distance, String timer, float alpha) {
        HudStyle.surface(x, y, w, ROW_HEIGHT, HudStyle.RADIUS_SM, alpha);
        HudStyle.dot(x + 5.0F, y + ROW_HEIGHT * 0.5F, 2.5F, HudStyle.accent(255.0F, alpha));

        float pillH = ROW_HEIGHT - 5.0F;
        float timerW = HudStyle.pillWidth(timer, HudStyle.NUM, HudStyle.SMALL_SIZE);
        float timerX = x + w - timerW - 2.5F;
        HudStyle.pill(timerX, y + 2.5F, pillH, timer, HudStyle.NUM, HudStyle.SMALL_SIZE,
                HudStyle.accent(40.0F, alpha), HudStyle.accent(255.0F, alpha));

        float distW = HudStyle.pillWidth(distance, HudStyle.NUM, HudStyle.TINY_SIZE);
        float distX = timerX - distW - 3.0F;
        HudStyle.pill(distX, y + 2.5F, pillH, distance, HudStyle.NUM, HudStyle.TINY_SIZE,
                HudStyle.white(14.0F, alpha), HudStyle.muted(alpha));

        float nameX = x + 10.0F;
        String visible = trimToWidth(name, TEXT_FONT, HudStyle.BODY_SIZE, distX - nameX - 4.0F);
        Render2D.text(TEXT_FONT, visible, nameX, y + (ROW_HEIGHT - HudStyle.BODY_SIZE) * 0.5F, HudStyle.BODY_SIZE, HudStyle.text(alpha));
    }

    private List<ServerHelper.HudEvent> events() {
        ModuleManager manager = Manager.getModules();
        if (manager == null) {
            return List.of();
        }
        return manager.getByType(ServerHelper.class)
                .map(ServerHelper::getHudEvents)
                .orElse(List.of());
    }

    private String distanceText(double distance) {
        if (!Double.isFinite(distance)) {
            return "0m";
        }
        return Math.max(0L, Math.round(distance)) + "m";
    }

    private record EventsState(List<ServerHelper.HudEvent> events, boolean preview, float alpha, float x, float y, float width, float height) {
    }
}
