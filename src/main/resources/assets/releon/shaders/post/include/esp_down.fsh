#version 330
#extension GL_ARB_separate_shader_objects : require

// Dual-Kawase downsample, 5 taps, weights 4/1/1/1/1.
//
// Same kernel as the donor's core/shader_esp_down.fsh. Two things had to change for 26.3:
//
//   * the donor fed its own `EspKawaseData` UBO (halfPixelX, halfPixelY, offset). The engine's
//     `SamplerInfo` block replaces it - but note that vanilla fills it with *pixel* dimensions
//     (PostPass writes `putVec2(target.width, target.height)` and one vec2 per input), NOT with
//     normalised texel sizes. Dividing by it is therefore a division by the size in pixels.
//   * the explicit offset uniform is gone: the donor ramped it per level to widen the halo, which is
//     now expressed by walking more pyramid levels (see EspRenderer#levelsFor), so the kernel here
//     is the plain unit-offset variant.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // InSize is the source size in pixels -> 1.0 / InSize is one texel step in UV space.
    vec2 halfPixel = 0.5 / InSize;

    vec4 sum = texture(InSampler, texCoord) * 4.0;
    sum += texture(InSampler, texCoord - halfPixel);
    sum += texture(InSampler, texCoord + halfPixel);
    sum += texture(InSampler, texCoord + vec2(halfPixel.x, -halfPixel.y));
    sum += texture(InSampler, texCoord - vec2(halfPixel.x, -halfPixel.y));

    fragColor = vec4((sum * 0.125).rgb, 1.0);
}