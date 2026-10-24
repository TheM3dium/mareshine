// HD movies use the original YUV renderer, split into GX-sized textures.
#include <sms_hd_movies.h>
#include <THPPlayer/THPDraw.h>
#include <dolphin/os.h>
#include "port_platform.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <strings.h>

namespace {
// GX I8 is tiled in 8x4 blocks. Every source/crop boundary is aligned to
// 16 pixels, so copying complete tile rows also works for the chroma planes.
void copy_plane(u8* out, const u8* source, int source_width,
                int x, int y, int width, int height)
{
    for (int row = 0; row < height / 4; ++row)
        std::memcpy(out + row * width * 4,
                    source + (y / 4 + row) * source_width * 4 + x * 4,
                    width * 4);
    DCFlushRange(out, static_cast<u32>(width * height));
}
}

extern "C" void GXPC_SetStreamingRange(const void* p, uint32_t bytes) __attribute__((weak));

// GX texture objects hold 32-bit addresses even on 64-bit hosts: a low,
// aligned pool, outside the game's MEM1 heap, for the frames of HD movies
// (HD THPs here, MP4s in mp4_movies.cpp). The GX layer is told they are
// video frames, so it uploads them without a change hash or a pack lookup.
extern "C" u8* port_hd_movie_pool(void)
{
    static u8* storage = nullptr;
    if (!storage) {
        const uint32_t bytes = 2048 * 2048 * 3 / 2;
        void* pool = port_low_alloc(bytes + 31);
        if (!pool) {
            port_log("[thp] Cannot allocate the HD movie texture pool\n");
            std::abort();
        }
        storage = reinterpret_cast<u8*>((reinterpret_cast<uintptr_t>(pool) + 31) & ~static_cast<uintptr_t>(31));
        if (GXPC_SetStreamingRange)
            GXPC_SetStreamingRange(storage, bytes);
    }
    return storage;
}

extern "C" const char* port_thp_movie_stem(void);
extern "C" int port_thp_movie_fills(void);

// Wide movies across the widened frame (SMS_MOVIE_FILL, on unless 0): read by
// the movie sizing (decomp-patches/zzzzzz-movies-01) and by mp4_movies.cpp.
extern "C" int port_movie_fill(void)
{
    const char* e = std::getenv("SMS_MOVIE_FILL");
    return !(e && !std::strcmp(e, "0"));
}
extern "C" float port_widescreen;

namespace {
// Dark edges some of the original movies carry (from their capture), as
// fractions of the picture: left, right, top, bottom. With the cutscene frame
// on they would show as lines between the frame and the movie, so the movie
// is stretched by that much (2.4 % at most) and its edges go under the frame.
// Measured on the HD pack (1920 wide, 960/1344 high).
struct Edges {
    const char* movie;
    float left, right, top, bottom;
};
const Edges kEdges[] = {
    {"autodemoA", 6 / 1920.0f, 0, 0, 0}, // 3 dark columns and a dark fade; positions are whole 1/640ths
    {"demogeso", 12 / 1920.0f, 11 / 1920.0f, 6 / 960.0f, 2 / 960.0f},
    {"omakeB", 23 / 1920.0f, 23 / 1920.0f, 0, 0},
};

// the rectangle to draw the movie in: a wide movie across the widened frame
// (mp4_movies.cpp decides), and the picture without its dark edges filling
// the game's rectangle; false when it is the game's own
bool crop_rect(s16& x, s16& y, s16& w, s16& h)
{
    bool changed = false;
    if (port_thp_movie_fills()) {
        // The game's 2D is stretched across the widened frame (sms_gx), so 640
        // game units are the whole width: evenly larger to that, keeping the
        // movie's shape, and no taller than the picture (448) on very wide
        // screens (20:9), where it fits the height instead.
        float k = 640.0f / w;
        if (h * k > 448.0f)
            k = 448.0f / h;
        const float cx = x + w * 0.5f, cy = y + h * 0.5f;
        w = static_cast<s16>(w * k + 0.5f);
        h = static_cast<s16>(h * k + 0.5f);
        x = static_cast<s16>(cx - w * 0.5f);
        y = static_cast<s16>(cy - h * 0.5f);
        changed = true;
    }
    const char* frame = std::getenv("SMS_CUTSCENE_FRAME");
    if (!frame || !*frame || !std::strcmp(frame, "0"))
        return changed;
    const char* stem = port_thp_movie_stem();
    for (const Edges& e : kEdges) {
        if (!stem || strcasecmp(stem, e.movie))
            continue;
        const float nw = w / (1.0f - e.left - e.right), nh = h / (1.0f - e.top - e.bottom);
        x = static_cast<s16>(x - nw * e.left + 0.5f);
        y = static_cast<s16>(y - nh * e.top + 0.5f);
        w = static_cast<s16>(nw + 0.5f);
        h = static_cast<s16>(nh + 0.5f);
        return true;
    }
    return changed;
}
} // namespace

extern "C" BOOL port_draw_hd_thp(u8* y, u8* u, u8* v, s16 x, s16 pos_y,
                                s16 width, s16 height, s16 draw_width,
                                s16 draw_height)
{
    // dark edges of the movie under the cutscene frame
    static bool cropping = false;
    if (!cropping) {
        s16 cx = x, cy = pos_y, cw = draw_width, ch = draw_height;
        if (crop_rect(cx, cy, cw, ch)) {
            cropping = true;
            if (!port_draw_hd_thp(y, u, v, cx, cy, width, height, cw, ch))
                THPGXYuv2RgbDraw(y, u, v, cx, cy, width, height, cw, ch);
            cropping = false;
            return TRUE;
        }
    }
    // a hardware-decoded MP4 stands in for the THP's picture (mp4_movies.cpp)
    static bool inside = false;
    if (!inside) {
        inside = true;
        BOOL drawn = port_mp4_draw(x, pos_y, draw_width, draw_height);
        inside = false;
        if (drawn)
            return TRUE;
    }
    if (width <= 1024 && height <= 1024)
        return FALSE;
    if (width <= 0 || height <= 0 || width > 2048 || height > 2048
        || (width & 15) || (height & 15))
        return TRUE;

    // each tile gets distinct storage until THPPlayerDrawDone flushes the
    // complete frame
    u8* storage = port_hd_movie_pool();
    constexpr int edge = 960;
    u8* cursor = storage;
    for (int top = 0; top < height; top += edge) {
        const int h = std::min(edge, height - top);
        for (int left = 0; left < width; left += edge) {
            const int w = std::min(edge, width - left);
            u8* ty = cursor;
            u8* tu = ty + w * h;
            u8* tv = tu + w * h / 4;
            cursor = tv + w * h / 4;
            copy_plane(ty, y, width, left, top, w, h);
            copy_plane(tu, u, width / 2, left / 2, top / 2, w / 2, h / 2);
            copy_plane(tv, v, width / 2, left / 2, top / 2, w / 2, h / 2);
            const int x0 = x + left * draw_width / width;
            const int y0 = pos_y + top * draw_height / height;
            const int x1 = x + (left + w) * draw_width / width;
            const int y1 = pos_y + (top + h) * draw_height / height;
            // Recursion stops here: these textures fit the native GX limit.
            THPGXYuv2RgbDraw(ty, tu, tv, x0, y0,
                            w, h, x1 - x0, y1 - y0);
        }
    }
    return TRUE;
}
