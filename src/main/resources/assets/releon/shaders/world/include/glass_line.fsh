#version 330
#extension GL_ARB_separate_shader_objects : require

// Glass Block - glowing outline.
//
// The donor drew the box outline with `EntityEsp.BOX_LINE_LAYER` (LEQUAL, no depth write) and faked
// the glow by drawing the same box a second time, thicker and at lower alpha. Here the glow is two
// extra passes over the *same* outline quads with a larger half-width, which is what the donor's
// `outlineGlow` slider effectively did; the shader itself only has to feather the core so the
// stacked passes read as a gradient instead of a hard band.

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    // texCoord0 carries the position across the line width (0 = outer edge, 1 = centre) so the
    // outer glow falloff can be shaped without any extra uniform.
    float across = clamp(texCoord0.x, 0.0, 1.0);
    float feather = smoothstep(0.0, 1.0, across);
    float alpha = vertexColor.a * mix(0.15, 1.0, feather);
    if (alpha < 0.004) discard;
    fragColor = vec4(vertexColor.rgb, alpha);
}
