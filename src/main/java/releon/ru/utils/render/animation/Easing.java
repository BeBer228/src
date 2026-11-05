package releon.ru.utils.render.animation;

@FunctionalInterface
public interface Easing {
    double ease(double value);

    default float apply(float value) {
        return (float) ease(value);
    }
}