package releon.ru.utils.render.post.fx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Ударная волна + хроматическая аберрация из точки убийства.
 * ping(pos) — запуск, render(...) — каждый кадр из KillEffect.onAfterTranslucent.
 */
public final class KillShockwaveRenderer {
    private static final int UBO_SIZE = 48;
    private static final PostShaderPass PASS = new PostShaderPass(
            "kill_shockwave",
            Identifier.fromNamespaceAndPath("releon", "post/fx/kill_shockwave"),
            "ShockwaveConfig",
            UBO_SIZE
    );

    private static final long DURATION_MS = 720L;
    private static final Vector4f TMP = new Vector4f();

    private static Vec3 origin;
    private static long startMs;
    private static float speed = 1.0F;

    private KillShockwaveRenderer() {
    }

    public static void ping(Vec3 position, float speedMultiplier) {
        origin = position;
        startMs = System.currentTimeMillis();
        speed = Math.max(0.25F, speedMultiplier);
    }

    public static void clear() {
        origin = null;
    }

    public static void render(RenderTarget target, Matrix4f projection, Matrix4f view, Vec3 cameraPos, int color, float strength) {
        if (origin == null || PASS.isDisabled() || target == null) {
            return;
        }
        float progress = (System.currentTimeMillis() - startMs) * speed / (float) DURATION_MS;
        if (progress >= 1.0F) {
            origin = null;
            return;
        }

        // мировая точка -> экран (0..1); если за спиной — волна из центра экрана, слабее
        float cx = 0.5F, cy = 0.5F, k = 0.55F;
        TMP.set((float) (origin.x - cameraPos.x), (float) (origin.y - cameraPos.y), (float) (origin.z - cameraPos.z), 1.0F);
        view.transform(TMP);
        projection.transform(TMP);
        if (TMP.w > 0.05F) {
            cx = TMP.x / TMP.w * 0.5F + 0.5F;
            cy = TMP.y / TMP.w * 0.5F + 0.5F;
            k = 1.0F;
        }

        float eased = 1.0F - (float) Math.pow(1.0F - progress, 3.0);
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float fcx = cx, fcy = cy, fk = k;

        PASS.apply(target, data -> {
            data.putFloat(0, target.width);
            data.putFloat(4, target.height);
            data.putFloat(8, fcx);
            data.putFloat(12, fcy);
            data.putFloat(16, eased);
            data.putFloat(20, 0.045F * strength * fk);
            data.putFloat(24, 0.006F * strength * fk);
            data.putFloat(28, 0.0F);
            data.putFloat(32, r);
            data.putFloat(36, g);
            data.putFloat(40, b);
            data.putFloat(44, 0.55F);
        });
    }
}
