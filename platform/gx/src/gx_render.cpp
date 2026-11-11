// OpenGL 3.3 backend: EFB as an FBO, batched primitive submission, GL state
// derived from the BP/XF registers, EFB copies (display and texture) and XFB
// presentation.
#include "gx_internal.h"
#include "gl_funcs.h"
#include "gx_glcache.h"
#include "gx_android.h"
#include "gx_glthread.h"
#include "gx_screen_extent.h"
#include "gx_render_parts.h"
#include "sms_gx/gx_pc.h"

#include <math.h>
#include <algorithm>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <atomic>
#include <unordered_map>

namespace gx {

uint32_t g_statTexUploads, g_statShaderCompiles;
uint64_t g_statTexHashBytes, g_statTexInvalidates;
namespace gl {
extern uint64_t g_statGlCalls;
void logTopCalls(uint32_t frames);
}
extern double g_decodeSeconds;
static GXPCStats s_stats;
void shaderShutdown();
void invalidateOverlay();

// SMS_GX_STATS=n: every n display frames, log draws, uploads and the wall time
// spent inside sms_gx (flushes, texture decode, copies) against the frame time.
static double s_gxSeconds = 0, s_texSeconds = 0, s_copySeconds = 0, s_peekSeconds = 0;
double g_flushSeconds = 0;  // in flushBatch (read by the vertex loader timer)
static double s_waitSeconds = 0;  // blocked on the GPU
double g_presentSeconds = 0, g_swapSeconds = 0;  // GXPC_Present (gx_platform.cpp)
// GXPC_Present's waits for the GL thread, and the part of them that was the
// frame's queued work rather than presenting
double g_presentWaited = 0, g_presentDrain = 0;
static double (*s_idleClock)(void) = nullptr;
static double nowSeconds() { return monoSeconds(); }
// The breakdown timers (textures, draws, copies, peeks, GPU waits, the vertex
// loader) run with SMS_GX_STATS or SMS_GX_HITCH_MS or while the overlay is
// open (GXPC_SetDetailedTimers). Otherwise only the overall sms_gx time is
// measured; the others cost a clock read per texture bind and per primitive.
static bool statsEnv() {
    const char* e = getenv("SMS_GX_STATS");
    return (e && atoi(e) > 0) || getenv("SMS_GX_HITCH_MS");
}
bool g_gxStats = statsEnv();
struct GxTimer {
    double* acc;
    double t0;
    explicit GxTimer(double* a = &s_gxSeconds) : acc(a == &s_gxSeconds || g_gxStats ? a : nullptr),
                                                 t0(acc ? nowSeconds() : 0) {}
    ~GxTimer() {
        if (acc) *acc += nowSeconds() - t0;
    }
};

enum { EFB_W = 640, EFB_H = 528 };

// The internal resolution: the EFB is s_scale times the GameCube's. FSR 1's
// modes make it fractional; EFB rectangles map through scaled() edge by edge,
// so pieces that meet on the GameCube still meet.
static float s_scale = 1;
static int scaled(int v) { return int(lroundf(float(v) * s_scale)); }
float efbScale() { return s_scale; }

// Widescreen (GXPC_SetWidescreen): the EFB is s_efbW = 640 * s_wide wide
// while the game keeps working in 640-wide coordinates, which each draw,
// copy and peek maps into it (XMap: x' = a * x + b, clip x scaled by clip):
//   stretched (a = s_wide): the game camera, which the widescreen patch
//     widens itself, 2D that spans the whole width (fades, screen copies
//     drawn back), full-width copies and clears, peeks;
//   centred (b = s_ox): 2D and 3D that cover part of the screen (the HUD).
// A full-screen perspective draw whose projection the game did not widen
// (title, file select, cutscene overlays) is stretched with its clip x
// scaled by 1 / s_wide: its field of view widens, and what the game placed
// at x lands on x + s_ox, beside the centred HUD.
static float s_wide = 1.0f;
static float s_wideCamAspect;
static int s_efbW = EFB_W, s_ox = 0;
struct XMap {
    float a = 1, b = 0, clip = 1;
};
static XMap s_xmap;
static bool s_stretch2D = false;  // GXPC_SetStretch2D: the game's faders
static bool s_wide2D = false;     // GXPC_SetWide2D: 2D the game laid out past the 4:3 edges (the map)
// SMS_WIDESCREEN_HUD=edges: while the game draws its gameplay HUD
// (GXPC_SetHud), a piece in the left third of the 4:3 frame goes to the left
// edge of the wide one and one in the right third to the right edge; the
// middle stays centred. A piece is the outermost J2D pane being drawn that
// is narrower than three quarters of the screen (wider ones are the HUD's
// screen-sized containers), with everything in it (GXPC_HudPaneBegin), or
// else a draw on its own.
static bool s_hudEdges = false, s_hud = false;
struct HudPane {
    float x1, x2;
};
static std::vector<HudPane> s_hudPanes;  // the J2D panes being drawn, outermost first
static float s_hudAnchor = -1.0f;        // the piece's centre (game x), -1 for none

static float hudOffset(float at) {
    if (at < float(EFB_W) / 3) return 0.0f;
    if (at > float(EFB_W) * 2 / 3) return float(2 * s_ox);
    return float(s_ox);
}
// the offset of a centred draw whose own centre is at game x `at`
static float centredOffset(float at) {
    if (!s_hud) return float(s_ox);
    return hudOffset(s_hudAnchor >= 0.0f ? s_hudAnchor : at);
}
// the game camera's aspect (TMarDirector: video width 660 * 0.91346 / 448)
static const float kCamAspect = 660.0f * 0.91346145f / 448.0f;
static GLuint s_efbFbo, s_efbColor, s_efbDepth;
// SMS_MSAA=n: the game draws into s_efbFbo, built from multisampled
// renderbuffers; every read of the EFB (copies, peeks) first resolves it into
// s_efbResolveFbo, which holds s_efbColor and s_efbDepth.
static int s_msaa = 0;
static GLuint s_efbResolveFbo, s_efbMsColor, s_efbMsDepth;
// nativeEfbBegin's EFB of the GameCube's own size, and the scaled one it stands in for
static GLuint s_nativeFbo, s_nativeColor, s_nativeDepth;
static struct {
    bool on, ending;  // ending: nativeEfbEnd has run, the copy back is still to come
    GLuint fbo, color, depth;
    int msaa;
    float scale;
} s_native;
static void nativeEfbRestore();
static GLuint s_vao;
static GLuint s_copyProg, s_copyVao;
static GLint s_copyUMode, s_copyURect, s_copyUAlphaOne;
static GLuint s_tmpFbo;
static GXPCStats s_lastFrameStats;
static bool s_ready = false;
static bool s_xfDirty = true;

// Append-only stream of per-draw data (vertices, indices, the XF block) in
// one GL buffer, a batch's pieces written through one unsynchronized map. The
// buffer is split into four segments; a fence marks the end of each one's use,
// and a segment is only written again once its fence has passed, so a write
// never touches data a queued draw still reads. Replaces a glBufferData
// reallocation (or a glBufferSubData into a buffer every queued draw uses) per
// batch, and a map per piece (map/unmap were half of all GL calls).
struct StreamPiece {
    const void* data;
    size_t bytes, align;
    size_t at;  // out: offset written at, a multiple of align
};
struct StreamBuffer {
    enum { kSegments = 4 };
    GLuint buf = 0;
    size_t size = 0, pos = 0;
    GLsync fence[kSegments] = {};

    // With ARB_buffer_storage (GL 4.4; not macOS) the buffer stays mapped:
    // writes go straight into it, without a map/unmap pair per batch.
    uint8_t* persistent = nullptr;

    // `bytes` of ring, then `extra` bytes the ring does not use (the
    // display-list arena), in the same buffer so draws from both share VAOs.
    void init(size_t bytes, size_t extra) {
        size = bytes;
        const size_t total = bytes + extra;
        glGenBuffers(1, &buf);
        glcBindArrayBuffer(buf);
        const char* e = getenv("SMS_GX_PERSISTENT_MAP");
        if (glBufferStorage && !(e && e[0] == '0')) {
            const GLbitfield flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            glBufferStorage(GL_ARRAY_BUFFER, GLsizeiptr(total), nullptr, flags);
            persistent = static_cast<uint8_t*>(glMapBufferRange(GL_ARRAY_BUFFER, 0, GLsizeiptr(total), flags));
            if (persistent) return;
            glDeleteBuffers(1, &buf);  // immutable storage: start over with a plain buffer
            glGenBuffers(1, &buf);
            glcBindArrayBuffer(buf);
        }
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(total), nullptr, GL_STREAM_DRAW);
    }
    // Writes bytes the GPU does not read now (nothing queued draws from them).
    void writeAt(size_t at, const void* data, size_t n) {
        if (!n) return;
        if (persistent) {
            memcpy(persistent + at, data, n);
            return;
        }
        glcBindArrayBuffer(buf);
#ifdef __ANDROID__
        // Android emulators synchronously acknowledge map/unmap calls. The
        // stream's fences already protect these bytes, so submit the known
        // CPU data without mapping a live GPU buffer.
        glBufferSubData(GL_ARRAY_BUFFER, GLintptr(at), GLsizeiptr(n), data);
        return;
#endif
        glt::postData(data, n, [at, n](const uint8_t* d) {
            void* dst = glMapBufferRange(GL_ARRAY_BUFFER, GLintptr(at), GLsizeiptr(n),
                                         GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT | GL_MAP_INVALIDATE_RANGE_BIT);
            if (dst) {
                memcpy(dst, d, n);
                glUnmapBuffer(GL_ARRAY_BUFFER);
            }
        });
    }
    size_t unfenced = 0;  // first segment written since the last fence

    size_t segOf(size_t p) const { return p * kSegments / size; }
    // Every draw reading what was written so far has been issued: fence the
    // segments from `unfenced` through `last`.
    void fenceThrough(size_t last) {
        for (size_t sg = unfenced; sg <= last && sg < kSegments; sg++)
            if (!fence[sg]) fence[sg] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }
    void waitSegment(size_t seg) {
        if (!fence[seg]) return;
        GxTimer tw(&s_waitSeconds);
        glClientWaitSync(fence[seg], GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull);
        glDeleteSync(fence[seg]);
        fence[seg] = 0;
    }
    static size_t worstCase(const StreamPiece* pc, int n) {
        size_t w = 0;
        for (int i = 0; i < n; i++) w += pc[i].bytes + pc[i].align;
        return w;
    }
    // appendAll of pieces this large starts over at the buffer's beginning
    bool wouldWrap(size_t worst) const { return pos + worst > size; }
    // Writes the pieces one after another (each at a multiple of its
    // alignment) and sets their offsets. Called before the draw that reads
    // them, after the previous one.
    void appendAll(StreamPiece* pc, int n) {
        size_t start = wouldWrap(worstCase(pc, n)) ? 0 : pos;
        size_t end = start;
        for (int i = 0; i < n; i++) {
            pc[i].at = (end + pc[i].align - 1) / pc[i].align * pc[i].align;
            end = pc[i].at + pc[i].bytes;
        }
        size_t first = pc[0].at;
        size_t cur = segOf(pos ? pos - 1 : 0);
        if (start == 0 && pos != 0) {  // wrap
            fenceThrough(kSegments - 1);
            unfenced = 0;
        } else if (segOf(first) != cur) {
            fenceThrough(segOf(first) - 1);
            unfenced = segOf(first);
        }
        for (size_t sg = segOf(first), last = segOf(end - 1); sg <= last; sg++) waitSegment(sg);
        pos = end;
        if (persistent) {
            for (int i = 0; i < n; i++) memcpy(persistent + pc[i].at, pc[i].data, pc[i].bytes);
            return;
        }
        glcBindArrayBuffer(buf);
#ifdef __ANDROID__
        for (int i = 0; i < n; ++i)
            glBufferSubData(GL_ARRAY_BUFFER, GLintptr(pc[i].at), GLsizeiptr(pc[i].bytes), pc[i].data);
        return;
#endif
        if (glt::active()) {  // the GL thread maps and writes a copy of the pieces
            static std::vector<uint8_t> block;
            block.resize(end - first);
            for (int i = 0; i < n; i++) memcpy(block.data() + (pc[i].at - first), pc[i].data, pc[i].bytes);
            const size_t bytes = block.size();
            glt::postData(block.data(), bytes, [first, bytes](const uint8_t* d) {
                void* dst = glMapBufferRange(GL_ARRAY_BUFFER, GLintptr(first), GLsizeiptr(bytes),
                                             GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT | GL_MAP_INVALIDATE_RANGE_BIT);
                if (dst) {
                    memcpy(dst, d, bytes);
                    glUnmapBuffer(GL_ARRAY_BUFFER);
                }
            });
            return;
        }
        uint8_t* dst = static_cast<uint8_t*>(
            glMapBufferRange(GL_ARRAY_BUFFER, GLintptr(first), GLsizeiptr(end - first),
                             GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT | GL_MAP_INVALIDATE_RANGE_BIT));
        if (dst) {
            for (int i = 0; i < n; i++) memcpy(dst + (pc[i].at - first), pc[i].data, pc[i].bytes);
            glUnmapBuffer(GL_ARRAY_BUFFER);
        }
    }
};
static StreamBuffer s_stream;
static const size_t kArenaBytes = size_t(64) << 20;  // the display-list arena after the ring
static GLint s_uboAlign = 256;

// One VAO per packed vertex format: attribute pointers into the vertex stream
// for the attributes the format holds; the others read the constant values
// set in rendererInit (matrix indices: set per batch in flushBatch).
static std::unordered_map<uint32_t, GLuint> s_fmtVaos;

static GLuint makeVao(uint32_t fmt, GLuint buf) {
    GLuint vao;
    const VtxFmtLayout& l = vtxFmtLayout(fmt);
    glGenVertexArrays(1, &vao);
    glcBindVertexArray(vao);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buf);
    glcBindArrayBuffer(buf);
    const GLsizei st = l.stride;
    auto off = [](size_t o) { return reinterpret_cast<const void*>(o); };
    auto attr = [&](GLuint i, bool on, GLint n, GLenum type, GLboolean norm, size_t o) {
        if (!on) return;
        glVertexAttribPointer(i, n, type, norm, st, off(o));
        glEnableVertexAttribArray(i);
    };
    attr(0, true, 3, GL_FLOAT, GL_FALSE, 0);
    attr(1, fmt & VF_NRM, 3, GL_FLOAT, GL_FALSE, l.nrm);
    attr(2, (fmt & VF_NRM) && (fmt & VF_NBT), 3, GL_FLOAT, GL_FALSE, l.nrm + 12);
    attr(3, (fmt & VF_NRM) && (fmt & VF_NBT), 3, GL_FLOAT, GL_FALSE, l.nrm + 24);
    attr(4, fmt & VF_CLR0, 4, GL_UNSIGNED_BYTE, GL_TRUE, l.clr[0]);
    attr(5, fmt & (VF_CLR0 << 1), 4, GL_UNSIGNED_BYTE, GL_TRUE, l.clr[1]);
    for (int t = 0; t < 8; t++) attr(GLuint(6 + t), fmt & (VF_TEX0 << t), 2, GL_FLOAT, GL_FALSE, l.tex[t]);
    if (fmt & VF_MTX) {
        glVertexAttribIPointer(14, 3, GL_UNSIGNED_INT, st, off(l.mtx));
        glEnableVertexAttribArray(14);
    }
    return vao;
}

static GLuint vaoForFormat(uint32_t fmt) {
    GLuint& vao = s_fmtVaos[fmt];
    if (!vao) vao = makeVao(fmt, s_stream.buf);
    return vao;
}

// The batch: packed vertices and their indices. Plain growable arrays rather
// than vectors, so growing does not zero-fill and an index is one store.
template <typename T> struct GrowBuf {
    T* data = nullptr;
    size_t size = 0, cap = 0;
    T* reserve(size_t more) {  // room for `more` past size; returns the end
        if (size + more > cap) {
            size_t c = cap ? cap : 4096;
            while (c < size + more) c *= 2;
            T* d = static_cast<T*>(realloc(data, c * sizeof(T)));
            if (!d) fatal("out of memory growing a draw batch");
            data = d;
            cap = c;
        }
        return data + size;
    }
};
static GrowBuf<uint8_t> s_bdata;  // the batch's packed vertices
static uint32_t s_bcount = 0, s_bfmt = 0, s_bstride = 12;
static GrowBuf<uint32_t> s_bidx;
static PrimClass s_bclass = PRIM_TRIS;
// A batch that draws display-list runs from the arena is a sequence of
// segments, drawn in order: index ranges of s_bidx (over s_bdata's vertices)
// and arena runs. A batch without arena runs keeps s_segs empty.
struct BatchSeg {
    bool arena;
    uint32_t first, count;  // stream: index range in s_bidx; arena: count
    size_t iOff;            // arena: byte offset of the indices
    GLint baseVertex;       // arena
    const uint8_t* verts;   // arena: CPU copy of the vertices
    uint32_t nverts;        // arena
};
static std::vector<BatchSeg> s_segs;
static uint32_t s_barenaVerts = 0, s_barenaIdx = 0;  // arena vertices and indices in the batch

static inline bool batchHasVertices() { return s_bcount || s_barenaVerts; }
static inline void noteStreamIndices(size_t first, size_t count) {
    if (s_segs.empty() || !count) return;
    BatchSeg& b = s_segs.back();
    if (!b.arena) b.count += uint32_t(count);
    else s_segs.push_back(BatchSeg{false, uint32_t(first), uint32_t(count), 0, 0, nullptr, 0});
}
bool batchUsesArena() { return s_barenaIdx != 0; }

struct Xfb {
    GLuint tex;
    int w, h;
};
static std::unordered_map<const void*, Xfb> s_xfbs;
static const void* s_lastXfb = nullptr;

void markXfMemDirty() { s_xfDirty = true; }
FILE* traceFile();
void traceFrameAdvance();
void traceDraw(int prim, uint32_t nverts, uint32_t nidx, const HostVertex* v, int progId);
void traceCopy(bool disp, int x, int y, int w, int h, const void* dest, uint32_t ctrl);
void traceProbe();
void (*g_displayCopyHook)(const void* xfb) = nullptr;
bool rendererReady() { return s_ready; }
GLuint quadVao() { return s_copyVao; }

GLuint compileProgram(const char* vs, const char* fs) {
    auto sh = [](GLenum t, const char* src) {
        GLuint s = glCreateShader(t);
        std::string adapted = android::shaderSource(src, t);
        src = adapted.c_str();
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[2048];
            glGetShaderInfoLog(s, sizeof log, nullptr, log);
            logmsg("internal shader failed: %s", log);
        }
        return s;
    };
    GLuint v = sh(GL_VERTEX_SHADER, vs), f = sh(GL_FRAGMENT_SHADER, fs);
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glBindFragDataLocation(p, 0, "o_color");
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    return p;
}

// EFB -> texture conversion.  The output is what sampling the GX texture of
// the copy's format would return, so later draws can use it directly.
static const char* kCopyVs = R"(#version 330 core
uniform vec4 u_rect; // x, y, w, h in EFB texture coordinates (0..1)
out vec2 v_uv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  v_uv = u_rect.xy + p * u_rect.zw;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";
static const char* kCopyFs = R"(#version 330 core
uniform sampler2D u_color;
uniform sampler2D u_depth;
uniform int u_mode;     // bit 5: depth copy, bit 4: intensity (YUV) conversion, bits 0-3: format
uniform int u_alphaOne; // EFB pixel format has no alpha
in vec2 v_uv;
out vec4 o_color;
float q(float v, float bits) { float m = exp2(bits) - 1.0; return floor(v * m + 0.5) / m; }
void main() {
  int f = u_mode & 15;
  if ((u_mode & 32) != 0) {
    uint z = uint(clamp(texture(u_depth, v_uv).r, 0.0, 1.0) * 16777215.0);
    float hi = float((z >> 16) & 255u) / 255.0, mid = float((z >> 8) & 255u) / 255.0, lo = float(z & 255u) / 255.0;
    if (f == 1) o_color = vec4(hi);                        // Z8
    else if (f == 11 || f == 3) o_color = vec4(mid, mid, mid, hi); // Z16 (IA8 layout)
    else if (f == 6) o_color = vec4(hi, mid, lo, 1.0);     // Z24X8
    else if (f == 0) o_color = vec4(q(hi, 4.0));           // Z4
    else if (f == 9) o_color = vec4(mid);                  // Z8M
    else if (f == 10) o_color = vec4(lo);                  // Z8L
    else if (f == 12) o_color = vec4(lo, lo, lo, mid);     // Z16L
    else o_color = vec4(hi);
    return;
  }
  vec4 c = texture(u_color, v_uv);
  if (u_alphaOne != 0) c.a = 1.0;
  if ((u_mode & 16) != 0) {
    float y = clamp(0.257 * c.r + 0.504 * c.g + 0.098 * c.b + 16.0 / 255.0, 0.0, 1.0);
    if (f == 0) o_color = vec4(q(y, 4.0));
    else if (f == 1) o_color = vec4(y);
    else if (f == 2) o_color = vec4(vec3(q(y, 4.0)), q(c.a, 4.0));
    else if (f == 3) o_color = vec4(vec3(y), c.a);
    else o_color = c;
    return;
  }
  if (f == 0) o_color = vec4(q(c.r, 4.0));                                // R4
  else if (f == 2) o_color = vec4(vec3(q(c.r, 4.0)), q(c.a, 4.0));        // RA4
  else if (f == 3) o_color = vec4(c.rrr, c.a);                            // RA8
  else if (f == 4) o_color = vec4(q(c.r, 5.0), q(c.g, 6.0), q(c.b, 5.0), 1.0); // RGB565
  else if (f == 5) o_color = c.a > 0.99 ? vec4(q(c.r, 5.0), q(c.g, 5.0), q(c.b, 5.0), 1.0)
                                        : vec4(q(c.r, 4.0), q(c.g, 4.0), q(c.b, 4.0), q(c.a, 3.0)); // RGB5A3
  else if (f == 7) o_color = vec4(c.a);                                   // A8
  else if (f == 8) o_color = vec4(c.r);                                   // R8
  else if (f == 9) o_color = vec4(c.g);                                   // G8
  else if (f == 10) o_color = vec4(c.b);                                  // B8
  else if (f == 11) o_color = vec4(c.ggg, c.r);                           // RG8 read as IA8
  else if (f == 12) o_color = vec4(c.bbb, c.g);                           // GB8 read as IA8
  else o_color = c;                                                       // RGBA8 / YUVA8
}
)";

// EFB copy write-back, encoded on the GPU: each output pixel holds 4 bytes of
// the copy's GX tile layout (byte k in pixel k / 4 of rows 256 pixels wide),
// so what is read back is the bytes to store. A texel is the copy's
// (x * ow / tw, y * oh / th) one, as the game sees tw x th texels of a copy
// that may be larger, and tiles past the edges hold zeros: the encoding
// encodeTexture does on the CPU (SMS_GX_COPY_VERIFY=1 compares the two).
static const char* kEncodeFs = R"(#version 330 core
uniform sampler2D u_src;
uniform ivec4 u_size;  // tw, th (the texels the game sees), ow, oh (the copy texture)
uniform int u_layout;  // copyLayout: 0 I4, 1 I8, 2 IA4, 3 IA8, 4 RGB565, 5 RGB5A3, 6 RGBA8
uniform int u_bytes;
out vec4 o_color;
uvec4 texel(int x, int y) {
  if (x >= u_size.x || y >= u_size.y) return uvec4(0u);
  ivec2 s = ivec2(x * u_size.z / u_size.x, y * u_size.w / u_size.y);
  return uvec4(texelFetch(u_src, s, 0) * 255.0 + 0.5);
}
uint enc4(uint v) { return (v * 15u + 127u) / 255u; }
uint byteAt(int b) {
  if (b >= u_bytes) return 0u;
  int L = u_layout;
  int tileW = L <= 2 ? 8 : 4, tileH = L == 0 ? 8 : 4, tileBytes = L == 6 ? 64 : 32;
  int cols = (u_size.x + tileW - 1) / tileW;
  int tile = b / tileBytes, off = b - tile * tileBytes;
  int tx = (tile % cols) * tileW, ty = (tile / cols) * tileH;
  if (L == 0) {
    int i = off * 2;
    return enc4(texel(tx + i % 8, ty + i / 8).r) << 4 | enc4(texel(tx + (i + 1) % 8, ty + (i + 1) / 8).r);
  }
  if (L == 1) return texel(tx + off % 8, ty + off / 8).r;
  if (L == 2) {
    uvec4 t = texel(tx + off % 8, ty + off / 8);
    return enc4(t.a) << 4 | enc4(t.r);
  }
  int i = (off & 31) / 2;
  bool lo = (off & 1) != 0;
  uvec4 t = texel(tx + i % 4, ty + i / 4);
  if (L == 3) return lo ? t.r : t.a;
  if (L == 4) {
    uint v = (t.r >> 3) << 11 | (t.g >> 2) << 5 | (t.b >> 3);
    return lo ? (v & 255u) : (v >> 8);
  }
  if (L == 5) {
    uint v = t.a >= 224u ? (0x8000u | (t.r >> 3) << 10 | (t.g >> 3) << 5 | (t.b >> 3))
                         : ((t.a >> 5) << 12 | (t.r >> 4) << 8 | (t.g >> 4) << 4 | (t.b >> 4));
    return lo ? (v & 255u) : (v >> 8);
  }
  if (off < 32) return lo ? t.r : t.a;  // RGBA8: the AR plane, then the GB one
  return lo ? t.b : t.g;
}
void main() {
  int k = (int(gl_FragCoord.y) * 256 + int(gl_FragCoord.x)) * 4;
  o_color = vec4(float(byteAt(k)), float(byteAt(k + 1)), float(byteAt(k + 2)), float(byteAt(k + 3))) / 255.0;
}
)";
static GLuint s_encProg, s_encFbo, s_encTex;
static GLint s_encUSize, s_encULayout, s_encUBytes;
static int s_encRows = 0;

void rendererInit(float efbScale) {
    s_scale = efbScale < 1.0f ? 1.0f : efbScale;
    g_gxStats = g_gxStats || statsEnv();  // settings.txt is read after static initialisation
    const char* renderer = (const char*)glGetString(GL_RENDERER);
    logmsg("OpenGL %s, renderer %s (%s)", (const char*)glGetString(GL_VERSION), renderer,
           (const char*)glGetString(GL_VENDOR));
    if (renderer && (strstr(renderer, "llvmpipe") || strstr(renderer, "softpipe") || strstr(renderer, "Software")))
        logmsg("WARNING: %s renders on the CPU and cannot keep the game at full speed. On Linux the 32-bit build "
               "gets a GPU driver only if its 32-bit GL libraries are installed; the 64-bit build "
               "(SMS_ARCH=64 ./build.sh) uses the system's driver.", renderer);
    s_efbW = s_wide > 1.0f ? (int(float(EFB_W) * s_wide + 1.0f) & ~1) : EFB_W;
    // The EFB is rounded up to whole, even columns (853.3 -> 854 at 16:9):
    // what is stretched across the frame (fades, wipes, copies drawn back)
    // spans all of them, else the last column or two showed the scene
    // through every fade to black (#11). The camera is a hair wider for it.
    if (s_efbW != EFB_W) s_wide = float(s_efbW) / float(EFB_W);
    if (const char* e = getenv("SMS_WIDESCREEN_HUD")) s_hudEdges = s_efbW != EFB_W && !strcmp(e, "edges");
    s_ox = (s_efbW - EFB_W) / 2;
    if (s_efbW != EFB_W) logmsg("widescreen: EFB %dx%d", s_efbW, EFB_H);
    int W = scaled(s_efbW), H = scaled(EFB_H);
    glGenTextures(1, &s_efbColor);
    glBindTexture(GL_TEXTURE_2D, s_efbColor);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenTextures(1, &s_efbDepth);
    glBindTexture(GL_TEXTURE_2D, s_efbDepth);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, W, H, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (const char* e = getenv("SMS_MSAA")) s_msaa = atoi(e);
    if (s_msaa > 1) {
        GLint maxSamples = 0;
        glGetIntegerv(GL_MAX_SAMPLES, &maxSamples);
        s_msaa = std::min(s_msaa, int(maxSamples));
    }
    if (s_msaa < 2) s_msaa = 0;
    glGenFramebuffers(1, &s_efbFbo);
    if (s_msaa) {
        glGenRenderbuffers(1, &s_efbMsColor);
        glBindRenderbuffer(GL_RENDERBUFFER, s_efbMsColor);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, s_msaa, GL_RGBA8, W, H);
        glGenRenderbuffers(1, &s_efbMsDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, s_efbMsDepth);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, s_msaa, GL_DEPTH_COMPONENT24, W, H);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glGenFramebuffers(1, &s_efbResolveFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, s_efbResolveFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_efbColor, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, s_efbDepth, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) logmsg("EFB resolve framebuffer incomplete");
        glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, s_efbMsColor);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, s_efbMsDepth);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            logmsg("anti-aliasing: %dx MSAA", s_msaa);
        } else {  // fall back to the plain EFB
            logmsg("anti-aliasing: %dx MSAA framebuffer incomplete, MSAA off", s_msaa);
            s_msaa = 0;
        }
    }
    if (!s_msaa) {
        glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_efbColor, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, s_efbDepth, 0);
    }
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) logmsg("EFB framebuffer incomplete");
    glClearColor(0, 0, 0, 1);
    glClearDepth(1.0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glGenFramebuffers(1, &s_tmpFbo);

    glcInvalidate();
    glGenVertexArrays(1, &s_vao);
    glcBindVertexArray(s_vao);
    s_stream.init(128u << 20, kArenaBytes);
    // Values of the attributes a packed format leaves out (see vaoForFormat):
    // the same defaults the loader used to write into every vertex.
    glVertexAttrib4f(1, 0.0f, 0.0f, 1.0f, 1.0f);  // normal
    glVertexAttrib4f(2, 0.0f, 0.0f, 0.0f, 1.0f);  // binormal
    glVertexAttrib4f(3, 0.0f, 0.0f, 0.0f, 1.0f);  // tangent
    glVertexAttrib4f(4, 1.0f, 1.0f, 1.0f, 1.0f);  // colour 0
    glVertexAttrib4f(5, 1.0f, 1.0f, 1.0f, 1.0f);  // colour 1
    for (GLuint i = 6; i < 14; i++) glVertexAttrib4f(i, 0.0f, 0.0f, 0.0f, 1.0f);  // texcoords

    glGetIntegerv(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, &s_uboAlign);
    if (s_uboAlign < 16) s_uboAlign = 16;

    s_copyProg = compileProgram(kCopyVs, kCopyFs);
    glUseProgram(s_copyProg);
    glUniform1i(glGetUniformLocation(s_copyProg, "u_color"), 0);
    glUniform1i(glGetUniformLocation(s_copyProg, "u_depth"), 1);
    s_copyUMode = glGetUniformLocation(s_copyProg, "u_mode");
    s_copyURect = glGetUniformLocation(s_copyProg, "u_rect");
    s_copyUAlphaOne = glGetUniformLocation(s_copyProg, "u_alphaOne");
    s_encProg = compileProgram(kCopyVs, kEncodeFs);
    glUseProgram(s_encProg);
    glUniform1i(glGetUniformLocation(s_encProg, "u_src"), 0);
    const GLfloat wholeRect[4] = {0.0f, 0.0f, 1.0f, 1.0f};
    glUniform4fv(glGetUniformLocation(s_encProg, "u_rect"), 1, wholeRect);
    s_encUSize = glGetUniformLocation(s_encProg, "u_size");
    s_encULayout = glGetUniformLocation(s_encProg, "u_layout");
    s_encUBytes = glGetUniformLocation(s_encProg, "u_bytes");
    glGenFramebuffers(1, &s_encFbo);
    glGenVertexArrays(1, &s_copyVao);
    postInit();
    glcInvalidate();
    s_ready = true;
}

// ------------------------------------------------------------------ batching
void onStateChange() {
    if (s_bidx.size || s_barenaIdx) flushBatch();
}

PrimClass primClass(uint8_t op) { return op >= 0xB8 ? PRIM_POINTS : op >= 0xA8 ? PRIM_LINES : PRIM_TRIS; }

uint8_t* primitiveBegin(uint8_t op, uint32_t n, uint32_t fmt, uint32_t stride) {
    PrimClass cls = primClass(op);
    if (batchHasVertices() && (cls != s_bclass || fmt != s_bfmt)) flushBatch();
    s_bclass = cls;
    s_bfmt = fmt;
    s_bstride = stride;
    s_bdata.size = size_t(s_bcount) * stride;
    return s_bdata.reserve(size_t(n) * stride);
}

uint32_t primitiveIndices(uint8_t op, uint32_t n, uint32_t base, uint32_t* w) {
    uint32_t* const w0 = w;
    auto tri = [&](uint32_t a, uint32_t b, uint32_t c) {
        w[0] = base + a;
        w[1] = base + b;
        w[2] = base + c;
        w += 3;
    };
    switch (op & 0xF8) {
    case 0x80:
    case 0x88:  // quads
        for (uint32_t i = 0; i + 3 < n; i += 4) {
            tri(i, i + 1, i + 2);
            tri(i, i + 2, i + 3);
        }
        break;
    case 0x90:
        for (uint32_t i = 0; i + 2 < n; i += 3) tri(i, i + 1, i + 2);
        break;
    case 0x98:
        for (uint32_t i = 2; i < n; i++) {
            if (i & 1) tri(i - 1, i - 2, i);
            else tri(i - 2, i - 1, i);
        }
        break;
    case 0xA0:
        for (uint32_t i = 2; i < n; i++) tri(0, i - 1, i);
        break;
    case 0xA8:
        for (uint32_t i = 0; i + 1 < n; i += 2) {
            *w++ = base + i;
            *w++ = base + i + 1;
        }
        break;
    case 0xB0:
        for (uint32_t i = 1; i < n; i++) {
            *w++ = base + i - 1;
            *w++ = base + i;
        }
        break;
    default:
        for (uint32_t i = 0; i < n; i++) *w++ = base + i;
        break;
    }
    return uint32_t(w - w0);
}

void primitiveEnd(uint8_t op, uint32_t n) {
    if (!s_ready || n == 0) return;
    uint32_t base = s_bcount;
    s_bcount += n;
    s_bdata.size = size_t(s_bcount) * s_bstride;
    uint32_t* w = s_bidx.reserve(size_t(n) * 3);  // no primitive makes more than 3 per vertex
    uint32_t k = primitiveIndices(op, n, base, w);
    noteStreamIndices(s_bidx.size, k);
    s_bidx.size += k;
    s_stats.vertices += n;
}

void appendDecoded(PrimClass cls, uint32_t fmt, uint32_t stride, const uint8_t* verts, uint32_t n, const uint32_t* idx,
                   uint32_t nidx) {
    if (batchHasVertices() && (cls != s_bclass || fmt != s_bfmt)) flushBatch();
    s_bclass = cls;
    s_bfmt = fmt;
    s_bstride = stride;
    s_bdata.size = size_t(s_bcount) * stride;
    if (!s_ready || n == 0) return;
    memcpy(s_bdata.reserve(size_t(n) * stride), verts, size_t(n) * stride);
    uint32_t base = s_bcount;
    s_bcount += n;
    s_bdata.size = size_t(s_bcount) * stride;
    uint32_t* w = s_bidx.reserve(nidx);
    for (uint32_t i = 0; i < nidx; i++) w[i] = idx[i] + base;
    noteStreamIndices(s_bidx.size, nidx);
    s_bidx.size += nidx;
    s_stats.vertices += n;
}

// ------------------------------------------------------------------ display-list arena
// Cached display-list runs (gx_fifo.cpp) are stored once in the arena, the
// part of the stream buffer after its ring (so batches draw them through the
// same VAOs), bump allocated. When it fills up it starts over once the GPU
// is done with it, and the runs are stored again as they are drawn.
// SMS_GX_DL_ARENA=0 copies them into each batch instead.
static size_t s_arenaPos = 0;  // from the arena's start
static uint32_t s_arenaGen = 1;
#ifdef __ANDROID__
// GLES 3.1 rebasing reads CPU indices, never a live GPU stream. Retain only
// arena indices (not vertex data); streamed indices already live in s_bidx.
static std::unordered_map<size_t, std::vector<uint32_t>> s_arenaIndices;
#endif

bool arenaEnabled() {
    static int on = -1;
    if (on < 0) {
        const char* e = getenv("SMS_GX_DL_ARENA");
        on = !(e && e[0] == '0');
    }
    return on != 0;
}
uint32_t arenaGeneration() { return s_arenaGen; }

bool arenaUpload(const uint8_t* verts, uint32_t n, uint32_t stride, const uint32_t* idx, uint32_t nidx, ArenaRef& out) {
    const size_t vbytes = size_t(n) * stride, ibytes = size_t(nidx) * 4, need = vbytes + stride + ibytes + 4;
    if (!s_ready || need > kArenaBytes / 8) return false;
    if (s_arenaPos + need > kArenaBytes) {  // start over, once nothing drawn from the arena is pending
        flushBatch();
        GLsync done = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glClientWaitSync(done, GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull);
        glDeleteSync(done);
        s_arenaPos = 0;
        s_arenaGen++;
#ifdef __ANDROID__
        s_arenaIndices.clear();
#endif
    }
    const size_t base = s_stream.size;
    const size_t vOff = (base + s_arenaPos + stride - 1) / stride * stride;
    const size_t iOff = (vOff + vbytes + 3) & ~size_t(3);
    s_stream.writeAt(vOff, verts, vbytes);
    s_stream.writeAt(iOff, idx, ibytes);
#ifdef __ANDROID__
    if (android::needsRebasedIndices()) s_arenaIndices[iOff].assign(idx, idx + nidx);
#endif
    s_arenaPos = iOff + ibytes - base;
    out.gen = s_arenaGen;
    out.baseVertex = int32_t(vOff / stride);
    out.iOff = iOff;
    return true;
}

void appendArena(PrimClass cls, uint32_t fmt, uint32_t stride, const ArenaRef& ref, uint32_t n, uint32_t nidx,
                 const uint8_t* verts) {
    if (batchHasVertices() && (cls != s_bclass || fmt != s_bfmt)) flushBatch();
    s_bclass = cls;
    s_bfmt = fmt;
    s_bstride = stride;
    s_bdata.size = size_t(s_bcount) * stride;
    if (!s_ready || n == 0 || nidx == 0) return;
    if (s_segs.empty() && s_bidx.size) s_segs.push_back(BatchSeg{false, 0, uint32_t(s_bidx.size), 0, 0, nullptr, 0});
    s_segs.push_back(BatchSeg{true, 0, nidx, ref.iOff, GLint(ref.baseVertex), verts, n});
    s_barenaVerts += n;
    s_barenaIdx += nidx;
    s_stats.vertices += n;
}

static inline int sext11(uint32_t v) { return int32_t(v << 21) >> 21; }
static inline float xff(uint32_t r) {
    float f;
    memcpy(&f, &g.xfReg[r], 4);
    return f;
}

GlCache g_glc;

void glcInvalidate() {
    // raw paths sample with the textures' own parameters: drop the samplers
    for (int u = 0; u < 8; u++)
        if (g_glc.sampler[u] != 0 && g_glc.sampler[u] != ~0u) glBindSampler(GLuint(u), 0);
    memset(&g_glc, 0xFF, sizeof(g_glc));  // ~0 names/enums, -1 flags, NaN floats
}

void glcForgetTexture(GLuint tex) {
    for (int u = 0; u < 8; u++)
        if (g_glc.tex[u] == tex) g_glc.tex[u] = ~0u;
}

// The GL state for the batch about to be drawn, from the GameCube's
// registers: the EFB framebuffer and viewport, the scissor (BP), culling
// (GENMODE), blending, logic op and colour/alpha write masks (CMODE0/1, and
// PE_CONTROL for whether the EFB has alpha), depth test and write (ZMODE),
// the depth range and clipping from XF's viewport, and point and line size
// (LPSIZE). Each piece goes to GL only when it differs from what g_glc says
// GL already has (glcInvalidate forgets that after foreign GL calls).
static void applyGlState() {
    GlCache& c = g_glc;
    int W = scaled(s_efbW), H = scaled(EFB_H);
    if (c.fbo != s_efbFbo) {
        c.fbo = s_efbFbo;
        glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
    }
    if (c.vp[0] != 0 || c.vp[1] != 0 || c.vp[2] != W || c.vp[3] != H) {
        c.vp[0] = c.vp[1] = 0;
        c.vp[2] = W;
        c.vp[3] = H;
        glViewport(0, 0, W, H);
    }

    // scissor (registers hold coordinates + 342)
    uint32_t tl = g.bp[BP_SCISSOR_TL], br = g.bp[BP_SCISSOR_BR];
    int top = int(tl & 0x7FF) - 342, left = int((tl >> 12) & 0x7FF) - 342;
    int bottom = int(br & 0x7FF) - 342, right = int((br >> 12) & 0x7FF) - 342;
    int sw = right - left + 1, sh = bottom - top + 1;
    if (sw < 0) sw = 0;
    if (sh < 0) sh = 0;
    glcCap(GL_SCISSOR_TEST, c.scissor, true);
    if (s_efbW != EFB_W) {  // into the draw's widescreen mapping; the whole width stays whole
        if (left <= 0 && left + sw >= EFB_W) {
            left = 0;
            sw = s_efbW;
        } else {
            int l = int(floorf(float(left) * s_xmap.a + s_xmap.b)), r = int(ceilf(float(left + sw) * s_xmap.a + s_xmap.b));
            left = l;
            sw = r - l;
        }
    }
    GLint sc[4] = {scaled(left), scaled(top), scaled(left + sw) - scaled(left), scaled(top + sh) - scaled(top)};
    if (memcmp(sc, c.sc, sizeof sc) != 0) {
        memcpy(c.sc, sc, sizeof sc);
        glScissor(sc[0], sc[1], sc[2], sc[3]);
    }

    // culling: GX front faces are clockwise on screen, which is counter-clockwise
    // in this framebuffer's (y-down) window coordinates
    uint32_t cull = (g.bp[BP_GENMODE] >> 14) & 3;
    if (c.frontFace != GL_CCW) {
        c.frontFace = GL_CCW;
        glFrontFace(GL_CCW);
    }
    if (cull == 0 || s_bclass != PRIM_TRIS) glcCap(GL_CULL_FACE, c.cull, false);
    else {
        glcCap(GL_CULL_FACE, c.cull, true);
        GLenum cf = cull == 1 ? GL_BACK : cull == 2 ? GL_FRONT : GL_FRONT_AND_BACK;
        if (c.cullFace != cf) {
            c.cullFace = cf;
            glCullFace(cf);
        }
    }

    static const GLenum cmp[8] = {GL_NEVER, GL_LESS, GL_EQUAL, GL_LEQUAL, GL_GREATER, GL_NOTEQUAL, GL_GEQUAL, GL_ALWAYS};
    uint32_t z = g.bp[BP_ZMODE];
    GLboolean dmask = GL_FALSE;
    if (z & 1) {
        glcCap(GL_DEPTH_TEST, c.depth, true);
        GLenum df = cmp[(z >> 1) & 7];
        if (c.depthFunc != df) {
            c.depthFunc = df;
            glDepthFunc(df);
        }
        dmask = (z >> 4) & 1 ? GL_TRUE : GL_FALSE;
    } else {
        glcCap(GL_DEPTH_TEST, c.depth, false);
    }
    if (c.depthMask != dmask) {
        c.depthMask = dmask;
        glDepthMask(dmask);
    }

    uint32_t pix = g.bp[BP_PE_CONTROL] & 7;
    bool hasAlpha = pix == 1;  // GX_PF_RGBA6_Z24
    uint32_t cm = g.bp[BP_CMODE0], cm1 = g.bp[BP_CMODE1];
    bool colorUpd = (cm >> 3) & 1, alphaUpd = ((cm >> 4) & 1) && hasAlpha;
    GLboolean mask[4] = {GLboolean(colorUpd), GLboolean(colorUpd), GLboolean(colorUpd),
                         GLboolean(alphaUpd || ((cm1 >> 8) & 1 && hasAlpha))};
    if (memcmp(mask, c.cmask, sizeof mask) != 0) {
        memcpy(c.cmask, mask, sizeof mask);
        glColorMask(mask[0], mask[1], mask[2], mask[3]);
    }

    static const GLenum srcF[8] = {GL_ZERO, GL_ONE, GL_DST_COLOR, GL_ONE_MINUS_DST_COLOR,
                                   GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_DST_ALPHA, GL_ONE_MINUS_DST_ALPHA};
    static const GLenum dstF[8] = {GL_ZERO, GL_ONE, GL_SRC_COLOR, GL_ONE_MINUS_SRC_COLOR,
                                   GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_DST_ALPHA, GL_ONE_MINUS_DST_ALPHA};
    auto noDstAlpha = [&](GLenum f) {
        if (hasAlpha) return f;
        return f == GL_DST_ALPHA ? GLenum(GL_ONE) : f == GL_ONE_MINUS_DST_ALPHA ? GLenum(GL_ZERO) : f;
    };
    auto blendEq = [&](GLenum rgb, GLenum a) {
        if (c.beq[0] != rgb || c.beq[1] != a) {
            c.beq[0] = rgb;
            c.beq[1] = a;
            glBlendEquationSeparate(rgb, a);
        }
    };
    auto blendFunc = [&](GLenum s0, GLenum d0, GLenum s1, GLenum d1) {
        if (c.bf[0] != s0 || c.bf[1] != d0 || c.bf[2] != s1 || c.bf[3] != d1) {
            c.bf[0] = s0;
            c.bf[1] = d0;
            c.bf[2] = s1;
            c.bf[3] = d1;
            glBlendFuncSeparate(s0, d0, s1, d1);
        }
    };
    bool dstAlpha = ((cm1 >> 8) & 1) != 0;
    GLenum aSrc = GL_ONE, aDst = GL_ZERO;
    if (dstAlpha) {
        float bc = float(cm1 & 0xFF) / 255.0f;
        if (!(c.blendColor[3] == bc) || !(c.blendColor[0] == 0.0f)) {
            c.blendColor[0] = c.blendColor[1] = c.blendColor[2] = 0.0f;
            c.blendColor[3] = bc;
            glBlendColor(0, 0, 0, bc);
        }
        aSrc = GL_CONSTANT_ALPHA;
        aDst = GL_ZERO;
    }
    if (cm & 1) {
        glcCap(GL_COLOR_LOGIC_OP, c.logic, false);
        glcCap(GL_BLEND, c.blend, true);
        if ((cm >> 11) & 1) {
            blendEq(GL_FUNC_REVERSE_SUBTRACT, dstAlpha ? GL_FUNC_ADD : GL_FUNC_REVERSE_SUBTRACT);
            blendFunc(GL_ONE, GL_ONE, dstAlpha ? aSrc : GL_ONE, dstAlpha ? aDst : GL_ONE);
        } else {
            GLenum s = noDstAlpha(srcF[(cm >> 8) & 7]), d = noDstAlpha(dstF[(cm >> 5) & 7]);
            blendEq(GL_FUNC_ADD, GL_FUNC_ADD);
            blendFunc(s, d, dstAlpha ? aSrc : s, dstAlpha ? aDst : d);
        }
    } else if ((cm >> 1) & 1) {
        static const GLenum lop[16] = {GL_CLEAR, GL_AND, GL_AND_REVERSE, GL_COPY, GL_AND_INVERTED, GL_NOOP,
                                       GL_XOR, GL_OR, GL_NOR, GL_EQUIV, GL_INVERT, GL_OR_REVERSE,
                                       GL_COPY_INVERTED, GL_OR_INVERTED, GL_NAND, GL_SET};
        glcCap(GL_BLEND, c.blend, false);
        glcCap(GL_COLOR_LOGIC_OP, c.logic, true);
        GLenum op = lop[(cm >> 12) & 15];
        if (c.logicOp != op) {
            c.logicOp = op;
            glLogicOp(op);
        }
    } else if (dstAlpha) {
        glcCap(GL_COLOR_LOGIC_OP, c.logic, false);
        glcCap(GL_BLEND, c.blend, true);
        blendEq(GL_FUNC_ADD, GL_FUNC_ADD);
        blendFunc(GL_ONE, GL_ZERO, aSrc, aDst);
    } else {
        glcCap(GL_COLOR_LOGIC_OP, c.logic, false);
        glcCap(GL_BLEND, c.blend, false);
    }

    // GX's near and far clipping is the two clip distances. GL's own depth
    // clip repeats it, but rounding can put a vertex exactly on the far plane
    // just past it (the episode select's background quad, clipped away whole),
    // so it is off while they clip; draws with GX clipping off keep it.
    bool clipOn = g.xfReg[0x05] == 0;
    glcCap(GL_CLIP_DISTANCE0, c.clip0, clipOn);
    glcCap(GL_CLIP_DISTANCE0 + 1, c.clip1, clipOn);
    // Without clip distances GL's own clip is GX's (android::clipsByDepthRange),
    // so a depth clamp may not switch it off; the depth range is the
    // viewport's z: GX's depth at the far plane, and its scale down to the near.
    const bool byRange = android::clipsByDepthRange();
    glcCap(GL_DEPTH_CLAMP, c.depthClamp, clipOn && !byRange);
    float range[2] = {0.0f, 1.0f};
    if (byRange && clipOn) {
        const float zScale = xff(XFR_VIEWPORT + 2), zFar = xff(XFR_VIEWPORT + 5);
        range[0] = (zFar - zScale) / 16777215.0f;
        range[1] = zFar / 16777215.0f;
    }
    if (!(c.depthRange[0] == range[0]) || !(c.depthRange[1] == range[1])) {
        c.depthRange[0] = range[0];
        c.depthRange[1] = range[1];
        glDepthRange(range[0], range[1]);
    }
    uint32_t lp = g.bp[BP_LPSIZE];
    float pt = float((lp >> 8) & 0xFF) / 6.0f * float(s_scale);
    if (pt < 1.0f) pt = 1.0f;
    if (!(c.pointSize == pt)) {
        c.pointSize = pt;
        glPointSize(pt);
    }
    // Without geometry shaders (some GLES drivers) lines are GL's own: as wide
    // as GX's, rounded up, or the iris wipes' black field (one line per row)
    // shows the picture through stripes at a raised internal resolution.
    float lw = 1.0f;
    if (!android::geometryShaders()) {
        lw = ceilf(float(lp & 0xFF) / 6.0f * float(s_scale));
        if (lw < 1.0f) lw = 1.0f;
    }
    if (!(c.lineWidth == lw)) {
        c.lineWidth = lw;
        glLineWidth(lw);
    }
}

// The values the batch's shader reads besides the XF block: TEV colour
// registers and konst colours, texture coordinate scales and sizes, the
// alpha test's references, fog, the indirect matrices, the EFB size, line
// width, the projection and viewport (with the widescreen mapping folded in)
// and the lighting channels' colours. Each program keeps the last values it
// was given (sp->uc), and UNI uploads one only when it changed.
static void uploadUniforms(const ShaderProgram* sp, float texW[8], float texH[8]) {
    UniformCache& uc = sp->uc;
    // uploads `value` (same size as the cached copy) only when it changed
#define UNI(loc, field, value, call)                                               \
    if (!uc.init || memcmp(uc.field, value, sizeof uc.field) != 0) {                \
        memcpy(uc.field, value, sizeof uc.field);                                   \
        if (sp->loc >= 0) call;                                                     \
    }
    GLint iv[16];
    for (int i = 0; i < 4; i++) {
        uint32_t ra = g.bp[BP_TEV_REG + 2 * i], bg = g.bp[BP_TEV_REG + 2 * i + 1];
        iv[4 * i + 0] = sext11(ra & 0x7FF);
        iv[4 * i + 1] = sext11((bg >> 12) & 0x7FF);
        iv[4 * i + 2] = sext11(bg & 0x7FF);
        iv[4 * i + 3] = sext11((ra >> 12) & 0x7FF);
    }
    UNI(uTevReg, tevreg, iv, glUniform4iv(sp->uTevReg, 4, iv));
    for (int i = 0; i < 4; i++) {
        uint32_t ra = g.kreg[2 * i], bg = g.kreg[2 * i + 1];
        iv[4 * i + 0] = int(ra & 0xFF);
        iv[4 * i + 1] = int((bg >> 12) & 0xFF);
        iv[4 * i + 2] = int(bg & 0xFF);
        iv[4 * i + 3] = int((ra >> 12) & 0xFF);
    }
    UNI(uKonst, konst, iv, glUniform4iv(sp->uKonst, 4, iv));

    // manual texcoord scaling: coordinates are in units of the given size
    float ts[16];
    for (int c = 0; c < 8; c++) ts[2 * c] = ts[2 * c + 1] = 1.0f;
    uint32_t nst = ((g.bp[BP_GENMODE] >> 10) & 15) + 1;
    for (uint32_t s = 0; s < nst; s++) {
        uint32_t ord = (g.bp[BP_TREF + (s >> 1)] >> ((s & 1) * 12)) & 0x3FF;
        uint32_t map = ord & 7, coord = (ord >> 3) & 7;
        if (g.tcManual[coord] && texW[map] > 0) {
            ts[2 * coord] = float((g.bp[BP_SU_SSIZE + 2 * coord] & 0xFFFF) + 1) / texW[map];
            ts[2 * coord + 1] = float((g.bp[BP_SU_SSIZE + 2 * coord + 1] & 0xFFFF) + 1) / texH[map];
        }
    }
    UNI(uTexScale, texscale, ts, glUniform2fv(sp->uTexScale, 8, ts));
    float tsz[16];
    for (int m = 0; m < 8; m++) {
        tsz[2 * m] = texW[m] > 0 ? texW[m] : 1.0f;
        tsz[2 * m + 1] = texH[m] > 0 ? texH[m] : 1.0f;
    }
    UNI(uTexSize, texsize, tsz, glUniform2fv(sp->uTexSize, 8, tsz));
    uint32_t ac = g.bp[BP_ALPHACOMPARE];
    GLint ar[2] = {GLint(ac & 0xFF), GLint((ac >> 8) & 0xFF)};
    UNI(uAlphaRef, alpharef, ar, glUniform2iv(sp->uAlphaRef, 1, ar));

    // fog
    uint32_t f0 = g.bp[BP_FOG0], f1 = g.bp[BP_FOG1], f2 = g.bp[BP_FOG2], f3 = g.bp[BP_FOG3];
    auto fogFloat = [](uint32_t r) {
        uint32_t bits = ((r >> 19) & 1) << 31 | ((r >> 11) & 0xFF) << 23 | (r & 0x7FF) << 12;
        float f;
        memcpy(&f, &bits, 4);
        return f;
    };
    float a = fogFloat(f0), c = fogFloat(f3);
    int bs = int(f2 & 31);
    float A = ldexpf(a, bs);
    float B = ldexpf(float(f1 & 0xFFFFFF) / 8388638.0f, bs - 1);
    float fog[4] = {A, B, c, float((f3 >> 20) & 1)};
    UNI(uFog, fog, fog, glUniform4fv(sp->uFog, 1, fog));
    uint32_t fc = g.bp[BP_FOG_COLOR];
    float fcol[4] = {float((fc >> 16) & 255) / 255.0f, float((fc >> 8) & 255) / 255.0f, float(fc & 255) / 255.0f, 1.0f};
    UNI(uFogColor, fogcolor, fcol, glUniform4fv(sp->uFogColor, 1, fcol));

    float im[24];
    for (int m = 0; m < 3; m++) {
        uint32_t r0 = g.bp[BP_IND_MTX + 3 * m], r1 = g.bp[BP_IND_MTX + 3 * m + 1], r2 = g.bp[BP_IND_MTX + 3 * m + 2];
        int sc = int(((r0 >> 22) & 3) | ((r1 >> 22) & 3) << 2 | ((r2 >> 22) & 3) << 4) - 17;
        float* row0 = &im[8 * m];
        float* row1 = &im[8 * m + 4];
        row0[0] = sext11(r0 & 0x7FF) / 1024.0f;
        row1[0] = sext11((r0 >> 11) & 0x7FF) / 1024.0f;
        row0[1] = sext11(r1 & 0x7FF) / 1024.0f;
        row1[1] = sext11((r1 >> 11) & 0x7FF) / 1024.0f;
        row0[2] = sext11(r2 & 0x7FF) / 1024.0f;
        row1[2] = sext11((r2 >> 11) & 0x7FF) / 1024.0f;
        row0[3] = float(sc);  // the scale's exponent: the shader shifts by it
        row1[3] = 0.0f;
    }
    UNI(uIndMtx, indmtx, im, glUniform4fv(sp->uIndMtx, 6, im));

    float efb[2] = {float(s_efbW), float(EFB_H)};
    UNI(uEfb, efb, efb, glUniform2fv(sp->uEfb, 1, efb));
    float lw[1] = {float(g.bp[BP_LPSIZE] & 0xFF) / 6.0f};  // line programs: GXSetLineWidth's sixths of a pixel
    UNI(uLineWidth, linewidth, lw, glUniform1fv(sp->uLineWidth, 1, lw));
    float proj[8] = {xff(XFR_PROJ), xff(XFR_PROJ + 1), xff(XFR_PROJ + 2), xff(XFR_PROJ + 3),
                     xff(XFR_PROJ + 4), xff(XFR_PROJ + 5), float(g.xfReg[XFR_PROJ + 6] & 1), 0.0f};
    UNI(uProj, proj, proj, glUniform4fv(sp->uProj, 2, proj));
    float vp[8] = {xff(XFR_VIEWPORT), xff(XFR_VIEWPORT + 1), xff(XFR_VIEWPORT + 2), 0.0f,
                   xff(XFR_VIEWPORT + 3), xff(XFR_VIEWPORT + 4), xff(XFR_VIEWPORT + 5), 0.0f};
    if (s_efbW != EFB_W) {  // the draw's widescreen mapping (centre and half-width, clip x scale)
        vp[4] = (vp[4] - 342.0f) * s_xmap.a + s_xmap.b + 342.0f;
        vp[0] *= s_xmap.a * s_xmap.clip;
    }
    UNI(uViewport, vp, vp, glUniform4fv(sp->uViewport, 2, vp));
    float ch[16];
    const int regs[4] = {XFR_AMB0, XFR_AMB0 + 1, XFR_MAT0, XFR_MAT0 + 1};
    for (int i = 0; i < 4; i++) {
        uint32_t v = g.xfReg[regs[i]];
        ch[4 * i + 0] = float((v >> 24) & 255) / 255.0f;
        ch[4 * i + 1] = float((v >> 16) & 255) / 255.0f;
        ch[4 * i + 2] = float((v >> 8) & 255) / 255.0f;
        ch[4 * i + 3] = float(v & 255) / 255.0f;
    }
    UNI(uAmbMat, ambmat, ch, glUniform4fv(sp->uAmbMat, 4, ch));
#undef UNI
    uc.init = true;
}

static uint32_t s_syncReads = 0;  // reads that made the CPU wait for the GPU
static uint64_t s_flushes = 0;    // flushBatch calls that drew
static void statsFrame() {
    static int every = -1;
    static uint32_t frames = 0, draws = 0, verts = 0, compiles0 = 0, uploads0 = 0, sync0 = 0;
    static double t0 = 0, gx0 = 0, tex0 = 0, draw0 = 0, copy0 = 0, peek0 = 0, wait0 = 0;
    if (every < 0) {
        const char* e = getenv("SMS_GX_STATS");
        every = e ? atoi(e) : 0;
#ifdef __ANDROID__
        // the launcher's performance log: draws, uploads and waits as well
        const char* timing = getenv("SMS_TIMING_LOG");
        if (!e && timing && *timing && strcmp(timing, "0") != 0) every = 120;
#endif
        t0 = nowSeconds();
    }
    if (every <= 0) return;
    static double last = 0, worst = 0;  // the slowest frame of the window
    const double now = nowSeconds();
    if (last > 0 && now - last > worst) worst = now - last;
    last = now;
    frames++;
    draws += s_stats.draws;
    verts += s_stats.vertices;
    if (frames < uint32_t(every)) return;
    double t = nowSeconds();
    logmsg("stats: %u frames, %.1f draws/frame, %.0f vertices/frame, %u shader compiles, %u texture uploads, "
           "%.1f ms/frame total (slowest %.1f), %.1f ms/frame in sms_gx (textures %.1f, batches %.1f, copies %.1f, peeks %.1f, "
           "GPU waits %.1f), %.1f synchronous GPU reads/frame",
           frames, double(draws) / frames, double(verts) / frames, g_statShaderCompiles - compiles0,
           g_statTexUploads - uploads0, (t - t0) * 1000.0 / frames, worst * 1000.0,
           (s_gxSeconds + g_decodeSeconds - gx0) * 1000.0 / frames,
           (s_texSeconds - tex0) * 1000.0 / frames, (g_flushSeconds - s_texSeconds - draw0) * 1000.0 / frames,
           (s_copySeconds - copy0) * 1000.0 / frames, (s_peekSeconds - peek0) * 1000.0 / frames,
           (s_waitSeconds - wait0) * 1000.0 / frames, double(s_syncReads - sync0) / frames);
    static uint64_t gl0 = 0, hash0 = 0, inv0 = 0, flush0 = 0;
    static double dec0 = 0, idle0 = 0;
    logmsg("stats: %.0f GL calls/frame, %.0f batch flushes/frame, %.0f KiB texture data hashed/frame, "
           "%.1f texture invalidations/frame, %.2f ms/frame decoding vertices, %.1f ms/frame idle",
           double(gl::g_statGlCalls - gl0) / frames, double(s_flushes - flush0) / frames,
           double(g_statTexHashBytes - hash0) / 1024.0 / frames, double(g_statTexInvalidates - inv0) / frames,
           (g_decodeSeconds - dec0) * 1000.0 / frames, ((s_idleClock ? s_idleClock() : 0) - idle0) * 1000.0 / frames);
    gl::logTopCalls(frames);
    dec0 = g_decodeSeconds;
    idle0 = s_idleClock ? s_idleClock() : 0;
    gl0 = gl::g_statGlCalls;
    hash0 = g_statTexHashBytes;
    inv0 = g_statTexInvalidates;
    flush0 = s_flushes;
    sync0 = s_syncReads;
    tex0 = s_texSeconds;
    draw0 = g_flushSeconds - s_texSeconds;
    wait0 = s_waitSeconds;
    copy0 = s_copySeconds;
    peek0 = s_peekSeconds;
    frames = draws = verts = 0;
    worst = 0;
    compiles0 = g_statShaderCompiles;
    uploads0 = g_statTexUploads;
    t0 = t;
    gx0 = s_gxSeconds + g_decodeSeconds;
}

// SMS_GX_HITCH_MS=n: logs each display frame longer than n ms with what it
// spent: draw-time shader compiles, texture uploads (and texture pack bytes
// sent), time making batches (where compiles fall), and waiting for the GL
// thread.
static void hitchFrame() {
    static double limit = -1, last = 0, flush0 = 0, wait0 = 0;
    static uint32_t compiles0 = 0, uploads0 = 0;
    static uint64_t hires0 = 0;
    if (limit < 0) {
        const char* e = getenv("SMS_GX_HITCH_MS");
        limit = e ? atof(e) / 1000.0 : 0;
    }
    if (limit <= 0) return;
    const double now = nowSeconds(), waited = glt::waitedSeconds();
    if (last > 0 && now - last > limit)
        logmsg("hitch: frame %u took %.1f ms: %u shader compiles, %u texture uploads (%.1f MiB from the texture pack), "
               "%.1f ms in batches, %.1f ms waiting for the GL thread",
               g_displayFrames, (now - last) * 1000.0, g_statShaderCompiles - compiles0, g_statTexUploads - uploads0,
               double(g_statHiresBytes - hires0) / (1 << 20), (g_flushSeconds - flush0) * 1000.0,
               (waited - wait0) * 1000.0);
    last = now;
    flush0 = g_flushSeconds;
    wait0 = waited;
    compiles0 = g_statShaderCompiles;
    uploads0 = g_statTexUploads;
    hires0 = g_statHiresBytes;
}

// Asynchronous GPU reads. Every synchronous read (glReadPixels into client
// memory, a query result) makes the CPU wait for all queued GPU work, which on
// a real GPU serialises the two. Reads the game makes every frame are answered
// from the same read one frame earlier instead (as Dolphin does), which the
// game cannot tell apart; SMS_GX_SYNC_READS=1 goes back to synchronous reads.
static uint32_t s_frameNo = 0;   // display copies so far
uint32_t g_displayFrames = 0;    // the same, for the rest of sms_gx
static uint32_t s_drawGen = 0;   // bumped by anything that changes the EFB
static bool asyncReads() {
    static int on = -1;
    if (on < 0) {
        const char* e = getenv("SMS_GX_SYNC_READS");
        on = !(e && e[0] == '1');
    }
    return on != 0;
}

// Pixel metrics (GXClearPixMetric/GXReadPixMetric). Delfino's pollution
// counters draw each goop layer with an alpha test and read how many pixels
// reached the colour unit; the game subtracts 4 per polygon of what it drew,
// so the count is the samples that passed plus 4 per triangle. Copy passes
// in between are left out: they end the running query, which is summed at
// the read. Each clear/read pair of a frame is a slot; a slot that drew the
// same number of triangles one frame earlier, in one query, is answered from
// that frame's query, otherwise the queries are waited for.
enum { kMetricSlots = 32 };
static GLuint s_mq[2][kMetricSlots];
static uint32_t s_mqTris[2][kMetricSlots], s_mqFrame[2][kMetricSlots];
// With the GL thread, the results of a frame's queries are read once it is
// shown (prefetchMetrics), so the next frame's reads do not wait for them.
static uint32_t s_mqResult[2][kMetricSlots];
static std::atomic<int> s_mqReady[2][kMetricSlots];
static bool s_mqFetching[2][kMetricSlots];

static uint32_t takeFetchedMetric(int par, int slot) {
    {
        GxTimer tw(&s_waitSeconds);
        glt::waitFlag(s_mqReady[par][slot]);
    }
    s_mqFetching[par][slot] = false;
    return s_mqResult[par][slot];
}
static bool s_mqUsed[2][kMetricSlots];
static int s_mqSlot = -1;
static uint32_t s_mqSlotFrame = ~0u;
static bool s_pixActive = false, s_pixTaint = false;
static uint32_t s_pixTris = 0;
static uint64_t s_pixSamples = 0;  // counted before the pending queries
static uint32_t s_pixLastRead = 0;
static GLuint s_pixCur = 0;
static bool s_pixCurPooled = false;
static std::vector<GLuint> s_queryPool;
static std::vector<std::pair<GLuint, bool>> s_pixPending;  // ended queries of this count, pooled?

static GLuint poolQuery() {
    if (s_queryPool.empty()) {
        GLuint q;
        glGenQueries(1, &q);
        return q;
    }
    GLuint q = s_queryPool.back();
    s_queryPool.pop_back();
    return q;
}
static void pixReleasePending() {
    for (auto& q : s_pixPending)
        if (q.second) s_queryPool.push_back(q.first);
    s_pixPending.clear();
}
static uint64_t queryResult(GLuint q) {
    GxTimer tw(&s_waitSeconds);
    GLuint n = 0;
    glGetQueryObjectuiv(q, GL_QUERY_RESULT, &n);
    return n;
}

void pixMetricClear() {
    flushBatch();
    if (!s_ready) return;
    if (s_pixActive) {
        glEndQuery(GL_SAMPLES_PASSED);
        if (s_pixCurPooled) s_queryPool.push_back(s_pixCur);
    }
    pixReleasePending();
    if (s_mqSlotFrame != s_frameNo) {
        s_mqSlotFrame = s_frameNo;
        s_mqSlot = -1;
    }
    s_mqSlot++;
    int cur = s_frameNo & 1;
    if (s_mqSlot < kMetricSlots) {
        if (s_mqFetching[cur][s_mqSlot]) takeFetchedMetric(cur, s_mqSlot);  // its last result was never asked for
        if (!s_mq[cur][s_mqSlot]) glGenQueries(1, &s_mq[cur][s_mqSlot]);
        s_pixCur = s_mq[cur][s_mqSlot];
        s_pixCurPooled = false;
    } else {
        s_pixCur = poolQuery();
        s_pixCurPooled = true;
    }
    s_pixSamples = 0;
    s_pixTris = 0;
    s_pixLastRead = 0;
    s_pixTaint = !asyncReads() || s_mqSlot >= kMetricSlots;
    glBeginQuery(GL_SAMPLES_PASSED, s_pixCur);
    s_pixActive = true;
}

uint32_t pixMetricRead() {
    flushBatch();
    if (!s_ready) return 0;
    if (!s_pixActive) return s_pixLastRead;
    glEndQuery(GL_SAMPLES_PASSED);
    uint64_t samples = 0;
    int cur = s_frameNo & 1, slot = s_mqSlot;
    bool fromPrev = false;
    if (!s_pixTaint) {
        s_mqUsed[cur][slot] = true;
        s_mqTris[cur][slot] = s_pixTris;
        s_mqFrame[cur][slot] = s_frameNo;
        int prev = cur ^ 1;
        if (s_mqUsed[prev][slot] && s_mqFrame[prev][slot] + 1 == s_frameNo && s_mqTris[prev][slot] == s_pixTris) {
            samples = s_mqFetching[prev][slot] ? takeFetchedMetric(prev, slot) : queryResult(s_mq[prev][slot]);
            fromPrev = true;
        }
    }
    if (!fromPrev) {
        s_syncReads++;
        samples = s_pixSamples + queryResult(s_pixCur);
        for (auto& q : s_pixPending) samples += queryResult(q.first);
    }
    pixReleasePending();
    if (s_pixCurPooled) s_queryPool.push_back(s_pixCur);
    // reading does not reset the hardware counter: keep counting (a second
    // read before the next clear waits for its queries)
    s_pixSamples = samples;
    s_pixTaint = true;
    s_pixCur = poolQuery();
    s_pixCurPooled = true;
    glBeginQuery(GL_SAMPLES_PASSED, s_pixCur);
    uint64_t v = uint64_t(double(samples) / (double(s_scale) * double(s_scale))) + uint64_t(s_pixTris) * 4;
    s_pixLastRead = v > 0xFFFFFFFFu ? 0xFFFFFFFFu : uint32_t(v);
    return s_pixLastRead;
}

void pixMetricEnd() {
    flushBatch();
    if (!s_ready || !s_pixActive) return;
    // GXReadPixMetric has already returned the scope's result and started
    // an empty continuation query. The game's pollution callback explicitly
    // ends that scope: ordinary scene drawing need not keep counting unused
    // samples. Keep the completed result and the pooled frame query intact.
    glEndQuery(GL_SAMPLES_PASSED);
    if (s_pixCurPooled) s_queryPool.push_back(s_pixCur);
    pixReleasePending();
    s_pixActive = false;
}

static void prefetchMetrics() {
    const uint32_t frame = s_frameNo - 1;
    const int par = int(frame & 1);
    for (int slot = 0; slot < kMetricSlots; slot++) {
        if (!s_mqUsed[par][slot] || s_mqFrame[par][slot] != frame || s_mqFetching[par][slot]) continue;
        s_mqFetching[par][slot] = true;
        s_mqReady[par][slot].store(0);
        const GLuint q = s_mq[par][slot];
        uint32_t* out = &s_mqResult[par][slot];
        std::atomic<int>* ready = &s_mqReady[par][slot];
        glt::post([=] {
            GLuint n = 0;
            glGetQueryObjectuiv(q, GL_QUERY_RESULT, &n);
            *out = n;
            glt::signalFlag(*ready);
        });
    }
}

static void pixMetricPause() {
    if (s_pixActive) {
        glEndQuery(GL_SAMPLES_PASSED);
        s_pixPending.emplace_back(s_pixCur, s_pixCurPooled);
        s_pixTaint = true;  // this count now spans several queries
    }
}
static void pixMetricResume() {
    if (s_pixActive) {
        s_pixCur = poolQuery();
        s_pixCurPooled = true;
        glBeginQuery(GL_SAMPLES_PASSED, s_pixCur);
    }
}

// A batch's horizontal extent on screen (game coordinates), from its
// positions, position matrices and the projection and viewport.
static void screenExtent(float sx, float cx, float* outLo, float* outHi) {
    const VtxFmtLayout& l = vtxFmtLayout(s_bfmt);
    ScreenExtent extent(sx, cx, xff(XFR_PROJ), xff(XFR_PROJ + 1), g.xfReg[XFR_PROJ + 6] & 1, g.xfMem);
    const int matrixOffset = (s_bfmt & VF_MTX) ? int(l.mtx) : -1;
    const uint32_t defIdx = g.xfReg[XFR_MATIDX_A] & 63;
    extent.append(s_bdata.data, s_bcount, s_bstride, matrixOffset, defIdx);
    for (const BatchSeg& sg : s_segs)
        if (sg.arena) extent.append(sg.verts, sg.nverts, s_bstride, matrixOffset, defIdx);
    *outLo = extent.lo;
    *outHi = extent.hi;
}

// How the current batch's game coordinates map into the widened EFB.
static XMap drawXMap() {
    XMap m;
    if (s_efbW == EFB_W) return m;
    if (s_stretch2D && (g.xfReg[XFR_PROJ + 6] & 1)) {  // a fade or wipe: all of it across the frame
        m.a = s_wide;
        return m;
    }
    if (s_wide2D && (g.xfReg[XFR_PROJ + 6] & 1)) {  // laid out wide: the 4:3 scale, centred
        m.b = float(s_ox);
        return m;
    }
    float sx = xff(XFR_VIEWPORT), cx = xff(XFR_VIEWPORT + 3) - 342.0f;
    bool fullWidth = fabsf(fabsf(sx) * 2.0f - float(EFB_W)) < 2.0f && fabsf(cx - float(EFB_W) / 2) < 2.0f;
    if (!fullWidth) {
        m.b = centredOffset(cx);
        return m;
    }
    if ((g.xfReg[XFR_PROJ + 6] & 1) == 0) {  // perspective
        if (s_hud) {  // a 3D part of the HUD (the water tank): moved like the rest
            if (s_hudAnchor >= 0.0f) { // the enclosing pane already determines placement
                m.b = centredOffset(s_hudAnchor);
                return m;
            }
            float lo, hi;
            screenExtent(sx, cx, &lo, &hi);
            if (lo <= hi) m.b = centredOffset((lo + hi) / 2);
            return m;
        }
        m.a = s_wide;
        float p00 = xff(XFR_PROJ), p11 = xff(XFR_PROJ + 2);
        float aspect = p00 != 0.0f ? fabsf(p11 / p00) : 0.0f;
        if (aspect < s_wideCamAspect) m.clip = 1.0f / s_wide;  // not widened by the game
        return m;
    }
    // 2D across the whole width: artwork (menus, the map, movies: colour
    // from an image) stays centred; fades, masks and passes over the frame
    // (untextured, a tiny utility texture, alpha only, or drawing screen
    // copies back) are stretched
    bool artwork = false;
    bool colour = (g.bp[BP_CMODE0] >> 3) & 1;  // colour update
    uint32_t gen = g.bp[BP_GENMODE];
    uint32_t nst = ((gen >> 10) & 15) + 1;
    for (uint32_t st = 0; colour && st < nst && !artwork; st++) {
        uint32_t ord = (g.bp[BP_TREF + (st >> 1)] >> ((st & 1) * 12)) & 0x3FF;
        if (!((ord >> 6) & 1)) continue;
        int map = int(ord & 7);
        const uint8_t* ptr = g.texImage[map];
        uint32_t img0 = g.bp[bpTexReg(BP_TX_IMAGE0, map)];
        uint32_t tw = (img0 & 0x3FF) + 1, th = ((img0 >> 10) & 0x3FF) + 1;
        int cw, ch;
        if (ptr && tw >= 32 && th >= 32 && !efbCopyLookup(ptr, &cw, &ch)) artwork = true;
    }
    // Artwork is centred regardless of its bounds unless HUD edge placement
    // needs its position. Avoid transforming every vertex to learn the same
    // offset already known here (menus, text and ordinary centred HUD pieces).
    if (artwork && !s_hud) {
        m.b = float(s_ox);
        return m;
    }
    float lo, hi;
    screenExtent(sx, cx, &lo, &hi);
    bool spans = lo <= 2.0f && hi >= float(EFB_W) - 2.0f;
    if (spans && !artwork) m.a = s_wide;
    else m.b = spans ? float(s_ox) : centredOffset((lo + hi) / 2);
    return m;
}

static void resetBatch() {
    s_bidx.size = s_bdata.size = 0;
    s_bcount = 0;
    s_segs.clear();
    s_barenaVerts = s_barenaIdx = 0;
}

// Draws what was batched since the last flush, with the state the game set
// for it. In order: the widescreen mapping for this draw (drawXMap), the
// shader for the current TEV/XF state, the GL state (applyGlState), the
// textures every enabled TEV and indirect stage uses, the uniforms; then the
// XF block (only when it changed), the vertices and the indices go into the
// stream buffer in one write, and the batch's index ranges (from the stream,
// or display lists kept in the arena) are drawn.
// The draw itself takes one of three ways:
//   - Android with a pixel metric counting (s_pixActive): each primitive is
//     counted against the old depth before its colour draw;
//   - earlyFallback (GXSetZCompLoc's early Z on a draw that writes depth and
//     whose alpha test can reject, on a GL without early fragment tests:
//     GL 3.3 without the extension, macOS): primitive by primitive, colour
//     tested against the old depth, then a depth-only pass that writes depth
//     even where the alpha test discarded, so overlapping draws keep order;
//   - otherwise all ranges at once (on the PC, consecutive ones on one VAO in
//     one multi-draw).
// SMS_GX_TRACE (traceFile) also logs the draw's vertices.
void flushBatch() {
    if ((!s_bidx.size && !s_barenaIdx) || !s_ready) {
        resetBatch();
        return;
    }
    if (s_native.ending) nativeEfbRestore();
    GxTimer timer;
    GxTimer tf(&g_flushSeconds);
    s_xmap = drawXMap();
    const ShaderProgram* sp = shaderForCurrentState(false, s_bclass == PRIM_LINES);
    glcUseProgram(sp->prog);
    applyGlState();

    // textures: every map referenced by an enabled TEV stage or an indirect stage
    float texW[8] = {0}, texH[8] = {0};
    uint32_t used = 0;
    uint32_t gen = g.bp[BP_GENMODE];
    uint32_t nst = ((gen >> 10) & 15) + 1, nind = (gen >> 16) & 7;
    for (uint32_t s = 0; s < nst; s++) {
        uint32_t ord = (g.bp[BP_TREF + (s >> 1)] >> ((s & 1) * 12)) & 0x3FF;
        if ((ord >> 6) & 1) used |= 1u << (ord & 7);
    }
    for (uint32_t i = 0; i < nind && i < 4; i++) used |= 1u << ((g.bp[BP_RAS1_IREF] >> (6 * i)) & 7);
    for (int m = 0; m < 8; m++) {
        if (!(used & (1u << m))) continue;
        GxTimer tt(&s_texSeconds);
        bindTextureMap(m, &texW[m], &texH[m]);
    }

    uploadUniforms(sp, texW, texH);

    uint32_t matA = g.xfReg[XFR_MATIDX_A], matB = g.xfReg[XFR_MATIDX_B];
    uint8_t defMtx[12] = {
        uint8_t(matA & 63), uint8_t((matA >> 6) & 63), uint8_t((matA >> 12) & 63), uint8_t((matA >> 18) & 63),
        uint8_t((matA >> 24) & 63), uint8_t(matB & 63), uint8_t((matB >> 6) & 63), uint8_t((matB >> 12) & 63),
        uint8_t((matB >> 18) & 63), 0, 0, 0,
    };
    if (!(s_bfmt & VF_MTX)) {  // the batch's default matrix indices, as a constant attribute
        static uint32_t cur[3] = {~0u, ~0u, ~0u};
        uint32_t m[3];
        memcpy(m, defMtx, 12);  // the shader unpacks the bytes little-endian
        if (memcmp(m, cur, 12) != 0) {
            memcpy(cur, m, 12);
            glVertexAttribI4ui(14, m[0], m[1], m[2], 0);
        }
    }
    // this batch's XF block (when it changed), vertices and indices, in one map
    static uint32_t xfBlock[736];
    static size_t s_xfOffset = 0;
    StreamPiece pc[3];
    int np = 0;
    size_t worst = sizeof(xfBlock) + size_t(s_uboAlign) + s_bdata.size + s_bstride + s_bidx.size * 4 + 4;
    // Starting a new lap of the stream overwrites older data, possibly the
    // block still bound: write it again with this batch.
    if (s_stream.wouldWrap(worst)) s_xfDirty = true;
    bool xfWritten = s_xfDirty;
    if (s_xfDirty) {
        memcpy(xfBlock, &g.xfMem[0], 256 * 4);
        memcpy(xfBlock + 256, &g.xfMem[0x400], 96 * 4);
        memcpy(xfBlock + 352, &g.xfMem[0x500], 256 * 4);
        memcpy(xfBlock + 608, &g.xfMem[0x600], 128 * 4);
        pc[np++] = {xfBlock, sizeof(xfBlock), size_t(s_uboAlign), 0};
        s_xfDirty = false;
    }
    const bool streamed = s_bidx.size != 0;
    if (streamed) {
        pc[np++] = {s_bdata.data, s_bdata.size, s_bstride, 0};
        pc[np++] = {s_bidx.data, s_bidx.size * 4, 4, 0};
    }
    if (np) s_stream.appendAll(pc, np);
    if (xfWritten) s_xfOffset = pc[0].at;
    if (xfWritten || g_glc.uniformBuffer == ~0u) {
        glBindBufferRange(GL_UNIFORM_BUFFER, 0, s_stream.buf, GLintptr(s_xfOffset), sizeof(xfBlock));
        g_glc.uniformBuffer = s_stream.buf;  // the range bind also sets the generic binding
    }
    const size_t vOff = streamed ? pc[np - 2].at : 0, iOff = streamed ? pc[np - 1].at : 0;
    const GLint streamBase = GLint(vOff / s_bstride);
    GLenum mode = s_bclass == PRIM_TRIS ? GL_TRIANGLES : s_bclass == PRIM_LINES ? GL_LINES : GL_POINTS;
    bool earlyFallback = !shaderHasEarlyFragmentTests() && earlyZWritesRejected();
    // The batch's index ranges in order: (VAO, byte offset of the indices, count, base vertex).
    struct Range {
        GLuint vao;
        size_t iOff;
        GLsizei count;
        GLint base;
#ifdef __ANDROID__
        const uint32_t* indices = nullptr;
#endif
    };
    static std::vector<Range> ranges;
    ranges.clear();
    if (s_segs.empty()) {
        ranges.push_back(Range{vaoForFormat(s_bfmt), iOff, GLsizei(s_bidx.size), streamBase});
    } else {
        for (const BatchSeg& sg : s_segs) {
            if (sg.arena) ranges.push_back(Range{vaoForFormat(s_bfmt), sg.iOff, GLsizei(sg.count), sg.baseVertex});
            else ranges.push_back(Range{vaoForFormat(s_bfmt), iOff + size_t(sg.first) * 4, GLsizei(sg.count), streamBase});
        }
    }
    #ifdef __ANDROID__
    if (android::needsRebasedIndices()) {
        for (Range& r : ranges) {
            if (r.iOff < s_stream.size) r.indices = s_bidx.data + (r.iOff - iOff) / 4;
            else {
                auto stored = s_arenaIndices.find(r.iOff);
                if (stored == s_arenaIndices.end() || stored->second.size() < size_t(r.count))
                    fatal("Android arena index range is unavailable");
                r.indices = stored->second.data();
            }
        }
    }
    if (s_pixActive && android::metricActive()) {
        const ShaderProgram* metricSp = shaderForCurrentState(false, s_bclass == PRIM_LINES, true);
        glcUseProgram(metricSp->prog);
        uploadUniforms(metricSp, texW, texH);
        const ShaderProgram* depthSp = nullptr;
        if (earlyFallback) {
            depthSp = shaderForCurrentState(true, s_bclass == PRIM_LINES);
            glcUseProgram(depthSp->prog);
            uploadUniforms(depthSp, texW, texH);
        }
        GLboolean masks[4];
        memcpy(masks, g_glc.cmask, sizeof masks);
        const GLboolean depthMask = g_glc.depthMask;
        const uint32_t alphaFunc = (g.bp[BP_ALPHACOMPARE] >> 16) & 0xFF;
        const bool ordered = earlyFallback || (depthMask && !earlyZWritesRejected() &&
                                               alphaFunc != 0x3F && alphaFunc != 0x7F);
        const size_t step = s_bclass == PRIM_TRIS ? 3 : s_bclass == PRIM_LINES ? 2 : 1;
        // Count each primitive against the depth before its colour draw. The
        // counter shader uses early tests, but cannot change colour or depth.
        // The ordinary shader then preserves GX's late/early Z semantics.
        for (const Range& r : ranges) {
            glcBindVertexArray(r.vao);
            android::IndexRange indicesRange(r.iOff, r.count, r.base, s_stream.buf, r.indices);
            if (!ordered) {
                // Opaque, early-Z and read-only-depth draws can count in the
                // colour shader itself, preserving the renderer's batching.
                glcUseProgram(metricSp->prog);
                glDrawElementsBaseVertex(mode, r.count, GL_UNSIGNED_INT,
                                         reinterpret_cast<const void*>(indicesRange.offset), indicesRange.base);
                continue;
            }
            for (size_t i = 0; i < size_t(r.count); i += step) {
                const void* indices = reinterpret_cast<const void*>(indicesRange.offset + i * 4);
                glcUseProgram(metricSp->prog);
                glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
                glDepthMask(GL_FALSE);
                glDrawElementsBaseVertex(mode, GLsizei(step), GL_UNSIGNED_INT, indices, indicesRange.base);
                glcUseProgram(sp->prog);
                glColorMask(masks[0], masks[1], masks[2], masks[3]);
                glDepthMask(earlyFallback ? GL_FALSE : depthMask);
                glDrawElementsBaseVertex(mode, GLsizei(step), GL_UNSIGNED_INT, indices, indicesRange.base);
                if (earlyFallback) {
                    glcUseProgram(depthSp->prog);
                    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
                    glDepthMask(GL_TRUE);
                    glDrawElementsBaseVertex(mode, GLsizei(step), GL_UNSIGNED_INT, indices, indicesRange.base);
                }
            }
        }
        glColorMask(masks[0], masks[1], masks[2], masks[3]);
        glDepthMask(depthMask);
        glcUseProgram(sp->prog);
    } else
    #endif
    if (earlyFallback) {
        const ShaderProgram* depthSp = shaderForCurrentState(true, s_bclass == PRIM_LINES);
        glcUseProgram(depthSp->prog);
        uploadUniforms(depthSp, texW, texH);
        GLboolean masks[4];
        memcpy(masks, g_glc.cmask, sizeof masks);
        size_t step = s_bclass == PRIM_TRIS ? 3 : s_bclass == PRIM_LINES ? 2 : 1;
        // Preserve primitive order and the original comparison (including LESS
        // and NOTEQUAL): colour tests the old depth, then an unconditional
        // fragment shader writes depth even where the alpha test discarded.
        // A batch-wide prepass followed by EQUAL would change overlapping draws.
        // Pixel metrics count the colour pass, whose pixels passed the alpha
        // test, and not the depth pass.
        for (const Range& r : ranges) {
            glcBindVertexArray(r.vao);
            for (size_t i = 0; i < size_t(r.count); i += step) {
                const void* indices = reinterpret_cast<const void*>(r.iOff + i * 4);
                glcUseProgram(sp->prog);
                glColorMask(masks[0], masks[1], masks[2], masks[3]);
                glDepthMask(GL_FALSE);
                glDrawElementsBaseVertex(mode, GLsizei(step), GL_UNSIGNED_INT, indices, r.base);
                pixMetricPause();
                glcUseProgram(depthSp->prog);
                glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
                glDepthMask(GL_TRUE);
                glDrawElementsBaseVertex(mode, GLsizei(step), GL_UNSIGNED_INT, indices, r.base);
                pixMetricResume();
            }
        }
        glColorMask(masks[0], masks[1], masks[2], masks[3]);
        glcUseProgram(sp->prog);
    } else {
#ifdef __ANDROID__
        // GLES 3.1 lacks base-vertex draws. Use the retained CPU indices for
        // ordinary draws too, avoiding a synchronous GPU index readback.
        for (const Range& r : ranges) {
            glcBindVertexArray(r.vao);
            android::IndexRange indicesRange(r.iOff, r.count, r.base, s_stream.buf, r.indices);
            glDrawElementsBaseVertex(mode, r.count, GL_UNSIGNED_INT,
                                     reinterpret_cast<const void*>(indicesRange.offset), indicesRange.base);
        }
#else
        // consecutive ranges on one VAO go in one multi-draw
        static std::vector<GLsizei> counts;
        static std::vector<const void*> offsets;
        static std::vector<GLint> bases;
        for (size_t i = 0; i < ranges.size();) {
            size_t j = i + 1;
            while (j < ranges.size() && ranges[j].vao == ranges[i].vao) j++;
            glcBindVertexArray(ranges[i].vao);
            if (j == i + 1) {
                glDrawElementsBaseVertex(mode, ranges[i].count, GL_UNSIGNED_INT,
                                         reinterpret_cast<const void*>(ranges[i].iOff), ranges[i].base);
            } else {
                counts.clear();
                offsets.clear();
                bases.clear();
                for (size_t k = i; k < j; k++) {
                    counts.push_back(ranges[k].count);
                    offsets.push_back(reinterpret_cast<const void*>(ranges[k].iOff));
                    bases.push_back(ranges[k].base);
                }
                glMultiDrawElementsBaseVertex(mode, counts.data(), GL_UNSIGNED_INT, offsets.data(), GLsizei(j - i),
                                              bases.data());
            }
            i = j;
        }
#endif
    }
    s_stats.draws++;
    s_flushes++;
    s_drawGen++;
    if (s_pixActive && s_bclass == PRIM_TRIS) s_pixTris += uint32_t((s_bidx.size + s_barenaIdx) / 3);
    if (traceFile()) {
        static std::vector<HostVertex> hv;
        hv.resize(s_bcount);
        for (uint32_t i = 0; i < s_bcount; i++) unpackVertex(s_bfmt, s_bdata.data + size_t(i) * s_bstride, defMtx, hv[i]);
        traceDraw(int(s_bclass), s_bcount, uint32_t(s_bidx.size), hv.data(), sp->id);
    }
    resetBatch();
    if (traceFile()) {
        traceProbe();
        glcInvalidate();
    }
}

// ------------------------------------------------------------------ EFB copies
// The framebuffer whose attachments hold the EFB's current pixels, and whose
// colour and depth are s_efbColor/s_efbDepth: with MSAA, the multisampled EFB
// is resolved into it first. Leaves the scissor test off.
// Only the region a read needs (x0, y0)-(x1, y1) in EFB pixels, with a
// margin for filtered sampling, and only its buffers are resolved; a region
// resolved since the last draw or clear is not resolved again (the texture
// copies, the display copy and the peeks of a frame each read the EFB).
static uint32_t s_resolvedGen = ~0u;  // s_drawGen at the last resolve
static GLbitfield s_resolvedBits = 0;
static int s_resolvedRect[4];

static GLuint efbReadFbo(int x0 = 0, int y0 = 0, int x1 = INT32_MAX, int y1 = INT32_MAX,
                         GLbitfield bits = GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT) {
    if (!s_msaa) return s_efbFbo;
    const int W = scaled(s_efbW), H = scaled(EFB_H);
    glDisable(GL_SCISSOR_TEST);
    x0 = std::max(0, x0 - 2);
    y0 = std::max(0, y0 - 2);
    x1 = std::min(W, x1 > W - 2 ? W : x1 + 2);
    y1 = std::min(H, y1 > H - 2 ? H : y1 + 2);
    if (s_resolvedGen == s_drawGen && (s_resolvedBits & bits) == bits && x0 >= s_resolvedRect[0] &&
        y0 >= s_resolvedRect[1] && x1 <= s_resolvedRect[2] && y1 <= s_resolvedRect[3])
        return s_efbResolveFbo;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, s_efbFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, s_efbResolveFbo);
    glBlitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, bits, GL_NEAREST);
    s_resolvedGen = s_drawGen;
    s_resolvedBits = bits;
    s_resolvedRect[0] = x0;
    s_resolvedRect[1] = y0;
    s_resolvedRect[2] = x1;
    s_resolvedRect[3] = y1;
    return s_efbResolveFbo;
}

// The goop pass (TPollutionManager) draws each pollution layer into the EFB,
// counts its pixels and copies it back over the layer's texture, every frame.
// At a scale that is not a whole number the round trip resamples the layer
// (a GameCube pixel spans one and a half), so the goop blurred away within
// seconds and drifted with the copy's half-pixel offset, and the game's own
// map of it followed: missing goop, goop that slid with the camera, bubbles
// from clean ground (#17). The pass draws into an EFB of the GameCube's own
// size instead, as on the GameCube; the layers are 256 texels or less, so
// nothing is lost to the eye. Whole-number scales keep the scaled EFB: they
// copy pixel for pixel already.

void nativeEfbBegin() {
    if (s_native.on) {
        s_native.ending = false;
        return;
    }
    if (!s_ready || s_scale == floorf(s_scale)) return;
    flushBatch();
    if (!s_nativeFbo) {
        glGenTextures(1, &s_nativeColor);
        glBindTexture(GL_TEXTURE_2D, s_nativeColor);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, s_efbW, EFB_H, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenTextures(1, &s_nativeDepth);
        glBindTexture(GL_TEXTURE_2D, s_nativeDepth);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, s_efbW, EFB_H, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT,
                     nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1, &s_nativeFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, s_nativeFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_nativeColor, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, s_nativeDepth, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) logmsg("native EFB framebuffer incomplete");
    }
    s_native = {true, false, s_efbFbo, s_efbColor, s_efbDepth, s_msaa, s_scale};
    s_efbFbo = s_nativeFbo;
    s_efbColor = s_nativeColor;
    s_efbDepth = s_nativeDepth;
    s_msaa = 0;
    s_scale = 1;
    s_resolvedGen = ~0u;
    glcInvalidate();
}

// Back to the scaled EFB. The game copies each layer back right after the
// pass, from another object: nativeEfbEnd leaves the native EFB bound until
// that copy has taken it (executeCopy), or until anything is drawn first.
static void nativeEfbRestore() {
    s_efbFbo = s_native.fbo;
    s_efbColor = s_native.color;
    s_efbDepth = s_native.depth;
    s_msaa = s_native.msaa;
    s_scale = s_native.scale;
    s_native.on = s_native.ending = false;
    s_resolvedGen = ~0u;
    glcInvalidate();
}

void nativeEfbEnd() {
    if (!s_native.on) return;
    flushBatch();
    s_native.ending = true;
}

// x0 and x1: the scaled EFB's columns, as copyEfb works them out.
static void clearRect(int x0, int x1, int y, int h) {
    uint32_t ar = g.bp[BP_CLEAR_AR], gb = g.bp[BP_CLEAR_GB], z = g.bp[BP_CLEAR_Z];
    glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
    glEnable(GL_SCISSOR_TEST);
    glScissor(x0, scaled(y), x1 - x0, scaled(y + h) - scaled(y));
    bool hasAlpha = (g.bp[BP_PE_CONTROL] & 7) == 1;
    // Copy clears obey the PE write enables, just like ordinary EFB writes.
    // A copy clear in RGB8 must not overwrite the backing alpha used later by
    // SMS's RGBA6 shadow volumes, nor may a disabled alpha/depth write do so.
    bool colour = (g.bp[BP_CMODE0] >> 3) & 1;
    bool alpha = hasAlpha && ((g.bp[BP_CMODE0] >> 4) & 1);
    glColorMask(colour, colour, colour, alpha);
    glDepthMask((g.bp[BP_ZMODE] >> 4) & 1);
    glClearColor(float(ar & 255) / 255.0f, float((gb >> 8) & 255) / 255.0f, float(gb & 255) / 255.0f,
                 hasAlpha ? float((ar >> 8) & 255) / 255.0f : 1.0f);
    glClearDepth(double(z & 0xFFFFFF) / 16777215.0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
}

// GXCopyTex stores into main memory on the hardware, and the game reads some
// copies back on the CPU: Delfino's goop is cleaned by drawing into the EFB
// and copying into the pollution texture, whose bytes TPollutionLayer::
// isPolluted then reads. So every texture copy is also read back and stored
// in RAM in its GX layout (the GL copy stays as the fast path for sampling).
// SMS_GX_COPY_WRITEBACK=0 turns this off.
static bool copyWriteBackEnabled() {
    static int on = -1;
    if (on < 0) {
        const char* e = getenv("SMS_GX_COPY_WRITEBACK");
        on = !(e && e[0] == '0');
    }
    return on != 0;
}

// Stores a write-back's encoded bytes (n of them) at its destination.
static void storeEncoded(const uint8_t* enc, const void* dest, uint32_t n) {
    static int logCopies = -1;
    if (logCopies < 0) logCopies = getenv("SMS_GX_COPY_LOG") ? atoi(getenv("SMS_GX_COPY_LOG")) : 0;
    if (logCopies > 0) {
        uint32_t changed = 0;
        for (uint32_t i = 0; i < n; i++) changed += enc[i] != static_cast<const uint8_t*>(dest)[i];
        logmsg("copy write-back %p: %u bytes, %u changed", dest, n, changed);
        logCopies--;
    }
    memcpy(const_cast<void*>(dest), enc, n);
    memoryWritten(dest, n);
    textureInvalidateRange(dest, n);
}

// Encodes the copy in `tex` (ow x oh) as tw x th texels of `layout` into
// s_encTex and leaves s_encFbo bound for reading; returns the rows (of 256
// pixels, 1 KiB) that hold the `bytes`.
static int encodeCopy(GLuint tex, int ow, int oh, int tw, int th, uint32_t layout, uint32_t bytes) {
    const int rows = int((bytes + 1023) / 1024);
    if (rows > s_encRows) {
        if (!s_encTex) glGenTextures(1, &s_encTex);
        glBindTexture(GL_TEXTURE_2D, s_encTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 256, rows, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, s_encFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_encTex, 0);
        s_encRows = rows;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, s_encFbo);
    glViewport(0, 0, 256, rows);
    glUseProgram(s_encProg);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glBindSampler(0, 0);
    const GLint size[4] = {tw, th, ow, oh};
    glUniform4iv(s_encUSize, 1, size);
    glUniform1i(s_encULayout, GLint(layout));
    glUniform1i(s_encUBytes, GLint(bytes));
    glBindVertexArray(s_copyVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(s_vao);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, s_encFbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    return rows;
}

// SMS_GX_COPY_VERIFY=1: every write-back is also encoded on the CPU, from a
// synchronous read of the copy, and differences are logged.
static bool copyVerify() {
    static int on = -1;
    if (on < 0) on = getenv("SMS_GX_COPY_VERIFY") && getenv("SMS_GX_COPY_VERIFY")[0] == '1';
    return on != 0;
}

static void verifyEncode(GLuint tex, int ow, int oh, int tw, int th, uint32_t layout, const uint8_t* gpu, uint32_t n) {
    std::vector<uint8_t> px(size_t(ow) * oh * 4), texels(size_t(tw) * th * 4), enc(n);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, s_tmpFbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, ow, oh, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    for (int y = 0; y < th; y++)
        for (int x = 0; x < tw; x++)
            memcpy(&texels[(size_t(y) * tw + x) * 4], &px[(size_t(y) * oh / th * ow + size_t(x) * ow / tw) * 4], 4);
    encodeTexture(texels.data(), layout, uint32_t(tw), uint32_t(th), enc.data());
    uint32_t diff = 0, first = n;
    for (uint32_t i = 0; i < n; i++)
        if (enc[i] != gpu[i]) {
            if (first == n) first = i;
            diff++;
        }
    if (diff)
        logmsg("copy verify: layout %u, %dx%d texels of a %dx%d copy: %u of %u bytes differ, the first at %u (GPU %02x, CPU %02x)",
               layout, tw, th, ow, oh, diff, n, first, gpu[first], enc[first]);
    else
        logmsg("copy verify: layout %u, %dx%d texels of a %dx%d copy: %u bytes match", layout, tw, th, ow, oh, n);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, s_encFbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
}

// A texture copy's write-back is read into a pixel buffer and stored one
// frame later, when the GPU has long finished it (or earlier, when the next
// copy to the same place comes). It is dropped when a newer copy of the same
// frame supersedes it or when the destination's bytes
// changed in the meantime (the CPU wrote them, or a stage load reused the
// memory). A cache flush of the range alone does not drop it: games flush
// copy destinations without writing them.
// With the GL thread, it reads a frame's write-backs once that frame is
// shown (prefetchWriteBacks), so storing one later does not wait for it.
struct WbFetch {
    std::atomic<int> ready{0};
    bool ok = false;
    std::vector<uint8_t> bytes;
};
struct PendingWriteBack {
    const void* dest;
    uint32_t bytes;
    uint64_t hash;
    GLuint pbo;
    GLsync fence;
    uint32_t readBytes;  // the pixel buffer's size
    uint32_t frame;
    bool cancelled;
    WbFetch* fetch;      // the GL thread's read of it, or null
};
static std::vector<PendingWriteBack> s_writeBacks;
static std::vector<GLuint> s_freePbos;

static void resolveWriteBack(PendingWriteBack& w) {
    if (w.fetch) {
        {
            GxTimer tw(&s_waitSeconds);
            glt::waitFlag(w.fetch->ready);
        }
        if (!w.cancelled && w.fetch->ok && hashBytes(w.dest, w.bytes) == w.hash)
            storeEncoded(w.fetch->bytes.data(), w.dest, w.bytes);
        delete w.fetch;
        w.fetch = nullptr;
    } else if (!w.cancelled && hashBytes(w.dest, w.bytes) == w.hash) {
        const void* px;
        {
            GxTimer tw(&s_waitSeconds);
            glClientWaitSync(w.fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, w.pbo);
            px = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, GLsizeiptr(w.readBytes), GL_MAP_READ_BIT);
        }
        if (px) storeEncoded(static_cast<const uint8_t*>(px), w.dest, w.bytes);
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    }
    glDeleteSync(w.fence);
    s_freePbos.push_back(w.pbo);
}

// Stores the write-backs issued before this frame (all of them with `all`).
static void resolveWriteBacks(bool all) {
    size_t keep = 0;
    for (size_t i = 0; i < s_writeBacks.size(); i++) {
        PendingWriteBack& w = s_writeBacks[i];
        if (!all && !w.cancelled && w.frame >= s_frameNo) s_writeBacks[keep++] = w;
        else resolveWriteBack(w);
    }
    s_writeBacks.resize(keep);
}

// tw x th: the copy's size in texels as the game sees it. `always`: written
// back even with SMS_GX_COPY_WRITEBACK=0 (R8 copies are the goop map, whose
// bytes Mario, NPCs and goop enemies read: without them cleaned goop stays
// in the game, invisible).
static void writeBackCopy(const void* dest, GLuint tex, int ow, int oh, int tw, int th, uint32_t layout,
                          bool always = false) {
    if (!dest || !(always || copyWriteBackEnabled())) return;
    if (tw < 1 || th < 1) return;
    uint32_t bytes = texLevelBytes(layout, uint32_t(tw), uint32_t(th));
    if (!bytes) return;
    efbCopySetBytes(dest, bytes);
    const int rows = encodeCopy(tex, ow, oh, tw, th, layout, bytes);
    const uint32_t readBytes = uint32_t(rows) * 1024;
    if (!asyncReads() || copyVerify()) {
        static std::vector<uint8_t> px;
        px.resize(readBytes);
        glReadPixels(0, 0, 256, rows, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        if (copyVerify()) verifyEncode(tex, ow, oh, tw, th, layout, px.data(), bytes);
        if (!asyncReads()) {
            s_syncReads++;
            storeEncoded(px.data(), dest, bytes);
            return;
        }
    }
    // A copy to the same place supersedes one of this frame; one from an
    // earlier frame (most games copy to the same buffer every frame) is
    // stored first, as the CPU may have read it in between.
    size_t keep = 0;
    for (size_t i = 0; i < s_writeBacks.size(); i++) {
        PendingWriteBack& w = s_writeBacks[i];
        if (w.dest != dest) s_writeBacks[keep++] = w;
        else if (w.frame < s_frameNo) resolveWriteBack(w);
        else {
            w.cancelled = true;
            resolveWriteBack(w);
        }
    }
    s_writeBacks.resize(keep);
    GLuint pbo;
    if (!s_freePbos.empty()) {
        pbo = s_freePbos.back();
        s_freePbos.pop_back();
    } else {
        glGenBuffers(1, &pbo);
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
    glBufferData(GL_PIXEL_PACK_BUFFER, GLsizeiptr(readBytes), nullptr, GL_STREAM_READ);
    glReadPixels(0, 0, 256, rows, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    PendingWriteBack w{dest, bytes, hashBytes(dest, bytes), pbo, glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0),
                       readBytes, s_frameNo, false, nullptr};
    s_writeBacks.push_back(w);
}

// The GL thread waits for the GPU's copies and reads them as soon as the frame
// that made them is shown, while the game goes on (see gx_glthread.h).
static void bindPack(GLuint pbo, GLint* prev) {  // on the GL thread
    if (prev) glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, prev);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
}

static void prefetchWriteBacks() {
    for (PendingWriteBack& w : s_writeBacks) {
        if (w.fetch || w.cancelled) continue;
        WbFetch* f = new WbFetch;
        f->bytes.resize(w.bytes);
        const GLsync fence = w.fence;
        const GLuint pbo = w.pbo;
        const uint32_t n = w.bytes;
        glt::post([=] {
            glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull);
            GLint prev = 0;
            bindPack(pbo, &prev);
            if (const void* px = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, GLsizeiptr(n), GL_MAP_READ_BIT)) {
                memcpy(f->bytes.data(), px, n);
                f->ok = true;
                glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
            }
            bindPack(GLuint(prev), nullptr);
            glt::signalFlag(f->ready);
        });
        w.fetch = f;
    }
}

static void prefetchPeeks();
#ifdef __ANDROID__
static void pointsFrameShown();
#endif
// GXCopyDisp / GXCopyTex: the EFB rectangle the BP registers name is copied
// out, to the XFB (a display copy, shown by GXPC_PresentXFB) or into a
// texture the game reads back (a texture copy: an EFB-sized texture of ours,
// and the pixels encoded into the game's memory at the destination, as the
// GameCube would have them: with copy_writeback on, and always for colour
// copies of format 8). Clears the
// rectangle afterwards when the copy asks for it. With widescreen, a copy
// that spans the whole 4:3 width takes the whole wide EFB.
static void copyEfb(uint32_t ctrl) {
    GxTimer timer;
    GxTimer tc(&s_copySeconds);
    uint32_t src = g.bp[BP_COPY_SRC_TL], size = g.bp[BP_COPY_SRC_WH];
    int x = int(src & 0x3FF), y = int((src >> 10) & 0x3FF);
    int w = int(size & 0x3FF) + 1, h = int((size >> 10) & 0x3FF) + 1;
    const void* dest = g.copyDest;
    bool disp = (ctrl >> 14) & 1;
    bool clear = (ctrl >> 11) & 1;
    if (traceFile()) traceCopy(disp, x, y, w, h, dest, ctrl);
    // widescreen: a full-width copy takes the whole EFB, others the centred 4:3 part
    int gw = w;
    bool stretched = false;
    if (s_efbW != EFB_W) {
        if (x <= 0 && x + w >= EFB_W) {
            x = 0;
            w = s_efbW;
        } else if (s_stretch2D) {  // a fader's pieces of the frame: stretched like its drawing
            stretched = true;
        } else {
            x += s_ox;
        }
    }
    // its columns in the scaled EFB, and its left edge and width in EFB pixels
    int x0 = scaled(x), x1 = scaled(x + w);
    float fx = float(x), fw = float(w);
    if (stretched) {
        // A stretched piece's edges fall inside widened pixels, and its quad
        // covers the scaled columns up to them, so the copy and its clear take
        // those columns too. Taken to whole widened pixels, they went a column
        // past the quad the circle wipe's blur draws its centre cell back with,
        // which left a black line down the opening iris above 1x.
        // The columns are the ones the quad rasterizes: those whose centre
        // lies at or right of the left edge and left of the right edge. An
        // edge on a half pixel (x 344 at 13:6 and 1.5x: 838.5) rounded up
        // took one column more than the quad, cleared and never drawn over.
        x0 = int(ceilf(float(x) * s_wide * s_scale - 0.5f));
        x1 = int(ceilf(float(x + w) * s_wide * s_scale - 0.5f));
        fx = float(x0) / s_scale;
        fw = float(x1 - x0) / s_scale;
    }
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_COLOR_LOGIC_OP);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_CLIP_DISTANCE0);
    glDisable(GL_CLIP_DISTANCE0 + 1);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    if (disp) {
        Xfb& xfb = s_xfbs[dest];
        const int y0 = scaled(y), sw = x1 - x0, sh = scaled(y + h) - y0;
        if (!xfb.tex || xfb.w != sw || xfb.h != sh) {
            if (!xfb.tex) glGenTextures(1, &xfb.tex);
            glBindTexture(GL_TEXTURE_2D, xfb.tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, sw, sh, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            xfb.w = sw;
            xfb.h = sh;
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, efbReadFbo(x0, y0, x0 + sw, y0 + sh, GL_COLOR_BUFFER_BIT));
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, s_tmpFbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, xfb.tex, 0);
        glBlitFramebuffer(x0, y0, x0 + sw, y0 + sh, 0, 0, sw, sh, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        s_lastXfb = dest;
        s_stats.efbCopies++;
    } else {
        uint32_t fmt = ((ctrl >> 3) & 1) << 3 | ((ctrl >> 4) & 7);
        bool intensity = ((ctrl >> 15) & 3) == 3;
        bool zcopy = (g.bp[BP_PE_CONTROL] & 7) == 3;
        bool half = (ctrl >> 9) & 1;
        int ow = x1 - x0, oh = scaled(y + h) - scaled(y);
        if (half) {
            ow /= 2;
            oh /= 2;
        }
        int tw = half ? gw / 2 : gw, th = half ? h / 2 : h;
        if (ow < 1) ow = 1;
        if (oh < 1) oh = 1;
        int cw = 0, chh = 0;
        GLuint tex = efbCopyLookup(dest, &cw, &chh);
        if (!tex || cw != ow || chh != oh) {
            if (!tex) glGenTextures(1, &tex);
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ow, oh, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        }
        uint32_t mode = fmt | (intensity ? 16u : 0u) | (zcopy ? 32u : 0u);
        efbCopyRegister(dest, tex, ow, oh, mode);
        // s_efbColor (s_efbDepth for depth copies), sampled below, holds the current EFB
        efbReadFbo(x0, scaled(y), x1, scaled(y + h), zcopy ? GL_DEPTH_BUFFER_BIT : GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, s_tmpFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        glViewport(0, 0, ow, oh);
        glUseProgram(s_copyProg);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, s_efbDepth);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, s_efbColor);
        float rect[4] = {fx / float(s_efbW), float(y) / EFB_H, fw / float(s_efbW), float(h) / EFB_H};
        glUniform4fv(s_copyURect, 1, rect);
        glUniform1i(s_copyUMode, GLint(mode));
        glUniform1i(s_copyUAlphaOne, (g.bp[BP_PE_CONTROL] & 7) != 1);
        glBindVertexArray(s_copyVao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(s_vao);
        s_stats.efbCopies++;
        writeBackCopy(dest, tex, ow, oh, tw, th, copyLayout(fmt, zcopy), !zcopy && fmt == 8);
    }
    if (clear) clearRect(x0, x1, y, h);
    glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
    if (clear) s_drawGen++;
    if (disp) {
        resolveWriteBacks(false);
        hiresEndFrame();
        s_frameNo++;
        g_displayFrames++;
        traceFrameAdvance();
        statsFrame();
        hitchFrame();
        s_lastFrameStats = s_stats;
        s_stats.draws = s_stats.vertices = s_stats.efbCopies = 0;
    }
}

void executeCopy(uint32_t ctrl) {
    if (!s_ready) return;
    flushBatch();
    glcInvalidate();  // copies, clears and write-backs set GL state directly
    pixMetricPause();
    struct Resume { ~Resume() { pixMetricResume(); } } resume;
    const void* dest = g.copyDest;
    bool disp = (ctrl >> 14) & 1;
    if (disp && s_native.on) nativeEfbRestore();
    copyEfb(ctrl);
    if (s_native.ending) nativeEfbRestore();
    // presenting (the hook) is timed apart from sms_gx: GXPC_GetTimes
    if (disp && g_displayCopyHook) g_displayCopyHook(dest);
#ifdef __ANDROID__
    if (disp) pointsFrameShown();
#endif
    if (disp && glt::active()) {  // the frame is shown: the GL thread reads its results (see gx_glthread.h)
        prefetchWriteBacks();
        prefetchPeeks();
        prefetchMetrics();
    }
}

}  // namespace gx

using namespace gx;

extern "C" {

int GXPC_Init(GXPCGetProcFn getProc, float efbScale) {
    if (!gl::load(getProc)) return 0;
    resetState();
    rendererInit(efbScale);
    hiresEnabled();  // immutable index/format support published before loader threads run
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

void GXPC_PrefetchResource(const void* data, uint32_t size, const char* name) {
    hiresPrefetchResource(data, size, name);
}

void GXPC_PreloadTextures(void) {
    if (!s_ready) return;
    flushBatch();
    glcInvalidate();
    hiresPreload();
}

void GXPC_PrepareStage(int stage) {
    if (!s_ready) return;
    flushBatch();
    glcInvalidate();
    shaderPrepareStage(stage);
    hiresPreload();
}

void GXPC_Shutdown(void) {
    if (!s_ready) return;
    textureShutdown();
    shaderShutdown();
    layersShutdown();
    invalidateOverlay();
    for (auto& kv : s_xfbs) glDeleteTextures(1, &kv.second.tex);
    s_xfbs.clear();
    s_ready = false;
    releaseGlThread();
}

void GXPC_InvalidateRange(const void* p, uint32_t size) {
    memoryWritten(p, size);
    if (copyWriteBackEnabled()) textureCpuWrote(p, size);
    else textureInvalidateRange(p, size);
}

}  // extern "C"

namespace gx {
// GXPeekARGB/GXPeekZ: the sun's lens-flare test peeks 17 depths a frame and
// Mario's occlusion test one colour. Peeks with no drawing in between form a
// group; the first peek of a group starts an asynchronous read of the whole
// buffer, and the group is answered from the same group's read one frame
// earlier (synchronously the first time a group appears).
enum { kPeekGroups = 8 };
struct PeekSnap {
    GLuint pbo = 0;
    GLsync fence = 0;
    uint32_t frame = ~0u;
    const uint8_t* map = nullptr;
    // the GL thread's mapping of it (prefetchPeeks), handed over through ready
    bool fetching = false;
    std::atomic<int> ready{0};
    const uint8_t* fetched = nullptr;
};
static PeekSnap s_peek[2][kPeekGroups][2];  // [frame parity][group][colour, depth]
static uint32_t s_peekDrawGen = ~0u, s_peekFrame = ~0u, s_peekIssued = 0;
static int s_peekGroup = -1;

// A snapshot the GL thread mapped (prefetchPeeks): its mapping, once ready.
static void takeFetchedPeek(PeekSnap& p) {
    if (!p.fetching) return;
    {
        GxTimer tw(&s_waitSeconds);
        glt::waitFlag(p.ready);
    }
    p.map = p.fetched;
    p.fetching = false;
}

// Maps the snapshots of the frame just shown on the GL thread, which waits
// for the GPU there instead of the game at its next peek.
static void prefetchPeeks() {
    const uint32_t frame = s_frameNo - 1;
    const GLsizeiptr bytes = GLsizeiptr(s_efbW) * EFB_H * 4;
    for (int gi = 0; gi < kPeekGroups; gi++)
        for (int t = 0; t < 2; t++) {
            PeekSnap& p = s_peek[frame & 1][gi][t];
            if (!p.pbo || p.frame != frame || p.map || p.fetching) continue;
            p.fetching = true;
            p.ready.store(0);
            PeekSnap* ps = &p;
            const GLsync fence = p.fence;
            const GLuint pbo = p.pbo;
            glt::post([=] {
                glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull);
                GLint prev = 0;
                bindPack(pbo, &prev);
                ps->fetched = static_cast<const uint8_t*>(glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, bytes, GL_MAP_READ_BIT));
                bindPack(GLuint(prev), nullptr);
                glt::signalFlag(ps->ready);
            });
        }
}

#ifdef __ANDROID__
// GLES cannot read GL_DEPTH_COMPONENT with glReadPixels. Sample the EFB
// depth texture into exact RGB bytes, then use the normal RGBA/PBO read path.
static GLuint androidDepthPeekSource() {
    static GLuint fbo = 0, texture = 0;
    static int width = 0;
    efbReadFbo(0, 0, INT32_MAX, INT32_MAX, GL_DEPTH_BUFFER_BIT);
    if (!fbo) { glGenFramebuffers(1, &fbo); glGenTextures(1, &texture); }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    if (width != s_efbW) {
        width = s_efbW;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, EFB_H, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE); glDisable(GL_COLOR_LOGIC_OP);
    glDisable(GL_CLIP_DISTANCE0); glDisable(GL_CLIP_DISTANCE1);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(0, 0, width, EFB_H);
    glUseProgram(s_copyProg);
    glActiveTexture(GL_TEXTURE0); glBindSampler(0, 0);
    glBindTexture(GL_TEXTURE_2D, s_efbColor);
    glActiveTexture(GL_TEXTURE1); glBindSampler(1, 0);
    glBindTexture(GL_TEXTURE_2D, s_efbDepth);
    const float rect[4] = {0, 0, 1, 1};
    glUniform4fv(s_copyURect, 1, rect);
    glUniform1i(s_copyUMode, 32 | 6);
    glBindVertexArray(s_copyVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    return fbo;
}
#endif

#ifdef __ANDROID__
// Android answers peeks without reading the EFB back. Reading it back where
// the game peeks (the snapshots below) made Adreno's driver flush the frame
// half drawn, after a full-screen pass for depth: Mario's occlusion test and
// the sun's 17 lens-flare probes took 3 to 7 ms of a 16.7 ms frame. Instead a
// peek draws just its pixel, on the GPU, into a slot of a storage buffer of
// its frame, which is read once a fence after the frame has passed. A peek
// is answered from the latest frame read: that frame's value for the same
// pixel, or else for the peek in the same place in its order (as the
// snapshots answer from the frame before). Only a peek with neither waits.
enum { kPointSlots = 64, kPointFrames = 3 };
struct PointFrame {
    uint32_t frame = ~0u;
    int used = 0;
    int16_t x[kPointSlots], y[kPointSlots];
    bool depth[kPointSlots];
    GLuint buffer = 0;   // kPointSlots values, then one for peeks past them
    GLsync fence = 0;    // after the frame's peeks
    bool read = false;   // value holds the buffer's
    uint32_t value[kPointSlots];
};
static PointFrame s_points[kPointFrames];
static GLuint s_pointProg, s_pointTex, s_pointFbo;
static GLint s_pointUSrc, s_pointUSlot, s_pointUDepth;
static int s_pointState = 0;  // 1 ready, -1 unavailable (the snapshots answer), 0 not tried
static uint32_t s_pointAnswerFrame = ~0u;
static const PointFrame* s_pointAnswers = nullptr;

// The value is the pixel's bytes as the snapshots hold them: R, G, B, A, or
// for depth its high, middle and low bytes.
static const char* kPointFs = R"(#version 330 core
layout(std430, binding = 1) buffer SmsPeeks { uint u_values[]; };
uniform sampler2D u_color;
uniform sampler2D u_depth;
uniform ivec2 u_src;
uniform int u_slot;
uniform int u_isDepth;
out vec4 o_color;
void main() {
  uint v;
  if (u_isDepth != 0) {
    uint z = uint(clamp(texelFetch(u_depth, u_src, 0).r, 0.0, 1.0) * 16777215.0);
    v = ((z >> 16) & 255u) | (((z >> 8) & 255u) << 8) | ((z & 255u) << 16) | 0xFF000000u;
  } else {
    uvec4 c = uvec4(round(clamp(texelFetch(u_color, u_src, 0), 0.0, 1.0) * 255.0));
    v = c.r | (c.g << 8) | (c.b << 16) | (c.a << 24);
  }
  u_values[u_slot] = v;
  o_color = vec4(0.0);
}
)";

static bool pointsReady() {
    if (s_pointState) return s_pointState > 0;
    s_pointState = -1;
    s_pointProg = compileProgram(kCopyVs, kPointFs);
    GLint linked = 0;
    glGetProgramiv(s_pointProg, GL_LINK_STATUS, &linked);
    if (!linked) {
        logmsg("peeks: the point shader did not link, reading the EFB back instead");
        return false;
    }
    glUseProgram(s_pointProg);
    glUniform1i(glGetUniformLocation(s_pointProg, "u_color"), 0);
    glUniform1i(glGetUniformLocation(s_pointProg, "u_depth"), 1);
    s_pointUSrc = glGetUniformLocation(s_pointProg, "u_src");
    s_pointUSlot = glGetUniformLocation(s_pointProg, "u_slot");
    s_pointUDepth = glGetUniformLocation(s_pointProg, "u_isDepth");
    glGenTextures(1, &s_pointTex);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s_pointTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glGenFramebuffers(1, &s_pointFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, s_pointFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_pointTex, 0);
    const bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
    if (!complete) {
        logmsg("peeks: the point framebuffer is incomplete, reading the EFB back instead");
        return false;
    }
    for (PointFrame& p : s_points) {
        glGenBuffers(1, &p.buffer);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, p.buffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (kPointSlots + 1) * 4, nullptr, GL_DYNAMIC_READ);
    }
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    s_pointState = 1;
    return true;
}

static void drawPoint(const PointFrame& p, int slot, int x, int y, bool depth) {
    const GLint src[2] = {std::min(int((float(x) + 0.5f) * s_scale), scaled(s_efbW) - 1),
                          std::min(int((float(y) + 0.5f) * s_scale), scaled(EFB_H) - 1)};
    glBindFramebuffer(GL_FRAMEBUFFER, s_pointFbo);
    glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE); glDisable(GL_COLOR_LOGIC_OP);
    glDisable(GL_CLIP_DISTANCE0); glDisable(GL_CLIP_DISTANCE1);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(0, 0, 1, 1);
    glUseProgram(s_pointProg);
    glActiveTexture(GL_TEXTURE0); glBindSampler(0, 0);
    glBindTexture(GL_TEXTURE_2D, s_efbColor);
    glActiveTexture(GL_TEXTURE1); glBindSampler(1, 0);
    glBindTexture(GL_TEXTURE_2D, s_efbDepth);
    glUniform2iv(s_pointUSrc, 1, src);
    glUniform1i(s_pointUSlot, slot);
    glUniform1i(s_pointUDepth, depth ? 1 : 0);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, p.buffer);
    glBindVertexArray(s_copyVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
}

// Copies a frame's values out of its buffer once its fence has passed (or,
// with `wait`, once it does).
static bool readPoints(PointFrame& p, bool wait) {
    if (p.read) return true;
    if (!p.fence) return false;
    const GLenum r = glClientWaitSync(p.fence, wait ? GL_SYNC_FLUSH_COMMANDS_BIT : 0, wait ? 1000000000ull : 0);
    if (r != GL_ALREADY_SIGNALED && r != GL_CONDITION_SATISFIED) return false;
    glDeleteSync(p.fence);
    p.fence = 0;
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, p.buffer);
    const void* m = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(p.used) * 4, GL_MAP_READ_BIT);
    if (m) {
        memcpy(p.value, m, size_t(p.used) * 4);
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    }
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    p.read = m != nullptr;
    return p.read;
}

// The frame this frame's peeks are answered from: the last one whose values
// are in without waiting, else the oldest kept, waited for.
static const PointFrame* pointAnswers() {
    if (s_pointAnswerFrame == s_frameNo) return s_pointAnswers;
    s_pointAnswerFrame = s_frameNo;
    s_pointAnswers = nullptr;
    for (uint32_t back = 1; back < kPointFrames; back++) {
        PointFrame& p = s_points[(s_frameNo - back) % kPointFrames];
        if (p.frame == s_frameNo - back && readPoints(p, false)) return s_pointAnswers = &p;
    }
    for (uint32_t back = kPointFrames - 1; back >= 1; back--) {
        PointFrame& p = s_points[(s_frameNo - back) % kPointFrames];
        if (p.frame == s_frameNo - back && readPoints(p, true)) return s_pointAnswers = &p;
    }
    return nullptr;
}

static uint32_t pointPeek(int x, int y, bool depth) {
    if (x < 0 || y < 0 || x >= s_efbW || y >= EFB_H) return 0;
    PointFrame& cur = s_points[s_frameNo % kPointFrames];
    if (cur.frame != s_frameNo) {
        if (cur.fence) glDeleteSync(cur.fence);
        cur.fence = 0;
        cur.frame = s_frameNo;
        cur.used = 0;
        cur.read = false;
    }
    const int index = cur.used;
    if (index < kPointSlots) {
        cur.x[index] = int16_t(x);
        cur.y[index] = int16_t(y);
        cur.depth[index] = depth;
        cur.used++;
    }
    const int slot = std::min(index, int(kPointSlots));
    drawPoint(cur, slot, x, y, depth);
    if (asyncReads())
        if (const PointFrame* p = pointAnswers()) {
            for (int i = 0; i < p->used; i++)
                if (p->depth[i] == depth && p->x[i] == x && p->y[i] == y) return p->value[i];
            if (index < p->used && p->depth[index] == depth) return p->value[index];
        }
    // nothing earlier to answer from: this peek's own value
    GxTimer tw(&s_waitSeconds);
    s_syncReads++;
    android::memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, cur.buffer);
    uint32_t v = 0;
    if (const void* m = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, GLintptr(slot) * 4, 4, GL_MAP_READ_BIT)) {
        memcpy(&v, m, 4);
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    }
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    return v;
}

// The display copy: a fence after the peeks of the frame just shown.
static void pointsFrameShown() {
    if (s_pointState <= 0) return;
    PointFrame& p = s_points[(s_frameNo - 1) % kPointFrames];
    if (p.frame != s_frameNo - 1 || !p.used || p.fence || p.read) return;
    android::memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    p.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
}
#endif

static uint32_t peekSync(int x, int y, bool depth) {
    glcInvalidate();
    GxTimer tw(&s_waitSeconds);
    s_syncReads++;
    uint32_t v = 0;
    uint8_t px[4] = {0, 0, 0, 0};
#ifdef __ANDROID__
    if (depth) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, androidDepthPeekSource());
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
        return uint32_t(px[0]) | uint32_t(px[1]) << 8 | uint32_t(px[2]) << 16;
    }
#endif
    glBindFramebuffer(GL_FRAMEBUFFER, efbReadFbo(scaled(x), scaled(y), scaled(x) + 1, scaled(y) + 1,
                                                 depth ? GL_DEPTH_BUFFER_BIT : GL_COLOR_BUFFER_BIT));
    if (depth) {
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(scaled(x), scaled(y), 1, 1, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, &v);
        return v;
    }
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(scaled(x), scaled(y), 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    return uint32_t(px[0]) | uint32_t(px[1]) << 8 | uint32_t(px[2]) << 16 | uint32_t(px[3]) << 24;
}

// A frame's peek snapshot is read at 1x: the EFB is first blitted (nearest)
// into this buffer, so a read stays 1.3 MiB. At scale 1 too: reading the EFB
// itself into a pixel buffer, while the game goes on drawing into it, garbled
// the whole picture into blocks on Intel's Windows driver (HD 530, 21.20).
static GLuint s_peekFbo, s_peekColor, s_peekDepth;

static GLuint peekSource(bool depth) {
    const GLbitfield bits = depth ? GL_DEPTH_BUFFER_BIT : GL_COLOR_BUFFER_BIT;
    if (!s_peekFbo) {
        glGenRenderbuffers(1, &s_peekColor);
        glBindRenderbuffer(GL_RENDERBUFFER, s_peekColor);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, s_efbW, EFB_H);
        glGenRenderbuffers(1, &s_peekDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, s_peekDepth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, s_efbW, EFB_H);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glGenFramebuffers(1, &s_peekFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, s_peekFbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, s_peekColor);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, s_peekDepth);
    }
    glDisable(GL_SCISSOR_TEST);
    const GLuint src = efbReadFbo(0, 0, INT32_MAX, INT32_MAX, bits);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, src);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, s_peekFbo);
    glBlitFramebuffer(0, 0, scaled(s_efbW), scaled(EFB_H), 0, 0, s_efbW, EFB_H, bits, GL_NEAREST);
    return s_peekFbo;
}

// Returns the raw texel: RGBA bytes packed little-endian, or the 32-bit depth.
static uint32_t peekRaw(int x, int y, bool depth) {
    if (s_efbW != EFB_W && x >= 0) x = int((float(x) + 0.5f) * s_wide);  // the game camera's coordinates: stretched
    flushBatch();
    glcInvalidate();
    GxTimer tp(&s_peekSeconds);
#ifdef __ANDROID__
    if (pointsReady()) return pointPeek(x, y, depth);
#endif
    if (!asyncReads() || x < 0 || y < 0 || x >= s_efbW || y >= EFB_H) return peekSync(x, y, depth);
    if (s_peekDrawGen != s_drawGen || s_peekFrame != s_frameNo) {
        if (s_peekFrame != s_frameNo) s_peekGroup = -1;
        s_peekGroup++;
        s_peekDrawGen = s_drawGen;
        s_peekFrame = s_frameNo;
        s_peekIssued = 0;
    }
    if (s_peekGroup >= kPeekGroups) return peekSync(x, y, depth);
    const GLsizeiptr bytes = GLsizeiptr(s_efbW) * EFB_H * 4;
    int cur = s_frameNo & 1, t = depth ? 1 : 0;
    if (!(s_peekIssued & (1u << t))) {
        s_peekIssued |= 1u << t;
        PeekSnap& now = s_peek[cur][s_peekGroup][t];
        takeFetchedPeek(now);
        if (!now.pbo) glGenBuffers(1, &now.pbo);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, now.pbo);
        if (now.map) {
            glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
            now.map = nullptr;
        }
        if (now.fence) glDeleteSync(now.fence);
        glBufferData(GL_PIXEL_PACK_BUFFER, bytes, nullptr, GL_STREAM_READ);
#ifdef __ANDROID__
        glBindFramebuffer(GL_READ_FRAMEBUFFER, depth ? androidDepthPeekSource() : peekSource(false));
#else
        glBindFramebuffer(GL_READ_FRAMEBUFFER, peekSource(depth));
#endif
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
#ifndef __ANDROID__
        if (depth) glReadPixels(0, 0, s_efbW, EFB_H, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, nullptr);
        else
#endif
            glReadPixels(0, 0, s_efbW, EFB_H, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
        now.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        now.frame = s_frameNo;
    }
    PeekSnap& prev = s_peek[cur ^ 1][s_peekGroup][t];
    if (prev.pbo && prev.frame + 1 == s_frameNo) {
        takeFetchedPeek(prev);
        if (!prev.map) {
            GxTimer tw(&s_waitSeconds);
            glClientWaitSync(prev.fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, prev.pbo);
            prev.map = static_cast<const uint8_t*>(glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, bytes, GL_MAP_READ_BIT));
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        }
        if (prev.map) {
            uint32_t v;
            memcpy(&v, prev.map + (size_t(y) * s_efbW + x) * 4, 4);
            return v;
        }
    }
    return peekSync(x, y, depth);
}

uint32_t peekColor(int x, int y) {
    uint32_t p = peekRaw(x, y, false);
    uint32_t c = (p >> 24) << 24 | (p & 0xFF) << 16 | ((p >> 8) & 0xFF) << 8 | ((p >> 16) & 0xFF);
    if (FILE* f = traceFile()) fprintf(f, "  GXPeekARGB(%d,%d) = %08X\n", x, y, c);
    return c;
}
uint32_t peekZ(int x, int y) {
    uint32_t v = peekRaw(x, y, true);
#ifdef __ANDROID__
    v = ((v & 0xFF) << 24) | (((v >> 8) & 0xFF) << 16) | (((v >> 16) & 0xFF) << 8);
#endif
    if (FILE* f = traceFile()) fprintf(f, "  GXPeekZ(%d,%d) = %06X\n", x, y, v >> 8);
    return v >> 8;
}
}  // namespace gx

extern "C" {

int GXPC_PresentXFB(const void* xfb, int winW, int winH) {
    flushBatch();
    glcInvalidate();
    if (!xfb) xfb = s_lastXfb;
    auto it = s_xfbs.find(xfb);
    if (it == s_xfbs.end()) return 0;
    const Xfb& x = it->second;
    // letterbox to 4:3, or to the widened aspect
    float aspect = 4.0f / 3.0f * float(s_efbW) / float(EFB_W);
    int vw = winW, vh = winH;
    if (postAspect() != 1) {
        if (float(vw) > float(vh) * aspect) vw = int(float(vh) * aspect + 0.5f);
        else vh = int(float(vw) / aspect + 0.5f);
    }
    const int baseH = int(float(x.h) / s_scale + 0.5f);  // the picture's height at the GameCube's resolution
    if (postAspect() == 2 && baseH > 0 && vh >= baseH) {
        vh = vh / baseH * baseH;
        vw = int(float(vh) * aspect + 0.5f);
    }
    int ox = (winW - vw) / 2, oy = (winH - vh) / 2;
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_FRAMEBUFFER, g_presentFbo);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    postPresent(x.tex, x.w, x.h, ox, oy, vw, vh, s_copyVao, s_vao);
    glViewport(0, 0, winW, winH);
    // Framebuffer 0 stays bound through the swap: macOS presents nothing
    // (a black window) if an FBO is bound at SDL_GL_SwapWindow.
    // GXPC_EndPresent restores the EFB.
    glBindFramebuffer(GL_FRAMEBUFFER, g_presentFbo);
    memset(&s_stats, 0, sizeof(s_stats));
    return 1;
}

void GXPC_EndPresent(void) {
    if (s_ready) glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
}

void GXPC_ReadEFB(uint8_t* rgba, int* w, int* h) {
    flushBatch();
    glcInvalidate();
    *w = scaled(s_efbW);
    *h = scaled(EFB_H);
    if (!rgba) return;
    glBindFramebuffer(GL_FRAMEBUFFER, efbReadFbo());
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, *w, *h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
}

int GXPC_ReadXFB(const void* xfb, uint8_t* rgba, int* w, int* h) {
    flushBatch();
    glcInvalidate();
    if (!xfb) xfb = s_lastXfb;
    auto it = s_xfbs.find(xfb);
    if (it == s_xfbs.end()) return 0;
    *w = it->second.w;
    *h = it->second.h;
    if (!rgba) return 1;
    glBindFramebuffer(GL_FRAMEBUFFER, s_tmpFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, it->second.tex, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, *w, *h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glBindFramebuffer(GL_FRAMEBUFFER, s_efbFbo);
    return 1;
}

void GXPC_GetLastFrameStats(GXPCStats* out) {
    *out = s_lastFrameStats;
    out->shaderCompiles = g_statShaderCompiles;
    out->textureUploads = g_statTexUploads;
}

void GXPC_SetHud(int on) {
    if (!s_hudEdges || s_hud == (on != 0)) return;
    flushBatch();
    s_hud = on != 0;
    s_hudPanes.clear();
    s_hudAnchor = -1.0f;
}

// Anchor on the outermost pane that is a piece, not a container; the batch
// queued so far keeps its mapping when the anchor moves to another side.
static void hudReanchor() {
    float now = -1.0f;
    for (const HudPane& p : s_hudPanes) {
        if (p.x2 - p.x1 < float(EFB_W) * 3 / 4) {
            now = (p.x1 + p.x2) / 2;
            break;
        }
    }
    if (now == s_hudAnchor) return;
    if (now < 0.0f || s_hudAnchor < 0.0f || hudOffset(now) != hudOffset(s_hudAnchor)) flushBatch();
    s_hudAnchor = now;
}

void GXPC_HudPaneBegin(float x1, float x2) {
    if (!s_hud) return;
    s_hudPanes.push_back({std::min(x1, x2), std::max(x1, x2)});
    hudReanchor();
}

void GXPC_HudPaneEnd(void) {
    if (!s_hud || s_hudPanes.empty()) return;
    s_hudPanes.pop_back();
    hudReanchor();
}

void GXPC_SetStretch2D(int on) {
    if (s_efbW == EFB_W || s_stretch2D == (on != 0)) return;
    flushBatch();  // what was queued keeps its own mapping
    s_stretch2D = on != 0;
}

void GXPC_SetWide2D(int on) {
    if (s_efbW == EFB_W || s_wide2D == (on != 0)) return;
    flushBatch();
    s_wide2D = on != 0;
}

void GXPC_SetWidescreen(float widthOver43) {
    if (s_ready) return;  // the EFB size is fixed once it exists
    s_wide = widthOver43 > 1.0f ? widthOver43 : 1.0f;
    s_wideCamAspect = kCamAspect * sqrtf(s_wide);
}
float GXPC_GetWidescreen(void) { return s_wide; }

double GXPC_GxSeconds(void) { return s_gxSeconds + g_decodeSeconds; }

void GXPC_GetTimes(GXPCTimes* out) {
    out->gx = s_gxSeconds + g_decodeSeconds;
    out->vertices = g_decodeSeconds;
    out->draws = g_flushSeconds - s_texSeconds;
    out->textures = s_texSeconds;
    out->copies = s_copySeconds;
    out->peeks = s_peekSeconds;
    out->gpuWait = s_waitSeconds;
    if (glt::active()) {
        // waiting for the GL thread (the driver's work for the frame) counts as
        // waiting for the GPU; the waits inside sms_gx's timed parts are in gx
        // already, the one before presenting is added, presenting itself is not
        out->gx += g_presentDrain;
        out->gpuWait = glt::waitedSeconds() - g_presentWaited + g_presentDrain;
    }
    out->present = g_presentSeconds;
    out->swap = g_swapSeconds;
    out->idle = s_idleClock ? s_idleClock() : 0;
}

void GXPC_SetDetailedTimers(int on) { g_gxStats = on || statsEnv(); }
void GXPC_SetIdleClock(double (*idleSeconds)(void)) { s_idleClock = idleSeconds; }

void GXPC_DrawOverlay(const uint8_t* rgba, int w, int h, int x, int y, int scale, int winW, int winH) {
    drawOverlayPanel(OVERLAY_DEBUG, rgba, w, h, x, y, scale, winW, winH);
}

}  // extern "C"

extern "C" {

void GXPC_GetStats(GXPCStats* out) {
    *out = s_stats;
    out->shaderCompiles = g_statShaderCompiles;
    out->textureUploads = g_statTexUploads;
}

}  // extern "C"
