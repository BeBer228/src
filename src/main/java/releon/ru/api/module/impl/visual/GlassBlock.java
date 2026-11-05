package releon.ru.api.module.impl.visual;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.*;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.utils.render.*;
import releon.ru.utils.render.color.ClientTheme;
import releon.ru.api.module.impl.visual.esp.prism.PrismDraw;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.OptionalInt;
import java.util.OptionalDouble;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Highlights the block under the crosshair. Views: tinted Glass (per-face shading and sheen), two-colour
 * Gradient, Rainbow, animated End Sky. Shape: the block's bounding box, a full cube or every box of the
 * voxel shape. Appear (fade/grow/pop) and movement (smooth/spring/instant) are animated independently.
 *
 * <p>All layers go into one vertex buffer and one render pass per frame; each layer is a
 * {@code (pipeline, firstVertex, count)} draw. Depth test {@code GREATER_THAN_OR_EQUAL} (26.3
 * reverse-Z) without depth writes, so terrain in front occludes the panel. Outline edges are
 * camera-facing strips whose {@code UV0.x} is 0 at both rims and 1 at the core, which the line shader
 * feathers.</p>
 */
public class GlassBlock extends Module {

    // ------------------------------------------------------------------ pipelines

    private static final RenderPipeline TEX_TRANSLUCENT = IncludeWorldMesh.pipeline("glass/texture", "glass_tex", true, false, true, false);
    private static final RenderPipeline TEX_ADDITIVE = IncludeWorldMesh.pipeline("glass/texture_additive", "glass_tex", true, true, true, false);
    private static final RenderPipeline FILL_TRANSLUCENT = IncludeWorldMesh.pipeline("glass/fill", "glass_fill", true, false, false, false);
    private static final RenderPipeline LINE_TRANSLUCENT = IncludeWorldMesh.pipeline("glass/line", "glass_line", true, false, false, false);
    private static final RenderPipeline LINE_ADDITIVE = IncludeWorldMesh.pipeline("glass/line_additive", "glass_line", true, true, false, false);
    private final IncludeWorldMesh gpu = new IncludeWorldMesh();
    private long previousFrame;
    private float delta() { long now=System.nanoTime(); float dt=previousFrame==0?1f/60:Math.min(.1f,(now-previousFrame)/1e9f);previousFrame=now;return dt; }
    private static final Identifier END_SKY = Identifier.withDefaultNamespace("textures/environment/end_sky.png");
    private static final Identifier END_PORTAL = Identifier.withDefaultNamespace("textures/entity/end_portal.png");

    private static final int FACE_VERTS = 36, EDGE_VERTS = 144, MAX_PARTS = 8;

    // ------------------------------------------------------------------ settings

    public final ModeSetting view = register(new ModeSetting(
            "View", "Panel look", "Glass", "Glass", "Gradient", "Rainbow", "End Sky"));
    public final ModeSetting shape = register(new ModeSetting(
            "Shape", "Block bounds, a full cube or every part of the block shape", "Block", "Block", "Full Cube", "Parts"));

    public final BooleanSetting enableFill = register(new BooleanSetting("Fill", "Fill the block faces", true));
    public final ColorSetting fillColor = register(new ColorSetting("Fill Color", "Fill colour (gradient bottom)", new Color(0xFF7FD8FF,true)));
    public final ColorSetting fillColor2 = register(new ColorSetting("Fill Color 2", "Gradient top colour", new Color(0xFFAA44FF,true)));
    public final NumberSetting fillAlpha = register(new NumberSetting("Fill Strength", "Fill opacity", 0.3, 0.05, 1.0, 0.05));
    public final NumberSetting shading = register(new NumberSetting("Face Shading", "Darker sides and bottom, brighter top", 0.35, 0.0, 1.0, 0.05));
    public final NumberSetting sheen = register(new NumberSetting("Sheen", "Diagonal glass highlight on the faces", 0.4, 0.0, 1.0, 0.05));

    public final BooleanSetting enableOutline = register(new BooleanSetting("Outline", "Outline the block edges", true));
    public final ModeSetting outlineColorMode = register(new ModeSetting(
            "Outline Color Mode", "Outline colour source", "Fill", "Fill", "Custom", "Client", "Rainbow"));
    public final ColorSetting outlineColor = register(new ColorSetting("Outline Color", "Outline colour", new Color(0xFF00FFFF,true)));
    public final NumberSetting outlineWidth = register(new NumberSetting("Outline Width", "Outline half-width in blocks", 0.006, 0.001, 0.05, 0.001));
    public final NumberSetting outlineGlow = register(new NumberSetting("Outline Glow", "Soft additive halo around the outline", 0.6, 0.0, 1.0, 0.05));

    public final ModeSetting endColorMode = register(new ModeSetting(
            "End Color", "End sky colour", "Space", "Space", "Client", "Custom", "Gradient"));
    public final ColorSetting endCustomColor = register(new ColorSetting("Color 1", "Main End colour", new Color(0xFF8822FF,true)));
    public final ColorSetting endSecondaryColor = register(new ColorSetting("Color 2", "Second gradient colour", new Color(0xFF00FFFF,true)));
    public final NumberSetting endVoidDepth = register(new NumberSetting("Void Depth", "Background density", 0.85, 0.1, 1.0, 0.05));
    public final NumberSetting endAlpha = register(new NumberSetting("Star Brightness", "Star layer brightness", 0.95, 0.1, 1.0, 0.05));

    public final ModeSetting appear = register(new ModeSetting(
            "Appear", "How the panel shows and hides", "Grow", "Fade", "Grow", "Pop", "None"));
    public final NumberSetting appearSpeed = register(new NumberSetting("Appear Speed", "Show/hide speed", 1.0, 0.2, 3.0, 0.1));
    public final ModeSetting movement = register(new ModeSetting(
            "Movement", "How the panel travels to a new block", "Smooth", "Smooth", "Spring", "Instant"));
    public final NumberSetting moveSpeed = register(new NumberSetting("Move Speed", "How fast the panel slides to a new block", 1.0, 0.2, 3.0, 0.1));
    public final NumberSetting pulse = register(new NumberSetting("Pulse", "Breathing opacity (0 disables)", 0.0, 0.0, 1.0, 0.05));
    public final NumberSetting pulseSpeed = register(new NumberSetting("Pulse Speed", "Breathing rate", 1.0, 0.2, 3.0, 0.1));
    public final NumberSetting animSpeed = register(new NumberSetting("Animation Speed", "Texture scroll and colour cycle speed", 1.0, 0.1, 3.0, 0.1));

    // ------------------------------------------------------------------ state

    private float anim;
    private final double[] from = new double[6], to = new double[6], velocity = new double[6];
    /** Parts of the target shape normalised to its bounds (0..1), mapped into the animated box each frame. */
    private final List<double[]> parts = new ArrayList<>();
    private boolean boxStarted;
    private BlockPos lastPos;
    private BlockState lastState;
    private String lastShape;

    private ByteBufferBuilder allocator;
    private final List<Batch> batches = new ArrayList<>(6);
    private boolean reportedFailure;
    private final float[][] projectedBoxes=new float[MAX_PARTS][6];
    private final List<float[]> renderBoxes=new ArrayList<>(MAX_PARTS);

    private record Batch(RenderPipeline pipeline, GpuTextureView texture, int first, int count) {}

    /** Vertex colour from face index (-Y,+Y,-Z,+Z,-X,+X; -1 for edges) and box-local 0..1 position. */
    @FunctionalInterface
    private interface Paint { int at(int face, float u, float v, float w); }

    public GlassBlock() {
        super("Glass Block", "Turns the block under the crosshair into glass, a gradient or the End sky.", ModuleCategory.VISUAL);
        java.util.function.BooleanSupplier end = () -> view.is("End Sky");
        java.util.function.BooleanSupplier fill = () -> !end.getAsBoolean() && enableFill.getValue();
        enableFill.setVisible(() -> !end.getAsBoolean());
        fillColor.setVisible(() -> fill.getAsBoolean() && !view.is("Rainbow"));
        fillColor2.setVisible(() -> fill.getAsBoolean() && view.is("Gradient"));
        for (var s : List.of(fillAlpha, shading, sheen)) s.setVisible(fill);
        for (var s : List.of(outlineColorMode, outlineWidth, outlineGlow)) s.setVisible(enableOutline::getValue);
        outlineColor.setVisible(() -> enableOutline.getValue() && outlineColorMode.is("Custom"));
        for (var s : List.of(endColorMode, endVoidDepth, endAlpha)) s.setVisible(end);
        endCustomColor.setVisible(() -> end.getAsBoolean() && (endColorMode.is("Custom") || endColorMode.is("Gradient")));
        endSecondaryColor.setVisible(() -> end.getAsBoolean() && endColorMode.is("Gradient"));
        appearSpeed.setVisible(() -> !appear.is("None"));
        moveSpeed.setVisible(() -> !movement.is("Instant"));
        pulseSpeed.setVisible(() -> pulse.getValue() > 0);
        IncludeDefaults.apply("Glass Block",getSettings(),"");
        for (var setting:getSettings()) setting.setVisible(false);
        for (var setting:List.of(view,shape,fillAlpha,appear,movement)) setting.setVisible(true);
        fillColor.setVisible(()->!view.is("Rainbow")&&!view.is("End Sky"));
        fillColor2.setVisible(()->view.is("Gradient"));
        String[] choices={"Fill","Outline"};
        var features=register(new MultiModeSetting("Элементы","",choices,"Fill","Outline"));
        features.addListener((setting,old,value)->{enableFill.setValue(value.contains("Fill"));enableOutline.setValue(value.contains("Outline"));});
    }

    @Override
    public void onDisable() {
        anim = 0f;
        boxStarted = false;
        lastPos = null;
        previousFrame = 0;
        gpu.close();
        releaseAllocator();
    }

    // ------------------------------------------------------------------ render

    @SubscribeEvent
    public void onRender3D(WorldRenderEvent event) {
        if (mc.level == null || mc.player == null || mc.gameRenderer.getMainCamera() == null) return;

        float dt = Math.min(delta(), 0.1f);
        boolean hasTarget = targetBox();
        if (appear.is("None")) anim = hasTarget ? 1f : 0f;
        else anim += ((hasTarget ? 1f : 0f) - anim) * (1f - (float) Math.exp(-dt * 9f * appearSpeed.getFloat()));
        if (anim <= 0.01f) { anim = 0f; boxStarted = false; return; }
        move(dt);

        RenderTarget target = mc.getMainRenderTarget();
        GpuTextureView main = mc.getMainRenderTarget().getColorTextureView();
        GpuTextureView depth = depthView(target);
        GpuDevice device = RenderSystem.getDevice();
        if (main == null || depth == null || device == null) return;

        float time = PrismDraw.time() * animSpeed.getFloat();
        float breathe = 1f - pulse.getFloat() * (0.5f + 0.5f * Mth.sin(PrismDraw.time() * 3f * pulseSpeed.getFloat()));
        float scale = switch (appear.getValue()) {
            case "Grow" -> easeOut(anim);
            case "Pop" -> easeOutBack(anim);
            default -> 1f;
        };
        // scaling modes reach full opacity early so the size change carries the transition
        float t = (appear.is("Grow") || appear.is("Pop") ? Math.min(1f, anim * 1.6f) : anim) * breathe;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double cx = (from[0] + from[3]) * .5, cy = (from[1] + from[4]) * .5, cz = (from[2] + from[5]) * .5;
        double hx = (from[3] - from[0]) * .5 * scale, hy = (from[4] - from[1]) * .5 * scale, hz = (from[5] - from[2]) * .5 * scale;
        // Padded outwards by a hair so the block's own faces do not z-fight the panel.
        final double pad = 0.0008;
        double ox = cx - hx - cam.x - pad, oy = cy - hy - cam.y - pad, oz = cz - hz - cam.z - pad;
        double sx = hx * 2 + pad * 2, sy = hy * 2 + pad * 2, sz = hz * 2 + pad * 2;
        List<float[]> boxes=renderBoxes;boxes.clear();
        for(int i=0;i<parts.size();i++){
            double[] p=parts.get(i);float[] b=projectedBoxes[i];
            b[0]=(float)(ox+p[0]*sx);b[1]=(float)(oy+p[1]*sy);b[2]=(float)(oz+p[2]*sz);
            b[3]=(float)(ox+p[3]*sx);b[4]=(float)(oy+p[4]*sy);b[5]=(float)(oz+p[5]*sz);boxes.add(b);
        }
        int n = boxes.size();
        if (n == 0) return;

        if (allocator == null) allocator = new ByteBufferBuilder(65536);
        BufferBuilder buffer = new BufferBuilder(allocator, com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        batches.clear();
        int verts = 0;

        if (view.is("End Sky")) {
            int baseAlpha = Math.round(endVoidDepth.getFloat() * 240f * t);
            if (baseAlpha > 2) {
                int c = rgba(endBaseColor(), baseAlpha);
                for (float[] b : boxes) appendBoxFaces(buffer, b, (f, u, v, w) -> c, 0f, 0f, 1f);
                verts = batch(FILL_TRANSLUCENT, null, verts, FACE_VERTS * n);
            }
            int skyAlpha = Math.round(endAlpha.getFloat() * 200f * t);
            GpuTextureView sky = boundTexture(END_SKY);
            if (sky != null && skyAlpha > 2) {
                int c = rgba(endColor1(), skyAlpha);
                for (float[] b : boxes) appendBoxFaces(buffer, b, (f, u, v, w) -> c, positiveMod(time * 0.025f), positiveMod(time * 0.015f), 1.5f);
                verts = batch(TEX_TRANSLUCENT, sky, verts, FACE_VERTS * n);
            }
            int starAlpha = Math.round(255f * t);
            GpuTextureView portal = boundTexture(END_PORTAL);
            if (portal != null && starAlpha > 2) {
                int c = rgba(endColor2(), starAlpha);
                for (float[] b : boxes) appendBoxFaces(buffer, b, (f, u, v, w) -> c, positiveMod(-time * 0.04f), positiveMod(time * 0.035f), 2.5f);
                verts = batch(TEX_ADDITIVE, portal, verts, FACE_VERTS * n);
            }
        } else if (enableFill.getValue()) {
            int alpha = Math.round(fillAlpha.getFloat() * 255f * t);
            if (alpha > 2) {
                Paint paint = fillPaint(alpha, time);
                for (float[] b : boxes) appendBoxFaces(buffer, b, paint, 0f, 0f, 1f);
                verts = batch(FILL_TRANSLUCENT, null, verts, FACE_VERTS * n);
            }
        }

        if (enableOutline.getValue()) {
            float width = Math.max(0.0008f, outlineWidth.getFloat());
            float glow = outlineGlow.getFloat();
            int coreAlpha = Math.round(255f * (0.4f + 0.6f * glow) * t);
            int glowAlpha = Math.round(coreAlpha * 0.45f * glow);
            if (glow > 0.01f && glowAlpha > 2) {
                Paint halo = outlinePaint(glowAlpha, time);
                for (float[] b : boxes) appendBoxEdges(buffer, b[0], b[1], b[2], b[3], b[4], b[5], halo, width * 3.5f);
                verts = batch(LINE_ADDITIVE, null, verts, EDGE_VERTS * n);
            }
            if (coreAlpha > 2) {
                Paint core = outlinePaint(coreAlpha, time);
                for (float[] b : boxes) appendBoxEdges(buffer, b[0], b[1], b[2], b[3], b[4], b[5], core, width);
                verts = batch(LINE_TRANSLUCENT, null, verts, EDGE_VERTS * n);
            }
        }

        MeshData mesh = verts == 0 ? null : buffer.build();
        if (mesh == null) return;
        submit(device, main, depth, mesh);
    }

    /** Eases {@link #from} toward {@link #to}: exponential, slightly under-damped spring, or snap. */
    private void move(float dt) {
        switch (movement.getValue()) {
            case "Instant" -> System.arraycopy(to, 0, from, 0, 6);
            case "Spring" -> {
                double stiffness = 140.0 * moveSpeed.getValue() * moveSpeed.getValue(), damping = 1.4 * Math.sqrt(stiffness);
                for (int i = 0; i < 6; i++) {
                    velocity[i] += ((to[i] - from[i]) * stiffness - velocity[i] * damping) * dt;
                    from[i] += velocity[i] * dt;
                }
            }
            default -> {
                float k = 1f - (float) Math.exp(-dt * 15f * moveSpeed.getFloat());
                for (int i = 0; i < 6; i++) from[i] += (to[i] - from[i]) * k;
            }
        }
    }

    private int batch(RenderPipeline pipeline, GpuTextureView texture, int first, int count) {
        batches.add(new Batch(pipeline, texture, first, count));
        return first + count;
    }

    /** One upload, one render pass, one draw per layer. */
    private void submit(GpuDevice device, GpuTextureView main, GpuTextureView depth, MeshData mesh) {
        try (mesh) {
            var encoder = device.createCommandEncoder();
            gpu.upload(encoder, mesh);
            try (var pass = encoder.createRenderPass(() -> "Glass block", main, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                for (Batch layer : batches) {
                    pass.setPipeline(layer.pipeline());
                    gpu.bind(pass, false);
                    if (layer.texture() != null) pass.bindTexture("Sampler0", layer.texture(), RenderSampler.repeat());
                    pass.draw(layer.first(), layer.count());
                }
            }
        }
    }

    private static void appendBoxFaces(BufferBuilder b, float[] box, Paint paint, float u, float v, float uvScale) {
        float[] xs = {box[0], box[3]}, ys = {box[1], box[4]}, zs = {box[2], box[5]};
        int[][] faces = {
                {0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 0}, // -Y
                {0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1}, // +Y
                {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}, // -Z
                {1, 0, 1, 0, 0, 1, 0, 1, 1, 1, 1, 1}, // +Z
                {0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1, 1}, // -X
                {1, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0}  // +X
        };
        float[][] uv = {{u, v}, {u + uvScale, v}, {u + uvScale, v + uvScale}, {u, v + uvScale}};
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int face = 0; face < faces.length; face++) {
            int[] f = faces[face];
            for (int k : order) {
                int xi = f[k * 3], yi = f[k * 3 + 1], zi = f[k * 3 + 2];
                b.addVertex(xs[xi], ys[yi], zs[zi]).setUv(uv[k][0], uv[k][1]).setColor(paint.at(face, xi, yi, zi));
            }
        }
    }

    /** Twelve edges as camera-facing strips (camera at origin): rim (u=0) - core (u=1) - rim (u=0). */
    private static void appendBoxEdges(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1,
                                       Paint paint, float w) {
        float[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
            edge(b, paint, w, xs[0], ys[i], zs[j], xs[1], ys[i], zs[j], 0, i, j, 1, i, j);
            edge(b, paint, w, xs[i], ys[0], zs[j], xs[i], ys[1], zs[j], i, 0, j, i, 1, j);
            edge(b, paint, w, xs[i], ys[j], zs[0], xs[i], ys[j], zs[1], i, j, 0, i, j, 1);
        }
    }

    private static void edge(BufferBuilder b, Paint paint, float w,
                             float ax, float ay, float az, float bx, float by, float bz,
                             int au, int av, int aw, int bu, int bv, int bw) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float mx = (ax + bx) * .5f, my = (ay + by) * .5f, mz = (az + bz) * .5f;
        float sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
        float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        // Camera exactly on the edge line: nothing visible to draw, keep the vertex count fixed.
        if (len < 1e-6f) { sx = 0; sy = 0; sz = 0; } else { sx *= w / len; sy *= w / len; sz *= w / len; }
        int ca = paint.at(-1, au, av, aw), cb = paint.at(-1, bu, bv, bw);
        strip(b, ax - sx, ay - sy, az - sz, bx - sx, by - sy, bz - sz, ax, ay, az, bx, by, bz, ca, cb, 0f, 1f);
        strip(b, ax, ay, az, bx, by, bz, ax + sx, ay + sy, az + sz, bx + sx, by + sy, bz + sz, ca, cb, 1f, 0f);
    }

    private static void strip(BufferBuilder b, float ax, float ay, float az, float bx, float by, float bz,
                              float cx, float cy, float cz, float dx, float dy, float dz, int ca, int cb, float u0, float u1) {
        b.addVertex(ax, ay, az).setUv(u0, 0f).setColor(ca);
        b.addVertex(bx, by, bz).setUv(u0, 1f).setColor(cb);
        b.addVertex(dx, dy, dz).setUv(u1, 1f).setColor(cb);
        b.addVertex(ax, ay, az).setUv(u0, 0f).setColor(ca);
        b.addVertex(dx, dy, dz).setUv(u1, 1f).setColor(cb);
        b.addVertex(cx, cy, cz).setUv(u1, 0f).setColor(ca);
    }

    // ------------------------------------------------------------------ colours

    private int baseColor(float time, float u, float v, float w) {
        return switch (view.getValue()) {
            case "Gradient" -> PrismDraw.lerpColor(fillColor.getValue().getRGB(), fillColor2.getValue().getRGB(), v);
            case "Rainbow" -> rainbow(time, u, v, w);
            default -> fillColor.getValue().getRGB();
        };
    }

    /** Base colour, then per-face light (top bright, bottom dark) and a diagonal sheen toward the upper corner. */
    private Paint fillPaint(int alpha, float time) {
        float shade = shading.getFloat(), shine = sheen.getFloat();
        return (face, u, v, w) -> {
            float light = face == 1 ? 1f : face == 0 ? 1f - shade : 1f - shade * 0.45f;
            int c = PrismDraw.lerpColor(0xFF000000, baseColor(time, u, v, w), light);
            float gleam = shine * Math.max(0f, (u + v + w) / 3f - 0.34f) * 1.5f;
            if (gleam > 0) c = PrismDraw.lerpColor(c, 0xFFFFFFFF, Math.min(0.8f, gleam));
            return rgba(c, Math.round(alpha * (0.85f + 0.15f * gleam)));
        };
    }

    private Paint outlinePaint(int alpha, float time) {
        if (outlineColorMode.is("Rainbow")) return (f, u, v, w) -> rgba(rainbow(time, u, v, w), alpha);
        if (outlineColorMode.is("Fill") && !view.is("End Sky"))
            return (f, u, v, w) -> rgba(PrismDraw.lerpColor(baseColor(time, u, v, w), 0xFFFFFFFF, 0.25f), alpha);
        int c = rgba(switch (outlineColorMode.getValue()) {
            case "Client" -> ClientTheme.primary();
            case "Fill" -> endColor2();
            default -> outlineColor.getValue().getRGB();
        }, alpha);
        return (f, u, v, w) -> c;
    }

    private static int rainbow(float time, float u, float v, float w) {
        return Color.HSBtoRGB(time * 0.15f + (u + v + w) * 0.12f, 0.75f, 1f);
    }

    private int endBaseColor() {
        if (endColorMode.is("Client")) return PrismDraw.lerpColor(0xFF0D031A, ClientTheme.primary(), 0.35f);
        if (endColorMode.is("Custom") || endColorMode.is("Gradient")) return PrismDraw.lerpColor(0xFF000000, endCustomColor.getValue().getRGB(), 0.35f);
        return 0xFF0D031A;
    }

    private int endColor1() {
        if (endColorMode.is("Client")) return ClientTheme.primary();
        if (endColorMode.is("Custom") || endColorMode.is("Gradient")) return endCustomColor.getValue().getRGB();
        return 0xFF6A1B9A;
    }

    private int endColor2() {
        if (endColorMode.is("Client")) return PrismDraw.lerpColor(ClientTheme.primary(), 0xFFFFFFFF, 0.4f);
        if (endColorMode.is("Gradient")) return endSecondaryColor.getValue().getRGB();
        if (endColorMode.is("Custom")) return PrismDraw.lerpColor(endCustomColor.getValue().getRGB(), 0xFFFFFFFF, 0.35f);
        return 0xFF00E5FF;
    }

    // ------------------------------------------------------------------ targeting

    /** Fills {@link #to} and {@link #parts} from the block under the crosshair; re-read when block or shape mode changes. */
    private boolean targetBox() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!pos.equals(lastPos) || state != lastState || !shape.is(lastShape)) {
            var voxel = state.getShape(mc.level, pos);
            lastPos = pos.immutable();
            lastState = state;
            lastShape = shape.getValue();
            if (voxel.isEmpty()) { lastState = null; return false; }
            AABB bounds = shape.is("Full Cube") ? new AABB(0, 0, 0, 1, 1, 1) : voxel.bounds();
            to[0] = pos.getX() + bounds.minX; to[1] = pos.getY() + bounds.minY; to[2] = pos.getZ() + bounds.minZ;
            to[3] = pos.getX() + bounds.maxX; to[4] = pos.getY() + bounds.maxY; to[5] = pos.getZ() + bounds.maxZ;
            List<AABB> pieces = shape.is("Parts") ? voxel.toAabbs() : List.of(bounds);
            if (pieces.isEmpty() || pieces.size() > MAX_PARTS) pieces = List.of(bounds);
            double sx = Math.max(1e-6, bounds.getXsize()), sy = Math.max(1e-6, bounds.getYsize()), sz = Math.max(1e-6, bounds.getZsize());
            parts.clear();
            for (AABB a : pieces)
                parts.add(new double[]{(a.minX - bounds.minX) / sx, (a.minY - bounds.minY) / sy, (a.minZ - bounds.minZ) / sz,
                        (a.maxX - bounds.minX) / sx, (a.maxY - bounds.minY) / sy, (a.maxZ - bounds.minZ) / sz});
        }
        if (!boxStarted) {
            // Snap on the first frame so the panel does not fly in from the origin.
            System.arraycopy(to, 0, from, 0, 6);
            Arrays.fill(velocity, 0);
            boxStarted = true;
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static float easeOut(float t) {
        float r = 1f - t;
        return 1f - r * r * r;
    }

    private static float easeOutBack(float t) {
        float r = t - 1f;
        return 1f + 2.7f * r * r * r + 1.7f * r * r;
    }

    private static int rgba(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }

    private static float positiveMod(float value) {
        float r = value % 1f;
        return r < 0f ? r + 1f : r;
    }

    private static GpuTextureView depthView(RenderTarget target) {
        if (target == null) return null;
        try {
            return target.getDepthTextureView();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static GpuTextureView boundTexture(Identifier id) {
        if (mc.getTextureManager() == null) return null;
        try {
            var texture = mc.getTextureManager().getTexture(id);
            return texture == null ? null : texture.getTextureView();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void releaseAllocator() {
        if (allocator == null) return;
        try {
            allocator.close();
        } catch (Throwable ignored) {
        }
        allocator = null;
    }

    private void report(Throwable t) {
        if (!reportedFailure) System.err.println("[Include] glass block pass failed: " + t);
        reportedFailure = true;
        releaseAllocator();
    }
}
