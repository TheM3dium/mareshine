// Post-processing: how the XFB reaches the window (gx_render_parts.h).
#include "gx_internal.h"
#include "gl_funcs.h"
#include "gx_render_parts.h"
#include "gx_nis_coef.h"

#include <math.h>
#include <algorithm>
#include <stdlib.h>
#include <string.h>

namespace gx {

// ------------------------------------------------------------ post-processing
// The XFB reaches the window through up to two passes: FXAA at the XFB's own
// size (SMS_FXAA), then a scaling pass into the letterboxed viewport that also
// sharpens (SMS_SHARPEN) and applies the brightness curve (SMS_GAMMA).
// SMS_PRESENT_FILTER picks the scaler: bilinear (an area average when the
// XFB is larger than the window, so a high internal resolution supersamples),
// nearest, sharp (bilinear only between texels: crisp pixels at any size), or
// fsr: AMD FidelityFX Super Resolution 1, an edge-adaptive upscale (EASU) to
// the viewport's size and then its sharpening (RCAS, as strong as
// SMS_SHARPEN asks), for a low internal resolution on a large window; or nis:
// NVIDIA Image Scaling, a directional 6-tap upscale with adaptive sharpening
// in one pass (SMS_SHARPEN raises it from NVIDIA's default). When the XFB is
// no smaller than the viewport there is nothing to upscale, and both scale as
// bilinear does.
// SMS_ASPECT=stretch fills the window; integer keeps whole multiples of 640x528.
static const char* kPostVs = R"(#version 330 core
uniform int u_flip;
out vec2 v_uv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  v_uv = u_flip != 0 ? vec2(p.x, 1.0 - p.y) : p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";
static const char* kFxaaFs = R"(#version 330 core
uniform sampler2D u_tex;
uniform vec2 u_rcp;  // 1 / texture size
in vec2 v_uv;
out vec4 o_color;
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
  vec3 nw = texture(u_tex, v_uv + vec2(-1.0, -1.0) * u_rcp).rgb;
  vec3 ne = texture(u_tex, v_uv + vec2( 1.0, -1.0) * u_rcp).rgb;
  vec3 sw = texture(u_tex, v_uv + vec2(-1.0,  1.0) * u_rcp).rgb;
  vec3 se = texture(u_tex, v_uv + vec2( 1.0,  1.0) * u_rcp).rgb;
  vec3 m  = texture(u_tex, v_uv).rgb;
  float lNW = luma(nw), lNE = luma(ne), lSW = luma(sw), lSE = luma(se), lM = luma(m);
  float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
  float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
  vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));
  float reduce = max((lNW + lNE + lSW + lSE) * (0.25 / 8.0), 1.0 / 128.0);
  float rcpMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
  dir = clamp(dir * rcpMin, vec2(-8.0), vec2(8.0)) * u_rcp;
  vec3 a = 0.5 * (texture(u_tex, v_uv + dir * (1.0 / 3.0 - 0.5)).rgb +
                  texture(u_tex, v_uv + dir * (2.0 / 3.0 - 0.5)).rgb);
  vec3 b = a * 0.5 + 0.25 * (texture(u_tex, v_uv - dir * 0.5).rgb + texture(u_tex, v_uv + dir * 0.5).rgb);
  float lB = luma(b);
  o_color = vec4((lB < lMin || lB > lMax) ? a : b, 1.0);
}
)";
static const char* kScaleFs = R"(#version 330 core
uniform sampler2D u_tex;
uniform vec2 u_src;      // texture size in texels
uniform vec2 u_dst;      // viewport size in pixels
uniform int u_filter;    // 0 bilinear/area, 1 nearest, 2 sharp
uniform float u_sharpen; // 0..1
uniform float u_gamma;   // 1: unchanged; above 1 brightens
in vec2 v_uv;
out vec4 o_color;
vec3 areaSample(vec2 uv) {
  vec2 ratio = u_src / u_dst;  // texels per pixel
  if (ratio.x <= 1.0 && ratio.y <= 1.0) return texture(u_tex, uv).rgb;
  ivec2 n = ivec2(clamp(ceil(ratio), vec2(1.0), vec2(8.0)));
  vec2 stepUv = ratio / vec2(n) / u_src;
  vec2 start = uv - 0.5 * ratio / u_src + 0.5 * stepUv;
  vec3 acc = vec3(0.0);
  for (int j = 0; j < n.y; j++)
    for (int i = 0; i < n.x; i++) acc += texture(u_tex, start + vec2(i, j) * stepUv).rgb;
  return acc / float(n.x * n.y);
}
vec3 fetch(vec2 uv) {
  if (u_filter == 1) return texelFetch(u_tex, clamp(ivec2(uv * u_src), ivec2(0), ivec2(u_src) - 1), 0).rgb;
  if (u_filter == 2 && u_dst.x >= u_src.x) {
    vec2 scale = max(floor(u_dst / u_src), vec2(1.0));
    vec2 texel = uv * u_src;
    vec2 base = floor(texel - 0.5) + 0.5;
    vec2 f = texel - base;
    vec2 region = 0.5 - 0.5 / scale;
    vec2 d = f - 0.5;
    f = (d - clamp(d, -region, region)) * scale + 0.5;
    return texture(u_tex, (base + f) / u_src).rgb;
  }
  return areaSample(uv);
}
void main() {
  vec3 c = fetch(v_uv);
  if (u_sharpen > 0.0) {
    // contrast-adaptive: sharpen less where the neighbourhood already has contrast
    vec2 d = max(1.0 / u_src, 1.0 / u_dst);
    vec3 n = texture(u_tex, v_uv + vec2(0.0, -d.y)).rgb, s = texture(u_tex, v_uv + vec2(0.0, d.y)).rgb;
    vec3 w = texture(u_tex, v_uv + vec2(-d.x, 0.0)).rgb, e = texture(u_tex, v_uv + vec2(d.x, 0.0)).rgb;
    vec3 mn = min(c, min(min(n, s), min(w, e))), mx = max(c, max(max(n, s), max(w, e)));
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 1e-4), 0.0, 1.0));
    vec3 wgt = -amp * mix(0.125, 0.2, u_sharpen);
    c = clamp((c + (n + s + w + e) * wgt) / (1.0 + 4.0 * wgt), 0.0, 1.0);
  }
  if (u_gamma != 1.0) c = pow(max(c, vec3(0.0)), vec3(1.0 / u_gamma));
  o_color = vec4(c, 1.0);
}
)";

// FSR 1's two passes, after AMD's FidelityFX Super Resolution 1.0
// (ffx_fsr1.h, MIT licence, Copyright (c) 2021 Advanced Micro Devices, Inc.),
// in plain GLSL 3.30: texel fetches in place of gathers, one pixel at a time.
//
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions: The above copyright
// notice and this permission notice shall be included in all copies or
// substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS",
// WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
// THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
// FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
// TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
// THE USE OR OTHER DEALINGS IN THE SOFTWARE.
//
// EASU: 12 taps around the sample, the local edge direction and length from
// their luma, then a Lanczos-like lobe stretched along the edge, clamped to the
// nearest four taps so it does not ring.
static const char* kEasuFs = R"(#version 330 core
uniform sampler2D u_tex;
uniform vec2 u_src;  // texture size in texels
in vec2 v_uv;
out vec4 o_color;
vec3 tap(ivec2 p) { return texelFetch(u_tex, clamp(p, ivec2(0), ivec2(u_src) - 1), 0).rgb; }
float luma(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }
// one quadrant's direction and edge length, weighted by its bilinear share
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
  float lenX = max(abs(lD - lC), abs(lC - lB));
  float dirX = lD - lB;
  dir.x += dirX * w;
  lenX = clamp(abs(dirX) / max(lenX, 1e-5), 0.0, 1.0);
  len += lenX * lenX * w;
  float lenY = max(abs(lE - lC), abs(lC - lA));
  float dirY = lE - lA;
  dir.y += dirY * w;
  lenY = clamp(abs(dirY) / max(lenY, 1e-5), 0.0, 1.0);
  len += lenY * lenY * w;
}
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * -dir.y + off.y * dir.x) * len;
  float d2 = min(dot(v, v), clp);
  float wB = 2.0 / 5.0 * d2 - 1.0;
  float wA = lob * d2 - 1.0;
  wB *= wB;
  wA *= wA;
  wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
  float w = wB * wA;
  aC += c * w;
  aW += w;
}
void main() {
  vec2 pp = v_uv * u_src - 0.5;
  vec2 fp = floor(pp);
  pp -= fp;
  ivec2 p = ivec2(fp);
  //    b c
  //  e f g h
  //  i j k l
  //    n o
  vec3 b = tap(p + ivec2(0, -1)), c = tap(p + ivec2(1, -1));
  vec3 e = tap(p + ivec2(-1, 0)), f = tap(p), g = tap(p + ivec2(1, 0)), h = tap(p + ivec2(2, 0));
  vec3 i = tap(p + ivec2(-1, 1)), j = tap(p + ivec2(0, 1)), k = tap(p + ivec2(1, 1)), l = tap(p + ivec2(2, 1));
  vec3 n = tap(p + ivec2(0, 2)), o = tap(p + ivec2(1, 2));
  float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g), hL = luma(h);
  float iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l), nL = luma(n), oL = luma(o);
  vec2 dir = vec2(0.0);
  float len = 0.0;
  easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
  easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
  easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
  easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
  float dirR = dot(dir, dir);
  bool zro = dirR < 1.0 / 32768.0;
  dir = zro ? vec2(1.0, 0.0) : dir * inversesqrt(dirR);
  len = len * 0.5;
  len *= len;
  float stretch = 1.0 / max(abs(dir.x), abs(dir.y));
  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
  float clp = 1.0 / lob;
  vec3 aC = vec3(0.0);
  float aW = 0.0;
  easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
  easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
  easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
  easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
  easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
  easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
  easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
  easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
  easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
  easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
  easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
  easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
  vec3 mn = min(min(f, g), min(j, k)), mx = max(max(f, g), max(j, k));
  o_color = vec4(clamp(aC / aW, mn, mx), 1.0);
}
)";
// RCAS: sharpens each pixel against its four neighbours by as much as it can
// without clipping (u_con = 2^-stops: 1 is the most), then the brightness curve.
static const char* kRcasFs = R"(#version 330 core
uniform sampler2D u_tex;
uniform vec2 u_dst;     // viewport size: the texture's size too
uniform float u_con;
uniform float u_gamma;  // 1: unchanged; above 1 brightens
in vec2 v_uv;
out vec4 o_color;
vec3 tap(ivec2 p) { return texelFetch(u_tex, clamp(p, ivec2(0), ivec2(u_dst) - 1), 0).rgb; }
void main() {
  ivec2 ip = ivec2(v_uv * u_dst);
  //    b
  //  d e f
  //    h
  vec3 b = tap(ip + ivec2(0, -1)), d = tap(ip + ivec2(-1, 0)), e = tap(ip);
  vec3 f = tap(ip + ivec2(1, 0)), h = tap(ip + ivec2(0, 1));
  vec3 mn4 = min(min(b, d), min(f, h)), mx4 = max(max(b, d), max(f, h));
  vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-5));
  vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * min(mn4, e) - 4.0, vec3(-1e-5));
  vec3 lobes = max(-hitMin, hitMax);
  float lobe = max(-(0.25 - 1.0 / 16.0), min(max(lobes.r, max(lobes.g, lobes.b)), 0.0)) * u_con;
  vec3 c = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
  if (u_gamma != 1.0) c = pow(max(c, vec3(0.0)), vec3(1.0 / u_gamma));
  o_color = vec4(clamp(c, 0.0, 1.0), 1.0);
}
)";


// NIS: NVIDIA Image Scaling's NVScaler (NIS_Scaler.h, SDK v1.0.3, MIT licence,
// Copyright (c) 2022 NVIDIA CORPORATION & AFFILIATES; the notice and the filter
// banks are in gx_nis_coef.h), ported from its compute shader to a fragment
// shader for GL 3.30: each pixel loads the 6x6 source texels' luma around its
// sample and derives the edge map of the four nearest itself, in place of the
// tile in shared memory. As in NVScaler: a 6-tap polyphase filter blended with
// 0, 45, 90 and 135 degree directional filters by the interpolated edge map,
// each with luma-ramped unsharp masking and anti-ringing (LTI); the result
// replaces the luma of a bilinear tap, which keeps its chroma. NVIDIA tuned it
// for up to 2x; with no tiles here, larger ratios work the same way.
static const char* kNisFs = R"(#version 330 core
uniform sampler2D u_tex;   // the picture, filtered linearly
uniform sampler2D u_coef;  // 4 x 64: texels 0-1 the scaler's 6 taps, 2-3 the USM's, one row per phase
uniform vec2 u_src;        // texture size in texels
uniform vec4 u_sharp;      // sharp strength min, its scale, sharp limit min, its scale
uniform float u_gamma;     // 1: unchanged; above 1 brightens
in vec2 v_uv;
out vec4 o_color;
const float kDetectRatio = 2.0 * 1127.0 / 1024.0;
const float kDetectThres = 64.0 / 1024.0;
const float kMinContrastRatio = 2.0;
const float kRatioNorm = 1.0 / (10.0 - 2.0);
const float kEps = 1.0 / 255.0;
const float kSharpStartY = 0.45;
const float kSharpScaleY = 1.0 / (0.9 - 0.45);
float P[36];  // luma of the 6x6 source texels around the sample, row by row
float getY(vec3 c) { return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b; }
#define PP(i, j) P[(i) * 6 + (j)]
// the edge weights (0, 90, 45, 135 degrees) of the 3x3 texels from row r, column c
vec4 edgeMap(int r, int c) {
  float g0 = abs(PP(r, c) + PP(r, c + 1) + PP(r, c + 2) - PP(r + 2, c) - PP(r + 2, c + 1) - PP(r + 2, c + 2));
  float g45 = abs(PP(r + 1, c) + PP(r, c) + PP(r, c + 1) - PP(r + 2, c + 1) - PP(r + 2, c + 2) - PP(r + 1, c + 2));
  float g90 = abs(PP(r, c) + PP(r + 1, c) + PP(r + 2, c) - PP(r, c + 2) - PP(r + 1, c + 2) - PP(r + 2, c + 2));
  float g135 = abs(PP(r + 1, c) + PP(r + 2, c) + PP(r + 2, c + 1) - PP(r, c + 1) - PP(r, c + 2) - PP(r + 1, c + 2));
  float max090 = max(g0, g90), min090 = min(g0, g90), max45135 = max(g45, g135), min45135 = min(g45, g135);
  if (max090 + max45135 == 0.0) return vec4(0.0);
  float e090 = min(max090 / (max090 + max45135), 1.0), e45135 = 1.0 - e090;
  bool c090 = max090 > min090 * kDetectRatio && max090 > kDetectThres && max090 > min45135;
  bool c45135 = max45135 > min45135 * kDetectRatio && max45135 > kDetectThres && max45135 > min090;
  bool is0 = max090 == g0, is45 = max45135 == g45;
  float f090 = c090 && c45135 ? e090 : 1.0, f45135 = c090 && c45135 ? e45135 : 1.0;
  return vec4(c090 && is0 ? f090 : 0.0, c090 && !is0 ? f090 : 0.0, c45135 && is45 ? f45135 : 0.0,
              c45135 && !is45 ? f45135 : 0.0);
}
void taps(int phase, int column, out float k[6]) {
  vec4 a = texelFetch(u_coef, ivec2(column, phase), 0), b = texelFetch(u_coef, ivec2(column + 1, phase), 0);
  k[0] = a.x; k[1] = a.y; k[2] = a.z; k[3] = a.w; k[4] = b.x; k[5] = b.y;
}
float calcLTI(float p0, float p1, float p2, float p3, float p4, float p5, int phase) {
  bool selector = phase <= 32;
  float sel = selector ? p0 : p3;
  float aMin = min(min(p1, p2), sel), aMax = max(max(p1, p2), sel);
  sel = selector ? p2 : p5;
  float bMin = min(min(p3, p4), sel), bMax = max(max(p3, p4), sel);
  float aCont = aMax - aMin, bCont = bMax - bMin;
  float ratio = max(aCont, bCont) / (min(aCont, bCont) + kEps);
  return 1.0 - clamp((ratio - kMinContrastRatio) * kRatioNorm, 0.0, 1.0);
}
float evalPoly6(float px[6], int phase) {
  float ks[6], ku[6];
  taps(phase, 0, ks);
  taps(phase, 2, ku);
  float y = 0.0, usm = 0.0;
  for (int i = 0; i < 6; i++) {
    y += ks[i] * px[i];
    usm += ku[i] * px[i];
  }
  float ramp = 1.0 - clamp((y - kSharpStartY) * kSharpScaleY, 0.0, 1.0);
  usm *= ramp * u_sharp.y + u_sharp.x;
  float limit = (ramp * u_sharp.w + u_sharp.z) * y;
  usm = min(limit, max(-limit, usm));
  return y + usm * calcLTI(px[0], px[1], px[2], px[3], px[4], px[5], phase);
}
float filterNormal(int phaseX, int phaseY) {
  float kx[6], ky[6];
  taps(phaseX, 0, kx);
  taps(phaseY, 0, ky);
  float h = 0.0;
  for (int j = 0; j < 6; j++) {
    float v = 0.0;
    for (int i = 0; i < 6; i++) v += PP(i, j) * ky[i];
    h += v * kx[j];
  }
  return h;
}
float addDirFilters(float fx, float fy, int phaseX, int phaseY, vec4 w) {
  float f = 0.0, t[6], s[7];
  if (w.x > 0.0) {  // 0 degrees
    for (int i = 0; i < 6; i++) t[i] = mix(PP(i, 2), PP(i, 3), fx);
    f += evalPoly6(t, phaseY) * w.x;
  }
  if (w.y > 0.0) {  // 90 degrees
    for (int i = 0; i < 6; i++) t[i] = mix(PP(2, i), PP(3, i), fy);
    f += evalPoly6(t, phaseX) * w.y;
  }
  if (w.z > 0.0) {  // 45 degrees
    float b = 0.5 + 0.5 * (fx - fy);
    s[1] = mix(PP(2, 1), PP(1, 2), b);
    s[3] = mix(PP(3, 2), PP(2, 3), b);
    s[5] = mix(PP(4, 3), PP(3, 4), b);
    b -= 0.5;
    bool up = b >= 0.0;
    s[0] = mix(PP(1, 1), up ? PP(0, 2) : PP(2, 0), abs(b));
    s[2] = mix(PP(2, 2), up ? PP(1, 3) : PP(3, 1), abs(b));
    s[4] = mix(PP(3, 3), up ? PP(2, 4) : PP(4, 2), abs(b));
    s[6] = mix(PP(4, 4), up ? PP(3, 5) : PP(5, 3), abs(b));
    float p = fx + fy;
    int o = p >= 1.0 ? 1 : 0;
    for (int i = 0; i < 6; i++) t[i] = s[i + o];
    f += evalPoly6(t, int((p - float(o)) * 64.0)) * w.z;
  }
  if (w.w > 0.0) {  // 135 degrees
    float b = 0.5 * (fx + fy);
    s[1] = mix(PP(3, 1), PP(4, 2), b);
    s[3] = mix(PP(2, 2), PP(3, 3), b);
    s[5] = mix(PP(1, 3), PP(2, 4), b);
    b -= 0.5;
    bool up = b >= 0.0;
    s[0] = mix(PP(4, 1), up ? PP(5, 2) : PP(3, 0), abs(b));
    s[2] = mix(PP(3, 2), up ? PP(4, 3) : PP(2, 1), abs(b));
    s[4] = mix(PP(2, 3), up ? PP(3, 4) : PP(1, 2), abs(b));
    s[6] = mix(PP(1, 4), up ? PP(2, 5) : PP(0, 3), abs(b));
    float p = 1.0 + fx - fy;
    int o = p >= 1.0 ? 1 : 0;
    for (int i = 0; i < 6; i++) t[i] = s[i + o];
    f += evalPoly6(t, int((p - float(o)) * 64.0)) * w.w;
  }
  return f;
}
void main() {
  vec2 src = v_uv * u_src - 0.5;
  vec2 base = floor(src), frac = src - base;
  ivec2 b = ivec2(base), hi = ivec2(u_src) - 1;
  for (int i = 0; i < 6; i++)
    for (int j = 0; j < 6; j++) P[i * 6 + j] = getY(texelFetch(u_tex, clamp(b + ivec2(j - 2, i - 2), ivec2(0), hi), 0).rgb);
  // the four nearest texels' edge maps (each from its 3x3), interpolated to the sample
  vec4 w = mix(mix(edgeMap(1, 1), edgeMap(1, 2), frac.x), mix(edgeMap(2, 1), edgeMap(2, 2), frac.x), frac.y);
  int phaseX = int(frac.x * 64.0), phaseY = int(frac.y * 64.0);
  float y = filterNormal(phaseX, phaseY) * (1.0 - w.x - w.y - w.z - w.w) + addDirFilters(frac.x, frac.y, phaseX, phaseY, w);
  vec3 c = texture(u_tex, (src + 0.5) / u_src).rgb;
  c = clamp(c + (y - getY(c)), 0.0, 1.0);
  if (u_gamma != 1.0) c = pow(c, vec3(1.0 / u_gamma));
  o_color = vec4(c, 1.0);
}
)";

struct PostSettings {
    bool fxaa = false;
    int filter = 0;        // 0 bilinear/area, 1 nearest, 2 sharp, 3 fsr, 4 nis
    float sharpen = 0.0f;  // 0..1
    float gamma = 1.0f;
    int aspect = 0;        // 0 keep, 1 stretch, 2 integer
};
static PostSettings s_post;
int postAspect() { return s_post.aspect; }
static GLuint s_fxaaProg, s_scaleProg, s_postTex, s_postFbo;
static GLint s_fxaaURcp, s_fxaaUFlip, s_scaleUSrc, s_scaleUDst, s_scaleUFilter, s_scaleUSharpen, s_scaleUGamma,
    s_scaleUFlip;
static int s_postW, s_postH;
static GLuint s_easuProg, s_rcasProg, s_fsrTex, s_fsrFbo;
static GLint s_easuUSrc, s_easuUFlip, s_rcasUDst, s_rcasUCon, s_rcasUGamma, s_rcasUFlip;
static int s_fsrW, s_fsrH;
static GLuint s_nisProg, s_nisCoef;
static GLint s_nisUSrc, s_nisUSharp, s_nisUGamma, s_nisUFlip;

void postInit() {
    if (const char* e = getenv("SMS_FXAA")) s_post.fxaa = atoi(e) != 0;
    if (const char* e = getenv("SMS_PRESENT_FILTER"))
        s_post.filter = !strcmp(e, "nearest") ? 1 : !strcmp(e, "sharp") ? 2 : !strcmp(e, "fsr") ? 3 : !strcmp(e, "nis") ? 4 : 0;
    if (const char* e = getenv("SMS_SHARPEN")) s_post.sharpen = std::max(0.0f, std::min(1.0f, float(atof(e)) / 100.0f));
    if (const char* e = getenv("SMS_GAMMA")) {
        float v = float(atof(e));
        if (v >= 0.3f && v <= 3.0f) s_post.gamma = v;
    }
    if (const char* e = getenv("SMS_ASPECT")) s_post.aspect = !strcmp(e, "stretch") ? 1 : !strcmp(e, "integer") ? 2 : 0;
    s_fxaaProg = compileProgram(kPostVs, kFxaaFs);
    glUseProgram(s_fxaaProg);
    glUniform1i(glGetUniformLocation(s_fxaaProg, "u_tex"), 0);
    s_fxaaURcp = glGetUniformLocation(s_fxaaProg, "u_rcp");
    s_fxaaUFlip = glGetUniformLocation(s_fxaaProg, "u_flip");
    s_scaleProg = compileProgram(kPostVs, kScaleFs);
    glUseProgram(s_scaleProg);
    glUniform1i(glGetUniformLocation(s_scaleProg, "u_tex"), 0);
    s_scaleUSrc = glGetUniformLocation(s_scaleProg, "u_src");
    s_scaleUDst = glGetUniformLocation(s_scaleProg, "u_dst");
    s_scaleUFilter = glGetUniformLocation(s_scaleProg, "u_filter");
    s_scaleUSharpen = glGetUniformLocation(s_scaleProg, "u_sharpen");
    s_scaleUGamma = glGetUniformLocation(s_scaleProg, "u_gamma");
    s_scaleUFlip = glGetUniformLocation(s_scaleProg, "u_flip");
    if (s_post.filter == 3) {
        s_easuProg = compileProgram(kPostVs, kEasuFs);
        glUseProgram(s_easuProg);
        glUniform1i(glGetUniformLocation(s_easuProg, "u_tex"), 0);
        s_easuUSrc = glGetUniformLocation(s_easuProg, "u_src");
        s_easuUFlip = glGetUniformLocation(s_easuProg, "u_flip");
        s_rcasProg = compileProgram(kPostVs, kRcasFs);
        glUseProgram(s_rcasProg);
        glUniform1i(glGetUniformLocation(s_rcasProg, "u_tex"), 0);
        s_rcasUDst = glGetUniformLocation(s_rcasProg, "u_dst");
        s_rcasUCon = glGetUniformLocation(s_rcasProg, "u_con");
        s_rcasUGamma = glGetUniformLocation(s_rcasProg, "u_gamma");
        s_rcasUFlip = glGetUniformLocation(s_rcasProg, "u_flip");
    }
    if (s_post.filter == 4) {
        s_nisProg = compileProgram(kPostVs, kNisFs);
        glUseProgram(s_nisProg);
        glUniform1i(glGetUniformLocation(s_nisProg, "u_tex"), 0);
        glUniform1i(glGetUniformLocation(s_nisProg, "u_coef"), 1);
        s_nisUSrc = glGetUniformLocation(s_nisProg, "u_src");
        s_nisUSharp = glGetUniformLocation(s_nisProg, "u_sharp");
        s_nisUGamma = glGetUniformLocation(s_nisProg, "u_gamma");
        s_nisUFlip = glGetUniformLocation(s_nisProg, "u_flip");
        // the filter banks as NVScaler reads them: per phase, 8 taps of the scaler then 8 of the USM
        float coef[64][16] = {};
        for (int p = 0; p < 64; p++)
            for (int i = 0; i < 6; i++) {
                coef[p][i] = kNisCoefScale[p][i];
                coef[p][8 + i] = kNisCoefUsm[p][i];
            }
        glGenTextures(1, &s_nisCoef);
        glBindTexture(GL_TEXTURE_2D, s_nisCoef);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, 64, 0, GL_RGBA, GL_FLOAT, coef);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    static const char* const kFilters[] = {"bilinear", "nearest", "sharp", "FSR 1", "NIS"};
    static const char* const kAspects[] = {"keep", "stretch", "integer"};
    logmsg("post-processing: FXAA %s, scaler %s, sharpen %d%%, brightness %.2f, aspect %s", s_post.fxaa ? "on" : "off",
           kFilters[s_post.filter], int(s_post.sharpen * 100.0f + 0.5f), double(s_post.gamma), kAspects[s_post.aspect]);
}

void postPresent(GLuint tex, int w, int h, int ox, int oy, int vw, int vh, GLuint quadVao, GLuint drawVao) {
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_COLOR_LOGIC_OP);
    glDisable(GL_CLIP_DISTANCE0);
    glDisable(GL_CLIP_DISTANCE0 + 1);
    glDisable(GL_SCISSOR_TEST);
    glBindSampler(0, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindVertexArray(quadVao);
    if (s_post.fxaa) {
        if (!s_postTex || s_postW != w || s_postH != h) {
            if (!s_postTex) glGenTextures(1, &s_postTex);
            glBindTexture(GL_TEXTURE_2D, s_postTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            if (!s_postFbo) glGenFramebuffers(1, &s_postFbo);
            glBindFramebuffer(GL_FRAMEBUFFER, s_postFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_postTex, 0);
            s_postW = w;
            s_postH = h;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, s_postFbo);
        glViewport(0, 0, w, h);
        glUseProgram(s_fxaaProg);
        glUniform2f(s_fxaaURcp, 1.0f / float(w), 1.0f / float(h));
        glUniform1i(s_fxaaUFlip, 0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        tex = s_postTex;
    }
    if (s_post.filter == 4 && (vw > w || vh > h)) {
        // NIS: one pass into the present framebuffer (the window, or HDR's
        // SDR texture). NVScalerUpdateConfig's SDR sharpness ramps, with
        // Sharpening 0..100% as NIS's 50 to 100% sharpness (50% is NVIDIA's
        // default)
        const float slider = 0.5f * s_post.sharpen;
        const float maxScale = 1.25f, minScale = 1.25f, limitScale = 1.25f;
        const float strengthMin = std::max(0.0f, 0.4f + slider * minScale * 1.2f), strengthMax = 1.6f + slider * maxScale * 1.8f;
        const float limitMin = std::max(0.1f, 0.14f + slider * limitScale * 0.32f), limitMax = 0.5f + slider * limitScale * 0.6f;
        glBindFramebuffer(GL_FRAMEBUFFER, g_presentFbo);
        glViewport(ox, oy, vw, vh);
        glUseProgram(s_nisProg);
        glUniform2f(s_nisUSrc, float(w), float(h));
        const float sharp[4] = {strengthMin, strengthMax - strengthMin, limitMin, limitMax - limitMin};
        glUniform4fv(s_nisUSharp, 1, sharp);
        glUniform1f(s_nisUGamma, s_post.gamma);
        glUniform1i(s_nisUFlip, 1);  // XFB row 0 is the top; the window's row 0 is its bottom
        glActiveTexture(GL_TEXTURE1);
        glBindSampler(1, 0);
        glBindTexture(GL_TEXTURE_2D, s_nisCoef);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(drawVao);
        return;
    }
    if (s_post.filter == 3 && (vw > w || vh > h)) {
        // FSR 1: EASU upscales into a viewport-sized texture (turned the
        // window's way up), then RCAS sharpens it into the window
        if (!s_fsrTex || s_fsrW != vw || s_fsrH != vh) {
            if (!s_fsrTex) glGenTextures(1, &s_fsrTex);
            glBindTexture(GL_TEXTURE_2D, s_fsrTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, vw, vh, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            if (!s_fsrFbo) glGenFramebuffers(1, &s_fsrFbo);
            glBindFramebuffer(GL_FRAMEBUFFER, s_fsrFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_fsrTex, 0);
            s_fsrW = vw;
            s_fsrH = vh;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, s_fsrFbo);
        glViewport(0, 0, vw, vh);
        glUseProgram(s_easuProg);
        glUniform2f(s_easuUSrc, float(w), float(h));
        glUniform1i(s_easuUFlip, 1);  // XFB row 0 is the top; the window's row 0 is its bottom
        glBindTexture(GL_TEXTURE_2D, tex);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindFramebuffer(GL_FRAMEBUFFER, g_presentFbo);
        glViewport(ox, oy, vw, vh);
        glUseProgram(s_rcasProg);
        glUniform2f(s_rcasUDst, float(vw), float(vh));
        // Sharpening 0..100% is RCAS's 1 to 0 stops below its strongest
        glUniform1f(s_rcasUCon, exp2f(-(1.0f - s_post.sharpen)));
        glUniform1f(s_rcasUGamma, s_post.gamma);
        glUniform1i(s_rcasUFlip, 0);
        glBindTexture(GL_TEXTURE_2D, s_fsrTex);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(drawVao);
        return;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, g_presentFbo);
    glViewport(ox, oy, vw, vh);
    glUseProgram(s_scaleProg);
    glUniform2f(s_scaleUSrc, float(w), float(h));
    glUniform2f(s_scaleUDst, float(vw), float(vh));
    glUniform1i(s_scaleUFilter, s_post.filter >= 3 ? 0 : s_post.filter);  // FSR or NIS not upscaling: bilinear
    glUniform1f(s_scaleUSharpen, s_post.sharpen);
    glUniform1f(s_scaleUGamma, s_post.gamma);
    glUniform1i(s_scaleUFlip, 1);  // XFB row 0 is the top; the window's row 0 is its bottom
    glBindTexture(GL_TEXTURE_2D, tex);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(drawVao);
}

}  // namespace gx
