// Shader generator: turns the XF (texgen, lighting) and BP (TEV, indirect,
// alpha compare, fog) register state into a GLSL 3.30 program.  Programs are
// cached by the bytes of the state that shapes the code; everything numeric
// (matrices, colours, references) is a uniform.
#include "gx_internal.h"
#include "gl_funcs.h"
#include "gx_glcache.h"

#include <stdio.h>
#include <sys/file.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <algorithm>
#include <chrono>
#include <unordered_map>
#include <vector>

#ifdef __ANDROID__
#include <EGL/egl.h>  // eglGetProcAddress: glMaxShaderCompilerThreadsKHR
#endif

namespace gx {

extern uint32_t g_statShaderCompiles;

struct ShaderKey {
    uint32_t numTexGens, numChans, dualTex, clip;
    uint32_t texgen[8], post[8];
    uint32_t chan[4];
    uint32_t numStages, numInd;
    uint32_t colorEnv[16], alphaEnv[16], order[16], ksel[16], ind[16];
    uint32_t swap[4];
    uint32_t iref, alphaFunc, fogType;
};

static void buildKey(ShaderKey& k) {
    memset(&k, 0, sizeof(k));
    k.numTexGens = g.xfReg[XFR_NUMTEXGENS] & 15;
    if (k.numTexGens > 8) k.numTexGens = 8;
    k.numChans = g.xfReg[XFR_NUMCHANS] & 3;
    k.dualTex = g.xfReg[XFR_DUALTEX] & 1;
    k.clip = g.xfReg[0x05] == 0;
    for (uint32_t i = 0; i < k.numTexGens; i++) {
        k.texgen[i] = g.xfReg[XFR_TEXGEN + i];
        if (k.dualTex) k.post[i] = g.xfReg[XFR_POSTTEX + i] & 0x13F;
    }
    for (uint32_t i = 0; i < 2; i++) {
        k.chan[i] = g.xfReg[XFR_COLOR0CTRL + i] & 0x7FFF;
        k.chan[2 + i] = g.xfReg[XFR_ALPHA0CTRL + i] & 0x7FFF;
    }
    uint32_t gen = g.bp[BP_GENMODE];
    k.numStages = ((gen >> 10) & 15) + 1;
    k.numInd = (gen >> 16) & 7;
    if (k.numInd > 4) k.numInd = 4;
    for (uint32_t s = 0; s < k.numStages; s++) {
        k.colorEnv[s] = g.bp[BP_TEV_COLOR_ENV + 2 * s] & 0xFFFFFF;
        k.alphaEnv[s] = g.bp[BP_TEV_COLOR_ENV + 2 * s + 1] & 0xFFFFFF;
        k.order[s] = (g.bp[BP_TREF + (s >> 1)] >> ((s & 1) * 12)) & 0x3FF;
        uint32_t ks = g.bp[BP_TEV_KSEL + (s >> 1)];
        k.ksel[s] = (s & 1) ? ((ks >> 14) & 0x3FF) : ((ks >> 4) & 0x3FF);
        k.ind[s] = k.numInd ? (g.bp[BP_IND_CMD + s] & 0x1FFFFF) : 0;
    }
    for (int t = 0; t < 4; t++)
        k.swap[t] = (g.bp[BP_TEV_KSEL + 2 * t] & 15) | (g.bp[BP_TEV_KSEL + 2 * t + 1] & 15) << 4;
    k.iref = k.numInd ? g.bp[BP_RAS1_IREF] & 0xFFFFFF : 0;
    k.alphaFunc = (g.bp[BP_ALPHACOMPARE] >> 16) & 0xFF;
    if (pixelMetricShader()) {
        k.alphaFunc |= 0x80000000u;
        if (pixelMetricCoverage()) k.alphaFunc |= 0x40000000u;
    }
    k.fogType = (g.bp[BP_FOG3] >> 21) & 7;
}

// ------------------------------------------------------------------ vertex shader
#ifdef SMS_GLES
static const char* kVsHeader = R"(#version 300 es
precision highp float;
precision highp int;
layout(std140) uniform XFBlock { uvec4 xf[184]; };
uniform vec4 u_proj[2];
uniform vec4 u_vp[2];
uniform vec2 u_efb;
uniform vec4 u_chan[4];
in vec3 a_pos; in vec3 a_nrm; in vec3 a_bin; in vec3 a_tan;
in vec4 a_clr0; in vec4 a_clr1;
in vec2 a_tex0; in vec2 a_tex1; in vec2 a_tex2; in vec2 a_tex3;
in vec2 a_tex4; in vec2 a_tex5; in vec2 a_tex6; in vec2 a_tex7;
in uvec3 a_mtx;
out vec4 v_c0; out vec4 v_c1;
out vec3 v_tc0; out vec3 v_tc1; out vec3 v_tc2; out vec3 v_tc3;
out vec3 v_tc4; out vec3 v_tc5; out vec3 v_tc6; out vec3 v_tc7;
out float v_depth;
float X(int i) { return uintBitsToFloat(xf[i >> 2][i & 3]); }
vec4 R4(int w) { return vec4(X(w), X(w + 1), X(w + 2), X(w + 3)); }
vec3 R3(int w) { return vec3(X(w), X(w + 1), X(w + 2)); }
vec4 C(int w) { uint u = xf[w >> 2][w & 3];
  return vec4(float((u >> 24) & 255u), float((u >> 16) & 255u), float((u >> 8) & 255u), float(u & 255u)) / 255.0; }
// light block: word 608 + 16 * light
vec4 lightColor(int l) { return C(608 + l * 16 + 3); }
vec3 lightA(int l) { return R3(608 + l * 16 + 4); }
vec3 lightK(int l) { return R3(608 + l * 16 + 7); }
vec3 lightPos(int l) { return R3(608 + l * 16 + 10); }
vec3 lightDir(int l) { return R3(608 + l * 16 + 13); }
)";
#else
static const char* kVsHeader = R"(#version 330 core
layout(std140) uniform XFBlock { uvec4 xf[184]; };
uniform vec4 u_proj[2];
uniform vec4 u_vp[2];
uniform vec2 u_efb;
uniform vec4 u_chan[4];
in vec3 a_pos; in vec3 a_nrm; in vec3 a_bin; in vec3 a_tan;
in vec4 a_clr0; in vec4 a_clr1;
in vec2 a_tex0; in vec2 a_tex1; in vec2 a_tex2; in vec2 a_tex3;
in vec2 a_tex4; in vec2 a_tex5; in vec2 a_tex6; in vec2 a_tex7;
in uvec3 a_mtx;
out vec4 v_c0; out vec4 v_c1;
out vec3 v_tc0; out vec3 v_tc1; out vec3 v_tc2; out vec3 v_tc3;
out vec3 v_tc4; out vec3 v_tc5; out vec3 v_tc6; out vec3 v_tc7;
out float v_depth;
float X(int i) { return uintBitsToFloat(xf[i >> 2][i & 3]); }
vec4 R4(int w) { return vec4(X(w), X(w + 1), X(w + 2), X(w + 3)); }
vec3 R3(int w) { return vec3(X(w), X(w + 1), X(w + 2)); }
vec4 C(int w) { uint u = xf[w >> 2][w & 3];
  return vec4(float((u >> 24) & 255u), float((u >> 16) & 255u), float((u >> 8) & 255u), float(u & 255u)) / 255.0; }
// light block: word 608 + 16 * light
vec4 lightColor(int l) { return C(608 + l * 16 + 3); }
vec3 lightA(int l) { return R3(608 + l * 16 + 4); }
vec3 lightK(int l) { return R3(608 + l * 16 + 7); }
vec3 lightPos(int l) { return R3(608 + l * 16 + 10); }
vec3 lightDir(int l) { return R3(608 + l * 16 + 13); }
)";
#endif

// Attenuation divides by k0 + k1*d + k2*d^2.  A light loaded with all-zero
// coefficients (J3D leaves unused lights like that) must contribute nothing
// rather than 0/0 = NaN, which would turn the whole channel black; a zero
// denominator with a positive numerator saturates, as the hardware reciprocal does.
static void emitLight(std::string& s, uint32_t ctrl, const char* comp, const char* sumName, int light) {
    char buf[1024];
    std::string sumS = std::string(sumName) + comp;
    const char* sum = sumS.c_str();
    uint32_t diff = (ctrl >> 7) & 3;
    bool attn = (ctrl >> 9) & 1, spot = (ctrl >> 10) & 1;
    const char* dfn = diff == 0 ? "1.0" : diff == 1 ? "dot(N, L)" : "max(dot(N, L), 0.0)";
    if (!attn) {
        snprintf(buf, sizeof buf,
                 "  { vec3 L = normalize(lightPos(%d) - vpos); %s += (%s) * lightColor(%d)%s; }\n", light, sum, dfn,
                 light, comp);
    } else if (spot) {
        snprintf(buf, sizeof buf,
                 "  { vec3 L = lightPos(%d) - vpos; float d2 = dot(L, L); float d = sqrt(d2); L /= d;\n"
                 "    float cs = max(dot(L, lightDir(%d)), 0.0); vec3 a = lightA(%d); vec3 k = lightK(%d);\n"
                 "    float at = max(a.x + a.y * cs + a.z * cs * cs, 0.0) / max(k.x + k.y * d + k.z * d2, 1e-30);\n"
                 "    %s += at * (%s) * lightColor(%d)%s; }\n",
                 light, light, light, light, sum, dfn, light, comp);
    } else {
        snprintf(buf, sizeof buf,
                 "  { vec3 L = normalize(lightPos(%d)); float nl = dot(N, L);\n"
                 "    float t = nl > 0.0 ? max(dot(N, lightDir(%d)), 0.0) : 0.0; vec3 a = lightA(%d); vec3 k = lightK(%d);\n"
                 "    float at = max(a.x + a.y * t + a.z * t * t, 0.0) / max(k.x + k.y * t + k.z * t * t, 1e-30);\n"
                 "    %s += at * (%s) * lightColor(%d)%s; }\n",
                 light, light, light, light, sum, dfn, light, comp);
    }
    s += buf;
}

static std::string genVS(const ShaderKey& k) {
    std::string s = kVsHeader;
#ifdef SMS_GLES
    if (k.alphaFunc & 0x80000000u) s.replace(0, 15, "#version 310 es");
#endif
    char buf[1024];
    s += "void main() {\n"
         "  int pm = int(a_mtx.x & 255u);\n"
         "  vec4 p = vec4(a_pos, 1.0);\n"
         "  vec3 vpos = vec3(dot(R4(pm * 4), p), dot(R4(pm * 4 + 4), p), dot(R4(pm * 4 + 8), p));\n"
         "  int nm = 256 + pm * 3;\n"
         "  vec3 N = vec3(dot(R3(nm), a_nrm), dot(R3(nm + 3), a_nrm), dot(R3(nm + 6), a_nrm));\n"
         "  N = dot(N, N) > 0.0 ? normalize(N) : vec3(0.0, 0.0, 1.0);\n"
         "  vec4 clip;\n"
         "  if (u_proj[1].z == 0.0) {\n"
         "    clip = vec4(u_proj[0].x * vpos.x + u_proj[0].y * vpos.z, u_proj[0].z * vpos.y + u_proj[0].w * vpos.z,\n"
         "                u_proj[1].x * vpos.z + u_proj[1].y, -vpos.z);\n"
         "  } else {\n"
         "    clip = vec4(u_proj[0].x * vpos.x + u_proj[0].y, u_proj[0].z * vpos.y + u_proj[0].w,\n"
         "                u_proj[1].x * vpos.z + u_proj[1].y, 1.0);\n"
         "  }\n"
         // viewport in EFB pixels, then to GL clip space over the whole EFB (row 0 = EFB top)
         "  gl_Position.x = ((u_vp[1].x - 342.0) * clip.w + u_vp[0].x * clip.x) * (2.0 / u_efb.x) - clip.w;\n"
         "  gl_Position.y = ((u_vp[1].y - 342.0) * clip.w + u_vp[0].y * clip.y) * (2.0 / u_efb.y) - clip.w;\n"
         "  float zw = u_vp[1].z * clip.w + u_vp[0].z * clip.z;\n"
         "  gl_Position.z = zw * (2.0 / 16777215.0) - clip.w;\n"
         "  gl_Position.w = clip.w;\n"
         "  v_depth = zw / (16777215.0 * clip.w);\n";
#ifndef SMS_GLES
    if (k.clip) s += "  gl_ClipDistance[0] = clip.z + clip.w;\n  gl_ClipDistance[1] = -clip.z;\n";
    else s += "  gl_ClipDistance[0] = 1.0;\n  gl_ClipDistance[1] = 1.0;\n";
#endif

    // lighting: channels 0/1, colour and alpha controlled separately
    const char* vtx[2] = {"a_clr0", "a_clr1"};
    for (int c = 0; c < 2; c++) {
        uint32_t cc = k.chan[c], ac = k.chan[2 + c];
        snprintf(buf, sizeof buf, "  vec4 col%d;\n", c);
        s += buf;
        if (uint32_t(c) >= k.numChans) {
            snprintf(buf, sizeof buf, "  col%d = vec4(0.0);\n", c);
            s += buf;
            continue;
        }
        for (int part = 0; part < 2; part++) {
            uint32_t ctrl = part ? ac : cc;
            const char* comp = part ? ".a" : ".rgb";
            snprintf(buf, sizeof buf, "  { vec4 mat = %s; ", (ctrl & 1) ? vtx[c] : (c ? "u_chan[3]" : "u_chan[2]"));
            s += buf;
            if (ctrl & 2) {
                snprintf(buf, sizeof buf, "vec4 amb = %s; vec4 lsum = amb;\n", (ctrl & 64) ? vtx[c] : (c ? "u_chan[1]" : "u_chan[0]"));
                s += buf;
                uint32_t mask = ((ctrl >> 2) & 15) | ((ctrl >> 11) & 15) << 4;
                for (int l = 0; l < 8; l++)
                    if (mask & (1u << l)) emitLight(s, ctrl, comp, "lsum", l);
                snprintf(buf, sizeof buf, "    col%d%s = (mat * clamp(lsum, 0.0, 1.0))%s; }\n", c, comp, comp);
            } else {
                snprintf(buf, sizeof buf, "col%d%s = mat%s; }\n", c, comp, comp);
            }
            s += buf;
        }
    }
    s += "  v_c0 = col0;\n  v_c1 = col1;\n";

    // texgen
    for (uint32_t i = 0; i < 8; i++) {
        if (i >= k.numTexGens) {
            snprintf(buf, sizeof buf, "  v_tc%u = vec3(a_tex%u, 1.0);\n", i, i);
            s += buf;
            continue;
        }
        uint32_t tg = k.texgen[i];
        uint32_t proj = (tg >> 1) & 1, form = (tg >> 2) & 1, type = (tg >> 4) & 7, row = (tg >> 7) & 31;
        uint32_t embSrc = (tg >> 12) & 7;
        std::string src;
        if (row == 0) src = "vec4(a_pos, 1.0)";
        else if (row == 1) src = "vec4(a_nrm, 1.0)";
        else if (row == 2) src = "a_clr0";
        else if (row == 3) src = "vec4(a_bin, 1.0)";
        else if (row == 4) src = "vec4(a_tan, 1.0)";
        else {
            snprintf(buf, sizeof buf, "vec4(a_tex%u, 1.0, 1.0)", (row - 5) & 7);
            src = buf;
        }
        if (form == 0 && row >= 5) {
            // AB11: third component forced to 1
        } else if (form == 0) {
            src = "vec4((" + src + ").xy, 1.0, 1.0)";
        }
        // texture matrix index for this coordinate
        const char* mi[8] = {"(a_mtx.x >> 8) & 255u", "(a_mtx.x >> 16) & 255u", "a_mtx.x >> 24", "a_mtx.y & 255u",
                              "(a_mtx.y >> 8) & 255u", "(a_mtx.y >> 16) & 255u", "a_mtx.y >> 24", "a_mtx.z & 255u"};
        if (type == 0) {
            snprintf(buf, sizeof buf,
                     "  { vec4 sv = %s; int tm = int(%s) * 4; vec3 t = vec3(dot(R4(tm), sv), dot(R4(tm + 4), sv), %s);\n",
                     src.c_str(), mi[i], proj ? "dot(R4(tm + 8), sv)" : "1.0");
            s += buf;
        } else if (type == 1) {
            // emboss: offset of the source coordinate (approximated as the source itself)
            snprintf(buf, sizeof buf, "  { vec3 t = v_tc%u;\n", embSrc < i ? embSrc : 0);
            s += buf;
        } else {
            snprintf(buf, sizeof buf, "  { vec3 t = vec3(%s.rg, 1.0);\n", type == 3 ? "col1" : "col0");
            s += buf;
        }
        if (k.dualTex && type == 0) {
            uint32_t pt = k.post[i] & 63;
            bool norm = (k.post[i] >> 8) & 1;
            snprintf(buf, sizeof buf,
                     "    %s int pw = 352 + %u * 4; vec4 pv = vec4(t, 1.0);\n"
                     "    t = vec3(dot(R4(pw), pv), dot(R4(pw + 4), pv), dot(R4(pw + 8), pv));\n",
                     norm ? "t = normalize(t);" : "", pt);
            s += buf;
        }
        snprintf(buf, sizeof buf, "    v_tc%u = t; }\n", i);
        s += buf;
    }
    s += "}\n";
    return s;
}

// ------------------------------------------------------------------ fragment shader
#ifdef SMS_GLES
static const char* kFsHeader = R"(#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
in vec4 v_c0; in vec4 v_c1;
in vec3 v_tc0; in vec3 v_tc1; in vec3 v_tc2; in vec3 v_tc3;
in vec3 v_tc4; in vec3 v_tc5; in vec3 v_tc6; in vec3 v_tc7;
in float v_depth;
uniform sampler2D u_tex[8];
uniform ivec4 u_tevreg[4];
uniform ivec4 u_konst[4];
uniform vec2 u_texscale[8];
uniform vec2 u_texsize[8];
uniform ivec2 u_alpharef;
uniform vec4 u_fog;
uniform vec4 u_fogcolor;
uniform vec4 u_indmtx[6];
layout(location = 0) out vec4 o_color;
ivec4 texi(vec4 c) { return ivec4(round(clamp(c, 0.0, 1.0) * 255.0)); }
vec2 proj(vec3 t) { return t.xy / (t.z == 0.0 ? 1.0 : t.z); }
ivec3 lerp3(ivec3 a, ivec3 b, ivec3 c) { ivec3 c2 = c + (c >> 7); return (a * (256 - c2) + b * c2 + 128) >> 8; }
int lerp1(int a, int b, int c) { int c2 = c + (c >> 7); return (a * (256 - c2) + b * c2 + 128) >> 8; }
)";
#else
static const char* kFsHeader = R"(#version 330 core
in vec4 v_c0; in vec4 v_c1;
in vec3 v_tc0; in vec3 v_tc1; in vec3 v_tc2; in vec3 v_tc3;
in vec3 v_tc4; in vec3 v_tc5; in vec3 v_tc6; in vec3 v_tc7;
in float v_depth;
uniform sampler2D u_tex[8];
uniform ivec4 u_tevreg[4];
uniform ivec4 u_konst[4];
uniform vec2 u_texscale[8];
uniform vec2 u_texsize[8];
uniform ivec2 u_alpharef;
uniform vec4 u_fog;
uniform vec4 u_fogcolor;
uniform vec4 u_indmtx[6];
out vec4 o_color;
ivec4 texi(vec4 c) { return ivec4(round(clamp(c, 0.0, 1.0) * 255.0)); }
vec2 proj(vec3 t) { return t.xy / (t.z == 0.0 ? 1.0 : t.z); }
ivec3 lerp3(ivec3 a, ivec3 b, ivec3 c) { ivec3 c2 = c + (c >> 7); return (a * (256 - c2) + b * c2 + 128) >> 8; }
int lerp1(int a, int b, int c) { int c2 = c + (c >> 7); return (a * (256 - c2) + b * c2 + 128) >> 8; }
)";
#endif

static const char* kColorArg[16] = {"cprev.rgb", "cprev.aaa", "creg0.rgb", "creg0.aaa", "creg1.rgb", "creg1.aaa",
                                    "creg2.rgb", "creg2.aaa", "tex.rgb",   "tex.aaa",   "ras.rgb",   "ras.aaa",
                                    "ivec3(255)", "ivec3(128)", "konst.rgb", "ivec3(0)"};
static const char* kAlphaArg[8] = {"cprev.a", "creg0.a", "creg1.a", "creg2.a", "tex.a", "ras.a", "konst.a", "0"};
static const char* kRegName[4] = {"cprev", "creg0", "creg1", "creg2"};

static std::string konstExpr(uint32_t sel, bool alpha) {
    static const int k8[8] = {255, 223, 191, 159, 127, 95, 63, 31};
    char buf[64];
    if (sel < 8) {
        snprintf(buf, sizeof buf, alpha ? "%d" : "ivec3(%d)", k8[sel]);
        return buf;
    }
    if (!alpha && sel >= 12 && sel < 16) {
        snprintf(buf, sizeof buf, "u_konst[%u].rgb", sel - 12);
        return buf;
    }
    if (sel >= 16) {
        const char comp = "rgba"[(sel - 16) >> 2];
        snprintf(buf, sizeof buf, alpha ? "u_konst[%u].%c" : "ivec3(u_konst[%u].%c)", sel & 3, comp);
        return buf;
    }
    return alpha ? "255" : "ivec3(255)";
}

static std::string swizzle(uint32_t swap) {
    std::string s;
    const char* c = "rgba";
    s += c[swap & 3];
    s += c[(swap >> 2) & 3];
    s += c[(swap >> 4) & 3];
    s += c[(swap >> 6) & 3];
    return s;
}

// indScale: the indirect scale registers (BP_RAS1_SS0/SS1) the program is
// made for, from its ProgramKey: the warm-up compiles programs while the GX
// state holds other values.
static std::string genFS(const ShaderKey& k, const uint32_t indScale[2]) {
    std::string s = kFsHeader;
#ifdef SMS_GLES
    if (k.alphaFunc & 0x80000000u) {
        s.replace(0, 15, "#version 310 es");
        if (k.alphaFunc & 0x40000000u)
            s.insert(s.find('\n') + 1, "#extension GL_OES_sample_variables : require\n");
        s += "layout(std430, binding=0) buffer PixelSamples { uint pixelBins[256]; };\n";
    }
#endif
    char buf[2048];
    s += "void main() {\n"
         "  ivec4 cprev = u_tevreg[0], creg0 = u_tevreg[1], creg1 = u_tevreg[2], creg2 = u_tevreg[3];\n"
         "  ivec4 ras0 = texi(v_c0), ras1 = texi(v_c1);\n"
         "  ivec4 tex = ivec4(255), ras = ivec4(0), konst = ivec4(0);\n"
         "  int bumpA = 0; vec2 prevTc = vec2(0.0);\n";
    // indirect texture lookups
    for (uint32_t i = 0; i < k.numInd; i++) {
        uint32_t map = (k.iref >> (6 * i)) & 7, coord = (k.iref >> (6 * i + 3)) & 7;
        uint32_t ss = (indScale[i < 2 ? 0 : 1] >> ((i & 1) * 8)) & 0xFF;
        snprintf(buf, sizeof buf,
                 "  ivec3 ind%u = texi(texture(u_tex[%u], proj(v_tc%u) / vec2(%d.0, %d.0))).abg;\n", i, map, coord,
                 1 << (ss & 15), 1 << ((ss >> 4) & 15));
        s += buf;
    }
    uint32_t lastDest = 0;
    bool lastAlphaDest = 0;
    uint32_t lastA = 0;
    for (uint32_t st = 0; st < k.numStages; st++) {
        uint32_t ord = k.order[st];
        uint32_t map = ord & 7, coord = (ord >> 3) & 7, en = (ord >> 6) & 1, chan = (ord >> 7) & 7;
        uint32_t ce = k.colorEnv[st], ae = k.alphaEnv[st];
        uint32_t rasSwap = k.swap[ae & 3], texSwap = k.swap[(ae >> 2) & 3];
        snprintf(buf, sizeof buf, "  // stage %u\n  {\n", st);
        s += buf;
        uint32_t ind = k.ind[st];
        if (en) {
            if (ind) {
                uint32_t bt = ind & 3, fmt = (ind >> 2) & 3, bias = (ind >> 4) & 7, bs = (ind >> 7) & 3,
                         mid = (ind >> 9) & 15, sw = (ind >> 13) & 7, tw = (ind >> 16) & 7, fb = (ind >> 20) & 1;
                static const int fmtMask[4] = {255, 31, 15, 7};
                snprintf(buf, sizeof buf, "    ivec3 iv = ind%u & ivec3(%d);\n", bt < k.numInd ? bt : 0, fmtMask[fmt]);
                s += buf;
                if (bs) {
                    snprintf(buf, sizeof buf, "    bumpA = (ind%u.%c) & 0xF8;\n", bt < k.numInd ? bt : 0, "xxyz"[bs]);
                    s += buf;
                }
                int bv = fmt == 0 ? -128 : 1;
                snprintf(buf, sizeof buf, "    iv += ivec3(%d, %d, %d);\n", (bias & 1) ? bv : 0, (bias & 2) ? bv : 0, (bias & 4) ? bv : 0);
                s += buf;
                if (mid >= 1 && mid <= 3) {
                    // Adapted from chasem-dev/sms-pc-port (MIT); notice in
                    // platform/gx/third_party/chasem-pc-port/LICENSE.
                    // The hardware's integer offset: s.10 matrix entries, the product >> 3 in 1/128 texels,
                    // shifted by the matrix's exponent and kept to the coordinate's 24 bits. Shifted 24 or
                    // more, nothing is left (Sunshine's goop bubbles, ms_raku_indawa, use an exponent of 29).
                    uint32_t m = mid - 1;
                    snprintf(buf, sizeof buf,
                             "    ivec3 m0 = ivec3(u_indmtx[%u].xyz * 1024.0), m1 = ivec3(u_indmtx[%u].xyz * 1024.0);\n"
                             "    ivec2 it = ivec2(m0.x * iv.x + m0.y * iv.y + m0.z * iv.z, m1.x * iv.x + m1.y * iv.y + m1.z * iv.z) >> 3;\n"
                             "    int sc = int(u_indmtx[%u].w);\n"
                             "    it = sc >= 32 ? ivec2(0) : sc >= 0 ? it << sc : it >> -sc;\n"
                             "    vec2 off = vec2((it << 8) >> 8) / 128.0;\n",
                             m * 2, m * 2 + 1, m * 2);
                } else {
                    snprintf(buf, sizeof buf, "    vec2 off = vec2(0.0);\n");
                }
                s += buf;
                snprintf(buf, sizeof buf, "    vec2 tcx = proj(v_tc%u) * u_texscale[%u] * u_texsize[%u];\n", coord, coord, map);
                s += buf;
                static const int wrapSize[8] = {0, 256, 128, 64, 32, 16, 0, 0};
                if (sw == 6) s += "    tcx.x = 0.0;\n";
                else if (sw) { snprintf(buf, sizeof buf, "    tcx.x = mod(tcx.x, %d.0);\n", wrapSize[sw]); s += buf; }
                if (tw == 6) s += "    tcx.y = 0.0;\n";
                else if (tw) { snprintf(buf, sizeof buf, "    tcx.y = mod(tcx.y, %d.0);\n", wrapSize[tw]); s += buf; }
                snprintf(buf, sizeof buf, "    tcx += off%s; prevTc = tcx;\n", fb ? " + prevTc" : "");
                s += buf;
                snprintf(buf, sizeof buf, "    tex = texi(texture(u_tex[%u], tcx / u_texsize[%u])).%s;\n", map, map, swizzle(texSwap).c_str());
            } else {
                snprintf(buf, sizeof buf, "    tex = texi(texture(u_tex[%u], proj(v_tc%u) * u_texscale[%u])).%s;\n", map, coord,
                         coord, swizzle(texSwap).c_str());
            }
            s += buf;
        }
        if (chan == 0) snprintf(buf, sizeof buf, "    ras = ras0.%s;\n", swizzle(rasSwap).c_str());
        else if (chan == 1) snprintf(buf, sizeof buf, "    ras = ras1.%s;\n", swizzle(rasSwap).c_str());
        else if (chan == 5) snprintf(buf, sizeof buf, "    ras = ivec4(bumpA);\n");
        else if (chan == 6) snprintf(buf, sizeof buf, "    ras = ivec4(bumpA | (bumpA >> 5));\n");
        else snprintf(buf, sizeof buf, "    ras = ivec4(0);\n");
        s += buf;
        uint32_t kc = k.ksel[st] & 31, ka = (k.ksel[st] >> 5) & 31;
        snprintf(buf, sizeof buf, "    konst = ivec4(%s, %s);\n", konstExpr(kc, false).c_str(), konstExpr(ka, true).c_str());
        s += buf;

        // colour
        {
            uint32_t d = ce & 15, c = (ce >> 4) & 15, b = (ce >> 8) & 15, a = (ce >> 12) & 15;
            uint32_t bias = (ce >> 16) & 3, op = (ce >> 18) & 1, clampv = (ce >> 19) & 1, scale = (ce >> 20) & 3,
                     dest = (ce >> 22) & 3;
            snprintf(buf, sizeof buf,
                     "    ivec3 A = (%s) & 255, B = (%s) & 255, Cc = (%s) & 255, D = %s;\n", kColorArg[a], kColorArg[b],
                     kColorArg[c], kColorArg[d]);
            s += buf;
            if (bias != 3) {
                snprintf(buf, sizeof buf, "    ivec3 r = D %c lerp3(A, B, Cc)%s;\n", op ? '-' : '+',
                         bias == 1 ? " + 128" : bias == 2 ? " - 128" : "");
                s += buf;
                if (scale == 1) s += "    r *= 2;\n";
                else if (scale == 2) s += "    r *= 4;\n";
                else if (scale == 3) s += "    r = r >> 1;\n";
            } else {
                uint32_t mode = (scale << 1) | op;
                const char* cond;
                switch (mode) {
                case 0: cond = "bvec3(A.r > B.r)"; break;
                case 1: cond = "bvec3(A.r == B.r)"; break;
                case 2: cond = "bvec3((A.g * 256 + A.r) > (B.g * 256 + B.r))"; break;
                case 3: cond = "bvec3((A.g * 256 + A.r) == (B.g * 256 + B.r))"; break;
                case 4: cond = "bvec3((A.b * 65536 + A.g * 256 + A.r) > (B.b * 65536 + B.g * 256 + B.r))"; break;
                case 5: cond = "bvec3((A.b * 65536 + A.g * 256 + A.r) == (B.b * 65536 + B.g * 256 + B.r))"; break;
                case 6: cond = "greaterThan(A, B)"; break;
                default: cond = "equal(A, B)"; break;
                }
                snprintf(buf, sizeof buf, "    ivec3 r = D + ivec3(%s) * Cc;\n", cond);
                s += buf;
            }
            snprintf(buf, sizeof buf, "    %s.rgb = %s;\n", kRegName[dest],
                     clampv ? "clamp(r, 0, 255)" : "clamp(r, -1024, 1023)");
            s += buf;
            lastDest = dest;
        }
        // alpha
        {
            uint32_t d = (ae >> 4) & 7, c = (ae >> 7) & 7, b = (ae >> 10) & 7, a = (ae >> 13) & 7;
            uint32_t bias = (ae >> 16) & 3, op = (ae >> 18) & 1, clampv = (ae >> 19) & 1, scale = (ae >> 20) & 3,
                     dest = (ae >> 22) & 3;
            snprintf(buf, sizeof buf, "    int Aa = (%s) & 255, Ba = (%s) & 255, Ca = (%s) & 255, Da = %s;\n",
                     kAlphaArg[a], kAlphaArg[b], kAlphaArg[c], kAlphaArg[d]);
            s += buf;
            if (bias != 3) {
                snprintf(buf, sizeof buf, "    int ra = Da %c lerp1(Aa, Ba, Ca)%s;\n", op ? '-' : '+',
                         bias == 1 ? " + 128" : bias == 2 ? " - 128" : "");
                s += buf;
                if (scale == 1) s += "    ra *= 2;\n";
                else if (scale == 2) s += "    ra *= 4;\n";
                else if (scale == 3) s += "    ra = ra >> 1;\n";
            } else {
                uint32_t mode = (scale << 1) | op;
                const char* cond;
                switch (mode) {
                case 0: cond = "A.r > B.r"; break;
                case 1: cond = "A.r == B.r"; break;
                case 2: cond = "(A.g * 256 + A.r) > (B.g * 256 + B.r)"; break;
                case 3: cond = "(A.g * 256 + A.r) == (B.g * 256 + B.r)"; break;
                case 4: cond = "(A.b * 65536 + A.g * 256 + A.r) > (B.b * 65536 + B.g * 256 + B.r)"; break;
                case 5: cond = "(A.b * 65536 + A.g * 256 + A.r) == (B.b * 65536 + B.g * 256 + B.r)"; break;
                case 6: cond = "Aa > Ba"; break;
                default: cond = "Aa == Ba"; break;
                }
                snprintf(buf, sizeof buf, "    int ra = Da + ((%s) ? Ca : 0);\n", cond);
                s += buf;
            }
            snprintf(buf, sizeof buf, "    %s.a = %s;\n", kRegName[dest], clampv ? "clamp(ra, 0, 255)" : "clamp(ra, -1024, 1023)");
            s += buf;
            lastA = dest;
            lastAlphaDest = true;
        }
        s += "  }\n";
    }
    (void)lastAlphaDest;
    // Adapted from the same MIT upstream: unclamped TEV results retain
    // their low eight bits. Bianco's water alpha relies on this wrap.
    snprintf(buf, sizeof buf, "  ivec4 outc = ivec4(%s.rgb, %s.a) & 255;\n", kRegName[lastDest], kRegName[lastA]);
    s += buf;

    // alpha compare
    {
        uint32_t c0 = k.alphaFunc & 7, c1 = (k.alphaFunc >> 3) & 7, op = (k.alphaFunc >> 6) & 3;
        auto cmp = [](uint32_t f, const char* ref) -> std::string {
            static const char* ops[8] = {"false", "<", "==", "<=", ">", "!=", ">=", "true"};
            if (f == 0) return "false";
            if (f == 7) return "true";
            return std::string("(outc.a ") + ops[f] + " " + ref + ")";
        };
        std::string a = cmp(c0, "u_alpharef.x"), b = cmp(c1, "u_alpharef.y");
        std::string e;
        switch (op) {
        case 0: e = a + " && " + b; break;
        case 1: e = a + " || " + b; break;
        case 2: e = a + " != " + b; break;
        default: e = a + " == " + b; break;
        }
        if (!(c0 == 7 && c1 == 7 && op <= 1)) s += "  if (!(" + e + ")) discard;\n";
    }
    s += "  vec4 color = vec4(outc) / 255.0;\n";
    if (k.fogType) {
        // Fog needs screen-linear pixel depth, not perspective interpolation of z/w.
        // See the pinned MIT reference in third_party/chasem-pc-port.
        // u_fog: x = A, y = B, z = C, w = 1 for orthographic
        s += "  float ze = u_fog.w != 0.0 ? u_fog.x * gl_FragCoord.z : u_fog.x / (u_fog.y - gl_FragCoord.z);\n"
             "  float f = clamp(ze - u_fog.z, 0.0, 1.0);\n";
        switch (k.fogType) {
        case 4: s += "  f = 1.0 - exp2(-8.0 * f);\n"; break;
        case 5: s += "  f = 1.0 - exp2(-8.0 * f * f);\n"; break;
        case 6: s += "  f = exp2(-8.0 * (1.0 - f));\n"; break;
        case 7: s += "  f = exp2(-8.0 * (1.0 - f) * (1.0 - f));\n"; break;
        default: break;
        }
        s += "  color.rgb = mix(color.rgb, u_fogcolor.rgb, f);\n";
    }
#ifdef SMS_GLES
    if (k.alphaFunc & 0x80000000u) {
        // Count coverage, not shader invocations: MSAA may invoke both
        // triangles at a shared edge for disjoint sets of samples.
        const char* weight = (k.alphaFunc & 0x40000000u)
            ? "uint(bitCount(gl_SampleMaskIn[0])) * 256u / uint(max(gl_NumSamples, 1))" : "1u";
        s += "  atomicAdd(pixelBins[(uint(gl_FragCoord.x) + uint(gl_FragCoord.y) * 17u) & 255u], ";
        s += weight;
        s += ");\n";
    }
#endif
    s += "  o_color = color;\n}\n";
    return s;
}

// ------------------------------------------------------------------ compile / cache
// The program cache key: the shader key plus the indirect scale factors,
// which are baked into the code. Compared as bytes (buildKey zero-fills).
struct ProgramKey {
    ShaderKey k;
    uint32_t indScale[2];
    bool operator==(const ProgramKey& o) const { return memcmp(this, &o, sizeof(*this)) == 0; }
};
struct ProgramKeyHash {
    size_t operator()(const ProgramKey& k) const { return size_t(hashBytes(&k, sizeof(k))); }
};
static std::unordered_map<ProgramKey, ShaderProgram, ProgramKeyHash> s_programs;
static ProgramKey s_lastKey;
static const ShaderProgram* s_lastProgram = nullptr;

#pragma pack(push, 1)
struct ShaderCacheFileHeader {
    uint32_t magic;       // 0x534D5343 ('SMSC')
    uint32_t version;     // kShaderCacheVersion
};
struct ShaderCacheEntryHeader {
    ProgramKey key;
    uint32_t binaryFormat;
    uint32_t binaryLength;
};
#pragma pack(pop)

// 2: fragment shaders take the indirect scale from the program key (programs
// made by the warm-up under version 1 could have another one baked in).
// 4: indirect coordinates and final unclamped TEV outputs wrap like GX.
// 5: fog uses fragment window depth.
static const uint32_t kShaderCacheVersion = 5;

// The cache file is shared with the launcher's background compile
// (GXPC_PrecompileShaders, another process): readers take a shared lock,
// writers an exclusive one, held until the file is closed.
static void lockCache(FILE* f, int op) { flock(fileno(f), op); }

static bool isShaderCacheEnabled() {
    static int s_enabled = -1;
    if (s_enabled < 0) {
        const char* e = getenv("SMS_SHADER_CACHE");
        if (e) s_enabled = atoi(e) != 0;
        else s_enabled = 1;
    }
    return s_enabled != 0;
}

static std::string getShaderCachePath() {
    const char* saveDir = getenv("SMS_SAVE_DIR");
    if (saveDir && *saveDir) {
        return std::string(saveDir) + "/shader_cache.bin";
    }
    return "shader_cache.bin";
}

static void setupProgramUniforms(GLuint p, ShaderProgram& sp) {
    sp.prog = p;
    sp.uc.init = false;
    glUseProgram(p);
    g_glc.prog = p;
    GLuint blk = glGetUniformBlockIndex(p, "XFBlock");
    if (blk != GL_INVALID_INDEX) glUniformBlockBinding(p, blk, 0);
    GLint units[8] = {0, 1, 2, 3, 4, 5, 6, 7};
    GLint loc = glGetUniformLocation(p, "u_tex");
    if (loc >= 0) glUniform1iv(loc, 8, units);
    sp.uTevReg = glGetUniformLocation(p, "u_tevreg");
    sp.uKonst = glGetUniformLocation(p, "u_konst");
    sp.uTexScale = glGetUniformLocation(p, "u_texscale");
    sp.uAlphaRef = glGetUniformLocation(p, "u_alpharef");
    sp.uFog = glGetUniformLocation(p, "u_fog");
    sp.uFogColor = glGetUniformLocation(p, "u_fogcolor");
    sp.uIndMtx = glGetUniformLocation(p, "u_indmtx");
    sp.uTexSize = glGetUniformLocation(p, "u_texsize");
    sp.uEfb = glGetUniformLocation(p, "u_efb");
    sp.uProj = glGetUniformLocation(p, "u_proj");
    sp.uViewport = glGetUniformLocation(p, "u_vp");
    sp.uAmbMat = glGetUniformLocation(p, "u_chan");
    sp.uDstAlpha = -1;
}

static void saveProgramToCache(const ProgramKey& key, GLuint p) {
    if (!isShaderCacheEnabled() || !glGetProgramBinary) return;
    GLint len = 0;
    glGetProgramiv(p, GL_PROGRAM_BINARY_LENGTH, &len);
    if (len <= 0) return;

    std::vector<uint8_t> bin(len);
    GLsizei actualLen = 0;
    GLenum format = 0;
    glGetProgramBinary(p, len, &actualLen, &format, bin.data());
    if (actualLen <= 0) return;

    std::string path = getShaderCachePath();
    FILE* f = fopen(path.c_str(), "ab+");
    if (!f) return;
    lockCache(f, LOCK_EX);
    fseek(f, 0, SEEK_END);
    if (ftell(f) == 0) {
        ShaderCacheFileHeader hdr = { 0x534D5343, kShaderCacheVersion };
        fwrite(&hdr, sizeof(hdr), 1, f);
    }
    ShaderCacheEntryHeader eh;
    eh.key = key;
    eh.binaryFormat = format;
    eh.binaryLength = (uint32_t)actualLen;
    fwrite(&eh, sizeof(eh), 1, f);
    fwrite(bin.data(), 1, actualLen, f);
    fclose(f);
}

// A program is compiled in two halves: started (issueProgram), then checked
// and set up (finishProgram). Started together, as the warm-up does, a driver
// with GL_KHR_parallel_shader_compile compiles them at the same time.
struct PendingProgram {
    ProgramKey key;
    std::string vs, fs;
    GLuint v = 0, f = 0, p = 0;
};

static GLuint startCompile(GLenum type, const std::string& src) {
    GLuint sh = glCreateShader(type);
    const char* p = src.c_str();
    glShaderSource(sh, 1, &p, nullptr);
    glCompileShader(sh);
    return sh;
}

static void issueProgram(PendingProgram& j) {
    j.vs = genVS(j.key.k);
    j.fs = genFS(j.key.k, j.key.indScale);
    j.v = startCompile(GL_VERTEX_SHADER, j.vs);
    j.f = startCompile(GL_FRAGMENT_SHADER, j.fs);
    j.p = glCreateProgram();
    glAttachShader(j.p, j.v);
    glAttachShader(j.p, j.f);
    static const char* attrs[] = {"a_pos", "a_nrm", "a_bin", "a_tan", "a_clr0", "a_clr1", "a_tex0", "a_tex1", "a_tex2",
                                  "a_tex3", "a_tex4", "a_tex5", "a_tex6", "a_tex7", "a_mtx"};
    for (GLuint i = 0; i < 15; i++) glBindAttribLocation(j.p, i, attrs[i]);
    glBindFragDataLocation(j.p, 0, "o_color");
    glLinkProgram(j.p);
}

static void logCompileFailure(GLuint sh, const std::string& src) {
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (ok) return;
    char log[4096];
    glGetShaderInfoLog(sh, sizeof log, nullptr, log);
    logmsg("shader compile failed:\n%s\n--- source ---\n%s", log, src.c_str());
}

static ShaderProgram finishProgram(PendingProgram& j) {
    g_statShaderCompiles++;
    GLint ok = 0;
    glGetProgramiv(j.p, GL_LINK_STATUS, &ok);
    if (!ok) {
        logCompileFailure(j.v, j.vs);
        logCompileFailure(j.f, j.fs);
        char log[4096];
        glGetProgramInfoLog(j.p, sizeof log, nullptr, log);
        logmsg("program link failed: %s", log);
    }
    glDeleteShader(j.v);
    glDeleteShader(j.f);
    ShaderProgram sp;
    setupProgramUniforms(j.p, sp);
    sp.id = int(g_statShaderCompiles);
    if (const char* dir = getenv("SMS_GX_DUMP_SHADERS")) {
        char path[1024];
        snprintf(path, sizeof path, "%s/prog%d.vs", dir, sp.id);
        if (FILE* file = fopen(path, "w")) { fputs(j.vs.c_str(), file); fclose(file); }
        snprintf(path, sizeof path, "%s/prog%d.fs", dir, sp.id);
        if (FILE* file = fopen(path, "w")) { fputs(j.fs.c_str(), file); fclose(file); }
    }
    if (ok) saveProgramToCache(j.key, j.p);
    return sp;
}

static ShaderProgram compileProgramFromSource(const ProgramKey& key) {
    PendingProgram j;
    j.key = key;
    issueProgram(j);
    return finishProgram(j);
}

const ShaderProgram* shaderForCurrentState() {
    ProgramKey key;
    ShaderKey& k = key.k;
    buildKey(k);
    key.indScale[0] = k.numInd ? g.bp[BP_RAS1_SS0] : 0;
    key.indScale[1] = k.numInd ? g.bp[BP_RAS1_SS0 + 1] : 0;
    // consecutive batches mostly share a program
    if (s_lastProgram && key == s_lastKey) return s_lastProgram;
    auto it = s_programs.find(key);
    if (it != s_programs.end()) {
        s_lastKey = key;
        return s_lastProgram = &it->second;
    }

    ShaderProgram sp = compileProgramFromSource(key);
    s_lastKey = key;
    return s_lastProgram = &(s_programs[key] = sp);
}

static void prewarmCommonShaders() {
    // Canonical 1: 1 texgen, 1 chan, 1 stage, modulate
    ProgramKey pk1;
    memset(&pk1, 0, sizeof(pk1));
    pk1.k.numTexGens = 1; pk1.k.numChans = 1; pk1.k.numStages = 1;
    pk1.k.colorEnv[0] = 0x0008F0; pk1.k.alphaEnv[0] = 0x0008F0;
    if (s_programs.find(pk1) == s_programs.end()) s_programs[pk1] = compileProgramFromSource(pk1);

    // Canonical 2: 1 texgen, 1 chan, 1 stage, replace
    ProgramKey pk2;
    memset(&pk2, 0, sizeof(pk2));
    pk2.k.numTexGens = 1; pk2.k.numChans = 1; pk2.k.numStages = 1;
    pk2.k.colorEnv[0] = 0x000FC0; pk2.k.alphaEnv[0] = 0x000FC0;
    if (s_programs.find(pk2) == s_programs.end()) s_programs[pk2] = compileProgramFromSource(pk2);

    // Canonical 3: 0 texgen (color only), 1 chan, 1 stage
    ProgramKey pk3;
    memset(&pk3, 0, sizeof(pk3));
    pk3.k.numTexGens = 0; pk3.k.numChans = 1; pk3.k.numStages = 1;
    pk3.k.colorEnv[0] = 0x000FF0; pk3.k.alphaEnv[0] = 0x000FF0;
    if (s_programs.find(pk3) == s_programs.end()) s_programs[pk3] = compileProgramFromSource(pk3);

    // Canonical 4: 1 texgen, 1 chan, 2 stages (alpha test + lighting)
    ProgramKey pk4;
    memset(&pk4, 0, sizeof(pk4));
    pk4.k.numTexGens = 1; pk4.k.numChans = 1; pk4.k.numStages = 2;
    pk4.k.colorEnv[0] = 0x0008F0; pk4.k.alphaEnv[0] = 0x0008F0;
    pk4.k.colorEnv[1] = 0x0008F0; pk4.k.alphaEnv[1] = 0x0008F0;
    if (s_programs.find(pk4) == s_programs.end()) s_programs[pk4] = compileProgramFromSource(pk4);

    // Canonical 5: 2 texgens, 1 chan, 2 stages
    ProgramKey pk5;
    memset(&pk5, 0, sizeof(pk5));
    pk5.k.numTexGens = 2; pk5.k.numChans = 1; pk5.k.numStages = 2;
    pk5.k.colorEnv[0] = 0x0008F0; pk5.k.alphaEnv[0] = 0x0008F0;
    pk5.k.colorEnv[1] = 0x0008F0; pk5.k.alphaEnv[1] = 0x0008F0;
    if (s_programs.find(pk5) == s_programs.end()) s_programs[pk5] = compileProgramFromSource(pk5);
}

// How far the cache file has been read: the launcher's background compile
// (GXPC_PrecompileShaders) keeps adding to it while the game runs, and each
// stage load (shaderPrepareStage) takes what came since.
static long s_cacheEnd = 0;

// The cache file's programs from s_cacheEnd on (all of it the first time);
// returns how many were new.
static size_t loadCachedPrograms() {
    FILE* f = fopen(getShaderCachePath().c_str(), "rb");
    if (!f) return 0;
    lockCache(f, LOCK_SH);
    size_t loaded = 0;
    ShaderCacheFileHeader hdr;
    if (fread(&hdr, sizeof hdr, 1, f) == 1 && hdr.magic == 0x534D5343 && hdr.version == kShaderCacheVersion) {
        if (s_cacheEnd > long(sizeof hdr) && fseek(f, s_cacheEnd, SEEK_SET) != 0) s_cacheEnd = 0;
        ShaderCacheEntryHeader eh;
        std::vector<uint8_t> bin;
        while (fread(&eh, sizeof eh, 1, f) == 1) {
            if (eh.binaryLength == 0 || eh.binaryLength > (16u << 20)) break;
            if (s_programs.count(eh.key)) {  // made here (or read) already
                if (fseek(f, long(eh.binaryLength), SEEK_CUR) != 0) break;
            } else {
                bin.resize(eh.binaryLength);
                if (fread(bin.data(), 1, eh.binaryLength, f) != eh.binaryLength) break;
                GLuint p = glCreateProgram();
                glProgramBinary(p, eh.binaryFormat, bin.data(), eh.binaryLength);
                GLint ok = 0;
                glGetProgramiv(p, GL_LINK_STATUS, &ok);
                if (ok) {
                    ShaderProgram sp;
                    setupProgramUniforms(p, sp);
                    sp.id = (int)s_programs.size();
                    s_programs[eh.key] = sp;
                    loaded++;
                } else {
                    glDeleteProgram(p);
                }
            }
            s_cacheEnd = ftell(f);
        }
    }
    fclose(f);
    return loaded;
}

void shaderInit() {
    if (!isShaderCacheEnabled()) return;

    GLint numFormats = 0;
    if (glProgramBinary) glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, &numFormats);
    if (numFormats > 0) {
        std::string path = getShaderCachePath();
        if (FILE* f = fopen(path.c_str(), "rb+")) {  // an older layout: start again
            lockCache(f, LOCK_EX);
            ShaderCacheFileHeader hdr;
            const bool empty = fread(&hdr, sizeof hdr, 1, f) != 1;
            if (!empty && (hdr.magic != 0x534D5343 || hdr.version != kShaderCacheVersion) &&
                ftruncate(fileno(f), 0) == 0)
                logmsg("shader: cache %s was an older version, cleared", path.c_str());
            fclose(f);
        }
        size_t loaded = loadCachedPrograms();
        if (loaded > 0) logmsg("shader: successfully pre-loaded %zu programs from cache (%s)", loaded, path.c_str());
    }

    prewarmCommonShaders();
    logmsg("shader: initialized cache and pre-warmed %zu active shaders", s_programs.size());
}

// ------------------------------------------------------------------ warm-up
// A program is otherwise compiled when a draw first needs it, and a stage's
// arrival needs dozens: the stutter of its first seconds. The program cache
// makes that once per device; the warm-up makes it before gameplay, from the
// program keys the game's stages are known to draw with (gx_shader_keys.inc,
// the PC port's recordings). While a stage loads (shaderPrepareStage, from
// TMarDirector::direct), its own programs are compiled, then the other known
// ones for up to a second (a quarter without GL_KHR_parallel_shader_compile),
// so a device's first loads make the lot. Programs the cache already holds
// cost nothing. SMS_GX_SHADER_WARMUP=0 turns it off.
//
// The PC port's keys have two more ShaderKey words (earlyZ and depthOnly,
// after fogType) than this renderer's; they are dropped when the keys are read.
#include "gx_shader_keys.inc"

static int s_warmState = -1;  // -1 not set up, 0 off, 1 on
static bool s_parallel = false;
static std::vector<ProgramKey> s_warmKeys;                      // each once
static std::unordered_map<int, std::vector<size_t>> s_stageKeys;  // a stage's, in s_warmKeys
static size_t s_warmNext = 0;  // the next of the others to compile

// The known program keys (each once) and each stage's, in `keys`; false if
// gx_shader_keys.inc is not in a layout this renderer can read.
static bool decodeWarmKeys(std::vector<ProgramKey>& keys, std::unordered_map<int, std::vector<size_t>>& stageKeys) {
    // the PC port's layout 1: ShaderKey up to fogType (113 words), earlyZ,
    // depthOnly, then indScale[2]
    const uint32_t kUpWords = 117, kUpDropped = 113, kWords = sizeof(ProgramKey) / 4;
    static_assert(sizeof(ProgramKey) == (117 - 2) * 4, "ProgramKey no longer matches the PC port's key layout 1");
    if (kWarmLayout != 1 || kWarmKeySize != kUpWords * 4 || kWarmFormat != 2) {
        logmsg("shader warm-up: off (gx_shader_keys.inc is for key layout %u of %u bytes)", unsigned(kWarmLayout),
               unsigned(kWarmKeySize));
        return false;
    }
    // The programs (each its count of nonzero words, then each as its index
    // and its value, LEB128), then each stage's (its number, its count, their
    // indices; 16 bits each but the number); the PC port's tools/gx/shader_keys.py.
    const uint8_t* p = kWarmKeys;
    const uint8_t* const end = kWarmKeys + sizeof kWarmKeys;
    auto u16 = [&] { uint32_t v = p + 2 <= end ? uint32_t(p[0]) | uint32_t(p[1]) << 8 : 0; p += 2; return v; };
    const uint32_t count = u16();
    std::vector<size_t> index(count);  // the file's key number -> keys
    std::unordered_map<ProgramKey, size_t, ProgramKeyHash> seen;
    for (uint32_t n = 0; n < count && p < end; n++) {
        uint32_t words[kWords] = {};
        for (int c = *p++; c > 0 && p < end; c--) {
            const uint32_t i = *p++;
            uint32_t v = 0;
            for (int shift = 0; p < end; shift += 7) {
                v |= uint32_t(*p & 0x7F) << shift;
                if (!(*p++ & 0x80)) break;
            }
            if (i < kUpDropped) words[i] = v;
            else if (i >= kUpDropped + 2 && i < kUpWords) words[i - 2] = v;
        }
        ProgramKey key;
        memcpy(&key, words, sizeof key);
        auto it = seen.emplace(key, keys.size());
        if (it.second) keys.push_back(key);
        index[n] = it.first->second;
    }
    while (p + 3 <= end) {
        const int stage = *p++;
        std::vector<size_t>& mine = stageKeys[stage];
        for (uint32_t n = u16(); n > 0 && p + 2 <= end; n--) {
            const uint32_t i = u16();
            if (i < count && std::find(mine.begin(), mine.end(), index[i]) == mine.end()) mine.push_back(index[i]);
        }
    }
    return true;
}

// whether the driver compiles in the background; asks for all its threads
static bool enableParallelCompile() {
    bool parallel = false;
    GLint n = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &n);
    for (GLint i = 0; i < n; i++) {
        const char* x = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, GLuint(i)));
        if (x && (!strcmp(x, "GL_KHR_parallel_shader_compile") || !strcmp(x, "GL_ARB_parallel_shader_compile")))
            parallel = true;
    }
#ifdef __ANDROID__
    // as many compiler threads as the driver offers (its default may be fewer)
    typedef void (*MaxThreadsFn)(GLuint);
    if (parallel)
        if (auto maxThreads = reinterpret_cast<MaxThreadsFn>(eglGetProcAddress("glMaxShaderCompilerThreadsKHR")))
            maxThreads(0xFFFFFFFFu);
#endif
    if (getenv("SMS_GX_SHADER_WARMUP_SERIAL")) parallel = false;
    return parallel;
}

static void warmSetup() {
    s_warmState = 0;
    const char* e = getenv("SMS_GX_SHADER_WARMUP");
    if (e && (!strcmp(e, "0") || !strcmp(e, "off"))) return;
    if (!decodeWarmKeys(s_warmKeys, s_stageKeys)) return;
    s_parallel = enableParallelCompile();
    size_t cached = 0;
    for (const ProgramKey& k : s_warmKeys) cached += s_programs.count(k);
    s_warmState = 1;
    logmsg("shader warm-up: %zu known programs, %zu cached%s", s_warmKeys.size(), cached,
           s_parallel ? ", compiled in parallel" : "");
}

// compiles `keys` together; returns how many were made
static uint32_t warmCompile(const std::vector<const ProgramKey*>& keys) {
    std::vector<PendingProgram> jobs(keys.size());
    for (size_t i = 0; i < keys.size(); i++) {
        jobs[i].key = *keys[i];
        issueProgram(jobs[i]);
    }
    for (PendingProgram& j : jobs) s_programs[j.key] = finishProgram(j);
    return uint32_t(jobs.size());
}

// While a stage loads (before its first frame): its known programs, compiled
// together, then others while time allows.
void shaderPrepareStage(int stage) {
    if (s_warmState < 0) warmSetup();
    s_lastProgram = nullptr;
    if (isShaderCacheEnabled() && glProgramBinary)
        if (size_t got = loadCachedPrograms())
            logmsg("shader: took %zu programs the background compile made", got);
    if (s_warmState != 1) return;
    const auto start = std::chrono::steady_clock::now();
    auto elapsed = [&] { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count(); };
    std::vector<const ProgramKey*> batch;
    auto it = s_stageKeys.find(stage & 0xFF);
    if (it != s_stageKeys.end())
        for (size_t i : it->second)
            if (!s_programs.count(s_warmKeys[i])) batch.push_back(&s_warmKeys[i]);
    const uint32_t own = warmCompile(batch);
    uint32_t others = 0;
    const double budget = s_parallel ? 1000 : 250;
    const size_t perBatch = s_parallel ? 16 : 1;  // about half a second each on a Tegra X1
    while (s_warmNext < s_warmKeys.size() && elapsed() < budget) {
        batch.clear();
        while (batch.size() < perBatch && s_warmNext < s_warmKeys.size()) {
            const ProgramKey& k = s_warmKeys[s_warmNext++];
            if (!s_programs.count(k)) batch.push_back(&k);
        }
        others += warmCompile(batch);
    }
    if (own || others)
        logmsg("shader warm-up: compiled %u programs for stage %d and %u others in %.1f ms%s", own, stage & 0xFF,
               others, elapsed(), s_warmNext < s_warmKeys.size() ? "" : " (all known programs made)");
}

// ------------------------------------------------------------------ background compile
// GXPC_PrecompileShaders, for the launcher (gx_precompile.cpp): with a GL
// context of its own, before the game starts, compiles every known program
// the cache file does not hold yet and adds it to the file. Nothing of the
// renderer's state is used, so it can run beside a game in another process.

// the program keys the cache file holds
static std::unordered_map<ProgramKey, bool, ProgramKeyHash> cachedKeys() {
    std::unordered_map<ProgramKey, bool, ProgramKeyHash> keys;
    FILE* f = fopen(getShaderCachePath().c_str(), "rb+");
    if (!f) return keys;
    lockCache(f, LOCK_EX);
    ShaderCacheFileHeader hdr;
    if (fread(&hdr, sizeof hdr, 1, f) != 1 || hdr.magic != 0x534D5343 || hdr.version != kShaderCacheVersion) {
        if (ftruncate(fileno(f), 0) != 0) {}  // an older layout: start again, as shaderInit does
    } else {
        ShaderCacheEntryHeader eh;
        while (fread(&eh, sizeof eh, 1, f) == 1) {
            if (eh.binaryLength == 0 || eh.binaryLength > (16u << 20)) break;
            if (fseek(f, long(eh.binaryLength), SEEK_CUR) != 0) break;
            keys[eh.key] = true;
        }
    }
    fclose(f);
    return keys;
}

}  // namespace gx

// progress(done, total, user) after each batch, and once with done == 0
// before the first; a nonzero return stops. Returns the programs made, or -1
// without a usable context or key list.
extern "C" int GXPC_PrecompileShaders(int (*progress)(int done, int total, void* user), void* user) {
    using namespace gx;
    if (!isShaderCacheEnabled() || !glProgramBinary || !glGetProgramBinary) return -1;
    std::vector<ProgramKey> known;
    std::unordered_map<int, std::vector<size_t>> stages;
    if (!decodeWarmKeys(known, stages)) return -1;
    auto cached = cachedKeys();
    std::vector<const ProgramKey*> todo;
    for (const ProgramKey& k : known)
        if (!cached.count(k)) todo.push_back(&k);
    const int total = int(todo.size());
    logmsg("shader precompile: %zu known programs, %d to compile", known.size(), total);
    if (progress && progress(0, total, user)) return 0;
    const bool parallel = enableParallelCompile();
    const size_t perBatch = parallel ? 16 : 1;
    const auto start = std::chrono::steady_clock::now();
    int done = 0;
    for (size_t i = 0; i < todo.size();) {
        std::vector<PendingProgram> jobs;
        for (; i < todo.size() && jobs.size() < perBatch; i++) {
            jobs.emplace_back();
            jobs.back().key = *todo[i];
            issueProgram(jobs.back());
        }
        for (PendingProgram& j : jobs) {
            GLint ok = 0;
            glGetProgramiv(j.p, GL_LINK_STATUS, &ok);
            if (ok) saveProgramToCache(j.key, j.p);
            glDeleteShader(j.v);
            glDeleteShader(j.f);
            glDeleteProgram(j.p);
        }
        done += int(jobs.size());
        if (progress && progress(done, total, user)) break;
    }
    logmsg("shader precompile: compiled %d programs in %.1f s", done,
           std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count());
    return done;
}

namespace gx {

void shaderShutdown() {
    for (auto& kv : s_programs) glDeleteProgram(kv.second.prog);
    s_programs.clear();
    s_lastProgram = nullptr;
    s_cacheEnd = 0;
}

}  // namespace gx
