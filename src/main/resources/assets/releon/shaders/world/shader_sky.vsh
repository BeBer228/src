#version 330
layout(std140) uniform SkyProjection { mat4 InverseViewProjection; };
out vec3 skyDir;
void main() {
    vec2 ndc = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vec4 p = InverseViewProjection * vec4(ndc, 0.5, 1.0);
    skyDir = p.xyz / p.w;
}
