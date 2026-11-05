package releon.ru.utils.render.post.fx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import releon.ru.utils.render.RenderSampler;

import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Универсальный полноэкранный пост-эффект в стиле FogBlurRenderer:
 * копия сцены -> SceneSampler, один UBO, один fullscreen-проход прямо в целевой таргет.
 * Ресурсы создаются лениво и пересоздаются только при смене разрешения.
 * При любой ошибке эффект отключается навсегда (как в FogBlur), чтобы не спамить крашами.
 */
public final class PostShaderPass {
    private static final Identifier FULLSCREEN_VERTEX = Identifier.fromNamespaceAndPath("releon", "post/fx/fullscreen");

    private final String name;
    private final Identifier fragment;
    private final String uniformBlock;
    private final int uniformSize;
    private final String[] extraSamplers;

    private RenderPipeline pipeline;
    private GpuBuffer uniformBuffer;
    private GpuTexture sceneCopy;
    private GpuTextureView sceneCopyView;
    private int width = -1;
    private int height = -1;
    private boolean disabled;

    /**
     * @param name         короткое имя (для отладки и id пайплайна)
     * @param fragment     фрагментный шейдер, например releon:post/fx/kill_shockwave
     * @param uniformBlock имя std140-блока в шейдере
     * @param uniformSize  размер блока в байтах (кратно 16)
     */
    public PostShaderPass(String name, Identifier fragment, String uniformBlock, int uniformSize, String... extraSamplers) {
        this.extraSamplers = extraSamplers;
        this.name = name;
        this.fragment = fragment;
        this.uniformBlock = uniformBlock;
        this.uniformSize = uniformSize;
    }

    public boolean isDisabled() {
        return disabled;
    }

    /**
     * @param target   основной таргет (тот же, что приходит в onAfterTranslucent)
     * @param uniforms запись UBO: смещения в байтах по std140
     */
    public void apply(RenderTarget target, Consumer<ByteBuffer> uniforms) {
        apply(target, uniforms, null);
    }

    /** @param bindings дополнительные bindTexture(...) для extraSamplers */
    public void apply(RenderTarget target, Consumer<ByteBuffer> uniforms, Consumer<RenderPass> bindings) {
        if (disabled || target == null || target.getColorTexture() == null || target.getColorTextureView() == null
                || target.width <= 0 || target.height <= 0) {
            return;
        }
        try {
            if (!init() || !ensureCopy(target.width, target.height)) {
                return;
            }
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(target.getColorTexture(), sceneCopy, 0, 0, 0, 0, 0, target.width, target.height);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = stack.calloc(uniformSize);
                uniforms.accept(data);
                data.position(0);
                encoder.writeToBuffer(uniformBuffer.slice(0, uniformSize), data);
            }

            try (RenderPass pass = encoder.createRenderPass(() -> "releon:" + name, target.getColorTextureView(), OptionalInt.empty())) {
                pass.setPipeline(pipeline);
                pass.setUniform(uniformBlock, uniformBuffer);
                pass.bindTexture("SceneSampler", sceneCopyView, RenderSampler.linear());
                if (bindings != null) {
                    bindings.accept(pass);
                }
                pass.draw(0, 6);
            }
        } catch (Throwable t) {
            disabled = true;
            t.printStackTrace();
            close();
        }
    }

    private boolean init() {
        if (pipeline == null) {
            RenderPipeline.Builder builder = RenderPipeline.builder()
                            .withLocation(Identifier.fromNamespaceAndPath("releon", "pipeline/post/fx/" + name))
                            .withVertexShader(FULLSCREEN_VERTEX)
                            .withFragmentShader(fragment)
                            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                            .withUniform(uniformBlock, UniformType.UNIFORM_BUFFER)
                            .withSampler("SceneSampler")
                            .withBlend(BlendFunction.TRANSLUCENT)
                            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                            .withDepthWrite(false)
                            .withCull(false);
            for (String sampler : extraSamplers) {
                builder = builder.withSampler(sampler);
            }
            pipeline = RenderPipelines.register(builder.build());
        }
        if (uniformBuffer == null || uniformBuffer.isClosed()) {
            uniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "releon:" + name + "_uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    uniformSize
            );
        }
        return true;
    }

    private boolean ensureCopy(int w, int h) {
        if (sceneCopy != null && w == width && h == height) {
            return true;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return false;
        }
        closeCopy();
        sceneCopy = device.createTexture(() -> "releon:" + name + "_scene",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, w, h, 1, 1);
        sceneCopyView = device.createTextureView(sceneCopy);
        width = w;
        height = h;
        return true;
    }

    public void close() {
        closeCopy();
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
    }

    private void closeCopy() {
        if (sceneCopyView != null) {
            sceneCopyView.close();
            sceneCopyView = null;
        }
        if (sceneCopy != null) {
            sceneCopy.close();
            sceneCopy = null;
        }
        width = -1;
        height = -1;
    }
}
