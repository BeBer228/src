#version 330
// KillEffect: ударная волна от точки смерти + хроматическая аберрация + вспышка.
uniform sampler2D SceneSampler;

layout(std140) uniform ShockwaveConfig {
    vec2  InSize;
    vec2  Center;     // экранная позиция цели (0..1), из Render3D-проекции
    float Progress;   // 0..1 за ~600 мс
    float Strength;   // 0.02..0.06
    float Aberration; // 0.002..0.008
    float _pad;
    vec4  Tint;       // цвет вспышки
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float aspect = InSize.x / InSize.y;
    vec2  d   = texCoord - Center;
    vec2  da  = vec2(d.x * aspect, d.y);
    float dist = length(da);

    float radius = Progress * 1.2;
    float width  = 0.08 * (1.0 - Progress * 0.5);
    float ring   = 1.0 - smoothstep(0.0, width, abs(dist - radius));
    float fade   = 1.0 - Progress;

    vec2 dir = dist > 1e-4 ? d / dist : vec2(0.0);
    vec2 uv  = texCoord - dir * ring * Strength * fade;

    float ca = Aberration * (ring + 0.35) * fade;
    vec3 col;
    col.r = texture(SceneSampler, uv + dir * ca).r;
    col.g = texture(SceneSampler, uv).g;
    col.b = texture(SceneSampler, uv - dir * ca).b;

    // свечение кольца и мягкая виньетка-вспышка
    col += Tint.rgb * ring * fade * 0.35;
    float vig = smoothstep(0.35, 1.1, length(texCoord - 0.5) * 1.6);
    col = mix(col, col * Tint.rgb * 0.6, vig * fade * Tint.a);
    fragColor = vec4(col, 1.0);
}
