#version 330
#extension GL_ARB_separate_shader_objects : require

// Full-screen triangle. The donor's core/shader_esp_glow.vsh emitted a six-vertex quad read from
// gl_VertexID; with a triangle list a single oversized triangle covers the viewport with three
// vertices and no tearing along the shared diagonal, and it matches the vanilla core/screenquad.vsh
// convention (`RenderPass#draw(0, 3, 0, 1)`).
//
// texCoord is in [0,1] with (0,0) at the top-left, the same orientation as every vanilla screen
// sampler, so the mask read by the combine pass lines up with the main target.

out vec2 texCoord;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * vec2(2.0, 2.0) + vec2(-1.0, -1.0), 0.0, 1.0);
    texCoord = uv;
}
