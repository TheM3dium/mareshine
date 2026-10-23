// HD movies as MP4 (H.264 or HEVC), decoded by the device's hardware video
// decoder, for devices too slow for the HD pack's THPs (Android).
//
// The game still plays the movie from its disc: the original THP keeps the
// sound, the timing, the subtitles, the rumble, skipping and the end of the
// movie, so the game cannot tell. Only its picture is replaced: for the frame
// the THP player draws (patch thp-06), the MP4's frame of the same number is
// drawn instead, through the THP's own YUV renderer and into the same
// rectangle (hd_movies.cpp calls port_mp4_draw). The MP4s are made by the app
// from the HD pack (CutsceneConvert.java) with exactly the THP's frames.
//
// SMS_HD_MOVIES_MP4=<folder> turns it on; <folder>/<movie>.mp4 replaces
// <movie>.thp. A movie without one, or one that cannot be decoded, shows the
// THP's own picture ("Fallback to THP" in the log).
#include <sms_hd_movies.h>
#include <THPPlayer/THPDraw.h>
#include <dolphin/os.h>
#include "port_platform.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <strings.h>

// the cutscene frame behind a movie (gx_render.cpp, SMS_CUTSCENE_FRAME)
extern "C" void GXPC_SetBackdropMovie(const char* ownOverlay) __attribute__((weak));

#include <dirent.h>

// the movie playing, for hd_movies.cpp ("" when none)
static std::string g_stem;
extern "C" const char* port_thp_movie_stem(void) { return g_stem.c_str(); }

// A wide (2:1) movie drawn across the widened frame (SMS_MOVIE_FILL, on by
// default): evenly larger, so it keeps its shape and fills the width.
extern "C" float port_widescreen;
extern "C" void GXPC_SetMovieWidth(float width) __attribute__((weak));
static bool g_fill = false;
extern "C" int port_thp_movie_fills(void) { return g_fill; }

static void decide_fill(u32 width, u32 height)
{
    const char* e = getenv("SMS_MOVIE_FILL");
    g_fill = (!e || strcmp(e, "0")) && height > 0 && width >= height * 19 / 10 && port_widescreen > 1.01f;
    if (GXPC_SetMovieWidth)
        GXPC_SetMovieWidth(g_fill ? 640.0f * port_widescreen : 640.0f);
}

// <dir>/<file>, whatever its case; empty if there is none
static std::string find_file(const std::string& dir, const std::string& file)
{
    std::string found;
    if (DIR* d = opendir(dir.c_str())) {
        while (struct dirent* e = readdir(d))
            if (!strcasecmp(e->d_name, file.c_str())) {
                found = dir + "/" + e->d_name;
                break;
            }
        closedir(d);
    }
    return found;
}

// The cutscene frame for a movie starting: its own overlay_<movie>.png beside
// the frame image (SMS_CUTSCENE_FRAME), if there is one, else the frame.
static void backdrop_for(const char* name)
{
    std::string stem = name ? name : "";
    if (size_t slash = stem.find_last_of('/'); slash != std::string::npos)
        stem = stem.substr(slash + 1);
    if (size_t dot = stem.find_last_of('.'); dot != std::string::npos)
        stem = stem.substr(0, dot);
    g_stem = stem;
    if (!GXPC_SetBackdropMovie)
        return;
    std::string own;
    const char* frame = getenv("SMS_CUTSCENE_FRAME");
    if (frame && *frame && strcmp(frame, "0")) {
        std::string dir = frame;
        dir = dir.substr(0, dir.find_last_of('/'));
        own = find_file(dir, "overlay_" + stem + ".png");
    }
    GXPC_SetBackdropMovie(own.c_str());
}

// a movie of its own (TMovieDirector), not the portal animation in a stage
static bool cutscene_movie(const char* name)
{
    if (!name)
        return false;
    for (const char* p = name; *p; p++)
        if (!strncasecmp(p, "EX128x144", 9))
            return false;
    return true;
}

#ifdef __ANDROID__
#include <atomic>
#include <condition_variable>
#include <dirent.h>
#include <fcntl.h>
#include <mutex>
#include <strings.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

namespace {

// a decoded frame: planar YUV 4:2:0, linear
struct Frame {
    int index = -1;
    std::vector<uint8_t> y, u, v;
};

class Mp4Movie {
public:
    ~Mp4Movie() { close(); }

    bool open(const std::string& path, int frames, std::string& why)
    {
        m_path = path;
        m_frames = frames;
        if (!start(why))
            return false;
        m_thread = std::thread([this] { run(); });
        return true;
    }

    void close()
    {
        {
            std::lock_guard<std::mutex> lk(m_mu);
            m_quit = true;
        }
        m_cv.notify_all();
        if (m_thread.joinable())
            m_thread.join();
        stop();
    }

    int width() const { return m_width; }
    int height() const { return m_height; }
    bool failed() const { return m_failed; }

    // The decoded frame `index`, waiting a little for the decoder; nullptr
    // when it does not come (the THP's own picture is drawn then).
    const Frame* frame(int index)
    {
        std::unique_lock<std::mutex> lk(m_mu);
        if (index < m_wanted) { // played from the start again
            m_restart = true;
            m_cv.notify_all();
        }
        m_wanted = index;
        // frames before the wanted one are no longer needed
        for (Frame& f : m_ring)
            if (f.index >= 0 && f.index < index && f.index != m_shown)
                f.index = -1;
        m_cv.notify_all();
        auto ready = [&] {
            for (Frame& f : m_ring)
                if (f.index == index)
                    return true;
            return m_failed || m_ended;
        };
        m_cv.wait_for(lk, std::chrono::milliseconds(40), ready);
        for (Frame& f : m_ring)
            if (f.index == index) {
                m_shown = index;
                return &f;
            }
        // late: the newest frame before it, if the decoder has one
        Frame* best = nullptr;
        for (Frame& f : m_ring)
            if (f.index >= 0 && f.index <= index && (!best || f.index > best->index))
                best = &f;
        if (best)
            m_shown = best->index;
        return best;
    }

private:
    bool start(std::string& why)
    {
        int fd = ::open(m_path.c_str(), O_RDONLY);
        if (fd < 0) {
            why = "cannot open the file";
            return false;
        }
        struct stat st;
        fstat(fd, &st);
        m_ex = AMediaExtractor_new();
        media_status_t s = AMediaExtractor_setDataSourceFd(m_ex, fd, 0, st.st_size);
        ::close(fd);
        if (s != AMEDIA_OK) {
            why = "not a readable MP4";
            return false;
        }
        const char* mime = nullptr;
        AMediaFormat* fmt = nullptr;
        for (size_t i = 0; i < AMediaExtractor_getTrackCount(m_ex); i++) {
            AMediaFormat* f = AMediaExtractor_getTrackFormat(m_ex, i);
            const char* m = nullptr;
            if (AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m && !strncmp(m, "video/", 6)) {
                AMediaExtractor_selectTrack(m_ex, i);
                fmt = f;
                mime = m;
                break;
            }
            AMediaFormat_delete(f);
        }
        if (!fmt) {
            why = "no video track";
            return false;
        }
        int32_t w = 0, h = 0;
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
        if (w <= 0 || h <= 0 || w > 2048 || h > 2048 || (w & 15) || (h & 15)) {
            AMediaFormat_delete(fmt);
            why = "unsupported size " + std::to_string(w) + "x" + std::to_string(h);
            return false;
        }
        m_width = w;
        m_height = h;
        // the frames come out in buffers the CPU reads (an image reader's
        // frames cannot be locked on every device, NVIDIA's for one)
        m_codec = AMediaCodec_createDecoderByType(mime);
        if (!m_codec || AMediaCodec_configure(m_codec, fmt, nullptr, nullptr, 0) != AMEDIA_OK
            || AMediaCodec_start(m_codec) != AMEDIA_OK) {
            AMediaFormat_delete(fmt);
            why = std::string("no hardware decoder for ") + mime;
            return false;
        }
        AMediaFormat_delete(fmt);
        m_ring.resize(4);
        m_out = 0;
        m_inputDone = false;
        m_ended = false;
        return true;
    }

    void stop()
    {
        if (m_codec) {
            AMediaCodec_stop(m_codec);
            AMediaCodec_delete(m_codec);
            m_codec = nullptr;
        }
        if (m_ex) {
            AMediaExtractor_delete(m_ex);
            m_ex = nullptr;
        }
    }

    // the layout of the decoder's output buffers
    void readLayout()
    {
        AMediaFormat* f = AMediaCodec_getOutputFormat(m_codec);
        if (!f)
            return;
        int32_t v = 0;
        if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, &v)) m_color = v;
        m_stride = m_width;
        m_slice = m_height;
        if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_STRIDE, &v) && v >= m_width) m_stride = v;
        if (AMediaFormat_getInt32(f, "slice-height", &v) && v >= m_height) m_slice = v;
        int32_t top = 0, left = 0, right = 0, bottom = 0;
        if (AMediaFormat_getInt32(f, "crop-left", &left) && AMediaFormat_getInt32(f, "crop-top", &top)) {
            m_cropX = left & ~1;
            m_cropY = top & ~1;
        }
        (void)right;
        (void)bottom;
        AMediaFormat_delete(f);
        port_log("[thp] %s: decoder output color format %d, stride %d, slice height %d\n", m_path.c_str(), m_color,
                 m_stride, m_slice);
    }

    // copies a decoded frame into a free ring slot as frame `index`; false
    // when the decoder's format cannot be read
    bool store(const uint8_t* buf, size_t size, int index)
    {
        // 19: I420 (planar), 21: NV12 (semi-planar), 0x7F420888: flexible,
        // laid out as NV12 by the decoders that use it for buffers
        const bool planar = m_color == 19 || m_color == 20;
        const bool semi = m_color == 21 || m_color == 39 || m_color == 0x7F420888;
        const int w = m_width, h = m_height, st = m_stride, sl = m_slice;
        const size_t ysize = size_t(st) * sl;
        const size_t need = planar ? ysize + 2 * size_t(st / 2) * (sl / 2) : ysize + size_t(st) * (sl / 2);
        if ((!planar && !semi) || size < need - (st - w)) {
            if (!m_failed)
                port_log("[thp] %s: the decoder's frames cannot be read (color format %d, %zu bytes for %dx%d, stride "
                         "%d/%d); the THP's picture is shown\n", m_path.c_str(), m_color, size, w, h, st, sl);
            return false;
        }
        std::unique_lock<std::mutex> lk(m_mu);
        Frame* slot = nullptr;
        m_cv.wait(lk, [&] {
            if (m_quit || m_restart)
                return true;
            for (Frame& f : m_ring)
                if (f.index < 0) {
                    slot = &f;
                    return true;
                }
            return false;
        });
        if (!slot)
            return true;
        lk.unlock();
        slot->y.resize(size_t(w) * h);
        slot->u.resize(size_t(w / 2) * (h / 2));
        slot->v.resize(size_t(w / 2) * (h / 2));
        const uint8_t* y0 = buf + size_t(m_cropY) * st + m_cropX;
        for (int row = 0; row < h; row++)
            memcpy(slot->y.data() + size_t(row) * w, y0 + size_t(row) * st, w);
        if (planar) {
            const uint8_t* u0 = buf + ysize + size_t(m_cropY / 2) * (st / 2) + m_cropX / 2;
            const uint8_t* v0 = u0 + size_t(st / 2) * (sl / 2);
            for (int row = 0; row < h / 2; row++) {
                memcpy(slot->u.data() + size_t(row) * (w / 2), u0 + size_t(row) * (st / 2), w / 2);
                memcpy(slot->v.data() + size_t(row) * (w / 2), v0 + size_t(row) * (st / 2), w / 2);
            }
        } else {
            const uint8_t* uv0 = buf + ysize + size_t(m_cropY / 2) * st + m_cropX;
            for (int row = 0; row < h / 2; row++) {
                const uint8_t* src = uv0 + size_t(row) * st;
                uint8_t* u = slot->u.data() + size_t(row) * (w / 2);
                uint8_t* v = slot->v.data() + size_t(row) * (w / 2);
                for (int x = 0; x < w / 2; x++) {
                    u[x] = src[2 * x];
                    v[x] = src[2 * x + 1];
                }
            }
        }
        lk.lock();
        slot->index = index;
        m_cv.notify_all();
        return true;
    }

    void run()
    {
        while (true) {
            {
                std::lock_guard<std::mutex> lk(m_mu);
                if (m_quit)
                    return;
                if (m_restart) {
                    m_restart = false;
                    for (Frame& f : m_ring)
                        f.index = -1;
                    m_shown = -1;
                    AMediaExtractor_seekTo(m_ex, 0, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
                    AMediaCodec_flush(m_codec);
                    m_out = 0;
                    m_inputDone = false;
                    m_ended = false;
                }
            }
            if (m_ended) {
                std::unique_lock<std::mutex> lk(m_mu);
                m_cv.wait(lk, [&] { return m_quit || m_restart; });
                continue;
            }
            if (!m_inputDone) {
                ssize_t in = AMediaCodec_dequeueInputBuffer(m_codec, 2000);
                if (in >= 0) {
                    size_t cap = 0;
                    uint8_t* buf = AMediaCodec_getInputBuffer(m_codec, in, &cap);
                    ssize_t n = buf ? AMediaExtractor_readSampleData(m_ex, buf, cap) : -1;
                    if (n < 0) {
                        AMediaCodec_queueInputBuffer(m_codec, in, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        m_inputDone = true;
                    } else {
                        AMediaCodec_queueInputBuffer(m_codec, in, 0, n, AMediaExtractor_getSampleTime(m_ex), 0);
                        AMediaExtractor_advance(m_ex);
                    }
                }
            }
            AMediaCodecBufferInfo info;
            ssize_t out = AMediaCodec_dequeueOutputBuffer(m_codec, &info, 2000);
            if (out >= 0) {
                bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                bool frame = info.size > 0;
                if (frame) {
                    if (m_color < 0)
                        readLayout();
                    size_t cap = 0;
                    uint8_t* buf = AMediaCodec_getOutputBuffer(m_codec, out, &cap);
                    bool ok = buf && store(buf + info.offset, size_t(info.size), m_out);
                    AMediaCodec_releaseOutputBuffer(m_codec, out, false);
                    if (!ok) {
                        std::lock_guard<std::mutex> lk(m_mu);
                        m_failed = true;
                        m_ended = true;
                        m_cv.notify_all();
                        continue;
                    }
                    m_out++;
                } else {
                    AMediaCodec_releaseOutputBuffer(m_codec, out, false);
                }
                if (eos) {
                    std::lock_guard<std::mutex> lk(m_mu);
                    m_ended = true;
                    m_cv.notify_all();
                    if (m_out != m_frames)
                        port_log("[thp] %s: %d frames decoded, the THP has %d\n", m_path.c_str(), m_out, m_frames);
                }
            } else if (out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                readLayout();
            } else if (out != AMEDIACODEC_INFO_TRY_AGAIN_LATER
                       && out != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                std::lock_guard<std::mutex> lk(m_mu);
                port_log("[thp] %s: the hardware decoder stopped (%d); the THP's picture is shown\n", m_path.c_str(),
                         (int)out);
                m_failed = true;
                m_ended = true;
                m_cv.notify_all();
            }
        }
    }

    std::string m_path;
    int m_frames = 0, m_width = 0, m_height = 0;
    AMediaExtractor* m_ex = nullptr;
    AMediaCodec* m_codec = nullptr;
    int m_color = -1, m_stride = 0, m_slice = 0, m_cropX = 0, m_cropY = 0;
    std::thread m_thread;
    std::mutex m_mu;
    std::condition_variable m_cv;
    std::vector<Frame> m_ring;
    int m_out = 0, m_wanted = -1, m_shown = -1;
    bool m_inputDone = false, m_ended = false, m_quit = false, m_restart = false;
    std::atomic<bool> m_failed{false};
};

Mp4Movie* g_movie = nullptr;
int g_frame = -1;
int g_thpWidth = 0, g_thpHeight = 0;

// <dir>/<stem>.mp4, whatever its case
std::string find_mp4(const char* dir, const std::string& stem)
{
    DIR* d = opendir(dir);
    if (!d)
        return std::string();
    std::string want = stem + ".mp4", found;
    while (struct dirent* e = readdir(d))
        if (!strcasecmp(e->d_name, want.c_str())) {
            found = std::string(dir) + "/" + e->d_name;
            break;
        }
    closedir(d);
    return found;
}

// GX I8 tiles (8x4 texels per 32-byte block) from a linear plane region
void tile_plane(uint8_t* out, const uint8_t* src, int stride, int x0, int y0, int w, int h)
{
    for (int by = 0; by < h; by += 4)
        for (int bx = 0; bx < w; bx += 8)
            for (int r = 0; r < 4; r++) {
                memcpy(out, src + size_t(y0 + by + r) * stride + x0 + bx, 8);
                out += 8;
            }
}

uint8_t* tile_pool() { return port_hd_movie_pool(); }
} // namespace

extern "C" void port_thp_opened(const char* name, u32 width, u32 height, u32 frames)
{
    port_thp_closed();
    if (cutscene_movie(name)) {
        backdrop_for(name);
        decide_fill(width, height);
    }
    const char* dir = getenv("SMS_HD_MOVIES_MP4");
    if (!dir || !*dir || !strcmp(dir, "0") || !name)
        return;
    std::string stem = name;
    if (size_t slash = stem.find_last_of('/'); slash != std::string::npos)
        stem = stem.substr(slash + 1);
    if (size_t dot = stem.find_last_of('.'); dot != std::string::npos)
        stem = stem.substr(0, dot);
    std::string path = find_mp4(dir, stem);
    if (path.empty()) {
        port_log("[thp] Fallback to THP: %s (no %s.mp4)\n", name, stem.c_str());
        return;
    }
    Mp4Movie* m = new Mp4Movie;
    std::string why;
    if (!m->open(path, (int)frames, why)) {
        port_log("[thp] Fallback to THP: %s (%s: %s)\n", name, path.c_str(), why.c_str());
        delete m;
        return;
    }
    if ((long)m->width() * height != (long)m->height() * width)
        port_log("[thp] %s: %dx%d does not have the THP's shape (%ux%u); it is drawn into the THP's rectangle\n",
                 path.c_str(), m->width(), m->height(), width, height);
    port_log("[thp] HD movie loaded: %s (%dx%d, hardware decoder)\n", path.c_str(), m->width(), m->height());
    g_movie = m;
    g_thpWidth = (int)width;
    g_thpHeight = (int)height;
}

extern "C" void port_thp_closed(void)
{
    g_stem.clear();
    g_fill = false;
    if (GXPC_SetMovieWidth)
        GXPC_SetMovieWidth(640.0f);
    if (GXPC_SetBackdropMovie)
        GXPC_SetBackdropMovie(nullptr);
    delete g_movie;
    g_movie = nullptr;
    g_frame = -1;
}

extern "C" void port_thp_draw_frame(s32 frame) { g_frame = frame; }

extern "C" BOOL port_mp4_draw(s16 x, s16 y, s16 draw_width, s16 draw_height)
{
    if (!g_movie || g_frame < 0 || g_movie->failed())
        return FALSE;
    const Frame* f = g_movie->frame(g_frame);
    if (!f)
        return FALSE;
    uint8_t* pool = tile_pool();
    if (!pool)
        return FALSE;
    const int width = g_movie->width(), height = g_movie->height();
    // tiles within the GX texture limit, as hd_movies.cpp draws HD THPs
    constexpr int edge = 960;
    uint8_t* cursor = pool;
    for (int top = 0; top < height; top += edge) {
        const int h = std::min(edge, height - top);
        for (int left = 0; left < width; left += edge) {
            const int w = std::min(edge, width - left);
            uint8_t* ty = cursor;
            uint8_t* tu = ty + w * h;
            uint8_t* tv = tu + w * h / 4;
            cursor = tv + w * h / 4;
            tile_plane(ty, f->y.data(), width, left, top, w, h);
            tile_plane(tu, f->u.data(), width / 2, left / 2, top / 2, w / 2, h / 2);
            tile_plane(tv, f->v.data(), width / 2, left / 2, top / 2, w / 2, h / 2);
            DCFlushRange(ty, static_cast<u32>(w * h * 3 / 2));
            const int x0 = x + left * draw_width / width;
            const int y0 = y + top * draw_height / height;
            const int x1 = x + (left + w) * draw_width / width;
            const int y1 = y + (top + h) * draw_height / height;
            THPGXYuv2RgbDraw(ty, tu, tv, x0, y0, w, h, x1 - x0, y1 - y0);
        }
    }
    return TRUE;
}

#else // not Android: the HD pack's THPs are fast enough there

extern "C" void port_thp_opened(const char* name, u32 width, u32 height, u32)
{
    if (cutscene_movie(name)) {
        backdrop_for(name);
        decide_fill(width, height);
    }
}
extern "C" void port_thp_closed(void)
{
    g_stem.clear();
    g_fill = false;
    if (GXPC_SetMovieWidth)
        GXPC_SetMovieWidth(640.0f);
    if (GXPC_SetBackdropMovie)
        GXPC_SetBackdropMovie(nullptr);
}
extern "C" void port_thp_draw_frame(s32) {}
extern "C" BOOL port_mp4_draw(s16, s16, s16, s16) { return FALSE; }

#endif
