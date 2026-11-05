#version 330
#extension GL_ARB_separate_shader_objects : require

// Dual-Kawase upsample: 8 taps on a diamond, weights 1/2/1/2/1/2/1/2, normalised by 0.0833.
//
// Ported from the donor's core/shader_esp_up.fsh, minus its per-level `offset` uniform: the donor
// multiplied the tap radius by `0.5 + radius * 3.5` and wrote that into its own UBO. The same
// widening is produced here by the caller walking a deeper pyramid (one extra level roughly doubles
// the halo), which removes a uniform and keeps the slider linear in perceived spread.
//
// `InSize` is the source size in pixels (see esp_down.fsh).

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 h = 1.0 / InSize;

    vec4 sum = texture(InSampler, texCoord + vec2(-h.x * 2.0, 0.0));
    sum += texture(InSampler, texCoord + vec2(-h.x, h.y)) * 2.0;
    sum += texture(InSampler, texCoord + vec2(0.0, h.y * 2.0));
    sum += texture(InSampler, texCoord + vec2(h.x, h.y)) * 2.0;
    sum += texture(InSampler, texCoord + vec2(h.x * 2.0, 0.0));
    sum += texture(InSampler, texCoord + vec2(h.x, -h.y)) * 2.0;
    sum += texture(InSampler, texCoord + vec2(0.0, -h.y * 2.0));
    sum += texture(InSampler, texCoord + vec2(-h.x, -h.y)) * 2.0;

    fragColor = vec4((sum * 0.0833).rgb, 1.0);
}