package releon.ru.api.module.impl.visual;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import releon.ru.utils.render.*;
import releon.ru.utils.render.color.ClientTheme;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Camera;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** PVP Visual hit particles: images that drift out of the struck target, push each other apart and fade. */
final class PvpHitParticles {

    private static final Identifier TEX_GLOW = Identifier.fromNamespaceAndPath("client", "textures/particles/glow.png");
    private static final int MAX_PARTICLES = 200;

    private final List<Hit> hits = new ArrayList<>();
    private final Vector3f right = new Vector3f();
    private final Vector3f up = new Vector3f();
    private final Vector3f rr = new Vector3f();
    private final Vector3f uu = new Vector3f();

    private static final class Hit {
        double x, y, z;
        float vx, vy, vz, rot, spin, size;
        long born, life;
        Identifier tex;
    }

    void clear() {
        hits.clear();
    }

    boolean isEmpty() {
        return hits.isEmpty();
    }

    void spawn(PVPVisual v, LivingEntity target) {
        var rnd = ThreadLocalRandom.current();
        List<Identifier> textures = new ArrayList<>();
        for (String name : PvpAssets.WORLD_IMAGES) if (v.hitImages.isSelected(name)) textures.add(PvpAssets.textureFor(name));
        if (textures.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < v.hitParticleCount.getValue().intValue() && hits.size() < MAX_PARTICLES; i++) {
            Hit h = new Hit();
            h.x = target.getX();
            h.y = target.getY() + rnd.nextDouble() * target.getBbHeight();
            h.z = target.getZ();
            h.vx = (float) rnd.nextDouble(-0.16, 0.16);
            h.vy = (float) rnd.nextDouble(0.004, 0.016);
            h.vz = (float) rnd.nextDouble(-0.16, 0.16);
            h.rot = rnd.nextFloat() * 6.2831855f;
            h.spin = (rnd.nextFloat() - 0.5f) * 0.12f;
            h.size = v.hitParticleSize.getFloat() * 0.42f * (0.8f + rnd.nextFloat() * 0.4f);
            h.born = now;
            h.life = Math.max(300L, Math.round(v.hitParticleLife.getValue() * (0.85 + rnd.nextDouble() * 0.3)));
            h.tex = textures.get(rnd.nextInt(textures.size()));
            hits.add(h);
        }
    }

    /** One game tick: drift with drag, then push overlapping particles apart. */
    void tick(PVPVisual v) {
        if (hits.isEmpty()) return;
        long now = System.currentTimeMillis();
        hits.removeIf(h -> now - h.born > h.life);
        for (Hit h : hits) {
            h.x += h.vx;
            h.y += h.vy;
            h.z += h.vz;
            h.vx *= 0.94f;
            h.vz *= 0.94f;
            h.rot += h.spin;
        }
        if (!v.hitCollisions.getValue()) return;
        for (int i = 0; i < hits.size(); i++) {
            Hit a = hits.get(i);
            for (int j = i + 1; j < hits.size(); j++) {
                Hit b = hits.get(j);
                double reach = a.size + b.size;
                double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
                if (Math.abs(dx) >= reach || Math.abs(dy) >= reach || Math.abs(dz) >= reach) continue;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq >= reach * reach || distSq < 1.0e-8) continue;
                double dist = Math.sqrt(distSq), push = (reach - dist) * 0.5 / dist;
                a.x -= dx * push; a.y -= dy * push; a.z -= dz * push;
                b.x += dx * push; b.y += dy * push; b.z += dz * push;
            }
        }
    }

    void render(PVPVisual v, Camera camera, Vec3 cam, float partial) {
        if (hits.isEmpty()) return;
        var q = camera.rotation();
        right.set(1, 0, 0).rotate(q);
        up.set(0, 1, 0).rotate(q);
        long now = System.currentTimeMillis();
        int color = v.getColor() | 0xFF000000;
        float glow = v.hitGlow.getValue() ? v.hitGlowStrength.getFloat() : 0.0f;

        List<Identifier> textures = new ArrayList<>(2);
        for (Hit h : hits) if (!textures.contains(h.tex)) textures.add(h.tex);
        for (Identifier tex : textures) {
            MeshData mesh = PVPVisual.buildTextured(m -> {
                for (Hit h : hits) {
                    if (h.tex != tex) continue;
                    float f = (float) (now - h.born) / h.life;
                    float a = Math.min(1.0f, f / 0.1f) * Mth.clamp((1.0f - f) / 0.35f, 0.0f, 1.0f);
                    if (a <= 0.01f) continue;
                    spin(h.rot);
                    float x = (float) (h.x + h.vx * partial - cam.x), y = (float) (h.y + h.vy * partial - cam.y), z = (float) (h.z + h.vz * partial - cam.z);
                    PVPVisual.emitBillboardQuad(m, x, y, z, rr, uu, h.size, PVPVisual.multiplyAlpha(color, a));
                }
            });
            if (mesh != null) PVPVisual.drawMesh(mesh, PVPVisual.TEX_TRANSLUCENT, "include:pvp_hit", PVPVisual.boundTexture(tex));
        }

        if (glow > 0.01f) {
            MeshData halo = PVPVisual.buildTextured(m -> {
                for (Hit h : hits) {
                    float f = (float) (now - h.born) / h.life;
                    float a = Math.min(1.0f, f / 0.1f) * Mth.clamp((1.0f - f) / 0.35f, 0.0f, 1.0f);
                    if (a <= 0.01f) continue;
                    spin(0.0f);
                    float x = (float) (h.x + h.vx * partial - cam.x), y = (float) (h.y + h.vy * partial - cam.y), z = (float) (h.z + h.vz * partial - cam.z);
                    PVPVisual.emitBillboardQuad(m, x, y, z, rr, uu, h.size * (1.4f + glow), PVPVisual.multiplyAlpha(color, a * 0.3f * Math.min(1.0f, glow)));
                }
            });
            if (halo != null) PVPVisual.drawMesh(halo, PVPVisual.TEX_ADDITIVE, "include:pvp_hit_glow", PVPVisual.boundTexture(TEX_GLOW));
        }
    }

    private void spin(float angle) {
        float cs = Mth.cos(angle), sn = Mth.sin(angle);
        rr.set(right).mul(cs).fma(sn, up);
        uu.set(up).mul(cs).fma(-sn, right);
    }
}
