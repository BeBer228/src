#version 330
#extension GL_ARB_separate_shader_objects : require

// Ported from the donor's core/shader_sky.fsh; only the stage interface is explicit (locations).

layout(std140) uniform ShaderSkyData {
    vec4 screenTimeOpacity;
    vec4 primaryColor;
    vec4 secondaryColor;
    vec4 accentColor;
    vec4 params;
    vec4 extra;
};

in vec3 skyDir;

layout(location = 0) out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * vec3(123.34, 456.21, 321.79));
    p += dot(p, p.yzx + 45.32);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);

    float n000 = hash(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash(i + vec3(1.0, 1.0, 1.0));

    float nx00 = mix(n000, n100, u.x);
    float nx10 = mix(n010, n110, u.x);
    float nx01 = mix(n001, n101, u.x);
    float nx11 = mix(n011, n111, u.x);
    float nxy0 = mix(nx00, nx10, u.y);
    float nxy1 = mix(nx01, nx11, u.y);
    return mix(nxy0, nxy1, u.z);
}

float fbm(vec3 p) {
    float value = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        value += noise(p) * amp;
        p = mat3(
            1.53, 0.72, 0.21,
           -0.64, 1.39, 0.57,
            0.31,-0.48, 1.71
        ) * p;
        amp *= 0.5;
    }
    return value;
}

vec2 domeUv(vec3 dir) {
    dir = normalize(dir);
    float k = 1.0 / (1.0 + max(dir.y, -0.35));
    return dir.xz * k * 0.55 + 0.5;
}

float hash2(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash2(i);
    float b = hash2(i + vec2(1.0, 0.0));
    float c = hash2(i + vec2(0.0, 1.0));
    float d = hash2(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm2(vec2 p) {
    float value = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 6; i++) {
        value += valueNoise2(p) * amp;
        p = mat2(1.6, 1.2, -1.2, 1.6) * p;
        amp *= 0.5;
    }
    return value;
}

float stars(vec3 dir, float amount, float time) {
    vec3 grid = dir * 130.0;
    vec3 cell = floor(grid);
    vec3 local = fract(grid) - 0.5;
    float h = hash(cell);
    float sparkle = smoothstep(0.997 - amount * 0.045, 1.0, h);
    float shape = smoothstep(0.18, 0.0, length(local));
    return sparkle * shape * (0.65 + 0.35 * sin(time * 2.0 + h * 12.0));
}

vec3 aurora(vec3 dir, float time, float scale, float intensity) {
    vec3 p = dir * scale * 2.6;
    float flow = fbm(p + vec3(time * 0.08, -time * 0.03, time * 0.04));
    float bands = 0.0;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float center = -0.15 + fi * 0.18 + sin((dir.x + dir.z) * (2.2 + fi) + time * (0.35 + fi * 0.08)) * 0.10;
        bands += smoothstep(0.18, 0.0, abs(dir.y - center - flow * 0.16));
    }
    vec3 base = mix(primaryColor.rgb * 0.16, secondaryColor.rgb * 0.32, clamp(dir.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 glow = mix(primaryColor.rgb, secondaryColor.rgb, flow);
    return base + glow * bands * 0.55 * intensity + accentColor.rgb * pow(bands, 3.0) * 0.24;
}

vec3 night(vec3 dir, float time, float scale, float intensity) {
    vec2 uv = domeUv(dir) * scale;
    vec2 drift = vec2(time * 0.0062379, cos(time * 0.0962379)) * (sin(time * 0.0041839) + 1.1);
    float n1 = fbm2(uv * 2.0 + drift);
    float n2 = fbm2(uv * 6.0 + drift * 1.15 + 12.523);
    float cloud = pow(max(0.8 - n1, 0.0), 2.0);
    float core = pow(max((0.9 - n2) * cloud, 0.0), 1.1);
    vec2 starCell = floor(uv * 420.0);
    float r1 = hash2(starCell);
    float r2 = hash2(starCell.yx + 17.0);
    float twinkle = sin((time + 10.0) * r1 * 2.7) * 0.8 + 0.5;
    float star = r1 * pow(r2, 20.0) * twinkle * 0.9;
    float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 base = mix(primaryColor.rgb * 0.08, secondaryColor.rgb * 0.22, h);
    return base + (primaryColor.rgb * cloud * 0.55 + secondaryColor.rgb * core * 0.95 + accentColor.rgb * star) * intensity;
}

vec3 snow(vec3 dir, float time, float scale, float intensity) {
    vec2 uv = domeUv(dir) * 2.0 - 1.0;
    float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 base = mix(primaryColor.rgb * 0.10, secondaryColor.rgb * 0.18, h);
    float c = smoothstep(1.0, 0.3, clamp(uv.y * 0.3 + 0.8, 0.0, 0.75));
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float sc = (6.0 + fi * 2.5) * scale;
        vec2 p = uv;
        p += time / sc;
        p.y += time * 2.0 / sc;
        p.x += sin(p.y + time * 0.5) / sc;
        p *= sc;
        vec2 cell = floor(p);
        vec2 local = fract(p);
        vec2 flake = 0.5 + 0.35 * sin(11.0 * fract(sin((cell + sc) * mat2(7.0, 3.0, 6.0, 5.0)) * 5.0)) - local;
        c += smoothstep(0.035, 0.0, length(flake)) * (0.45 + fi * 0.08);
    }
    return base + mix(primaryColor.rgb, accentColor.rgb, 0.75) * c * intensity;
}

vec3 sky(vec3 dir, float time, float scale, float intensity) {
    vec2 p = domeUv(dir) * scale * 1.1;
    float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    float q = fbm2(p * 0.5);
    float r = 0.0;
    vec2 uv = p - q + time * 0.03;
    float weight = 0.8;
    for (int i = 0; i < 8; i++) {
        r += abs(weight * valueNoise2(uv));
        uv = mat2(1.6, 1.2, -1.2, 1.6) * uv + time * 0.03;
        weight *= 0.7;
    }
    float f = 0.0;
    uv = p - q + time * 0.03;
    weight = 0.2;
    for (int i = 0; i < 8; i++) {
        f += weight * valueNoise2(uv);
        uv = mat2(1.6, 1.2, -1.2, 1.6) * uv + time * 0.03;
        weight *= 0.6;
    }
    float clouds = clamp(0.2 + 8.0 * f * r + r * 0.25, 0.0, 1.0);
    vec3 base = mix(primaryColor.rgb * 0.30, secondaryColor.rgb * 0.50, h);
    vec3 cloud = vec3(1.1, 1.1, 0.9) * mix(accentColor.rgb, secondaryColor.rgb, 0.18);
    return mix(base, cloud, clouds * intensity);
}

vec3 starMode(vec3 dir, float time, float scale, float intensity) {
    vec2 uv = (domeUv(dir) - 0.5) * vec2(2.0, 2.0) * scale;
    uv.x += sin(0.2 + uv.y * 0.8) * 0.5;
    uv.x *= 50.0;
    float dx = fract(uv.x);
    uv.x = floor(uv.x);
    uv.y *= 0.15;
    float o = sin(uv.x * 215.4);
    float s = cos(uv.x * 33.1) * 0.3 + 0.7;
    float trail = mix(95.0, 35.0, s);
    float yv = fract(uv.y + time * 0.4 * s + o) * trail;
    yv = 1.0 / max(yv, 0.001);
    yv = smoothstep(0.0, 1.0, yv * yv);
    yv = sin(yv * 3.14159) * (s * 5.0);
    float d2 = sin(dx * 3.14159);
    yv *= d2 * d2;
    float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 base = mix(primaryColor.rgb * 0.05, secondaryColor.rgb * 0.18, h);
    return base + mix(primaryColor.rgb, secondaryColor.rgb, s) * sqrt(max(yv, 0.0)) * intensity;
}

vec3 glow(vec3 dir, float time, float scale, float intensity) {
    vec2 uv = (domeUv(dir) - 0.5) * 2.0 * scale;
    for (float i = 1.0; i < 10.0; i++) {
        uv.x += 0.6 / i * cos(i * 2.5 * uv.y + time);
        uv.y += 0.6 / i * cos(i * 1.5 * uv.x + time);
    }
    float wave = 0.1 / max(abs(sin(time - uv.y - uv.x)), 0.035);
    float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 base = mix(primaryColor.rgb * 0.04, secondaryColor.rgb * 0.10, h);
    return base + mix(primaryColor.rgb, secondaryColor.rgb, smoothstep(0.0, 1.2, wave)) * wave * intensity;
}

void main() {
    float time = screenTimeOpacity.z * params.y;
    float opacity = screenTimeOpacity.w;
    float mode = params.x;
    float scale = max(params.z, 0.01);
    float intensity = params.w;
    float starAmount = extra.x;
    float vanillaBlend = extra.y;
    vec3 dir = normalize(skyDir);
    vec3 color;

    if (mode < 0.5) {
        color = aurora(dir, time, scale, intensity);
    } else if (mode < 1.5) {
        color = night(dir, time, scale, intensity);
    } else if (mode < 2.5) {
        color = snow(dir, time, scale, intensity);
    } else if (mode < 3.5) {
        color = sky(dir, time, scale, intensity);
    } else if (mode < 4.5) {
        color = starMode(dir, time, scale, intensity);
    } else {
        color = glow(dir, time, scale, intensity);
    }

    float starValue = stars(dir, starAmount, time) * smoothstep(-0.15, 0.65, dir.y);
    color += accentColor.rgb * starValue;
    color *= 0.72 + smoothstep(-0.65, 0.85, dir.y) * 0.34;

    float alpha = opacity * mix(1.0, 0.72, clamp(vanillaBlend, 0.0, 1.0));
    fragColor = vec4(color, alpha);
}
