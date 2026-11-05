#version 330
#extension GL_ARB_separate_shader_objects : require

// Target ESP "Scan" circle: a glowing line sliding down the target's model with a trail behind it.
// Vertex colour carries the model data: r = face normal angle, g = normalised width, a = normalised height.

uniform sampler2D Sampler0;
layout(std140) uniform ScanParams {
    vec4 timing;   // x = phase, y = appear, z = glow strength, w = hit
    vec4 baseData; // rgb = scan colour, w = 1 when the skin texture is sampled
    vec4 hitColor; // rgb = colour while the target is hurt
};

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 skin = baseData.w > 0.5 ? texture(Sampler0, texCoord0) : vec4(1.0);
    if (skin.a < 0.1) {
        discard;
    }

    float phase = timing.x;
    float appear = timing.y;
    float glowStrength = timing.z;
    float hit = timing.w;
    float height = vertexColor.a;
    float horizontal = vertexColor.g;

    vec3 baseColor = mix(baseData.rgb, hitColor.rgb, hit);
    vec3 lineColor = mix(baseColor, vec3(1.0), 0.78 * (1.0 - hit));

    float scanHeight = 1.0 - phase;
    float signedDistance = height - scanHeight;
    float distanceToLine = abs(signedDistance);
    float antialias = max(fwidth(height) * 1.35, 0.0025);
    float coreWidth = mix(0.012, 0.009, hit);
    float glowWidth = (0.045 + glowStrength * 0.035) * mix(1.0, 0.86, hit);
    float core = 1.0 - smoothstep(coreWidth, coreWidth + antialias, distanceToLine);
    float halo = 1.0 - smoothstep(coreWidth + antialias, glowWidth + antialias, distanceToLine);

    float trailLength = 0.22 + glowStrength * 0.055;
    float trail = step(0.0, signedDistance) * (1.0 - smoothstep(0.0, trailLength, signedDistance));
    trail *= 1.0 - core;

    const float TWO_PI = 6.28318530718;
    float rotation = fract(vertexColor.r + horizontal * 0.12 - phase * 2.0);
    float rotatingArc = pow(max(0.0, cos(rotation * TWO_PI)), 10.0);
    float pulse = 0.92 + 0.08 * sin(phase * TWO_PI * 4.0);

    float opacity = (core * 0.98 + halo * (0.22 + glowStrength * 0.12) + trail * 0.13) * appear * skin.a;
    if (opacity < 0.002) {
        discard;
    }

    vec3 skinDetail = mix(vec3(1.0), skin.rgb, 0.16);
    vec3 emission = baseColor * skinDetail * (halo * (0.72 + glowStrength * 0.16) + trail * 0.28);
    emission += lineColor * core * pulse * (1.2 + rotatingArc * 0.85);
    fragColor = vec4(emission, clamp(opacity, 0.0, 1.0));
}
