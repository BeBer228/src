package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/**
 * Маленький помощник поверх {@link LightningFx}: пул неоновых линий + искры.
 * Позволяет рисовать любые светящиеся фигуры (круги, спирали, хвосты комет)
 * тем же красивым 3-слойным свечением, что и у молний.
 */
final class NeonKit {
    private final LightningFx.Line[] lines;
    private int lineCount;
    private float[] sparks = new float[4 * 64];
    private int sparkCount;

    NeonKit(int capacity) {
        lines = new LightningFx.Line[capacity];
        for (int i = 0; i < capacity; i++) {
            lines[i] = new LightningFx.Line();
        }
    }

    void begin() {
        lineCount = 0;
        sparkCount = 0;
    }

    /** Новая пустая линия или null, если пул закончился. */
    LightningFx.Line line(float width, float alpha) {
        if (lineCount >= lines.length) {
            return null;
        }
        LightningFx.Line l = lines[lineCount++];
        l.reset();
        l.width = width;
        l.alpha = alpha;
        return l;
    }

    void spark(float x, float y, float z, float a) {
        if (a <= 0.01f) {
            return;
        }
        if ((sparkCount + 1) * 4 > sparks.length) {
            sparks = java.util.Arrays.copyOf(sparks, sparks.length * 2);
        }
        int k = sparkCount * 4;
        sparks[k] = x;
        sparks[k + 1] = y;
        sparks[k + 2] = z;
        sparks[k + 3] = Math.min(a, 1.0f);
        sparkCount++;
    }

    boolean isEmpty() {
        return lineCount == 0 && sparkCount == 0;
    }

    void flush(VertexConsumer c, PoseStack.Pose e, Vec3 cam, float px, int color, float alpha) {
        if (isEmpty()) {
            return;
        }
        LightningFx.draw(c, e, cam, lines, lineCount, sparks, sparkCount, px, color, alpha);
    }

    // ------------------------------------------------------------ shapes

    /** Горизонтальный круг (замкнутый). */
    static void circle(LightningFx.Line l, float y, float r, int segments, float startAngle) {
        if (l == null) {
            return;
        }
        for (int i = 0; i <= segments; i++) {
            double a = startAngle + Math.PI * 2.0 * i / segments;
            l.add((float) Math.cos(a) * r, y, (float) Math.sin(a) * r);
        }
    }

    /** Плавно гасит концы линии (хвост/голова). */
    static void fadeEnds(LightningFx.Line l, float power) {
        if (l == null) {
            return;
        }
        for (int i = 0; i < l.n; i++) {
            float t = (float) i / Math.max(1, l.n - 1);
            l.a[i] = (float) Math.pow(Math.sin(Math.PI * t), power);
        }
    }

    /** Хвост кометы: последняя точка = голова (яркая и толстая), начало = прозрачный кончик. */
    static void cometTail(LightningFx.Line l) {
        if (l == null) {
            return;
        }
        for (int i = 0; i < l.n; i++) {
            float t = (float) i / Math.max(1, l.n - 1);
            l.w[i] = 0.12f + 0.88f * t;
            l.a[i] = t * t;
        }
    }

    static float px(Vec3 cam, float centerY) {
        return LightningFx.pxScale(cam, centerY);
    }
}
