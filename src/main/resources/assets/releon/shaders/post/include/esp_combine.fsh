#version 330
#extension GL_ARB_separate_shader_objects : require

// Additive composite: sharp silhouette mask + dual-Kawase blurred silhouette -> screen.
//
// Ported 1:1 from the donor's core/shader_esp_combine.fsh. The UBO layout, the friend-tint trick
// (friends are identified by a channel ratio inside the blurred mip where brightness is meaningless), the halo/fill/rim/shimmer/pulse maths and the additive
// colour convention are unchanged. Two additions:
//
//   * `params6` - edge avoidance. The donor relied on the vanilla entity-outline depth test to stop
//     its glow from bleeding through walls. Depth is unavailable here (the composite runs after the
//     depth buffer has been reused), so the main target's own colour is used as a cheap edge/occlusion
//     detector: where a candidate glow pixel differs sharply from its neighbour, the world thinks a
//     surface is in front and the glow is suppressed. Luminance-only, so it is invariant to colour.
//   * the friend classification falls back to the main target when neither the mask nor the glow
//     carries a tint, so a friend standing behind another silhouette still reads green.

uniform sampler2D MaskTexture;   // sharp silhouette, full resolution
uniform sampler2D GlowTexture;   // blurred silhouette, half resolution
uniform sampler2D SceneTexture;  // main render target, used only for edge avoidance

layout(std140) uniform EspParams {
    vec4 mainColor;    // rgb - main colour, a - global opacity
    vec4 friendColor;  // rgb - friend colour, a - 1 when friend tinting is on
    vec4 params1;      // texelX, texelY, outlineWidth, outlineMode (0 outside, 1 inside, 2 both)
    vec4 params2;      // glowStrength, glowFalloff, fillOpacity, innerGlow
    vec4 params3;      // outlineStrength, outlineWhite, glowEnabled, fillEnabled
    vec4 params4;      // shimmerT, shimmerEnabled, shimmerWidth, shimmerBrightness
    vec4 params5;      // pulseAmount, pulseT, saturation, outlineEnabled
    vec4 params6;      // edgeThreshold, hurtEnabled, 0, 0
    vec4 hurtColor;    // rgb - colour flashed on damage
};

// Matches the `texCoord` output of core/esp_screenquad.vsh (location 0).
in vec2 texCoord;
out vec4 fragColor;

float maxChan(vec3 c) {
    return max(c.r, max(c.g, c.b));
}

// Mask encoding (ShaderEsp.outlineColor): G is always 1, friends lower B to 0x55, damage lowers R.
// Channel ratios do not depend on brightness, so this also works on faint blurred edges.
bool isFriendTint(vec3 c) {
    return c.b < c.g * 0.6;
}

// Distance in pixels to the nearest pixel of the opposite state (outside -> silhouette, inside ->
// background), searched on 8 rays ring by ring with an early exit: 8*w taps instead of (2w+1)^2.
// Returns a large number when no boundary is nearby.
const vec2 RAYS[8] = vec2[8](vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0),
                             vec2(0.7071, 0.7071), vec2(-0.7071, 0.7071), vec2(0.7071, -0.7071), vec2(-0.7071, -0.7071));

float edgeDistance(int w, bool inside) {
    vec2 ts = params1.xy;
    for (int r = 1; r <= w; r++) {
        for (int i = 0; i < 8; i++) {
            float m = maxChan(textureLod(MaskTexture, texCoord + RAYS[i] * float(r) * ts, 0.0).rgb);
            if (inside ? (m < 0.5) : (m >= 0.5)) return float(r);
        }
    }
    return float(w) + 1.0;
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 sceneUV = texCoord;
    vec3 maskS = texture(MaskTexture, texCoord).rgb;
    vec3 glowS = texture(GlowTexture, texCoord).rgb;

    float sharp = maxChan(maskS);
    float blur = clamp(maxChan(glowS), 0.0, 1.0);
    bool inside = sharp >= 0.5;

    // Nothing past the halo -> not our business.
    if (!inside && blur < 0.0015) discard;

    vec3 tint = mainColor.rgb;
    if (friendColor.a > 0.5) {
        vec3 probe = inside ? maskS : glowS;
        if (isFriendTint(probe)) tint = friendColor.rgb;
    }
    if (params6.y > 0.5) {
        vec3 probe = inside ? maskS : glowS;
        float hurt = clamp((probe.g - probe.r) / max(probe.g, 1e-4) / 0.66, 0.0, 1.0);
        tint = mix(tint, hurtColor.rgb, hurt);
    }
    float grey = dot(tint, vec3(0.2126, 0.7152, 0.0722));
    tint = clamp(mix(vec3(grey), tint, params5.z), 0.0, 1.0);

    float pulse = 1.0 + params5.x * sin(params5.y * 6.2831853);

    // --- outer halo -------------------------------------------------------
    float halo = 0.0;
    if (params3.z > 0.5 && !inside) {
        halo = pow(blur, params2.y) * params2.x;
    }

    // --- silhouette fill + inner glow towards the rim ----------------------
    float fill = 0.0;
    if (params3.w > 0.5 && inside) {
        fill = params2.z + pow(clamp(1.0 - blur, 0.0, 1.0), 1.5) * params2.w;
    }

    // --- rim along the silhouette boundary ---------------------------------
    float rim = 0.0;
    int w = int(params1.z + 0.5);
    if (params5.w > 0.5 && w > 0) {
        int mode = int(params1.w + 0.5);
        if (mode == 2 || (mode == 0 && !inside) || (mode == 1 && inside)) {
            float d = edgeDistance(w, inside);
            if (d <= float(w)) {
                rim = (1.0 - d / (float(w) + 1.0)) * params3.x;
            }
        }
    }

    if (halo + fill + rim <= 0.0) discard;

    vec3 scene = vec3(0.0);
    float edge = 0.0;
    if (params6.x > 0.0) {
        scene = texture(SceneTexture, sceneUV).rgb;
        vec2 one = params1.xy;
        float centre = luma(scene);
        float l = luma(texture(SceneTexture, sceneUV - vec2(one.x, 0.0)).rgb);
        float r = luma(texture(SceneTexture, sceneUV + vec2(one.x, 0.0)).rgb);
        float u = luma(texture(SceneTexture, sceneUV - vec2(0.0, one.y)).rgb);
        float d = luma(texture(SceneTexture, sceneUV + vec2(0.0, one.y)).rgb);
        edge = clamp(max(max(abs(centre - l), abs(centre - r)),
                         max(abs(centre - u), abs(centre - d))) / params6.x, 0.0, 1.0);
    }

    float body = (halo + fill) * pulse;
    // Glow is only suppressed by occlusion; the interior of the silhouette is left untouched.
    float occlusion = inside ? 1.0 : (1.0 - edge);
    body *= occlusion;

    vec3 rgb = tint * body;
    rgb += mix(tint, vec3(1.0), clamp(params3.y, 0.0, 1.0)) * rim * pulse * occlusion;

    // --- vertical shimmer sweep -------------------------------------------
    if (params4.y > 0.5) {
        float sweep = 1.0 - smoothstep(0.0, params4.z, abs(texCoord.y - params4.x));
        rgb += vec3(sweep * params4.w * max(body, rim));
    }

    rgb *= mainColor.a;

    if (maxChan(rgb) < 0.002) discard;

    // additive blending (SRC_ALPHA, ONE) - brightness already lives in rgb,
    // channel overshoot is what produces the white core on the rim
    fragColor = vec4(rgb, 1.0);
}
