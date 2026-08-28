package com.junaidshahid.lumen

/**
 * All GLSL ES 3.00 sources for the scene. Three programs cover everything:
 * a full-screen background, a lit solid for the slab and tiles, and a textured
 * sprite for numbers, contact shadows and merge sparks.
 */
object Shaders {

    /**
     * No vertex buffer: the three corners of an oversized triangle are derived
     * from gl_VertexID, which avoids an attribute setup for a full-screen pass.
     */
    const val BG_VERT = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 1.0, 1.0);
}
"""

    const val BG_FRAG = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform float uTime;
uniform vec2 uRes;
uniform float uEnergy;

float hash(vec2 p) { return fract(sin(dot(p, vec2(41.3, 289.1))) * 43758.5453); }

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fbm(vec2 p) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 4; i++) { s += a * noise(p); p *= 2.03; a *= 0.5; }
    return s;
}

void main() {
    float aspect = uRes.x / max(uRes.y, 1.0);
    vec2 p = vec2((vUv.x - 0.5) * aspect, vUv.y - 0.5);

    vec3 col = mix(vec3(0.020, 0.028, 0.055), vec3(0.055, 0.075, 0.140),
                   smoothstep(-0.65, 0.70, p.y));

    // Two aurora sheets drifting against each other, slowly enough to feel still.
    float t = uTime * 0.045;
    float n1 = fbm(p * 1.6 + vec2(t, -t * 0.6));
    float n2 = fbm(p * 2.4 + vec2(-t * 0.8, t * 0.5) + 11.3);
    float ribbon = smoothstep(0.45, 0.95, n1) * 0.55 + smoothstep(0.55, 1.00, n2) * 0.35;
    col += ribbon * mix(vec3(0.05, 0.16, 0.20), vec3(0.10, 0.09, 0.24), n2) * (0.7 + uEnergy);

    // A pool of light sitting behind the board.
    float glow = exp(-dot(p, p) * 3.2);
    col += glow * vec3(0.055, 0.085, 0.135) * (1.0 + uEnergy * 1.5);

    col *= 1.0 - 0.55 * smoothstep(0.35, 1.15, length(p));
    // Dither, otherwise a gradient this dark bands visibly on OLED panels.
    col += (hash(gl_FragCoord.xy + uTime) - 0.5) * (1.5 / 255.0);

    fragColor = vec4(col, 1.0);
}
"""

    const val LIT_VERT = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
uniform mat4 uMvp;
uniform mat4 uModel;
uniform mat3 uNrmMat;
out vec3 vN;
out vec3 vW;
void main() {
    vW = (uModel * vec4(aPos, 1.0)).xyz;
    vN = uNrmMat * aNrm;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    const val LIT_FRAG = """#version 300 es
precision highp float;
in vec3 vN;
in vec3 vW;
out vec4 fragColor;
uniform vec3 uBase;
uniform vec3 uEye;
uniform float uEmissive;
uniform float uAlpha;
uniform float uRoughness;

const vec3 KEY_DIR  = vec3(-0.3363, 0.8265, 0.3652);
const vec3 FILL_DIR = vec3( 0.7025, 0.3399, 0.6249);

void main() {
    vec3 N = normalize(vN);
    vec3 V = normalize(uEye - vW);

    float key  = max(dot(N, KEY_DIR), 0.0);
    float fill = max(dot(N, FILL_DIR), 0.0);
    // Hemispheric ambient, so downward faces darken without going to black.
    float amb  = 0.5 + 0.5 * N.y;

    vec3 col = uBase * (0.16 + 0.30 * amb);
    col += uBase * key * 0.85;
    col += uBase * fill * 0.22 * vec3(0.75, 0.85, 1.00);

    float shine = mix(96.0, 18.0, uRoughness);
    vec3 H = normalize(KEY_DIR + V);
    col += vec3(0.90, 0.95, 1.00) * pow(max(dot(N, H), 0.0), shine) * (1.0 - uRoughness * 0.7) * 0.55;

    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);
    col += uBase * rim * 0.55 + vec3(0.10, 0.16, 0.24) * rim * 0.60;

    col += uBase * uEmissive;

    col = col / (col + vec3(0.85));
    col = pow(col, vec3(1.0 / 2.2));
    fragColor = vec4(col, uAlpha);
}
"""

    const val SPRITE_VERT = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aUv;
uniform mat4 uMvp;
uniform vec4 uUvRect;
out vec2 vUv;
void main() {
    vUv = uUvRect.xy + aUv.xy * uUvRect.zw;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    /** The texture carries shape in its alpha only; colour always comes from the tint. */
    const val SPRITE_FRAG = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec4 uTint;
void main() {
    float a = texture(uTex, vUv).a * uTint.a;
    if (a < 0.002) discard;
    fragColor = vec4(uTint.rgb, a);
}
"""
}
