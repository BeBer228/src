package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/**
 * Общий "движок" молний для TargetESP.
 * <p>
 * Что делает молнию красивой:
 * <ul>
 *     <li>сплошная лента (polyline) с miter-стыками, а не набор отдельных квадов — нет щелей и наложений на изломах;</li>
 *     <li>мягкие края (feather): у каждого слоя центр яркий, края уходят в прозрачность — свечение без "кирпичей";</li>
 *     <li>3 слоя: широкое цветное свечение → яркое тело → почти белый горячий сердечник;</li>
 *     <li>ширина в пикселях экрана (масштаб от дистанции), поэтому молния одинаково тонкая вблизи и не пропадает вдали;</li>
 *     <li>фрактальный зигзаг через midpoint displacement, "треск" — перегенерация формы каждые ~50 мс.</li>
 * </ul>
 */
final class LightningFx {

    /** Размер пикселя в мире на расстоянии 1 блок (≈ FOV 70, 1080p). */
    static final float PX_TO_WORLD = 0.0012f;

    private static final float GLOW_PX = 22.0f;
    private static final float BODY_PX = 6.5f;
    private static final float CORE_PX = 2.2f;

    private static int rngState = 1;

    private LightningFx() {
    }

    // ------------------------------------------------------------------ polyline

    static final class Line {
        float[] x = new float[64];
        float[] y = new float[64];
        float[] z = new float[64];
        /** множитель ширины в точке */
        float[] w = new float[64];
        /** множитель прозрачности в точке */
        float[] a = new float[64];
        int n;
        /** общая ширина/яркость линии (ветки тоньше и тусклее) */
        float width = 1.0f;
        float alpha = 1.0f;

        void reset() {
            n = 0;
            width = 1.0f;
            alpha = 1.0f;
        }

        void add(float px, float py, float pz) {
            if (n == x.length) {
                int size = n * 2;
                x = java.util.Arrays.copyOf(x, size);
                y = java.util.Arrays.copyOf(y, size);
                z = java.util.Arrays.copyOf(z, size);
                w = java.util.Arrays.copyOf(w, size);
                a = java.util.Arrays.copyOf(a, size);
            }
            x[n] = px;
            y[n] = py;
            z[n] = pz;
            w[n] = 1.0f;
            a[n] = 1.0f;
            n++;
        }

        /** Обрезает линию до доли {@code frac} длины (по точкам) — эффект "удара/роста" молнии. */
        void truncate(float frac) {
            if (n < 2 || frac >= 1.0f) {
                return;
            }
            if (frac <= 0.0f) {
                n = 0;
                return;
            }
            float f = frac * (n - 1);
            int k = (int) f;
            float t = f - k;
            if (k + 1 >= n) {
                return;
            }
            int j = k + 1;
            x[j] = x[k] + (x[j] - x[k]) * t;
            y[j] = y[k] + (y[j] - y[k]) * t;
            z[j] = z[k] + (z[j] - z[k]) * t;
            w[j] = w[k] + (w[j] - w[k]) * t;
            a[j] = a[k] + (a[j] - a[k]) * t;
            n = j + 1;
        }
    }

    // ------------------------------------------------------------------ random

    static void seed(int seed) {
        int s = seed * 0x9E3779B9 + 0x7F4A7C15;
        s ^= s >>> 16;
        rngState = s == 0 ? 1 : s;
    }

    /** xorshift → [-1, 1] */
    static float rnd() {
        int s = rngState;
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        rngState = s;
        return (s & 0xFFFFFF) / (float) 0x7FFFFF - 1.0f;
    }

    /** [0, 1] */
    static float rnd01() {
        return (rnd() + 1.0f) * 0.5f;
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Строит зигзаг через опорные точки (cx, cy, cz). Смещения идут вдоль поверхности
     * воображаемого цилиндра вокруг цели (+ немного радиально), поэтому молния "обнимает" тело.
     */
    static void jagged(Line out, float[] cx, float[] cy, float[] cz, int count, int levels, float roughness) {
        if (count < 2) {
            return;
        }
        out.add(cx[0], cy[0], cz[0]);
        for (int i = 0; i < count - 1; i++) {
            subdivide(out, cx[i], cy[i], cz[i], cx[i + 1], cy[i + 1], cz[i + 1], levels, roughness);
        }
    }

    private static void subdivide(Line out, float x1, float y1, float z1, float x2, float y2, float z2,
                                  int level, float roughness) {
        if (level <= 0) {
            out.add(x2, y2, z2);
            return;
        }
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float mx = (x1 + x2) * 0.5f;
        float my = (y1 + y2) * 0.5f;
        float mz = (z1 + z2) * 0.5f;
        if (len > 1.0E-4f) {
            // радиальное направление (от оси цели)
            float rl = (float) Math.sqrt(mx * mx + mz * mz);
            float rx = rl > 1.0E-4f ? mx / rl : 1.0f;
            float rz = rl > 1.0E-4f ? mz / rl : 0.0f;
            // s = d × r  (касательная к поверхности, перпендикулярная сегменту)
            float sx = dy * rz;
            float sy = dz * rx - dx * rz;
            float sz = -dy * rx;
            float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl > 1.0E-5f) {
                sx /= sl;
                sy /= sl;
                sz /= sl;
            } else {
                sx = 0.0f;
                sy = 1.0f;
                sz = 0.0f;
            }
            float u = rnd() * roughness * len;
            float v = rnd() * roughness * len * 0.35f;
            mx += sx * u + rx * v;
            my += sy * u;
            mz += sz * u + rz * v;
        }
        subdivide(out, x1, y1, z1, mx, my, mz, level - 1, roughness);
        subdivide(out, mx, my, mz, x2, y2, z2, level - 1, roughness);
    }

    /** Ветка от точки {@code idx} основной линии. */
    static void branch(Line out, Line main, int idx, float length, float outward) {
        int n = main.n;
        if (n < 3) {
            return;
        }
        int i0 = Math.max(0, idx - 1);
        int i1 = Math.min(n - 1, idx + 1);
        float tx = main.x[i1] - main.x[i0];
        float ty = main.y[i1] - main.y[i0];
        float tz = main.z[i1] - main.z[i0];
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl < 1.0E-5f) {
            return;
        }
        tx /= tl;
        ty /= tl;
        tz /= tl;
        float px = main.x[idx];
        float py = main.y[idx];
        float pz = main.z[idx];
        float rl = (float) Math.sqrt(px * px + pz * pz);
        float rx = rl > 1.0E-4f ? px / rl : 1.0f;
        float rz = rl > 1.0E-4f ? pz / rl : 0.0f;

        float bx = tx * 0.75f + rx * outward + rnd() * 0.55f;
        float by = ty * 0.75f + rnd() * 0.65f;
        float bz = tz * 0.75f + rz * outward + rnd() * 0.55f;
        float bl = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        if (bl < 1.0E-5f) {
            return;
        }
        float s = length / bl;
        float[] cx = {px, px + bx * s};
        float[] cy = {py, py + by * s};
        float[] cz = {pz, pz + bz * s};
        jagged(out, cx, cy, cz, 2, 3, 0.42f);
        for (int i = 0; i < out.n; i++) {
            float t = (float) i / (out.n - 1);
            out.w[i] = 1.0f - t * 0.92f;
            out.a[i] = 1.0f - t * t * 0.85f;
        }
    }

    /** Классический профиль: тонкие концы, полная толщина в середине. */
    static void taper(Line l, float endWidth) {
        for (int i = 0; i < l.n; i++) {
            float t = (float) i / Math.max(1, l.n - 1);
            float s = (float) Math.pow(Math.sin(Math.PI * t), 0.55);
            l.w[i] = endWidth + (1.0f - endWidth) * s;
        }
    }

    // ------------------------------------------------------------------ rendering

    static float pxScale(Vec3 cameraLocalPos, float centerY) {
        double dx = cameraLocalPos.x;
        double dy = cameraLocalPos.y - centerY;
        double dz = cameraLocalPos.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(2.0f, dist) * PX_TO_WORLD;
    }

    /**
     * Рисует набор линий 3 проходами (свечение → тело → сердечник) + искры в точках.
     * Проходы идут слоями для всех линий сразу, чтобы яркие сердечники всегда были поверх свечения.
     */
    static void draw(VertexConsumer c, PoseStack.Pose e, Vec3 cam, Line[] lines, int lineCount,
                     float[] sparks, int sparkCount, float px, int color, float alpha) {
        int glow = color & 0xFFFFFF;
        int body = mix(glow, 0xFFFFFF, 0.38f);
        int core = mix(glow, 0xFFFFFF, 0.88f);

        for (int i = 0; i < lineCount; i++) {
            ribbon(c, e, cam, lines[i], GLOW_PX * px * 0.5f, glow, alpha * 0.38f);
        }
        for (int i = 0; i < sparkCount; i++) {
            int k = i * 4;
            dot(c, e, cam, sparks[k], sparks[k + 1], sparks[k + 2], 16.0f * px, glow, alpha * 0.45f * sparks[k + 3]);
        }
        for (int i = 0; i < lineCount; i++) {
            ribbon(c, e, cam, lines[i], BODY_PX * px * 0.5f, body, alpha * 0.85f);
        }
        for (int i = 0; i < lineCount; i++) {
            ribbon(c, e, cam, lines[i], CORE_PX * px * 0.5f, core, alpha);
        }
        for (int i = 0; i < sparkCount; i++) {
            int k = i * 4;
            dot(c, e, cam, sparks[k], sparks[k + 1], sparks[k + 2], 4.5f * px, core, alpha * sparks[k + 3]);
        }
    }

    /**
     * Лента, развёрнутая к камере, с мягкими краями: центр = alpha, края = 0.
     * Порядок вершин такой же, как в исходном коде (лицевая сторона смотрит в камеру).
     */
    static void ribbon(VertexConsumer c, PoseStack.Pose e, Vec3 cam, Line l, float half, int rgb, float alpha) {
        int n = l.n;
        if (n < 2 || half <= 1.0E-5f || alpha * l.alpha <= 0.004f) {
            return;
        }
        float lw = half * l.width;
        float la = alpha * l.alpha;
        float prevSx = 0, prevSy = 0, prevSz = 0;
        boolean hasPrev = false;
        float pcx = 0, pcy = 0, pcz = 0, plx = 0, ply = 0, plz = 0, prx = 0, pry = 0, prz = 0;
        int pc = 0;
        int edge = rgb & 0xFFFFFF; // alpha = 0

        for (int i = 0; i < n; i++) {
            int i0 = Math.max(0, i - 1);
            int i1 = Math.min(n - 1, i + 1);
            float tx = l.x[i1] - l.x[i0];
            float ty = l.y[i1] - l.y[i0];
            float tz = l.z[i1] - l.z[i0];
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            float sx, sy, sz;
            float miter = 1.0f;
            if (tl < 1.0E-6f) {
                if (!hasPrev) {
                    continue;
                }
                sx = prevSx;
                sy = prevSy;
                sz = prevSz;
            } else {
                tx /= tl;
                ty /= tl;
                tz /= tl;
                float vx = (float) cam.x - l.x[i];
                float vy = (float) cam.y - l.y[i];
                float vz = (float) cam.z - l.z[i];
                sx = ty * vz - tz * vy;
                sy = tz * vx - tx * vz;
                sz = tx * vy - ty * vx;
                float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (sl < 1.0E-6f) {
                    if (!hasPrev) {
                        continue;
                    }
                    sx = prevSx;
                    sy = prevSy;
                    sz = prevSz;
                } else {
                    sx /= sl;
                    sy /= sl;
                    sz /= sl;
                }
                // miter: компенсируем сужение на изломе
                if (i > 0 && i < n - 1) {
                    float dx = l.x[i] - l.x[i - 1];
                    float dy = l.y[i] - l.y[i - 1];
                    float dz = l.z[i] - l.z[i - 1];
                    float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dl > 1.0E-6f) {
                        float cos = Math.abs((dx * tx + dy * ty + dz * tz) / dl);
                        miter = 1.0f / Math.max(0.55f, cos);
                    }
                }
            }
            if (hasPrev && sx * prevSx + sy * prevSy + sz * prevSz < 0.0f) {
                sx = -sx;
                sy = -sy;
                sz = -sz;
            }
            float h = lw * l.w[i] * miter;
            float cx = l.x[i], cy = l.y[i], cz = l.z[i];
            float lx = cx - sx * h, ly = cy - sy * h, lz = cz - sz * h;
            float rx = cx + sx * h, ry = cy + sy * h, rz = cz + sz * h;
            int col = argb(rgb, la * l.a[i]);

            if (hasPrev) {
                // левая половина: край(0) → центр
                c.addVertex(e, plx, ply, plz).setColor(edge);
                c.addVertex(e, pcx, pcy, pcz).setColor(pc);
                c.addVertex(e, cx, cy, cz).setColor(col);
                c.addVertex(e, lx, ly, lz).setColor(edge);
                // правая половина: центр → край(0)
                c.addVertex(e, pcx, pcy, pcz).setColor(pc);
                c.addVertex(e, prx, pry, prz).setColor(edge);
                c.addVertex(e, rx, ry, rz).setColor(edge);
                c.addVertex(e, cx, cy, cz).setColor(col);
            }
            pcx = cx; pcy = cy; pcz = cz;
            plx = lx; ply = ly; plz = lz;
            prx = rx; pry = ry; prz = rz;
            pc = col;
            prevSx = sx;
            prevSy = sy;
            prevSz = sz;
            hasPrev = true;
        }
    }

    /** Мягкая круглая искра (веер из квадов, центр яркий, край прозрачный), смотрит в камеру. */
    static void dot(VertexConsumer c, PoseStack.Pose e, Vec3 cam, float x, float y, float z,
                    float radius, int rgb, float alpha) {
        if (alpha <= 0.004f || radius <= 1.0E-5f) {
            return;
        }
        float fx = (float) cam.x - x;
        float fy = (float) cam.y - y;
        float fz = (float) cam.z - z;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1.0E-5f) {
            return;
        }
        fx /= fl;
        fy /= fl;
        fz /= fl;
        // u = up × f
        float ux = fz, uy = 0.0f, uz = -fx;
        float ul = (float) Math.sqrt(ux * ux + uz * uz);
        if (ul < 1.0E-4f) {
            ux = 1.0f;
            uz = 0.0f;
            ul = 1.0f;
        }
        ux /= ul;
        uz /= ul;
        // v = f × u
        float vx = fy * uz - fz * uy;
        float vy = fz * ux - fx * uz;
        float vz = fx * uy - fy * ux;

        int center = argb(rgb, alpha);
        int edge = rgb & 0xFFFFFF;
        final int sectors = 10;
        float pxp = x + ux * radius, pyp = y + uy * radius, pzp = z + uz * radius;
        for (int s = 1; s <= sectors; s++) {
            double ang = Math.PI * 2.0 * s / sectors;
            float cs = (float) Math.cos(ang) * radius;
            float sn = (float) Math.sin(ang) * radius;
            float qx = x + ux * cs + vx * sn;
            float qy = y + uy * cs + vy * sn;
            float qz = z + uz * cs + vz * sn;
            c.addVertex(e, x, y, z).setColor(center);
            c.addVertex(e, pxp, pyp, pzp).setColor(edge);
            c.addVertex(e, qx, qy, qz).setColor(edge);
            c.addVertex(e, qx, qy, qz).setColor(edge);
            pxp = qx;
            pyp = qy;
            pzp = qz;
        }
    }

    // ------------------------------------------------------------------ color / math

    static int argb(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    static int mix(int from, int to, float p) {
        p = clamp(p, 0.0f, 1.0f);
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        return (Math.round(fr + (tr - fr) * p) << 16)
                | (Math.round(fg + (tg - fg) * p) << 8)
                | Math.round(fb + (tb - fb) * p);
    }

    /** Цвет с учётом удара: основной → второй цвет. */
    static int hitColor(TargetEspRenderContext ctx) {
        float hurt = clamp(ctx.hurtProgress(), 0.0f, 1.0f);
        float mixK = clamp((float) Math.pow(hurt, 0.5) + ctx.chainImpactProgress() * 0.25f, 0.0f, 1.0f);
        return mix(ctx.primaryColor(), ctx.secondaryColor(), mixK);
    }

    static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    static float smooth(float x) {
        float v = clamp(x, 0.0f, 1.0f);
        return v * v * (3.0f - 2.0f * v);
    }
}
