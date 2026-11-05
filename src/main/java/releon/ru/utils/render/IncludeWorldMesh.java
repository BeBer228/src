package releon.ru.utils.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

/** Reusable buffers for the ported world effects. Positions are relative to the active camera. */
public final class IncludeWorldMesh implements AutoCloseable {
    private GpuBuffer vertices, matrices, effect;
    private final Matrix4f matrix = new Matrix4f();
    private final java.util.Map<String, GpuBuffer> extra = new java.util.HashMap<>();
    public static RenderPipeline pipeline(String name, String fragment, boolean depth, boolean additive, boolean texture, boolean effect) {
        return pipeline(name, fragment, depth, additive, texture, effect, false, null);
    }
    public static RenderPipeline pipeline(String name, String fragment, boolean depth, boolean additive, boolean texture, boolean effect, boolean colorOnly, String extraBlock) {
        var builder = RenderPipeline.builder().withLocation(id("pipeline/include/" + name))
                .withVertexShader(id(colorOnly ? "world/include/color_mesh" : "world/include/mesh")).withFragmentShader(id("world/include/" + fragment))
                .withVertexFormat(colorOnly ? DefaultVertexFormat.POSITION_COLOR : DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.TRIANGLES)
                .withUniform("WorldMatrices", UniformType.UNIFORM_BUFFER)
                .withDepthTestFunction(depth ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false).withCull(false).withBlend(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT);
        if (texture) builder.withSampler("Sampler0");
        if (effect) builder.withUniform("VisualConfig", UniformType.UNIFORM_BUFFER);
        if (extraBlock != null) builder.withUniform(extraBlock, UniformType.UNIFORM_BUFFER);
        return RenderPipelines.register(builder.build());
    }
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("releon", path); }
    public void upload(CommandEncoder encoder, MeshData mesh) {
        var device = RenderSystem.getDevice();
        int bytes = mesh.vertexBuffer().remaining();
        if (vertices == null || vertices.size() < bytes) {
            if (vertices != null) vertices.close();
            int capacity = Math.max(65536, Integer.highestOneBit(bytes - 1) << 1);
            vertices = device.createBuffer(() -> "Include world vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
        }
        encoder.writeToBuffer(vertices.slice(0, bytes), mesh.vertexBuffer());
        if (matrices == null) matrices = device.createBuffer(() -> "Include world matrix", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(64);
            matrix.set(Render3D.lastProjMat).mul(Render3D.lastModMat).get(0, data);
            encoder.writeToBuffer(matrices.slice(), data);
        }
    }
    public void effect(CommandEncoder encoder, float type, float time, float speed, float glow) {
        if (effect == null) effect = RenderSystem.getDevice().createBuffer(() -> "Include effect parameters", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(16);
            data.putFloat(0, type).putFloat(4, time).putFloat(8, speed).putFloat(12, glow);
            encoder.writeToBuffer(effect.slice(), data);
        }
    }
    public void bind(RenderPass pass, boolean withEffect) {
        pass.setUniform("WorldMatrices", matrices);
        if (withEffect) pass.setUniform("VisualConfig", effect);
        pass.setVertexBuffer(0, vertices);
    }
    public void uniform(CommandEncoder encoder, String name, float[] values) {
        GpuBuffer buffer = extra.get(name);
        int bytes = (values.length * 4 + 15) & ~15;
        if (buffer == null || buffer.size() != bytes) {
            if (buffer != null) buffer.close();
            buffer = RenderSystem.getDevice().createBuffer(() -> name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, bytes);
            extra.put(name, buffer);
        }
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.calloc(bytes);
            for (int i = 0; i < values.length; i++) data.putFloat(i * 4, values[i]);
            encoder.writeToBuffer(buffer.slice(), data);
        }
    }
    public void bindExtra(RenderPass pass, String name) { pass.setUniform(name, extra.get(name)); }
    @Override public void close() {
        if (vertices != null) { vertices.close(); vertices = null; }
        if (matrices != null) { matrices.close(); matrices = null; }
        if (effect != null) { effect.close(); effect = null; }
        extra.values().forEach(GpuBuffer::close);
        extra.clear();
    }
}
