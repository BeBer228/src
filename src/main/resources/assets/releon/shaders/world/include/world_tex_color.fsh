#version 330
#extension GL_ARB_separate_shader_objects : require

// Textured billboard fragment stage: samples the particle / glyph texture and multiplies by the
// per-vertex tint (which already carries the fade alpha). Matches vanilla's
// core/position_tex_color.fsh layout.

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    // Discard fully transparent texels so the additive pass does not add black.
    if (tex.a <= 0.002) {
        discard;
    }
    vec4 color = tex * vertexColor;
    if (color.a <= 0.002) {
        discard;
    }
    fragColor = color;
}
