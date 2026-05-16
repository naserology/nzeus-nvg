#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform samplerExternalOES uTex;    // Camera SurfaceTexture (YUV->RGB native)
uniform sampler2D uMlTex;           // Optional: Zero-DCE enhanced texture
uniform float uHasMl;               // 0.0 or 1.0
uniform vec2 uResolution;

// Mode: 0=NVG, 1=THERMAL, 2=CLAHE-color, 3=RAW
uniform int uMode;
uniform float uGain;
uniform float uGamma;
uniform float uEdge;
uniform float uPhosphor;
uniform float uTime;
uniform float uDenoise;             // 0..1 bilateral strength

in vec2 vTex;
out vec4 fragColor;

// 3x3 bilateral-ish denoise (cheap, GPU-friendly)
vec3 denoise3x3(samplerExternalOES tex, vec2 uv, vec2 px, float strength) {
    if (strength < 0.01) return texture(tex, uv).rgb;
    vec3 center = texture(tex, uv).rgb;
    vec3 sum = center;
    float wsum = 1.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            vec2 o = vec2(float(dx), float(dy)) * px;
            vec3 s = texture(tex, uv + o).rgb;
            float d = length(s - center);
            float w = exp(-d * d * 25.0);
            sum += s * w;
            wsum += w;
        }
    }
    vec3 filtered = sum / wsum;
    return mix(center, filtered, strength);
}

// Sobel edge magnitude
float sobel(samplerExternalOES tex, vec2 uv, vec2 px) {
    float tl = dot(texture(tex, uv + vec2(-px.x,-px.y)).rgb, vec3(0.299,0.587,0.114));
    float  t = dot(texture(tex, uv + vec2( 0.0,-px.y)).rgb, vec3(0.299,0.587,0.114));
    float tr = dot(texture(tex, uv + vec2( px.x,-px.y)).rgb, vec3(0.299,0.587,0.114));
    float  l = dot(texture(tex, uv + vec2(-px.x, 0.0)).rgb, vec3(0.299,0.587,0.114));
    float  r = dot(texture(tex, uv + vec2( px.x, 0.0)).rgb, vec3(0.299,0.587,0.114));
    float bl = dot(texture(tex, uv + vec2(-px.x, px.y)).rgb, vec3(0.299,0.587,0.114));
    float  b = dot(texture(tex, uv + vec2( 0.0, px.y)).rgb, vec3(0.299,0.587,0.114));
    float br = dot(texture(tex, uv + vec2( px.x, px.y)).rgb, vec3(0.299,0.587,0.114));
    float gx = -tl - 2.0*l - bl + tr + 2.0*r + br;
    float gy = -tl - 2.0*t - tr + bl + 2.0*b + br;
    return length(vec2(gx, gy));
}

// Inferno colormap for THERMAL mode
vec3 inferno(float t) {
    t = clamp(t, 0.0, 1.0);
    vec3 c0 = vec3(0.0,    0.0,   0.0);
    vec3 c1 = vec3(0.27,   0.0,   0.33);
    vec3 c2 = vec3(0.73,   0.21,  0.33);
    vec3 c3 = vec3(0.99,   0.49,  0.14);
    vec3 c4 = vec3(0.99,   0.85,  0.18);
    vec3 c5 = vec3(0.99,   1.0,   0.65);
    if (t < 0.2) return mix(c0, c1, t / 0.2);
    if (t < 0.4) return mix(c1, c2, (t - 0.2) / 0.2);
    if (t < 0.6) return mix(c2, c3, (t - 0.4) / 0.2);
    if (t < 0.8) return mix(c3, c4, (t - 0.6) / 0.2);
    return mix(c4, c5, (t - 0.8) / 0.2);
}

// Local contrast approximation (cheap CLAHE-ish)
vec3 localContrast(samplerExternalOES tex, vec2 uv, vec2 px) {
    float lum = dot(texture(tex, uv).rgb, vec3(0.299, 0.587, 0.114));
    float avg = 0.0;
    const int R = 3;
    for (int y = -R; y <= R; y++) {
        for (int x = -R; x <= R; x++) {
            vec2 o = vec2(float(x), float(y)) * px * 2.0;
            avg += dot(texture(tex, uv + o).rgb, vec3(0.299, 0.587, 0.114));
        }
    }
    avg /= float((2*R+1)*(2*R+1));
    float boosted = lum + (lum - avg) * 1.4;
    return texture(tex, uv).rgb * (boosted / max(lum, 0.01));
}

void main() {
    vec2 px = 1.0 / uResolution;

    // Sample base, optionally pulling from ML-enhanced texture
    vec3 base;
    if (uHasMl > 0.5) {
        base = texture(uMlTex, vTex).rgb;
    } else {
        base = denoise3x3(uTex, vTex, px, uDenoise);
    }

    // RAW mode bypasses everything
    if (uMode == 3) {
        fragColor = vec4(base, 1.0);
        return;
    }

    // Local contrast (CLAHE-ish) — only on external sampler path
    if (uMode == 0 || uMode == 2) {
        if (uHasMl < 0.5) {
            base = localContrast(uTex, vTex, px);
        }
    }

    // Gain
    base *= uGain;

    // Gamma
    base = pow(max(base, 0.0), vec3(1.0 / max(uGamma, 0.1)));

    // Edge boost (unsharp via sobel)
    if (uEdge > 0.001 && uHasMl < 0.5) {
        float e = sobel(uTex, vTex, px);
        base += vec3(e) * uEdge * 0.5;
    }

    // Mode color grade
    vec3 outRgb;
    if (uMode == 0) {
        // NVG: luminance -> phosphor green
        float lum = clamp(dot(base, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
        vec3 phosphor = vec3(0.04, 1.0, 0.10) * lum;
        // Slight inner glow tint
        phosphor += vec3(0.0, 0.15, 0.02) * pow(lum, 0.5);
        outRgb = mix(base, phosphor, uPhosphor);
    } else if (uMode == 1) {
        float lum = clamp(dot(base, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
        outRgb = inferno(lum);
    } else {
        // CLAHE-color
        outRgb = clamp(base, 0.0, 1.0);
    }

    // Subtle scanlines (NVG only)
    if (uMode == 0) {
        float scan = 0.92 + 0.08 * sin(gl_FragCoord.y * 3.14159);
        outRgb *= scan;
        // Phosphor flicker
        float flicker = 0.97 + 0.03 * sin(uTime * 11.0);
        outRgb *= flicker;
    }

    // Vignette
    vec2 c = vTex - 0.5;
    float vig = 1.0 - dot(c, c) * 0.6;
    outRgb *= vig;

    fragColor = vec4(clamp(outRgb, 0.0, 1.0), 1.0);
}
