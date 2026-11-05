package releon.ru.api.module.impl.visual;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import releon.ru.utils.render.*;
import releon.ru.utils.render.color.ClientTheme;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ghosts, Lightning, Circle, Crystals and Chains target indicators for {@link PVPVisual}. Positions are camera-relative. */
final class TargetEspModes {

    private static final Identifier TEX_GLOW = Identifier.fromNamespaceAndPath("include", "textures/targetesp/glow.png");
    private static final Identifier TEX_CHAIN = Identifier.fromNamespaceAndPath("include", "textures/targetesp/chain.png");
    private static final int HIT_COLOR = 0xFFFF4040;

    private static final RenderPipeline SCAN=IncludeWorldMesh.pipeline("pvp/scan","target_scan",true,true,true,false,false,"ScanParams");
    private static final Vector3f[] OCTA = {
            new Vector3f(0, 1.5f, 0), new Vector3f(0, -1.5f, 0), new Vector3f(1, 0, 0),
            new Vector3f(-1, 0, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)};
    private static final int[][] OCTA_FACES = {{0, 2, 4}, {0, 4, 3}, {0, 3, 5}, {0, 5, 2}, {1, 4, 2}, {1, 3, 4}, {1, 5, 3}, {1, 2, 5}};
    private static final float[] OCTA_SHADE = {1.0f, 0.8f, 0.6f, 0.9f, 0.7f, 0.5f, 0.4f, 0.6f};

    private final PVPVisual v;
    private final Minecraft mc = Minecraft.getInstance();
    private final Vector3f right = new Vector3f();
    private final Vector3f up = new Vector3f();
    private final Vector3f tmp = new Vector3f();
    private final Map<Integer, Float> projectilePresence = new HashMap<>();
    private long lastNanos = System.nanoTime();
    private float dt;
    private float time;
    private float hit;
    private float chainSpin;
    private float chainHit;
    private float lightningTime;

    // Bolt scratch: flattened segments (ax, ay, az, bx, by, bz, widthFactor).
    private final List<float[]> bolt = new ArrayList<>();

    TargetEspModes(PVPVisual visual) {
        this.v = visual;
    }

    // ------------------------------------------------------------------ frame state

    /** Advances the shared clock once per frame; {@code target} drives the hit flash (may be null). */
    void frame(Camera camera, LivingEntity target) {
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastNanos) * 1.0e-9f);
        lastNanos = now;
        time += dt;
        hit = target != null && target.hurtTime > 0 ? target.hurtTime / 10.0f : Math.max(0.0f, hit - dt * 6.0f);
        var q = camera.rotation();
        right.set(1, 0, 0).rotate(q);
        up.set(0, 1, 0).rotate(q);
    }

    void reset() {
        projectilePresence.clear();
        hit = 0.0f;
        chainHit = 0.0f;
        chainSpin = 0.0f;
    }

    private int tint(float angleDeg, float amount) {
        int base = v.colorMode.is("Theme")
                ? ClientTheme.wave(700,angleDeg/360f,255)
                : v.getColor();
        return PVPVisual.blendColors(base | 0xFF000000, HIT_COLOR, amount);
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float easeInOutQuad(float x) {
        return x < 0.5f ? 2.0f * x * x : 1.0f - (float) Math.pow(-2.0f * x + 2.0f, 2.0) / 2.0f;
    }

    /** Integer hash noise in [-1, 1). */
    private static float noise(int a, int b, int c) {
        int h = a * 668265261 + b * 374761393 + c * -1640531535;
        h ^= h >>> 15;
        h *= -2048144789;
        h ^= h >>> 13;
        return (h >>> 8) / 8388608.0f - 1.0f;
    }

    private static float noise(int a, int b, int c, float t) {
        return Mth.lerp(t * t * (3.0f - 2.0f * t), noise(a, b, c), noise(a, b, c + 1));
    }

    private static void draw(MeshData mesh, RenderPipeline pipeline, String label, Identifier texture, boolean repeat) {
        if (mesh != null) PVPVisual.drawMesh(mesh, pipeline, label, texture == null ? null : PVPVisual.boundTexture(texture), repeat);
    }

    private static void cv(BufferBuilder m, float x, float y, float z, int color) {
        m.addVertex(x, y, z).setColor(PVPVisual.multiplyAlpha(color, PVPVisual.targetVertexAlpha));
    }

    private static void cquad(BufferBuilder m, float[] a, int ca, float[] b, int cb, float[] c, int cc, float[] d, int cd) {
        cv(m, a[0], a[1], a[2], ca);
        cv(m, b[0], b[1], b[2], cb);
        cv(m, c[0], c[1], c[2], cc);
        cv(m, a[0], a[1], a[2], ca);
        cv(m, c[0], c[1], c[2], cc);
        cv(m, d[0], d[1], d[2], cd);
    }

    private static void uvQuad(BufferBuilder m, float[] a, float[] b, float[] c, float[] d, float u0, float u1, int color) {
        color = PVPVisual.multiplyAlpha(color, PVPVisual.targetVertexAlpha);
        m.addVertex(a[0], a[1], a[2]).setUv(u0, 0f).setColor(color);
        m.addVertex(b[0], b[1], b[2]).setUv(u1, 0f).setColor(color);
        m.addVertex(c[0], c[1], c[2]).setUv(u1, 1f).setColor(color);
        m.addVertex(a[0], a[1], a[2]).setUv(u0, 0f).setColor(color);
        m.addVertex(c[0], c[1], c[2]).setUv(u1, 1f).setColor(color);
        m.addVertex(d[0], d[1], d[2]).setUv(u0, 1f).setColor(color);
    }

    private void glow(BufferBuilder m, float x, float y, float z, float size, int color) {
        PVPVisual.emitBillboardQuad(m, x, y, z, right, up, size, color);
    }

    // ------------------------------------------------------------------ Ghosts

    void ghosts(LivingEntity t, float cx, float cy, float cz) {
        float width = t.getBbWidth();
        float baseY = t.getBbHeight() / 2.0f + 0.2f;
        double sec = System.currentTimeMillis() / 1000.0;
        double spin = sec * 3.0 % (Math.PI * 2);
        double step = Math.toRadians(60.0) / 14;
        // wide soft halo first, then the sharp bodies, then a hot white core on the leading ghost of each tail
        for (int pass = 0; pass < 3; pass++) {
            float sizeMul = pass == 0 ? 2.6f : pass == 1 ? 1.0f : 0.42f;
            float alphaMul = pass == 0 ? 0.16f : pass == 1 ? 1.0f : 0.55f;
            int whiten = pass == 2 ? 1 : 0;
            MeshData mesh = PVPVisual.buildTextured(m -> {
                for (int i = 0; i < 4; i++) {
                    double t0 = sec + i * 15.0;
                    double phase = i * (Math.PI / 2);
                    for (int j = whiten == 1 ? 10 : 0; j < 15; j++) {
                        double a = j * step;
                        double ang = a + spin + phase;
                        int color = tint(j * 14, hit);
                        if (whiten == 1) color = PVPVisual.blendColors(color, 0xFFFFFFFF, 0.6f);
                        glow(m, (float) (cx + width * Math.cos(ang)), (float) (cy + baseY + Math.sin(t0 + a + i) * 0.7),
                                (float) (cz + width * Math.sin(ang)), Mth.lerp(j / 14.0f, 0.2f, 0.6f) / 2.0f * sizeMul,
                                PVPVisual.multiplyAlpha(color, alphaMul));
                    }
                }
            });
            draw(mesh, PVPVisual.TEX_ADDITIVE, "include:pvp_ghosts", TEX_GLOW, false);
        }
        bloom(t, cx, cy, cz, 0.8f);
    }

    /** Large soft light around the target's body, tinted like the indicator. */
    private void bloom(LivingEntity t, float cx, float cy, float cz, float strength) {
        float h = t.getBbHeight();
        float pulse = 0.88f + 0.12f * Mth.sin(time * 2.4f);
        int color = PVPVisual.multiplyAlpha(tint(time * 40.0f, hit), (0.10f + 0.10f * hit) * strength * pulse);
        draw(PVPVisual.buildTextured(m -> glow(m, cx, cy + h * 0.5f, cz, h * 0.95f, color)),
                PVPVisual.TEX_ADDITIVE, "include:pvp_bloom", TEX_GLOW, false);
    }

    // ------------------------------------------------------------------ Circle

    void circle(LivingEntity t, float cx, float cy, float cz, double distance) {
        long ms = System.currentTimeMillis() % 1750L;
        float dir = ms > 875L ? -1.0f : 1.0f;
        float u = dir < 0.0f ? ms / 875.0f - 1.0f : 1.0f - ms / 875.0f;
        float e = easeInOutQuad(u);
        float full = t.getBbHeight() + 0.15f;
        float radius = t.getBbWidth() * 0.8f;
        float half = t.getBbHeight() * 0.006f;
        float y = cy + full * e;
        float tail = full * 0.8f * Math.min(e, 1.0f - e) * dir;
        int main = tint(0, hit);
        int mid = PVPVisual.multiplyAlpha(main, 0.55f);
        int end = PVPVisual.replaceAlpha(main, 0);
        int stepDeg = distance > 24.0 ? 6 : 3; // far targets need fewer ring segments
        MeshData mesh = PVPVisual.build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, m -> {
            for (int deg = 0; deg < 360; deg += stepDeg) {
                float c0 = Mth.cos((float) Math.toRadians(deg)), s0 = Mth.sin((float) Math.toRadians(deg));
                float c1 = Mth.cos((float) Math.toRadians(deg + stepDeg)), s1 = Mth.sin((float) Math.toRadians(deg + stepDeg));
                cquad(m, new float[]{cx + c0 * (radius - half), y, cz + s0 * (radius - half)}, main,
                        new float[]{cx + c1 * (radius - half), y, cz + s1 * (radius - half)}, main,
                        new float[]{cx + c1 * (radius + half), y, cz + s1 * (radius + half)}, main,
                        new float[]{cx + c0 * (radius + half), y, cz + s0 * (radius + half)}, main);
                cquad(m, new float[]{cx + c0 * radius, y, cz + s0 * radius}, mid,
                        new float[]{cx + c1 * radius, y, cz + s1 * radius}, mid,
                        new float[]{cx + c1 * radius, y + tail, cz + s1 * radius}, end,
                        new float[]{cx + c0 * radius, y + tail, cz + s0 * radius}, end);
            }
        });
        draw(mesh, PVPVisual.COLOR_ADDITIVE, "include:pvp_circle", null, false);
        // glowing head of the ring
        int dot = PVPVisual.multiplyAlpha(main, 0.22f);
        draw(PVPVisual.buildTextured(m -> {
            for (int deg = 0; deg < 360; deg += 9) {
                float r = (float) Math.toRadians(deg);
                glow(m, cx + Mth.cos(r) * radius, y, cz + Mth.sin(r) * radius, radius * 0.22f, dot);
            }
        }), PVPVisual.TEX_ADDITIVE, "include:pvp_circle_glow", TEX_GLOW, false);
        bloom(t, cx, cy, cz, 0.6f);
    }

    // ------------------------------------------------------------------ Crystals

    void crystals(LivingEntity t, float cx, float cy, float cz) {
        long ms = System.currentTimeMillis();
        float vis = PVPVisual.targetVertexAlpha;
        float radius = (1.2f - 0.5f * vis) * t.getBbWidth() * 1.5f;
        float height = t.getBbHeight();
        int base = tint(0, hit);
        int dark = PVPVisual.replaceAlpha(0, base >>> 24);
        Quaternionf q = new Quaternionf();
        Vector3f dirTo = new Vector3f();
        Vector3f unitY = new Vector3f(0, 1, 0);
        List<float[]> centers = new ArrayList<>();
        MeshData mesh = PVPVisual.build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, m -> {
            for (int deg = 0; deg < 360; deg += 20) {
                float angle = (float) Math.toRadians(deg + (float) ((ms + (long) (7333.0f * (deg / 360.0f))) % 7333L) / 7333.0f * 360.0f);
                float px = Mth.sin(angle) * radius;
                float pz = Mth.cos(angle) * radius;
                float py = 0.1f + height * easeInOutQuad(Math.abs(Mth.sin(deg)));
                centers.add(new float[]{cx + px, cy + py, cz + pz});
                q.identity().rotationTo(unitY, dirTo.set(-px, height * 0.5f - py, -pz).normalize())
                        .rotateY((float) Math.toRadians(((float) (ms % 1200L) / 1200.0f * 360.0f + deg * 13.7f) % 360.0f));
                for (int f = 0; f < OCTA_FACES.length; f++) {
                    int color = PVPVisual.blendColors(base, dark, 1.0f - OCTA_SHADE[f]);
                    for (int k = 0; k < 3; k++) {
                        q.transform(tmp.set(OCTA[OCTA_FACES[f][k]]).mul(0.1f));
                        cv(m, cx + px + tmp.x, cy + py + tmp.y, cz + pz + tmp.z, color);
                    }
                }
            }
        });
        draw(mesh, PVPVisual.COLOR_ADDITIVE, "include:pvp_crystals", null, false);

        int glowColor = PVPVisual.multiplyAlpha(base, 0.28f);
        int haloColor = PVPVisual.multiplyAlpha(base, 0.1f);
        int sparkColor = PVPVisual.multiplyAlpha(PVPVisual.blendColors(base, 0xFFFFFFFF, 0.7f), 0.5f);
        draw(PVPVisual.buildTextured(m -> {
            for (float[] c : centers) {
                glow(m, c[0], c[1], c[2], 1.1f, haloColor);
                glow(m, c[0], c[1], c[2], 0.5f, glowColor);
                glow(m, c[0], c[1], c[2], 0.16f, sparkColor);
            }
        }), PVPVisual.TEX_ADDITIVE, "include:pvp_crystals_glow", TEX_GLOW, false);
        bloom(t, cx, cy, cz, 0.7f);
    }

    // ------------------------------------------------------------------ Chains

    void chains(LivingEntity t, float cx, float cy, float cz) {
        chainSpin = (chainSpin + 75.0f * dt) % 720.0f;
        float vis = PVPVisual.targetVertexAlpha;
        // the second, counter-rotating chain joins once the first has fully faded in
        chainHit += ((vis >= 0.95f ? 1.0f : 0.0f) - chainHit) * (1.0f - (float) Math.exp(-dt / 0.2f));
        float s = Mth.sin((float) Math.toRadians(chainSpin));
        float aZ = 20.0f * Math.min(1.0f + s, 1.0f);
        float aX = 20.0f * s;
        float baseRadius = t.getBbWidth() * 1.5f;
        float y0 = cy + t.getBbHeight() * 0.5f - 0.5f;
        Quaternionf q = new Quaternionf();

        // link corners (p0..p3), colour and the u range of the chain texture for every segment of both chains
        List<float[][]> links = new ArrayList<>();
        List<float[]> linkU = new ArrayList<>();
        List<Integer> linkColors = new ArrayList<>();
        for (int idx = 0; idx < 2; idx++) {
            float presence = idx == 0 ? 1.0f : chainHit;
            if (presence < 0.0039f) continue;
            float radius = baseRadius * (1.2f - 0.5f * (idx == 0 ? vis : chainHit));
            float ox = (idx == 0 ? aZ : -aZ) / 100.0f;
            float oz = (idx == 0 ? -aX : aX) / 100.0f;
            q.identity().rotateZ((float) Math.toRadians(idx == 0 ? aZ : -aZ)).rotateX((float) Math.toRadians(idx == 0 ? aX : -aX));
            for (float a = 22.5f; a <= 720.0f; a += 22.5f) {
                float a0 = (float) Math.toRadians(a - 22.5f + chainSpin * 0.5f);
                float a1 = (float) Math.toRadians(a + chainSpin * 0.5f);
                links.add(new float[][]{
                        chainPoint(q, ox + Mth.sin(a0) * radius, 0.0f, oz + Mth.cos(a0) * radius, cx, y0, cz),
                        chainPoint(q, ox + Mth.sin(a1) * radius, 0.0f, oz + Mth.cos(a1) * radius, cx, y0, cz),
                        chainPoint(q, ox + Mth.sin(a1) * radius, 1.0f, oz + Mth.cos(a1) * radius, cx, y0, cz),
                        chainPoint(q, ox + Mth.sin(a0) * radius, 1.0f, oz + Mth.cos(a0) * radius, cx, y0, cz)});
                linkU.add(new float[]{(a - 22.5f) / 360.0f * 3.0f, a / 360.0f * 3.0f});
                linkColors.add(PVPVisual.multiplyAlpha(tint(a * 3.0f, hit), presence));
            }
        }
        draw(PVPVisual.buildTextured(m -> {
            for (int i = 0; i < links.size(); i++) {
                float[][] c = links.get(i);
                uvQuad(m, c[0], c[1], c[2], c[3], 0.0f, 1.0f, PVPVisual.multiplyAlpha(linkColors.get(i), 0.22f));
            }
        }), PVPVisual.TEX_ADDITIVE, "include:pvp_chains_glow", TEX_GLOW, false);
        MeshData mesh = PVPVisual.buildTextured(m -> {
            for (int i = 0; i < links.size(); i++) {
                float[][] c = links.get(i);
                uvQuad(m, c[0], c[1], c[2], c[3], linkU.get(i)[0], linkU.get(i)[1], linkColors.get(i));
            }
        });
        draw(mesh, PVPVisual.TEX_ADDITIVE, "include:pvp_chains", TEX_CHAIN, true);
        bloom(t, cx, cy, cz, 0.7f);
    }

    private float[] chainPoint(Quaternionf q, float x, float y, float z, float cx, float cy, float cz) {
        q.transform(tmp.set(x, y, z));
        return new float[]{cx + tmp.x, cy + tmp.y, cz + tmp.z};
    }

    // ------------------------------------------------------------------ Lightning on the target

    void lightning(LivingEntity t, float cx, float cy, float cz) {
        lightningTime += dt;
        int cycle = (int) lightningTime;
        float cycleFrac = lightningTime - cycle;
        float height = t.getBbHeight();
        float radius = t.getBbWidth() * 0.75f + 0.16f;
        float halfWidth = height * 0.005f;
        float glowSize = height * 0.09f;

        List<float[]> segments = new ArrayList<>();
        List<int[]> colors = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            float u = lightningTime * 1.3f + i / 3.0f;
            int seg = (int) Math.floor(u);
            float frac = u - seg;
            float alpha = Mth.clamp(frac / 0.08f, 0.0f, 1.0f) * (1.0f - Mth.clamp((frac - 0.42f) / 0.3f, 0.0f, 1.0f))
                    * Mth.clamp(0.85f + 0.15f * noise(i, 900, cycle, cycleFrac), 0.0f, 1.0f);
            if (alpha < 0.0039f) continue;

            int core = PVPVisual.multiplyAlpha(PVPVisual.blendColors(0xFFFFFFFF, HIT_COLOR, hit), alpha);
            float ang0 = noise(i, 11, seg) * (float) Math.PI;
            float turns = 0.6f + 0.5f * Math.abs(noise(i, 12, seg));
            float dir = noise(i, 13, seg) >= 0.0f ? 1.0f : -1.0f;
            float top = height * (0.85f + 0.05f * noise(i, 14, seg));
            float drop = height * 0.67f;
            float rad = radius * (1.0f + 0.12f * noise(i, 15, seg));
            float wobble = height * 0.16f * Mth.sin(lightningTime * 1.7f + i * 2.1f);

            float px = 0, py = 0, pz = 0;
            for (int j = 0; j <= 3; j++) {
                float s = j / 3.0f;
                float ang = ang0 + lightningTime * 0.9f + dir * turns * (float) (Math.PI * 2) * s;
                float x = cx + Mth.cos(ang) * rad;
                float y = cy + top - drop * s + wobble;
                float z = cz + Mth.sin(ang) * rad;
                if (j > 0) {
                    bolt.clear();
                    genBolt(px, py, pz, x, y, z, 4, (i * 131 + j) * 17 + seg * 101 | 1, 0, 1.0f);
                    for (float[] b : bolt) {
                        segments.add(b);
                        colors.add(new int[]{core});
                    }
                }
                px = x;
                py = y;
                pz = z;
            }
        }
        drawBolts(segments, colors, halfWidth, glowSize);
        bloom(t, cx, cy, cz, 0.9f * (segments.isEmpty() ? 0.3f : 1.0f));
    }

    // ------------------------------------------------------------------ Projectile effects (Lightning / Chains)

    /** Lightning from the hand to the player's flying arrows and tridents. */
    void projectileLightning(float partial, Vec3 cam) {
        if (mc.player == null || mc.level == null) return;
        lightningTime += dt;
        float[] hand = handPos(partial, cam);
        List<float[]> segments = new ArrayList<>();
        List<int[]> colors = new ArrayList<>();
        List<float[]> tips = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractArrow arrow) || arrow.getOwner() != mc.player) continue;
            float presence = presence(arrow);
            if (presence < 0.0039f) continue;
            float[] end = entityPos(arrow, partial, cam);
            float dx = end[0] - hand[0], dy = end[1] - hand[1], dz = end[2] - hand[2];
            float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0f) continue;
            float[] side = perpendicular(dx / len, dy / len, dz / len);
            float flicker = Mth.clamp(0.8f + 0.2f * noise(arrow.getId(), 900, (int) lightningTime, lightningTime - (int) lightningTime), 0.0f, 1.0f);
            int pieces = Mth.clamp((int) Math.ceil(len / 2.2f), 2, 24);
            float travel = lightningTime * 1.7f - (int) (lightningTime * 1.7f);
            float px = hand[0], py = hand[1], pz = hand[2];
            for (int n = 1; n <= pieces; n++) {
                float f = (float) n / pieces;
                float sine = Mth.sin((float) Math.PI * f);
                float ph = f * 7.0f + lightningTime * 4.0f;
                float amp = Math.min(0.3f, len * 0.06f) * sine;
                float x = hand[0] + dx * f + (side[0] * Mth.sin(ph) + side[3] * Mth.cos(ph)) * amp;
                float y = hand[1] + dy * f + (side[1] * Mth.sin(ph) + side[4] * Mth.cos(ph)) * amp - Math.min(0.6f, len * 0.035f) * sine;
                float z = hand[2] + dz * f + (side[2] * Mth.sin(ph) + side[5] * Mth.cos(ph)) * amp;
                float d = Math.abs((n - 0.5f) / pieces - travel);
                d = Math.min(d, 1.0f - d);
                float k = Math.max(0.0f, 1.0f - d / 0.22f);
                int color = PVPVisual.multiplyAlpha(PVPVisual.blendColors(v.getColor() | 0xFF000000, 0xFFFFFFFF, 0.4f + 0.6f * k),
                        (0.5f + 0.5f * k * k) * flicker * presence);
                bolt.clear();
                genBolt(px, py, pz, x, y, z, 4, (arrow.getId() * 131 + n) * 17 | 1, 0, 1.0f);
                for (float[] b : bolt) {
                    segments.add(b);
                    colors.add(new int[]{color});
                }
                px = x;
                py = y;
                pz = z;
            }
            tips.add(new float[]{end[0], end[1], end[2], 0.067f + 0.02f * Mth.sin(lightningTime * 9.0f + arrow.getId()),
                    flicker * presence});
        }
        drawBolts(segments, colors, 0.0037f, 0.047f);
        if (!tips.isEmpty()) {
            int tipColor = PVPVisual.blendColors(v.getColor() | 0xFF000000, 0xFFFFFFFF, 0.7f);
            draw(PVPVisual.buildTextured(m -> {
                for (float[] tip : tips) glow(m, tip[0], tip[1], tip[2], tip[3], PVPVisual.multiplyAlpha(tipColor, 0.45f * tip[4]));
            }), PVPVisual.TEX_ADDITIVE, "include:pvp_bolt_tips", TEX_GLOW, false);
        }
    }

    /** A sagging chain from the hand to the player's flying arrows and tridents. */
    void projectileChains(float partial, Vec3 cam) {
        if (mc.player == null || mc.level == null) return;
        float[] hand = handPos(partial, cam);
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractArrow arrow) || arrow.getOwner() != mc.player) continue;
            if (arrow.getDeltaMovement().lengthSqr() < 1.0e-5) continue;
            float[] end = entityPos(arrow, partial, cam);
            float dx = end[0] - hand[0], dy = end[1] - hand[1], dz = end[2] - hand[2];
            float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.01f) continue;
            int n = 16;
            float sag = len * 0.06f;
            float[][] pts = new float[n + 1][];
            float[][] nrm = new float[n + 1][];
            for (int i = 0; i <= n; i++) {
                float f = (float) i / n;
                pts[i] = new float[]{hand[0] + dx * f, hand[1] + dy * f - sag * 4.0f * f * (1.0f - f), hand[2] + dz * f};
            }
            for (int i = 0; i <= n; i++) {
                float[] a = pts[Math.max(i - 1, 0)], b = pts[Math.min(i + 1, n)];
                float tx = b[0] - a[0], ty = b[1] - a[1], tz = b[2] - a[2];
                // normal = tangent x position: the ribbon faces the camera (at the origin)
                float nx = ty * pts[i][2] - tz * pts[i][1];
                float ny = tz * pts[i][0] - tx * pts[i][2];
                float nz = tx * pts[i][1] - ty * pts[i][0];
                float nl = Math.max(1.0e-5f, Mth.sqrt(nx * nx + ny * ny + nz * nz));
                nrm[i] = new float[]{nx / nl * 0.5f, ny / nl * 0.5f, nz / nl * 0.5f};
            }
            final float length = len;
            MeshData mesh = PVPVisual.buildTextured(m -> {
                for (int i = 0; i < n; i++) {
                    float[] a0 = {pts[i][0] - nrm[i][0], pts[i][1] - nrm[i][1], pts[i][2] - nrm[i][2]};
                    float[] a1 = {pts[i + 1][0] - nrm[i + 1][0], pts[i + 1][1] - nrm[i + 1][1], pts[i + 1][2] - nrm[i + 1][2]};
                    float[] b1 = {pts[i + 1][0] + nrm[i + 1][0], pts[i + 1][1] + nrm[i + 1][1], pts[i + 1][2] + nrm[i + 1][2]};
                    float[] b0 = {pts[i][0] + nrm[i][0], pts[i][1] + nrm[i][1], pts[i][2] + nrm[i][2]};
                    uvQuad(m, a0, a1, b1, b0, (float) i / n * length / 1.3333f, (float) (i + 1) / n * length / 1.3333f, tint(i * 24.0f, 0.0f));
                }
            });
            draw(mesh, PVPVisual.TEX_ADDITIVE, "include:pvp_projectile_chain", TEX_CHAIN, true);
        }
    }

    private float presence(AbstractArrow arrow) {
        boolean flying = !arrow.onGround() && arrow.getDeltaMovement().lengthSqr() > 1.0e-5;
        float p = Mth.clamp(projectilePresence.getOrDefault(arrow.getId(), 0.0f) + (flying ? dt / 0.15f : -dt / 0.3f), 0.0f, 1.0f);
        projectilePresence.put(arrow.getId(), p);
        if (projectilePresence.size() > 64) projectilePresence.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        return p;
    }

    private float[] handPos(float partial, Vec3 cam) {
        return new float[]{
                (float) (Mth.lerp(partial, mc.player.xOld, mc.player.getX()) - cam.x),
                (float) (Mth.lerp(partial, mc.player.yOld, mc.player.getY()) + mc.player.getEyeHeight() * 0.75f - cam.y),
                (float) (Mth.lerp(partial, mc.player.zOld, mc.player.getZ()) - cam.z)};
    }

    private static float[] entityPos(Entity e, float partial, Vec3 cam) {
        return new float[]{
                (float) (Mth.lerp(partial, e.xOld, e.getX()) - cam.x),
                (float) (Mth.lerp(partial, e.yOld, e.getY()) - cam.y),
                (float) (Mth.lerp(partial, e.zOld, e.getZ()) - cam.z)};
    }

    /** Two unit vectors perpendicular to the direction: {u.x, u.y, u.z, v.x, v.y, v.z}. */
    private static float[] perpendicular(float dx, float dy, float dz) {
        float ax = Math.abs(dy) < 0.9f ? 0.0f : 1.0f;
        float ay = Math.abs(dy) < 0.9f ? 1.0f : 0.0f;
        float ux = -dz * ay, uy = dz * ax, uz = dx * ay - dy * ax;
        float ul = Mth.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1.0e-5f) ul = 1.0f;
        ux /= ul;
        uy /= ul;
        uz /= ul;
        return new float[]{ux, uy, uz, dy * uz - dz * uy, dz * ux - dx * uz, dx * uy - dy * ux};
    }

    // ------------------------------------------------------------------ bolt geometry

    /** Midpoint-displacement bolt into {@link #bolt}; thin branches fork off the upper levels. */
    private void genBolt(float ax, float ay, float az, float bx, float by, float bz, int depth, int seed, int branchLevel, float width) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (depth <= 0 || len < 1.0e-4f) {
            if (len >= 1.0e-4f) bolt.add(new float[]{ax, ay, az, bx, by, bz, width});
            return;
        }
        float nx = dx / len, ny = dy / len, nz = dz / len;
        float[] p = perpendicular(nx, ny, nz);
        float o1 = noise(seed, 0, 1) * 0.5f, o2 = noise(seed, 1, 1) * 0.5f;
        float mx = (ax + bx) * 0.5f + (p[0] * o1 + p[3] * o2) * len * 0.3f;
        float my = (ay + by) * 0.5f + (p[1] * o1 + p[4] * o2) * len * 0.3f;
        float mz = (az + bz) * 0.5f + (p[2] * o1 + p[5] * o2) * len * 0.3f;
        genBolt(ax, ay, az, mx, my, mz, depth - 1, seed * 2 + 7, branchLevel, width);
        genBolt(mx, my, mz, bx, by, bz, depth - 1, seed * 2 + 9, branchLevel, width);
        if (branchLevel < 2 && depth >= 2 && noise(seed, 2, 1) > 0.45f) {
            float b1 = noise(seed, 3, 1), b2 = noise(seed, 4, 1);
            float ex = nx * 0.4f + p[0] * b1 + p[3] * b2;
            float ey = ny * 0.4f + p[1] * b1 + p[4] * b2;
            float ez = nz * 0.4f + p[2] * b1 + p[5] * b2;
            float el = Mth.sqrt(ex * ex + ey * ey + ez * ez);
            if (el < 1.0e-5f) el = 1.0f;
            float bl = len * 0.75f / el;
            genBolt(mx, my, mz, mx + ex * bl, my + ey * bl, mz + ez * bl, Math.min(depth - 1, 3), seed * 3 + 13, branchLevel + 1, width * 0.55f);
        }
    }

    /** Ribbon per segment facing the camera plus a soft glow dot at every segment centre. */
    private void drawBolts(List<float[]> segments, List<int[]> colors, float halfWidth, float glowSize) {
        if (segments.isEmpty()) return;
        MeshData ribbons = PVPVisual.build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, m -> {
            for (int i = 0; i < segments.size(); i++) {
                float[] s = segments.get(i);
                float tx = s[3] - s[0], ty = s[4] - s[1], tz = s[5] - s[2];
                float tl = Mth.sqrt(tx * tx + ty * ty + tz * tz);
                float mx = (s[0] + s[3]) * 0.5f, my = (s[1] + s[4]) * 0.5f, mz = (s[2] + s[5]) * 0.5f;
                // normal = tangent x position (camera at the origin)
                float nx = ty * mz - tz * my, ny = tz * mx - tx * mz, nz = tx * my - ty * mx;
                float nl = Math.max(1.0e-5f, Mth.sqrt(nx * nx + ny * ny + nz * nz));
                float w = halfWidth * s[6];
                nx = nx / nl * w;
                ny = ny / nl * w;
                nz = nz / nl * w;
                // extend by half a width so neighbouring segments overlap at the joints
                float ex = tx / tl * w, ey = ty / tl * w, ez = tz / tl * w;
                int c = colors.get(i)[0];
                cquad(m, new float[]{s[0] - ex - nx, s[1] - ey - ny, s[2] - ez - nz}, c,
                        new float[]{s[3] + ex - nx, s[4] + ey - ny, s[5] + ez - nz}, c,
                        new float[]{s[3] + ex + nx, s[4] + ey + ny, s[5] + ez + nz}, c,
                        new float[]{s[0] - ex + nx, s[1] - ey + ny, s[2] - ez + nz}, c);
            }
        });
        draw(ribbons, PVPVisual.COLOR_ADDITIVE, "include:pvp_bolt", null, false);

        draw(PVPVisual.buildTextured(m -> {
            for (int i = 0; i < segments.size(); i++) {
                float[] s = segments.get(i);
                glow(m, (s[0] + s[3]) * 0.5f, (s[1] + s[4]) * 0.5f, (s[2] + s[5]) * 0.5f, glowSize * s[6] * 0.5f,
                        PVPVisual.multiplyAlpha(colors.get(i)[0], 0.13f));
            }
        }), PVPVisual.TEX_ADDITIVE, "include:pvp_bolt_glow", TEX_GLOW, false);
    }

    // ------------------------------------------------------------------ Circle: Wave

    /** A bobbing ring with a rippling skirt that hangs up or down depending on the direction of travel. */
    void wave(LivingEntity t, float cx, float cy, float cz) {
        float vis = PVPVisual.targetVertexAlpha;
        float a = time * 2.4f;
        float dirv = -Mth.cos(a);
        float ph = time * 1.6f;
        float y = cy + (t.getBbHeight() + 0.1f) * (0.5f + 0.5f * Mth.sin(a));
        float radius = t.getBbWidth() * 0.9f + (1.0f - vis);
        float half = t.getBbHeight() * 0.006f;
        int main = tint(0, hit);
        int skirtNear = PVPVisual.multiplyAlpha(main, 0.34f);
        int skirtFar = PVPVisual.multiplyAlpha(main, 0.03f);
        int dot = PVPVisual.multiplyAlpha(main, 0.2f);
        draw(PVPVisual.build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, m -> {
            for (int deg = 0; deg < 360; deg += 3) {
                float r0 = (float) Math.toRadians(deg), r1 = (float) Math.toRadians(deg + 3);
                float c0 = Mth.cos(r0), s0 = Mth.sin(r0), c1 = Mth.cos(r1), s1 = Mth.sin(r1);
                cquad(m, new float[]{cx + c0 * (radius - half), y, cz + s0 * (radius - half)}, main,
                        new float[]{cx + c1 * (radius - half), y, cz + s1 * (radius - half)}, main,
                        new float[]{cx + c1 * (radius + half), y, cz + s1 * (radius + half)}, main,
                        new float[]{cx + c0 * (radius + half), y, cz + s0 * (radius + half)}, main);
                float o0 = (0.45f + 0.12f * Mth.sin(r0 * 3.0f - ph)) * dirv;
                float o1 = (0.45f + 0.12f * Mth.sin(r1 * 3.0f - ph)) * dirv;
                cquad(m, new float[]{cx + c0 * radius, y, cz + s0 * radius}, skirtNear,
                        new float[]{cx + c1 * radius, y, cz + s1 * radius}, skirtNear,
                        new float[]{cx + c1 * radius, y + o1, cz + s1 * radius}, skirtFar,
                        new float[]{cx + c0 * radius, y + o0, cz + s0 * radius}, skirtFar);
            }
        }), PVPVisual.COLOR_ADDITIVE, "include:pvp_wave", null, false);
        draw(PVPVisual.buildTextured(m -> {
            for (int deg = 0; deg < 360; deg += 7) {
                float r = (float) Math.toRadians(deg);
                glow(m, cx + Mth.cos(r) * radius, y, cz + Mth.sin(r) * radius, radius * 0.2f, dot);
            }
        }), PVPVisual.TEX_ADDITIVE, "include:pvp_wave_glow", TEX_GLOW, false);
        bloom(t, cx, cy, cz, 0.6f);
    }

    // ------------------------------------------------------------------ Circle: Band

    /** Radius of the body-hugging band at {@code f} (0..1 of the height): wider at the chest, narrower at the head. */
    private static float bodyRadius(LivingEntity t, float f, float vis) {
        float base = t.getBbWidth() * 0.5f + 0.1f;
        float c = Mth.clamp(f, 0.0f, 1.0f);
        float r = c < 0.48f ? Mth.lerp(smooth(0.24f, 0.46f, c), base * 1.02f, base * 1.46f)
                : c > 0.68f ? Mth.lerp(smooth(0.68f, 0.84f, c), base * 1.46f, base * 1.16f)
                : base * 1.46f;
        return r * (0.86f + 0.14f * smooth(0.0f, 1.0f, vis));
    }

    private static void wall(BufferBuilder m, LivingEntity t, float cx, float cy, float cz, float vis,
                             float y0, float y1, float e0, float e1, int c0, int c1) {
        float h = t.getBbHeight();
        float r0 = bodyRadius(t, y0 / h, vis) + e0, r1 = bodyRadius(t, y1 / h, vis) + e1;
        for (int deg = 0; deg < 360; deg += 3) {
            float a = (float) Math.toRadians(deg), b = (float) Math.toRadians(deg + 3);
            float ca = Mth.cos(a), sa = Mth.sin(a), cb = Mth.cos(b), sb = Mth.sin(b);
            cquad(m, new float[]{cx + ca * r0, cy + y0, cz + sa * r0}, c0, new float[]{cx + cb * r0, cy + y0, cz + sb * r0}, c0,
                    new float[]{cx + cb * r1, cy + y1, cz + sb * r1}, c1, new float[]{cx + ca * r1, cy + y1, cz + sa * r1}, c1);
        }
    }

    /** A glowing belt that slides up and down the body, swelling at the chest, with a comet tail behind it. */
    void band(LivingEntity t, float cx, float cy, float cz) {
        float vis = PVPVisual.targetVertexAlpha;
        float h = t.getBbHeight();
        float ang = (System.currentTimeMillis() % 3600L) / 3600.0f * (float) (Math.PI * 2);
        float s = Mth.sin(ang);
        float yc = Mth.lerp(0.5f + 0.5f * Mth.cos(ang), 0.035f, h - 0.035f);
        float y2 = Mth.clamp(yc + s * Math.min(0.55f, h * 0.55f) * vis, 0.02f, h - 0.02f);
        float e = (float) Math.sqrt(Math.abs(s));
        int base = tint(0, hit);
        int white = PVPVisual.blendColors(base, 0xFFFFFFFF, 0.72f * (1.0f - hit));
        float thick = h * 0.019f;
        float[] stops = {0.0f, 0.18f, 0.52f, 1.0f};
        int[] alphas = {125, 72, 24, 0};
        MeshData mesh = PVPVisual.build(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, m -> {
            for (int i = 0; i < 3; i++) {
                wall(m, t, cx, cy, cz, vis, Mth.lerp(stops[i], yc, y2), Mth.lerp(stops[i + 1], yc, y2), 0.0f, 0.0f,
                        PVPVisual.replaceAlpha(base, (int) (alphas[i] * e)), PVPVisual.replaceAlpha(base, (int) (alphas[i + 1] * e)));
            }
            wall(m, t, cx, cy, cz, vis, Math.max(0.0f, yc - 0.075f), yc, 0.025f, 0.035f,
                    PVPVisual.replaceAlpha(base, 0), PVPVisual.replaceAlpha(base, 72));
            wall(m, t, cx, cy, cz, vis, yc, Math.min(h, yc + 0.075f), 0.035f, 0.025f,
                    PVPVisual.replaceAlpha(base, 72), PVPVisual.replaceAlpha(base, 0));
            wall(m, t, cx, cy, cz, vis, yc - thick, yc + thick, 0.045f, 0.045f,
                    PVPVisual.replaceAlpha(white, 245), PVPVisual.replaceAlpha(white, 245));
            for (int i = 1; i <= 5; i++) {
                float y = Mth.lerp(i / 6.0f, yc, y2);
                int c = PVPVisual.replaceAlpha(base, (int) ((1.0f - i / 6.0f) * 34.0f * e));
                wall(m, t, cx, cy, cz, vis, y - thick, y + thick, 0.012f, 0.012f, c, c);
            }
        });
        draw(mesh, PVPVisual.COLOR_ADDITIVE, "include:pvp_band", null, false);
        bloom(t, cx, cy, cz, 0.6f);
    }

    // ------------------------------------------------------------------ Circle: Scan (on the target's model)

    private int scanTarget = -1;
    private long scanStart;
    private long scanSeen;

    /** Player model box in model pixels: {x0, x1, y0, y1, z0, z1}, then the skin offset {u, v} and size {w, h, d}. */
    private static final float[] HEAD = {-4, 4, 24, 32, -4, 4, 0, 0, 8, 8, 8};
    private static final float[] BODY = {-4, 4, 12, 24, -2, 2, 16, 16, 8, 12, 4};
    private static final float[] LEG_R = {-4, 0, 0, 12, -2, 2, 0, 16, 4, 12, 4};
    private static final float[] LEG_L = {0, 4, 0, 12, -2, 2, 16, 48, 4, 12, 4};
    private static final float MODEL_SCALE = 0.9375f / 16.0f;
    private static final float MODEL_HEIGHT = 32.0f;

    /**
     * A bright line sliding down the target's body with a soft trail above it. The body is the player model rebuilt
     * from boxes (skin UVs, head turn, walk swing, sneak lean) and shaded per pixel by {@code target_scan.fsh};
     * other mobs get one box of their hitbox.
     */
    void scan(LivingEntity t, float cx, float cy, float cz) {
        long now = System.currentTimeMillis();
        if (t.getId() != scanTarget || now - scanSeen > 250L) {
            scanTarget = t.getId();
            scanStart = now;
        }
        scanSeen = now;
        float phase = ((now - scanStart) % 1800L) / 1800.0f;
        float appear = smooth(0.0f, 0.08f, phase) * (1.0f - smooth(0.88f, 1.0f, phase)) * PVPVisual.targetVertexAlpha;
        if (appear < 0.004f) return;

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float grow = 1.008f - Mth.clamp(hit, 0.0f, 1.0f) * 0.018f;
        float yaw = Mth.rotLerp(partial, t.yBodyRotO, t.yBodyRot);
        Matrix4f root = new Matrix4f().translation(cx, cy, cz).rotateY((float) -Math.toRadians(yaw));
        Identifier texture = TEX_GLOW;
        boolean skin = false;
        float height;

        MeshData mesh;
        if (t instanceof AbstractClientPlayer player) {
            var model = player.getSkin();
            texture = model.body().texturePath();
            skin = true;
            boolean slim = model.model() == PlayerModelType.SLIM;
            root.scale(MODEL_SCALE * grow);
            height = MODEL_HEIGHT * MODEL_SCALE * grow;

            float pos = t.walkAnimation.position(partial);
            float speed = Math.min(1.0f, t.walkAnimation.speed(partial));
            float armR = Mth.cos(pos * 0.6662f + (float) Math.PI) * speed;
            float armL = Mth.cos(pos * 0.6662f) * speed;
            float legR = Mth.cos(pos * 0.6662f) * 1.4f * speed;
            float legL = Mth.cos(pos * 0.6662f + (float) Math.PI) * 1.4f * speed;
            float headYaw = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partial, t.yHeadRotO, t.yHeadRot) - yaw), -85.0f, 85.0f);
            float pitch = (float) Math.toRadians(Mth.lerp(partial, t.xRotO, t.getXRot()));
            Matrix4f upper = new Matrix4f(root);
            if (t.isCrouching()) upper.translate(0, 12, 0).rotateX(0.5f).translate(0, -12, 0);

            float armW = slim ? 3.0f : 4.0f;
            float[] armRight = {-4 - armW, -4, 12, 24, -2, 2, 40, 16, armW, 12, 4};
            float[] armLeft = {4, 4 + armW, 12, 24, -2, 2, 32, 48, armW, 12, 4};
            float[][] boxes = {HEAD, BODY, armRight, armLeft, LEG_R, LEG_L};
            Matrix4f[] transforms = {
                    new Matrix4f(upper).translate(0, 24, 0).rotateY((float) -Math.toRadians(headYaw)).rotateX(pitch).translate(0, -24, 0),
                    upper,
                    new Matrix4f(upper).translate(-5, 22, 0).rotateX(armR).translate(5, -22, 0),
                    new Matrix4f(upper).translate(5, 22, 0).rotateX(armL).translate(-5, -22, 0),
                    new Matrix4f(root).translate(-2, 12, 0).rotateX(legR).translate(2, -12, 0),
                    new Matrix4f(root).translate(2, 12, 0).rotateX(legL).translate(-2, -12, 0)};
            float finalHeight = height;
            mesh = PVPVisual.buildTextured(m -> {
                for (int i = 0; i < boxes.length; i++) emitBox(m, transforms[i], boxes[i], true, finalHeight, cy);
            });
        } else {
            float w = t.getBbWidth() * 0.5f;
            height = t.getBbHeight() * grow;
            root.scale(grow);
            float[] box = {-w, w, 0, t.getBbHeight(), -w, w, 0, 0, 0, 0, 0};
            float finalHeight = height;
            mesh = PVPVisual.buildTextured(m -> emitBox(m, root, box, false, finalHeight, cy));
        }

        int base = tint(0, 0.0f);
        float[] params = {
                phase, appear, 1.35f, Mth.clamp(hit, 0.0f, 1.0f),
                (base >> 16 & 0xFF) / 255.0f, (base >> 8 & 0xFF) / 255.0f, (base & 0xFF) / 255.0f, skin ? 1.0f : 0.0f,
                (HIT_COLOR >> 16 & 0xFF) / 255.0f, (HIT_COLOR >> 8 & 0xFF) / 255.0f, (HIT_COLOR & 0xFF) / 255.0f, 0.0f};
        PVPVisual.drawMesh(mesh, SCAN, "include:pvp_scan", PVPVisual.boundTexture(texture), false, "ScanParams", params);
        bloom(t, cx, cy, cz, 0.5f);
    }

    /**
     * One textured box. Vertex colour carries the shader's model data: r = face normal angle, g = normalised
     * width, a = normalised height (0 at the feet, 1 at the top of the model).
     */
    private void emitBox(BufferBuilder m, Matrix4f xf, float[] b, boolean skin, float height, float cy) {
        float x0 = b[0], x1 = b[1], y0 = b[2], y1 = b[3], z0 = b[4], z1 = b[5];
        float u = b[6], v = b[7], w = b[8], h = b[9], d = b[10];
        float uf0 = u + d, uf1 = u + d + w, vt = v + d, vb = v + d + h;
        float[][] faces = {
                {0, 0, 1, x0, y1, z1, uf0, vt, x1, y1, z1, uf1, vt, x1, y0, z1, uf1, vb, x0, y0, z1, uf0, vb},
                {0, 0, -1, x1, y1, z0, u + 2 * d + w, vt, x0, y1, z0, u + 2 * d + 2 * w, vt, x0, y0, z0, u + 2 * d + 2 * w, vb, x1, y0, z0, u + 2 * d + w, vb},
                {-1, 0, 0, x0, y1, z0, u, vt, x0, y1, z1, u + d, vt, x0, y0, z1, u + d, vb, x0, y0, z0, u, vb},
                {1, 0, 0, x1, y1, z1, u + d + w, vt, x1, y1, z0, u + 2 * d + w, vt, x1, y0, z0, u + 2 * d + w, vb, x1, y0, z1, u + d + w, vb},
                {0, 1, 0, x0, y1, z0, uf0, v, x1, y1, z0, uf1, v, x1, y1, z1, uf1, v + d, x0, y1, z1, uf0, v + d},
                {0, -1, 0, x0, y0, z1, uf1, v, x1, y0, z1, u + d + 2 * w, v, x1, y0, z0, u + d + 2 * w, v + d, x0, y0, z0, uf1, v + d}};
        Vector3f p = new Vector3f();
        Vector3f n = new Vector3f();
        int[] colors = new int[4];
        float[] xs = new float[4], ys = new float[4], zs = new float[4], us = new float[4], vs = new float[4];
        for (float[] f : faces) {
            xf.transformDirection(n.set(f[0], f[1], f[2]));
            int angle = Math.round((float) (Math.atan2(n.z, n.x) / (Math.PI * 2) + 0.5) * 255.0f) & 0xFF;
            for (int i = 0; i < 4; i++) {
                int k = 3 + i * 5;
                xf.transformPosition(p.set(f[k], f[k + 1], f[k + 2]));
                xs[i] = p.x;
                ys[i] = p.y;
                zs[i] = p.z;
                us[i] = skin ? f[k + 3] / 64.0f : 0.0f;
                vs[i] = skin ? f[k + 4] / 64.0f : 0.0f;
                int hz = Mth.clamp(Math.round((skin ? (f[k] + 8.0f) / 16.0f : 0.5f) * 255.0f), 0, 255);
                int hy = Mth.clamp(Math.round((p.y - cy) / height * 255.0f), 0, 255);
                colors[i] = hy << 24 | angle << 16 | hz << 8;
            }
            for (int i : new int[]{0, 1, 2, 0, 2, 3}) m.addVertex(xs[i], ys[i], zs[i]).setUv(us[i], vs[i]).setColor(colors[i]);
        }
    }
}
