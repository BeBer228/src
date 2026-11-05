#version 330
#extension GL_ARB_separate_shader_objects : require

// Glass Block - opaque-ish tinted panel mode.
//
// Ported from the donor's `enableFill` path (`EntityEsp.BOX_FILL_LAYER`: no texture, plain vertex
// colour, depth-tested without depth write). The donor additionally multiplied a lightmap sample in;
// world blocks are lit by `LightTexture`, but this overlay is a deliberate highlight, so the colour
// is used as authored and only the vertex alpha drives transparency.

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    if (vertexColor.a < 0.004) discard;
    fragColor = vertexColor;
}
