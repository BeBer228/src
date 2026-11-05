#version 330
#extension GL_ARB_separate_shader_objects : require

// Flat vertex-coloured fragment stage for the ported 3D modules (Wings, PVPVisual).
// Blending and depth state live in the pipeline; this only writes the interpolated colour.
// Matches vanilla's core/position_color.fsh argument layout without the OIT paths, because these
// draws are transparent overlays that must not participate in vanilla's order-independent
// transparency accumulation.

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a == 0.0) {
        discard;
    }
    fragColor = vertexColor;
}
