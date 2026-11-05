package releon.ru.utils.repository.gps;

public final class GpsUtils {
    private static volatile GpsTarget target;

    private GpsUtils() {
    }

    public static void set(double x, double z) {
        target = new GpsTarget(x, z);
    }

    public static void clear() {
        target = null;
    }

    public static boolean isActive() {
        return target != null;
    }

    public static GpsTarget get() {
        return target;
    }

    public record GpsTarget(double x, double z) {
    }
}