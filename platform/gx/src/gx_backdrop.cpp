// The cutscene frame (Mareshine, SMS_CUTSCENE_FRAME=<image>) while a movie
// plays (GXPC_SetBackdropMovie, from platform/thp/mp4_movies.cpp). The image,
// scaled to cover the window, fills the bars beside the movie; a movie may
// have its own, overlay_<movie>.png, laid over everything with its
// transparency at the window's height (it can cover bars inside the movie
// itself), the strips beside it a plain blue. Either may have a _20x9 version
// for screens wider than 2:1. It fades in when a movie starts and out when
// it ends. Drawn over the presented frame (gx_platform.cpp), under the touch
// controls, with GXPC_DrawTexture.
#include "gx_internal.h"
#include "sms_gx/gx_pc.h"

#include <algorithm>
#include <chrono>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <sys/stat.h>
#include <unordered_map>

#define STBI_ONLY_PNG
#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_STATIC
#include "third_party/stb_image.h"

extern "C" float port_widescreen;
extern "C" int port_thp_movie_fills(void) __attribute__((weak));

namespace {

struct Image {
    unsigned tex = 0;
    int w = 0, h = 0;
    bool tried = false;
};
std::unordered_map<std::string, Image> s_images;
std::string s_own;  // the movie's own overlay, "" for the shared frame
bool s_on = false, s_fadingOut = false;
const double kFade = 0.3;  // seconds
double s_fadeT0 = 0;
float s_fadeFrom = 0;
float s_movieWidth = 640.0f;  // the movie's width in the game's 640-wide units
int s_lastKeepX = 0, s_lastKeepW = 0;
unsigned s_blue = 0;

double clockNow() {
    using namespace std::chrono;
    return duration<double>(steady_clock::now().time_since_epoch()).count();
}

float alphaNow() {
    const double t = clockNow() - s_fadeT0;
    if (s_on) return float(std::min(1.0, t / kFade));
    if (s_fadingOut) return float(std::max(0.0, double(s_fadeFrom) * (1.0 - t / kFade)));
    return 0.0f;
}

const Image* load(const std::string& path) {
    Image& im = s_images[path];
    if (im.tried) return im.tex ? &im : nullptr;
    im.tried = true;
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return nullptr;
    int n = 0;
    uint8_t* px = stbi_load(path.c_str(), &im.w, &im.h, &n, 4);
    if (!px) {
        gx::logmsg("cutscene frame: cannot read %s (a PNG is needed)", path.c_str());
        return nullptr;
    }
    im.tex = GXPC_UploadTexture(px, im.w, im.h);
    stbi_image_free(px);
    if (im.tex) gx::logmsg("cutscene frame: %s (%dx%d)", path.c_str(), im.w, im.h);
    return im.tex ? &im : nullptr;
}

// On a screen wider than about 2:1 the image's _20x9 version, when there is one.
const Image* loadFor(const std::string& path, int winW, int winH) {
    if (winW > winH * 2) {
        const size_t dot = path.rfind('.');
        const std::string wide =
            dot == std::string::npos ? path + "_20x9" : path.substr(0, dot) + "_20x9" + path.substr(dot);
        if (const Image* im = load(wide)) return im;
    }
    return load(path);
}

// The part of an image placed at (ix, iy, iw, ih) that falls in [x0, x1) of the window.
void addPart(std::vector<GXPCSprite>& out, const Image& im, float ix, float iy, float iw, float ih, float x0, float x1) {
    x0 = std::max(x0, ix);
    x1 = std::min(x1, ix + iw);
    if (x1 <= x0) return;
    GXPCSprite s;
    s.x = x0;
    s.y = iy;
    s.w = x1 - x0;
    s.h = ih;
    s.u = int((x0 - ix) / iw * im.w + 0.5f);
    s.v = 0;
    s.uw = std::max(1, int((x1 - x0) / iw * im.w + 0.5f));
    s.vh = im.h;
    out.push_back(s);
}

}  // namespace

extern "C" void GXPC_SetBackdropMovie(const char* ownOverlay) {
    if (ownOverlay) {
        if (!s_on) s_fadeT0 = clockNow();
        s_on = true;
        s_fadingOut = false;
        s_own = ownOverlay;
    } else if (s_on) {  // the movie's frame stays while it fades out
        s_fadeFrom = alphaNow();
        s_on = false;
        s_fadingOut = true;
        s_fadeT0 = clockNow();
    }
}

extern "C" void GXPC_SetBackdropActive(int on) { GXPC_SetBackdropMovie(on ? "" : nullptr); }

extern "C" void GXPC_SetMovieWidth(float width) { s_movieWidth = width > 0 ? width : 640.0f; }

extern "C" void GXPC_DrawBackdrop(int winW, int winH) {
    if ((!s_on && !s_fadingOut) || winW <= 0 || winH <= 0) return;
    const char* frame = getenv("SMS_CUTSCENE_FRAME");
    if (!frame || !*frame || !strcmp(frame, "0")) return;
    const float alpha = alphaNow();
    // the picture: the game's frame (4:3 widened) fitted to the window, and the
    // movie's share of its width in the middle
    const float wide = port_widescreen > 1.0f ? port_widescreen : 1.0f;
    const float picW = std::min(float(winW), winH * 4.0f / 3.0f * wide);
    const float movie = (port_thp_movie_fills && port_thp_movie_fills()) ? 640.0f * wide : s_movieWidth;
    int keepW = int(picW * std::min(1.0f, movie / (640.0f * wide)) + 0.5f);
    int keepX = (winW - keepW) / 2;
    if (s_on) {
        s_lastKeepX = keepX;
        s_lastKeepW = keepW;
    } else {
        if (alpha <= 0.0f) {
            s_fadingOut = false;
            return;
        }
        keepX = s_lastKeepX;
        keepW = s_lastKeepW;
    }
    const Image* im = nullptr;
    bool own = false;
    if (!s_own.empty() && (im = loadFor(s_own, winW, winH)))
        own = true;
    else
        im = loadFor(frame, winW, winH);
    if (!im) return;
    std::vector<GXPCSprite> parts;
    if (own) {
        // at the window's height, over everything; blue strips beside it
        const float k = float(winH) / im->h, dw = im->w * k, x = (winW - dw) * 0.5f;
        if (x > 0.5f) {
            if (!s_blue) {
                const uint8_t blue[4] = {31, 135, 214, 255};
                s_blue = GXPC_UploadTexture(blue, 1, 1);
            }
            GXPCSprite strip[2] = {{0, 0, x, float(winH), 0, 0, 1, 1}, {x + dw, 0, winW - x - dw, float(winH), 0, 0, 1, 1}};
            GXPC_DrawTexture(s_blue, 1, 1, strip, 2, alpha, winW, winH);
        }
        GXPCSprite all = {x, 0, dw, float(winH), 0, 0, im->w, im->h};
        parts.push_back(all);
    } else {
        if (keepX <= 0 && keepX + keepW >= winW) return;  // no bars beside the movie
        // cover the window, keeping the image's shape; only beside the movie
        const float k = std::max(float(winW) / im->w, float(winH) / im->h);
        const float dw = im->w * k, dh = im->h * k, ix = (winW - dw) * 0.5f, iy = (winH - dh) * 0.5f;
        addPart(parts, *im, ix, iy, dw, dh, 0, float(keepX));
        addPart(parts, *im, ix, iy, dw, dh, float(keepX + keepW), float(winW));
    }
    GXPC_DrawTexture(im->tex, im->w, im->h, parts.data(), int(parts.size()), alpha, winW, winH);
}
