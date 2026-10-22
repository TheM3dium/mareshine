// The graphics layer's side of save states (platform/savestate): a picture of
// the last frame shown for a new state, and rechecking cached decodes after a
// state put the game's memory back.
#include "gx_internal.h"
#include "gl_funcs.h"
#include "sms_gx/gx_pc.h"

#include <stdlib.h>
#include <string.h>

#include <algorithm>
#include <vector>

#define STB_IMAGE_WRITE_STATIC
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "third_party/stb_image_write.h"

// A picture of the last frame shown: at most 320 pixels wide, as a PNG. The
// caller frees it.
extern "C" uint8_t* GXPC_SaveStateThumbnail(size_t* size) {
    *size = 0;
    int w = 0, h = 0;
    if (!GXPC_ReadXFB(nullptr, nullptr, &w, &h) || w <= 0 || h <= 0) return nullptr;
    std::vector<uint8_t> full(size_t(w) * h * 4);
    if (!GXPC_ReadXFB(nullptr, full.data(), &w, &h)) return nullptr;
    const int tw = std::min(w, 320), th = int(int64_t(h) * tw / w);
    if (th <= 0) return nullptr;
    std::vector<uint8_t> small(size_t(tw) * th * 4);
    for (int y = 0; y < th; y++)
        for (int x = 0; x < tw; x++) {
            // the average of the pixels it covers
            const int x0 = x * w / tw, x1 = std::max(x0 + 1, (x + 1) * w / tw);
            const int y0 = y * h / th, y1 = std::max(y0 + 1, (y + 1) * h / th);
            unsigned sum[3] = {0, 0, 0}, n = 0;
            for (int sy = y0; sy < y1; sy++)
                for (int sx = x0; sx < x1; sx++, n++)
                    for (int c = 0; c < 3; c++) sum[c] += full[(size_t(sy) * w + sx) * 4 + c];
            uint8_t* o = &small[(size_t(y) * tw + x) * 4];
            for (int c = 0; c < 3; c++) o[c] = uint8_t(sum[c] / n);
            o[3] = 255;
        }
    int len = 0;
    unsigned char* png = stbi_write_png_to_mem(small.data(), tw * 4, tw, th, 4, &len);
    if (!png) return nullptr;
    uint8_t* out = (uint8_t*)malloc(size_t(len));
    if (out) {
        memcpy(out, png, size_t(len));
        *size = size_t(len);
    }
    STBIW_FREE(png);
    return out;
}

// The game's memory changed under the caches (a save state was loaded, a DVD
// or ARAM transfer, DCZeroRange): write stamps for the display-list and
// texture caches, and the textures it covers are checked again.
extern "C" void GXPC_MemoryWritten(const void* p, uint32_t size) {
    gx::memoryWritten(p, size);
    gx::textureCpuWrote(p, size);
}
