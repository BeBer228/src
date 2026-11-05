package releon.ru.api.drag.impl;

import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.List;

public final class MediaPlayer extends HudPanel {
    private static final float WIDTH = 152.0F;
    private static final float HEIGHT = 50.0F;
    private static final float TEXT_X = 51.0F;
    private static final float TEXT_W = WIDTH - TEXT_X - 7.0F;
    private static final float CONTROLS_Y = 31.0F;
    private static final float CONTROL_GAP = 13.0F;

    private MediaInfo media = MediaInfo.empty();
    private Object session;
    private long lastPollMs;
    private boolean iconPulseWasPlaying;
    private long iconPulseStartedAt;
    private float smoothedIconAlpha = 100.0F;
    private float smoothProgress;
    private final float[] eqHeights = new float[3];
    private final SmoothAnimation panelAnimation = new SmoothAnimation();

    public MediaPlayer() {
        super("mediaplayer", "MediaPlayer", 10.0F, 120.0F, WIDTH, HEIGHT);
    }

    @Override
    public void render() {
        MediaPlayerState state = logics();
        if (state == null) {
            return;
        }
        renderMediaPlayer(state);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event == null || event.button() != 0 || !drag.visible() || session == null) {
            return false;
        }

        float controlsCenterX = drag.x() + TEXT_X + TEXT_W * 0.5F;
        float controlsY = drag.y() + CONTROLS_Y;
        float hitHalf = 6.0F;

        if (hit(event, controlsCenterX - CONTROL_GAP, controlsY, hitHalf)) {
            invokeSession("previous");
            return true;
        }
        if (hit(event, controlsCenterX, controlsY, hitHalf)) {
            invokeSession("playPause");
            return true;
        }
        if (hit(event, controlsCenterX + CONTROL_GAP, controlsY, hitHalf)) {
            invokeSession("next");
            return true;
        }
        return false;
    }

    private MediaPlayerState logics() {
        pollMedia();
        boolean preview = media.isEmpty() && editPreview();
        boolean visible = !media.isEmpty() || preview;

        panelAnimation.update();
        panelAnimation.run(visible ? 1.0 : 0.0, 0.24F, visible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);
        float alpha = panelAnimation.get();
        contentVisible(visible || alpha > 0.01F || panelAnimation.isAlive());
        if (alpha <= 0.0F && !panelAnimation.isAlive()) {
            return null;
        }

        MediaInfo shown = preview ? new MediaInfo("No track", "No artist", 0L, 0L, false) : media;
        size(WIDTH, HEIGHT);

        return new MediaPlayerState(shown, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderMediaPlayer(MediaPlayerState state) {
        float x = state.x;
        float y = state.y;
        float alpha = state.alpha;

        HudStyle.glass(x, y, WIDTH, HEIGHT, alpha);

        // ------------------------------------------------ artwork
        float art = HEIGHT - 10.0F;
        float artX = x + 5.0F;
        float artY = y + 5.0F;
        int iconAlpha = resolveMusicIconAlpha(state.media.playing, alpha);
        float glowK = iconAlpha / 255.0F;
        HudStyle.glow(artX - 10.0F, artY - 10.0F, art + 20.0F, art + 20.0F, HudStyle.accent2(90.0F * glowK, 1.0F));
        Render2D.gradientRect(artX, artY, art, art, 5.0F, 5.0F, 5.0F, 5.0F,
                HudStyle.accent(210.0F, alpha), HudStyle.accent2(210.0F, alpha),
                HudStyle.accent2(150.0F, alpha), HudStyle.accent(150.0F, alpha));
        Render2D.outline(artX, artY, art, art, 5.0F, HudStyle.STROKE, HudStyle.white(50.0F, alpha));
        drawIconCentered("u", artX + art * 0.5F, artY + art * 0.5F - 9.0F, 18.0F, HudStyle.white(255.0F, alpha * (0.55F + 0.45F * glowK)));
        renderEqualizer(artX + art - 11.0F, artY + art - 4.0F, state.media.playing, alpha);

        // ------------------------------------------------ text
        float textX = x + TEXT_X;
        float textW = TEXT_W;
        String title = trimToWidth(safe(state.media.title), TITLE_FONT, 6.5F, textW);
        String artist = trimToWidth(safe(state.media.artist), TEXT_FONT, 5.5F, textW);
        Render2D.text(TITLE_FONT, title, textX, y + 6.5F, 6.5F, HudStyle.text(alpha));
        Render2D.text(TEXT_FONT, artist, textX, y + 15.0F, 5.5F, HudStyle.muted(alpha));

        // ------------------------------------------------ controls
        float cx = textX + textW * 0.5F;
        float cy = y + CONTROLS_Y;
        float play = 11.0F;
        HudStyle.glow(cx - play, cy - play, play * 2.0F, play * 2.0F, HudStyle.accent(80.0F, alpha));
        Render2D.gradientRect(cx - play * 0.5F, cy - play * 0.5F, play, play, play * 0.5F, play * 0.5F, play * 0.5F, play * 0.5F,
                HudStyle.accent(255.0F, alpha), HudStyle.accent2(255.0F, alpha),
                HudStyle.accent2(255.0F, alpha), HudStyle.accent(255.0F, alpha));
        drawIconCentered(state.media.playing ? "p" : "o", cx, cy - 3.0F, 6.0F, HudStyle.white(255.0F, alpha));
        drawIconCentered("s", cx - CONTROL_GAP, cy - 2.5F, 5.5F, HudStyle.text(alpha * 0.85F));
        drawIconCentered("n", cx + CONTROL_GAP, cy - 2.5F, 5.5F, HudStyle.text(alpha * 0.85F));

        // ------------------------------------------------ progress
        int duration = safeDuration(state.media);
        int position = safePosition(state.media, duration);
        float target = clamp(position / (float) duration, 0.0F, 1.0F);
        smoothProgress = smoothProgress + (target - smoothProgress) * 0.2F;
        if (Math.abs(target - smoothProgress) > 0.5F) {
            smoothProgress = target;
        }

        String posText = formatDuration(position);
        String durText = formatDuration(duration);
        Render2D.text(HudStyle.NUM, posText, textX, cy - 2.5F, HudStyle.TINY_SIZE, HudStyle.muted(alpha));
        HudStyle.textRight(HudStyle.NUM, durText, textX + textW, cy - 2.5F, HudStyle.TINY_SIZE, HudStyle.muted(alpha));

        HudStyle.progress(textX, y + HEIGHT - 8.0F, textW, 2.0F, smoothProgress, alpha);
    }

    private void renderEqualizer(float x, float bottom, boolean playing, float alpha) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            float wave = playing
                    ? 0.35F + 0.65F * (float) Math.abs(Math.sin(now / (180.0D + i * 55.0D) + i * 1.7D))
                    : 0.25F;
            eqHeights[i] += (wave - eqHeights[i]) * 0.25F;
            float h = 1.5F + 5.0F * eqHeights[i];
            Render2D.rect(x + i * 2.6F, bottom - h, 1.6F, h, 0.8F, HudStyle.white(230.0F, alpha));
        }
    }

    private void pollMedia() {
        if (System.currentTimeMillis() - lastPollMs < 350L) {
            return;
        }
        lastPollMs = System.currentTimeMillis();

        try {
            Class<?> api = Class.forName("dev.redstones.mediaplayerinfo.MediaPlayerInfo");
            Object instance = api.getField("Instance").get(null);
            Object result = instance.getClass().getMethod("getMediaSessions").invoke(instance);

            if (!(result instanceof List<?> sessions) || sessions.isEmpty()) {
                session = null;
                media = MediaInfo.empty();
                return;
            }

            Object chosen = null;
            Object chosenMedia = null;
            for (Object candidate : sessions) {
                Object candidateMedia = mediaObject(candidate);
                if (candidateMedia == null) {
                    continue;
                }
                if (chosen == null) {
                    chosen = candidate;
                    chosenMedia = candidateMedia;
                }
                if (bool(candidateMedia, "getPlaying")) {
                    chosen = candidate;
                    chosenMedia = candidateMedia;
                    break;
                }
            }

            if (chosenMedia == null) {
                session = null;
                media = MediaInfo.empty();
                return;
            }

            session = chosen;
            media = new MediaInfo(
                    string(chosenMedia, "getTitle", "No track"),
                    string(chosenMedia, "getArtist", "No artist"),
                    number(chosenMedia, "getPosition"),
                    number(chosenMedia, "getDuration"),
                    bool(chosenMedia, "getPlaying")
            );
        } catch (Throwable t) {
            session = null;
            media = MediaInfo.empty();
        }
    }

    private Object mediaObject(Object session) {
        if (session == null) {
            return null;
        }
        try {
            return session.getClass().getMethod("getMedia").invoke(session);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void invokeSession(String method) {
        if (session == null) {
            return;
        }
        try {
            session.getClass().getMethod(method).invoke(session);
            lastPollMs = 0L;
        } catch (Throwable ignored) {
        }
    }

    private boolean hit(MouseButtonEvent event, float centerX, float centerY, float half) {
        return event.x() >= centerX - half && event.x() <= centerX + half && event.y() >= centerY - half && event.y() <= centerY + half;
    }

    private void drawIconCentered(String icon, float centerX, float y, float size, int color) {
        float width = Render2D.textWidth(FontType.MAINMENUSCREEN, icon, size);
        Render2D.text(FontType.MAINMENUSCREEN, icon, centerX - width * 0.5F, y, size, color);
    }

    private String formatDuration(int seconds) {
        int safe = Math.max(0, seconds);
        return twoDigits(safe / 60) + ":" + twoDigits(safe % 60);
    }

    private int safeDuration(MediaInfo info) {
        return Math.max(1, (int) (info == null ? 0L : info.duration));
    }

    private int safePosition(MediaInfo info, int duration) {
        int position = Math.max(0, (int) (info == null ? 0L : info.position));
        return Math.min(position, Math.max(1, duration));
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private String string(Object object, String method, String fallback) {
        try {
            Object value = object.getClass().getMethod(method).invoke(object);
            String text = value == null ? "" : value.toString();
            return text.isBlank() ? fallback : text;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private long number(Object object, String method) {
        try {
            Object value = object.getClass().getMethod(method).invoke(object);
            return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean bool(Object object, String method) {
        try {
            Object value = object.getClass().getMethod(method).invoke(object);
            return value instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int resolveMusicIconAlpha(boolean playing, float widgetAlpha) {
        long now = System.currentTimeMillis();
        if (playing != iconPulseWasPlaying) {
            iconPulseWasPlaying = playing;
            iconPulseStartedAt = now;
        }

        float targetAlpha;
        if (!playing) {
            targetAlpha = 100.0F;
        } else {
            long elapsed = Math.max(0L, now - iconPulseStartedAt);
            double phase = ((elapsed % 1800L) / 1800.0D) * (Math.PI * 2.0D);
            double wave = Math.sin(phase - Math.PI / 2.0D);
            targetAlpha = 150.0F + (float) (50.0D * wave);
        }

        smoothedIconAlpha += (targetAlpha - smoothedIconAlpha) * 0.14F;
        if (Math.abs(targetAlpha - smoothedIconAlpha) < 0.5F) {
            smoothedIconAlpha = targetAlpha;
        }
        return Math.round(clamp(smoothedIconAlpha * widgetAlpha, 0.0F, 255.0F));
    }

    private record MediaInfo(String title, String artist, long position, long duration, boolean playing) {
        private static MediaInfo empty() {
            return new MediaInfo("", "", 0L, 0L, false);
        }

        private boolean isEmpty() {
            return title == null || title.isBlank();
        }
    }

    private record MediaPlayerState(MediaInfo media, float alpha, float x, float y, float width, float height) {
    }
}
