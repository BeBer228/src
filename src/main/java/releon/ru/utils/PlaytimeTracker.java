package releon.ru.utils;

public final class PlaytimeTracker {

    private static final long START_TIME = System.currentTimeMillis();

    private PlaytimeTracker() {
    }

    public static String getFormatted() {
        long elapsed = System.currentTimeMillis() - START_TIME;
        long totalSeconds = elapsed / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
}