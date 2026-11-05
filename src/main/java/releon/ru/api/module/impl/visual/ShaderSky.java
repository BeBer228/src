package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.*;
import releon.ru.utils.render.IncludeDefaults;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.color.ClientTheme;
import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.OptionalInt;

public final class ShaderSky extends Module {
    private static ShaderSky instance;
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("releon", "world/shader_sky");
    private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("releon", "pipeline/shader_sky"))
            .withVertexShader(SHADER).withFragmentShader(SHADER)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .withUniform("ShaderSkyData", UniformType.UNIFORM_BUFFER)
            .withUniform("SkyProjection", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withCull(false).build());
    private final ModeSetting mode = register(new ModeSetting("Mode", "Рисунок неба.", "Sky", "Aurora", "Night", "Snow", "Sky", "Star", "Glow"));
    private final NumberSetting intensity = register(new NumberSetting("Intensity", "Яркость.", 1, .1, 1, .05));
    private final NumberSetting speed = register(new NumberSetting("Speed", "Скорость анимации.", 1, .1, 3, .1));
    private final NumberSetting stars = register(new NumberSetting("Stars", "Плотность звёзд.", .75, 0, 1, .05));
    private final ModeSetting colorMode = register(new ModeSetting("Color Mode", "Цвет неба.", "Custom", "Theme", "Custom"));
    private final ColorSetting color1 = register(new ColorSetting("Color 1", "Основной цвет.", new Color(-10328335, true)));
    private final ColorSetting color2 = register(new ColorSetting("Color 2", "Второй цвет.", new Color(-10328335, true)));
    private GpuBuffer dataBuffer, projectionBuffer;
    private final Matrix4f inverse = new Matrix4f();
    private long start;

    public ShaderSky() {
        super("Shader Sky", "Процедурное небо с солнцем, луной и звёздами Minecraft.", ModuleCategory.VISUAL);
        instance = this;
        IncludeDefaults.apply("Shader Sky", getSettings(), "");
        color1.visibleWhen(() -> colorMode.is("Custom"));
        color2.visibleWhen(() -> colorMode.is("Custom"));
    }
    @Override protected void onEnable() { start = System.currentTimeMillis(); }
    @Override protected void onDisable() {
        if (dataBuffer != null) { dataBuffer.close(); dataBuffer = null; }
        if (projectionBuffer != null) { projectionBuffer.close(); projectionBuffer = null; }
    }
    public static void renderSky() {
        ShaderSky sky = instance;
        if (sky == null || !sky.isEnabled() || sky.mc.level == null) return;
        sky.draw();
    }
    private void draw() {
        var target = mc.getMainRenderTarget();
        if (target == null || target.getColorTextureView() == null) return;
        var device = RenderSystem.getDevice();
        if (dataBuffer == null) dataBuffer = device.createBuffer(() -> "Shader sky data", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 96);
        if (projectionBuffer == null) projectionBuffer = device.createBuffer(() -> "Shader sky projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        var encoder = device.createCommandEncoder();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.calloc(96);
            data.putFloat(0, target.width).putFloat(4, target.height).putFloat(8, (System.currentTimeMillis() - start) / 1000f).putFloat(12, .78f);
            int first = colorMode.is("Custom") ? color1.getValue().getRGB() : ClientTheme.primary();
            int second = colorMode.is("Custom") ? color2.getValue().getRGB() : ClientTheme.secondary();
            putColor(data, 16, first); putColor(data, 32, second); putColor(data, 48, 0xFFFFFFFF);
            data.putFloat(64, mode.getModes().indexOf(mode.getValue())).putFloat(68, speed.getFloat()).putFloat(72, 1.10f).putFloat(76, intensity.getFloat());
            data.putFloat(80, stars.getFloat()).putFloat(84, 1);
            encoder.writeToBuffer(dataBuffer.slice(), data);
            ByteBuffer projection = stack.malloc(64);
            inverse.set(Render3D.lastProjMat).mul(Render3D.lastModMat).invert().get(0, projection);
            encoder.writeToBuffer(projectionBuffer.slice(), projection);
        }
        try (var pass = encoder.createRenderPass(() -> "Shader sky", target.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("ShaderSkyData", dataBuffer);
            pass.setUniform("SkyProjection", projectionBuffer);
            pass.draw(0, 3);
        }
    }
    private static void putColor(ByteBuffer b, int offset, int color) {
        b.putFloat(offset, (color >> 16 & 255) / 255f).putFloat(offset + 4, (color >> 8 & 255) / 255f)
                .putFloat(offset + 8, (color & 255) / 255f).putFloat(offset + 12, (color >>> 24) / 255f * .78f);
    }
}
