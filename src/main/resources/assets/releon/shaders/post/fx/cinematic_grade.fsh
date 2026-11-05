#version 330
// Cinematic grade: кинематографичная цветокоррекция (ACES-тонмап, split-toning, виньетка, мягкий bloom-порог).
uniform sampler2D SceneSampler;

layout(std140) uniform GradeConfig {
    vec2  InSize;
    float Exposure;    // 0.9..1.3
    float Saturation;  // 0.9..1.3
    vec4  Shadows;     // цвет теней (напр. холодный синий)
    vec4  Highlights;  // цвет светов (напр. тёплый)
    float Vignette;    // 0..0.6
    float Bloom;       // 0..0.4
    vec2  _pad;
};

in vec2 texCoord;
out vec4 fragColor;

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec3 c = texture(SceneSampler, texCoord).rgb;

    // дешёвый bloom: 8 тапов по кругу, только яркие пиксели
    vec3 b = vec3(0.0);
    vec2 px = 4.0 / InSize;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        vec3 s = texture(SceneSampler, texCoord + vec2(cos(a), sin(a)) * px).rgb;
        b += max(s - 0.7, 0.0);
    }
    c += b / 8.0 * Bloom * 4.0;

    c = aces(c * Exposure);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, Saturation);
    c = mix(c * mix(vec3(1.0), Shadows.rgb, Shadows.a), c, smoothstep(0.0, 0.5, l));
    c = mix(c, c * mix(vec3(1.0), Highlights.rgb, Highlights.a), smoothstep(0.5, 1.0, l));
    c *= 1.0 - Vignette * smoothstep(0.3, 0.9, length(texCoord - 0.5) * 1.4);
    fragColor = vec4(c, 1.0);
}
