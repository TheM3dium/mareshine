// Headless self-test for sms_gx: creates an OpenGL ES 3.1 context through
// EGL (surfaceless Mesa, falling back to the default display), drives the GX
// and GD APIs the way game code does and checks EFB/XFB pixels.
//
//   gx_copy_feedback_test [--headless|--window] [out.ppm]
//     --window shows the final frame for two seconds; out.ppm receives the EFB
//
// GXVert.h is replaced by sms_gx/gxvert_pc.h (included first, it claims the
// header guard), which is the TARGET_PC configuration the port uses.
#include <sms_gx/gxvert_pc.h>
#include "gd_host_prefix.h"  // __cntlzw for GDLight.h
#include <dolphin/gx.h>
#include <dolphin/gd.h>
#include <dolphin/mtx.h>

#include "sms_gx/gx_pc.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vector>

namespace gx {
void decodeTexture(const uint8_t* src, uint32_t fmt, uint32_t w, uint32_t h, const uint8_t* tlut, uint32_t tlutFmt,
                   uint8_t* out);
}

// The OS layer normally provides these.
void OSPanic(const char* file, int line, const char* msg, ...) {
    fprintf(stderr, "OSPanic %s:%d %s\n", file, line, msg);
    abort();
}
void DCFlushRange(void* p, u32 n) { GXPC_InvalidateRange(p, uint32_t(n)); }

static int s_fail = 0, s_pass = 0;

static bool near(int a, int b, int tol) { return a >= b - tol && a <= b + tol; }

static void expectPixel(const char* what, int x, int y, int r, int g, int b, int tol = 2) {
    u32 c = 0;
    GXPeekARGB(u16(x), u16(y), &c);
    int pr = int((c >> 16) & 255), pg = int((c >> 8) & 255), pb = int(c & 255);
    bool ok = near(pr, r, tol) && near(pg, g, tol) && near(pb, b, tol);
    printf("%s %-44s (%3d,%3d) got %3d %3d %3d want %3d %3d %3d\n", ok ? "PASS" : "FAIL", what, x, y, pr, pg, pb, r, g, b);
    ok ? s_pass++ : s_fail++;
}

static void expect(const char* what, bool ok) {
    printf("%s %s\n", ok ? "PASS" : "FAIL", what);
    ok ? s_pass++ : s_fail++;
}

// ------------------------------------------------------------------ helpers
static void setOrtho() {
    Mtx44 m;
    memset(m, 0, sizeof(m));
    // left 0, right 640, top 0, bottom 480, near 0, far 10
    m[0][0] = 2.0f / 640;
    m[0][3] = -1.0f;
    m[1][1] = 2.0f / -480;
    m[1][3] = 1.0f;
    m[2][2] = -1.0f / 10;
    m[2][3] = -1.0f;
    m[3][3] = 1.0f;
    GXSetProjection(m, GX_ORTHOGRAPHIC);
    Mtx id = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}};
    GXLoadPosMtxImm(id, GX_PNMTX0);
    GXSetCurrentMtx(GX_PNMTX0);
}

static void colorQuad(float x0, float y0, float x1, float y1, u8 r, u8 g, u8 b, u8 a) {
    GXBegin(GX_QUADS, GX_VTXFMT0, 4);
    GXPosition3f32(x0, y0, -1); GXColor4u8(r, g, b, a);
    GXPosition3f32(x1, y0, -1); GXColor4u8(r, g, b, a);
    GXPosition3f32(x1, y1, -1); GXColor4u8(r, g, b, a);
    GXPosition3f32(x0, y1, -1); GXColor4u8(r, g, b, a);
    GXEnd();
}

static void colorSetup() {
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxAttrFmt(GX_VTXFMT0, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
    GXSetVtxAttrFmt(GX_VTXFMT0, GX_VA_CLR0, GX_CLR_RGBA, GX_RGBA8, 0);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, GX_LIGHT_NULL, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTexGens(0);
    GXSetNumTevStages(1);
    GXSetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR0A0);
    GXSetTevOp(GX_TEVSTAGE0, GX_PASSCLR);
    GXSetBlendMode(GX_BM_NONE, GX_BL_ONE, GX_BL_ZERO, GX_LO_CLEAR);
    GXSetAlphaCompare(GX_ALWAYS, 0, GX_AOP_AND, GX_ALWAYS, 0);
    GXSetZMode(GX_FALSE, GX_ALWAYS, GX_FALSE);
    GXSetCullMode(GX_CULL_NONE);
}

// Encodes an RGBA8 image (w, h multiples of 4) into GX RGBA8 tiles.
static std::vector<u8> encodeRGBA8(const u8* rgba, int w, int h) {
    std::vector<u8> out(size_t(w) * h * 4);
    size_t o = 0;
    for (int ty = 0; ty < h; ty += 4)
        for (int tx = 0; tx < w; tx += 4) {
            for (int i = 0; i < 16; i++) {
                const u8* p = rgba + ((ty + i / 4) * w + tx + i % 4) * 4;
                out[o + 2 * i] = p[3];
                out[o + 2 * i + 1] = p[0];
                out[o + 32 + 2 * i] = p[1];
                out[o + 32 + 2 * i + 1] = p[2];
            }
            o += 64;
        }
    return out;
}

static void texQuad(float x0, float y0, float x1, float y1, u8 r, u8 g, u8 b, u8 a) {
    GXBegin(GX_QUADS, GX_VTXFMT1, 4);
    GXPosition3f32(x0, y0, -1); GXColor4u8(r, g, b, a); GXTexCoord2f32(0, 0);
    GXPosition3f32(x1, y0, -1); GXColor4u8(r, g, b, a); GXTexCoord2f32(1, 0);
    GXPosition3f32(x1, y1, -1); GXColor4u8(r, g, b, a); GXTexCoord2f32(1, 1);
    GXPosition3f32(x0, y1, -1); GXColor4u8(r, g, b, a); GXTexCoord2f32(0, 1);
    GXEnd();
}

static void texSetup() {
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    GXSetVtxAttrFmt(GX_VTXFMT1, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
    GXSetVtxAttrFmt(GX_VTXFMT1, GX_VA_CLR0, GX_CLR_RGBA, GX_RGBA8, 0);
    GXSetVtxAttrFmt(GX_VTXFMT1, GX_VA_TEX0, GX_TEX_ST, GX_F32, 0);
    GXSetNumTexGens(1);
    GXSetTexCoordGen(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY);
}

#include <EGL/egl.h>
#include <EGL/eglext.h>
static bool createMetricTestContext() {
    auto platform = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    EGLDisplay d = platform ? platform(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr) : eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(d, nullptr, nullptr) || !eglBindAPI(EGL_OPENGL_ES_API)) return false;
    const EGLint attrs[] = {EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,0x40,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(d, attrs, &cfg, 1, &n) || !n) return false;
    const EGLint ctx[] = {EGL_CONTEXT_MAJOR_VERSION,3,EGL_CONTEXT_MINOR_VERSION,1,EGL_NONE};
    EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, ctx);
    const EGLint pb[] = {EGL_WIDTH,16,EGL_HEIGHT,16,EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(d,cfg,pb);
    return c != EGL_NO_CONTEXT && eglMakeCurrent(d,surface,surface,c);
}
extern "C" void GXPC_FinishPixMetric(void);
static void* metricGetProc(const char* n) { return reinterpret_cast<void*>(eglGetProcAddress(n)); }

int main() {
    GXPC_SetHeadless(1); GXPC_SetAutoPresent(0); GXPC_SetWidescreen(getenv("SMS_TEST_WIDE") ? float(atof(getenv("SMS_TEST_WIDE"))) : 4.0f/3.0f);
    if (!createMetricTestContext() || !GXPC_Init(metricGetProc,1)) return 77;
    static u8 arena[8<<20] __attribute__((aligned(32))); static u8 fifo[256*1024];
    GXPC_SetMemoryWindow(arena,sizeof arena); GXInit(fifo,sizeof fifo);
    GXSetCopyClear((GXColor){0,0,0,0},0xFFFFFF);
    GXSetDispCopySrc(0,0,640,528); GXSetDispCopyDst(640,528);
    static u8 xfb[640*528*2]; GXCopyDisp(xfb,GX_TRUE);
    setOrtho(); colorSetup();
    Mtx44 m={}; m[0][0]=2.0f/512; m[0][3]=-1; m[1][1]=-2.0f/512; m[1][3]=1; m[2][2]=-.1f; m[2][3]=-1; m[3][3]=1;
    GXSetProjection(m,GX_ORTHOGRAPHIC); GXSetViewport(0,0,512,512,0,1); GXSetScissor(0,0,512,512);
    colorQuad(180,210,200,230,255,0,0,255);
    u8* image=arena+(5<<20); memset(image,0,512*512);
    GXSetTexCopySrc(0,0,512,512); GXSetTexCopyDst(512,512,GX_CTF_R8,GX_FALSE);
    GXTexObj obj; GXInitTexObj(&obj,image,512,512,GX_TF_I8,GX_CLAMP,GX_CLAMP,GX_FALSE);
    GXInitTexObjLOD(&obj,GX_NEAR,GX_NEAR,0,0,0,GX_FALSE,GX_FALSE,GX_ANISO_1);
    auto centroid=[&] { double sum=0,wx=0; for(int y=0;y<512;y++) for(int x=0;x<512;x++) {
        unsigned i=((y/4)*64+x/8)*32+(y%4)*8+x%8; sum+=image[i]; wx+=double(image[i])*x;
    } return sum>0?wx/sum:-1.0; };
    GXCopyTex(image,GX_TRUE); const double first=centroid();
    std::vector<u8> firstBytes(image,image+512*512);
    for(int n=0;n<150;n++) {
        GXLoadTexObj(&obj,GX_TEXMAP0); texSetup(); GXSetTevOrder(GX_TEVSTAGE0,GX_TEXCOORD0,GX_TEXMAP0,GX_COLOR0A0); GXSetTevOp(GX_TEVSTAGE0,GX_REPLACE);
        texQuad(0,0,512,512,255,255,255,255); GXCopyTex(image,GX_TRUE);
    }
    const double last=centroid(); printf("copy feedback centroid first=%.6f last=%.6f delta=%.6f\n",first,last,last-first);
    expect("repeated wide R8 copy preserves position",first>0 && last>0 && fabs(last-first)<.75);
    expect("repeated wide R8 copy preserves every mask byte",memcmp(image,firstBytes.data(),firstBytes.size())==0);
    GXPC_Shutdown(); return s_fail?1:0;
}
