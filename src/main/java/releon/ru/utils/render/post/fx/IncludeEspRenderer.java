package releon.ru.utils.render.post.fx;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import releon.ru.utils.render.RenderSampler;
import java.util.OptionalInt;

/** Include's adaptive dual-Kawase silhouette renderer, adapted to Minecraft 1.21.11. */
public final class IncludeEspRenderer {
    public static final int MAX_MIPS = 5;
    private static final RenderPipeline DOWN = pipeline("esp_down", false), UP = pipeline("esp_up", false), COMBINE = pipeline("esp_combine", true);
    private final TextureTarget[] mips = new TextureTarget[MAX_MIPS];
    private final GpuBuffer[] samplerData = new GpuBuffer[MAX_MIPS * 2];
    private TextureTarget scene;
    private RenderTarget mask;
    private GpuBuffer uniform;
    private int levels, x0, y0, x1, y1;

    private static RenderPipeline pipeline(String name, boolean composite) {
        var builder = RenderPipeline.builder().withLocation(id("pipeline/" + name))
                .withVertexShader(id("post/include/esp_screenquad")).withFragmentShader(id("post/include/" + name))
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false);
        if (composite) builder.withBlend(BlendFunction.LIGHTNING).withUniform("EspParams", UniformType.UNIFORM_BUFFER)
                .withSampler("MaskTexture").withSampler("GlowTexture").withSampler("SceneTexture");
        else builder.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER).withSampler("InSampler");
        return RenderPipelines.register(builder.build());
    }
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("releon", path); }
    public static boolean isFriend(Object entity) {
        return entity instanceof net.minecraft.world.entity.player.Player player && releon.ru.utils.repository.friend.FriendUtils.isFriend(player);
    }
    public void invalidate() {
        mask = null;
        if (scene != null) { scene.destroyBuffers(); scene = null; }
        for (int i = 0; i < MAX_MIPS; i++) if (mips[i] != null) { mips[i].destroyBuffers(); mips[i] = null; }
        for (int i = 0; i < samplerData.length; i++) if (samplerData[i] != null) { samplerData[i].close(); samplerData[i] = null; }
        if (uniform != null) { uniform.close(); uniform = null; }
    }
    public void useMask(RenderTarget mask, float radius, int x0, int y0, int x1, int y1) {
        this.mask = mask;
        levels = radius < 0 ? 0 : Math.max(1, Math.min(MAX_MIPS, 1 + Math.round(radius * (MAX_MIPS - 1))));
        int pad = (4 << levels) + 8;
        this.x0 = Math.max(0, x0 - pad); this.y0 = Math.max(0, y0 - pad);
        this.x1 = Math.min(mask.width, x1 + pad); this.y1 = Math.min(mask.height, y1 + pad);
        int w = mask.width, h = mask.height;
        for (int i = 0; i < levels; i++) {
            w = Math.max(1, w / 2); h = Math.max(1, h / 2);
            mips[i] = sized(mips[i], "esp_mip_" + i, w, h);
        }
    }
    public boolean isReady() { return mask != null && mask.getColorTextureView() != null && x0 < x1 && y0 < y1; }
    private static TextureTarget sized(TextureTarget target, String name, int width, int height) {
        if (target == null) return new TextureTarget(name, width, height, false);
        if (target.width != width || target.height != height) target.resize(width, height);
        return target;
    }
    public void renderGlow() {
        if (!isReady() || levels == 0) return;
        for (int i = 0; i < levels; i++) blur(i == 0 ? mask : mips[i - 1], mips[i], DOWN, i);
        for (int i = levels - 1; i > 0; i--) blur(mips[i], mips[i - 1], UP, MAX_MIPS + i);
    }
    private void blur(RenderTarget input, RenderTarget output, RenderPipeline pipeline, int index) {
        var device = RenderSystem.getDevice();
        if (samplerData[index] == null) samplerData[index] = device.createBuffer(() -> "ESP sampler dimensions", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
        var encoder = device.createCommandEncoder();
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(16);
            data.putFloat(0, output.width).putFloat(4, output.height).putFloat(8, input.width).putFloat(12, input.height);
            encoder.writeToBuffer(samplerData[index].slice(), data);
        }
        try (var pass = encoder.createRenderPass(() -> "ESP Kawase", output.getColorTextureView(), OptionalInt.of(0))) {
            scissor(pass, output.width, output.height);
            pass.setPipeline(pipeline); pass.setUniform("SamplerInfo", samplerData[index]);
            pass.bindTexture("InSampler", input.getColorTextureView(), RenderSampler.linear());
            pass.draw(0, 3);
        }
    }
    public GpuTextureView snapshotScene(RenderTarget target) {
        scene = sized(scene, "esp_scene", target.width, target.height);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(target.getColorTexture(), scene.getColorTexture(), 0, 0, 0, 0, 0, target.width, target.height);
        return scene.getColorTextureView();
    }
    public void composite(Params params, RenderTarget target, GpuTextureView sceneView) {
        if (!isReady()) return;
        var device = RenderSystem.getDevice();
        if (uniform == null) uniform = device.createBuffer(() -> "ESP parameters", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 144);
        var encoder = device.createCommandEncoder();
        writeParams(encoder, params, target.width, target.height, sceneView != null);
        try (var pass = encoder.createRenderPass(() -> "Include ESP composite", target.getColorTextureView(), OptionalInt.empty())) {
            scissor(pass, target.width, target.height);
            pass.setPipeline(COMBINE); pass.setUniform("EspParams", uniform);
            pass.bindTexture("MaskTexture", mask.getColorTextureView(), RenderSampler.linear());
            pass.bindTexture("GlowTexture", levels > 0 ? mips[0].getColorTextureView() : mask.getColorTextureView(), RenderSampler.linear());
            pass.bindTexture("SceneTexture", sceneView != null ? sceneView : mask.getColorTextureView(), RenderSampler.linear());
            pass.draw(0, 3);
        }
    }
    private void scissor(RenderPass pass, int width, int height) {
        float sx = width / (float) mask.width, sy = height / (float) mask.height;
        int left = Math.max(0, (int) (x0 * sx) - 2), bottom = Math.max(0, (int) (y0 * sy) - 2);
        int right = Math.min(width, (int) Math.ceil(x1 * sx) + 2), top = Math.min(height, (int) Math.ceil(y1 * sy) + 2);
        pass.enableScissor(left, bottom, right - left, top - bottom);
    }
    public static final class Params {
        public int color = 0xFF00FFFF;
        public int friendColor = 0xFF55FF55;
        public boolean friendEnabled = true;
        public boolean hurtEnabled = false;
        public int hurtColor = 0xFFFF2A2A;
        public float opacity = 1.0f;
        public float saturation = 1.0f;

        public boolean glowEnabled = true;
        /** 0..1 - how deep the pyramid walks, i.e. how wide the halo is. */
        public float glowRadius = 0.55f;
        public float glowStrength = 1.6f;
        public float glowFalloff = 1.1f;

        public boolean fillEnabled = true;
        public float fillOpacity = 0.45f;
        public float innerGlow = 0.6f;

        public boolean outlineEnabled = true;
        /** 1..5 px. */
        public int outlineWidth = 2;
        /** 0 outside, 1 inside, 2 both. */
        public int outlineMode = 0;
        public float outlineStrength = 1.8f;
        public float outlineWhite = 0.45f;

        public boolean pulseEnabled = false;
        public float pulseSpeed = 1.0f;
        public float pulseAmount = 0.3f;

        public boolean shimmerEnabled = false;
        public float shimmerWidth = 0.04f;
        public float shimmerPeriodSec = 5.0f;
        public float shimmerBrightness = 0.8f;

        /**
         * Occlusion sensitivity of the composite, 0..1. {@code 0} disables the check (the halo shines
         * through walls, like the donor's depth-less path); {@code 0.22} is a good default.
         */
        public float edgeThreshold = 0.22f;

    }

    private void writeParams(CommandEncoder encoder, Params p, int width, int height, boolean sceneAvailable) {
        try (var stack = MemoryStack.stackPush()) {
            var bytes = stack.calloc(144);
            var b = Std140Builder.intoBuffer(bytes);
            putRgb(b, p.color);
            b.putFloat(clampParam(p.opacity, 0f, 1f));

            putRgb(b, p.friendColor);
            b.putFloat(p.friendEnabled ? 1f : 0f);

            // texel size, outline width, outline mode
            b.putVec4(1f / Math.max(1, width), 1f / Math.max(1, height),
                    Math.max(0, Math.min(5, p.outlineWidth)), p.outlineMode);

            // glow strength, glow falloff, fill opacity, inner glow
            b.putVec4(Math.max(0f, p.glowStrength), Math.max(0.05f, p.glowFalloff),
                    Math.max(0f, p.fillOpacity), Math.max(0f, p.innerGlow));

            // outline strength, outline white, glow on, fill on
            b.putVec4(Math.max(0f, p.outlineStrength), clampParam(p.outlineWhite, 0f, 1f),
                    p.glowEnabled ? 1f : 0f, p.fillEnabled ? 1f : 0f);

            // shimmer phase, shimmer on, shimmer width, shimmer brightness
            b.putVec4(phase(p.shimmerPeriodSec), p.shimmerEnabled ? 1f : 0f,
                    Math.max(0.005f, p.shimmerWidth), Math.max(0f, p.shimmerBrightness));

            // pulse amount, pulse phase, saturation, outline on
            b.putVec4(p.pulseEnabled ? clampParam(p.pulseAmount, 0f, 1f) : 0f,
                    phase(1f / Math.max(0.05f, p.pulseSpeed)),
                    Math.max(0f, p.saturation), p.outlineEnabled ? 1f : 0f);

            // edge threshold, hurt tint on
            b.putVec4(sceneAvailable ? Math.max(0f, p.edgeThreshold) : 0f, p.hurtEnabled ? 1f : 0f, 0f, 0f);

            putRgb(b, p.hurtColor);
            b.putFloat(1f);

            bytes.position(0).limit(144);
            encoder.writeToBuffer(uniform.slice(), bytes);
        }
    }
    private static void putRgb(Std140Builder b, int argb) {
        b.putFloat(((argb >> 16) & 0xFF) / 255f);
        b.putFloat(((argb >> 8) & 0xFF) / 255f);
        b.putFloat((argb & 0xFF) / 255f);
    }

    private static float clampParam(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    /** Position inside a cycle of {@code periodSec}, 0..1. */
    private static float phase(float periodSec) {
        long periodMs = Math.max(1L, (long) (periodSec * 1000f));
        return (System.currentTimeMillis() % periodMs) / (float) periodMs;
    }

}
