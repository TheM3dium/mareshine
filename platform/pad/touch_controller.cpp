#include "touch_controller.h"

#include "port_savestate.h"
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <string>

// pad.cpp (Android): asks the app to open the in-game menu.
extern "C" void port_open_menu(void) __attribute__((weak));
extern "C" void GXPC_SetOverlayAlpha(float alpha) __attribute__((weak));
#include <sms_gx/gx_pc.h>
#include <algorithm>
#include <cmath>

extern "C" int port_camera_invert_touch(int value, int axis); // pad.cpp
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

extern "C" {
__attribute__((weak)) void GXPC_DrawOverlay(const uint8_t* rgba, int w, int h, int x, int y, int scale, int winW, int winH);
// the sprites are the same every frame: uploaded once (sms_gx), forgotten when rebuilt
__attribute__((weak)) void GXPC_DrawOverlayStatic(const uint8_t* rgba, int w, int h, int x, int y, int scale, int winW, int winH);
__attribute__((weak)) void GXPC_ForgetOverlayCache(void);
}

namespace {

enum TouchButtonId {
    TBTN_A,
    TBTN_B,
    TBTN_X,
    TBTN_Y,
    TBTN_L,
    TBTN_R,
    TBTN_R_HARD,
    TBTN_Z,
    TBTN_START,
    TBTN_OPACITY,
    TBTN_MENU,
    TBTN_SAVE,
    TBTN_LOAD,
    TBTN_COUNT
};

struct TouchButton {
    TouchButtonId id;
    const char* label;
    float relX, relY;    // Fraction of width / height
    float relRadius;     // Fraction of height
    uint16_t padMask;
    uint8_t baseR, baseG, baseB;
    bool pressed;
    int64_t fingerId;
    bool hidden; // SMS_TOUCH_LAYOUT: not shown, not pressable
};

// Button layout definitions
TouchButton s_buttons[TBTN_COUNT] = {
    // ID          Label   RelX   RelY   RelR    PadMask             R    G    B
    { TBTN_A,      "A",    0.90f, 0.74f, 0.082f, PAD_BUTTON_A,       46,  204, 113, false, -1 }, // Emerald Green
    { TBTN_B,      "B",    0.80f, 0.82f, 0.072f, PAD_BUTTON_B,       231, 76,  60,  false, -1 }, // Coral Red
    { TBTN_X,      "X",    0.82f, 0.62f, 0.062f, PAD_BUTTON_X,       241, 196, 15,  false, -1 }, // Amber Yellow
    { TBTN_Y,      "Y",    0.73f, 0.70f, 0.062f, PAD_BUTTON_Y,       52,  152, 219, false, -1 }, // Sky Blue
    { TBTN_L,      "L",    0.08f, 0.16f, 0.072f, PAD_TRIGGER_L,      149, 165, 166, false, -1 }, // Silver
    { TBTN_R,      "R",    0.91f, 0.52f, 0.072f, 0,                  41,  128, 185, false, -1 }, // Ocean Blue (Soft Run Spray)
    { TBTN_R_HARD, "R+",   0.82f, 0.44f, 0.062f, PAD_TRIGGER_R,      31,  78,  121, false, -1 }, // Deep Blue (Aim Spray)
    { TBTN_Z,      "Z",    0.90f, 0.30f, 0.062f, PAD_TRIGGER_Z,      155, 89,  182, false, -1 }, // Purple, near R and R+
    { TBTN_START,  "START",0.50f, 0.08f, 0.050f, PAD_BUTTON_START,  230, 126, 34,  false, -1 }, // Orange
    { TBTN_OPACITY,"HIDE", 0.40f, 0.08f, 0.045f, 0,                  127, 140, 141, false, -1 }, // hides the others (SHOW brings them back); "HUD" in SMS_TOUCH_LAYOUT
    { TBTN_MENU,   "MENU", 0.60f, 0.08f, 0.050f, 0,                  69,  90,  100, false, -1 }, // in-game menu (port_open_menu)
    // save states (platform/savestate): small, at the top right beside Z, out
    // of the way of the play buttons; both act once held (kStateHoldSeconds)
    { TBTN_SAVE,   "SAVE", 0.74f, 0.08f, 0.042f, 0,                  22,  160, 133, false, -1 }, // quick save (hold)
    { TBTN_LOAD,   "LOAD", 0.83f, 0.08f, 0.042f, 0,                  211, 84,  0,   false, -1 }  // quick load (hold)
};
// SAVE and LOAD act once held this long, so a brush of the finger neither
// overwrites the quick slot nor throws the player's progress away; with the
// delay off (SMS_SAVESTATE_DELAY=0) they act when pressed. [0] SAVE, [1] LOAD.
const double kStateHoldSeconds = 0.5;
bool s_stateDelay = true;
double s_stateSince[2] = {-1, -1};
bool s_stateFired[2] = {false, false};

void stateFire(int which)
{
    s_stateFired[which] = true;
    port_savestate_hotkey(which ? PORT_SAVESTATE_HOTKEY_LOAD_NEWEST : PORT_SAVESTATE_HOTKEY_SAVE);
}

// Virtual Analog Stick State
struct VirtualStick {
    bool active;
    int64_t fingerId;
    float anchorX, anchorY; // In screen pixels
    float currX, currY;     // In screen pixels
    float radius;           // Outer max deflection
    int stickX, stickY;     // Clamped to -100 .. 100
};

VirtualStick s_stick = { false, -1, 0, 0, 0, 0, 0, 0, 0 };
VirtualStick s_cstick = { false, -1, 0, 0, 0, 0, 0, 0, 0 };

// Camera Touch Swipe State
struct CameraSwipe {
    bool active;
    int64_t fingerId;
    float lastX, lastY;
    int substickX, substickY;
};

CameraSwipe s_cam = { false, -1, 0, 0, 0, 0 };


int s_winW = 1280;
int s_winH = 720;
float s_hudOpacity = 0.70f;
// the HUD button hides (and shows again) every other touch control; while
// they are hidden they do not react either, only the HUD button does
bool s_hideOthers = false;
bool s_visible = true;
bool s_inited = false;

// Where the sticks rest: x, y as fractions of the window, r (the reach) as a
// fraction of its height. The layout editor in the app moves and sizes them.
struct StickLayout {
    float x, y, r;
    bool hidden;
};
StickLayout s_stickL = { 0.16f, 0.72f, 0.14f, false };
StickLayout s_cstickL = { 0.61f, 0.76f, 0.11f, false };

// SMS_TOUCH_LAYOUT (the app's touch layout editor): "NAME=x,y,size,shown;..."
// for A B X Y L R RPLUS Z START HUD MENU SAVE LOAD and STICK CSTICK. Names left out keep
// their place. SMS_TOUCH_OPACITY: percent. SMS_TOUCH_AUTOHIDE: seconds without
// a touch after which the controls fade out (0 never); a touch brings them back.
const char* const kTouchNames[] = { "A", "B", "X", "Y", "L", "R", "RPLUS", "Z", "START", "HUD", "MENU", "SAVE", "LOAD" };
float s_autoHide = 0;
double s_lastTouch = 0;
bool s_layoutLoaded = false;

double nowSec()
{
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

// The built-in places, kept so a changed SMS_TOUCH_LAYOUT can drop entries.
struct SavedPlace { float x, y, r; };
SavedPlace s_defButtons[TBTN_COUNT];
StickLayout s_defStick, s_defCStick;
bool s_defSaved = false;

void loadLayout()
{
    if (s_layoutLoaded) return;
    s_layoutLoaded = true;
    if (!s_defSaved) {
        s_defSaved = true;
        for (int i = 0; i < TBTN_COUNT; i++) s_defButtons[i] = { s_buttons[i].relX, s_buttons[i].relY, s_buttons[i].relRadius };
        s_defStick = s_stickL;
        s_defCStick = s_cstickL;
    } else {
        for (int i = 0; i < TBTN_COUNT; i++) {
            s_buttons[i].relX = s_defButtons[i].x;
            s_buttons[i].relY = s_defButtons[i].y;
            s_buttons[i].relRadius = s_defButtons[i].r;
            s_buttons[i].hidden = false;
        }
        s_stickL = s_defStick;
        s_cstickL = s_defCStick;
        s_hudOpacity = 0.70f;
        s_autoHide = 0;
    }
    if (const char* e = getenv("SMS_TOUCH_OPACITY"); e && *e) {
        float v = (float)atof(e) / 100.0f;
        s_hudOpacity = v < 0.05f ? 0.05f : v > 1.0f ? 1.0f : v;
    }
    if (const char* e = getenv("SMS_TOUCH_AUTOHIDE"); e && *e) s_autoHide = (float)atof(e);
    if (const char* e = getenv("SMS_SAVESTATE_DELAY"); e && *e) s_stateDelay = atoi(e) != 0;
    const char* e = getenv("SMS_TOUCH_LAYOUT");
    if (!e || !*e) return;
    std::string all = e;
    size_t pos = 0;
    while (pos < all.size()) {
        size_t end = all.find(';', pos);
        std::string item = all.substr(pos, end == std::string::npos ? std::string::npos : end - pos);
        pos = end == std::string::npos ? all.size() : end + 1;
        size_t eq = item.find('=');
        if (eq == std::string::npos) continue;
        std::string name = item.substr(0, eq);
        float x, y, r;
        int shown = 1;
        if (sscanf(item.c_str() + eq + 1, "%f,%f,%f,%d", &x, &y, &r, &shown) < 3) continue;
        x = std::max(0.0f, std::min(1.0f, x));
        y = std::max(0.0f, std::min(1.0f, y));
        r = std::max(0.02f, std::min(0.4f, r));
        if (name == "STICK") s_stickL = { x, y, r, shown == 0 };
        else if (name == "CSTICK") s_cstickL = { x, y, r, shown == 0 };
        else
            for (int i = 0; i < TBTN_COUNT; i++)
                if (name == kTouchNames[i]) {
                    s_buttons[i].relX = x;
                    s_buttons[i].relY = y;
                    s_buttons[i].relRadius = r;
                    s_buttons[i].hidden = shown == 0;
                }
    }
}

void placeSticks()
{
    s_stick.radius = s_winH * s_stickL.r;
    s_stick.anchorX = s_winW * s_stickL.x;
    s_stick.anchorY = s_winH * s_stickL.y;
    s_stick.currX = s_stick.anchorX;
    s_stick.currY = s_stick.anchorY;
    s_cstick.radius = s_winH * s_cstickL.r;
    s_cstick.anchorX = s_winW * s_cstickL.x;
    s_cstick.anchorY = s_winH * s_cstickL.y;
    s_cstick.currX = s_cstick.anchorX;
    s_cstick.currY = s_cstick.anchorY;
}

// Sprite Cache
struct Sprite {
    int w, h;
    std::vector<uint8_t> pixels;
};

Sprite s_buttonSprites[TBTN_COUNT][2]; // [id][0=normal, 1=pressed]
Sprite s_stickBaseSprite;
Sprite s_stickKnobSprite;
Sprite s_stickKnobPressedSprite;

Sprite s_cstickBaseSprite;
Sprite s_cstickKnobSprite;
Sprite s_cstickKnobPressedSprite;

// Simple 5x7 bitmap font for rendering labels
struct Glyph5x7 {
    char c;
    uint8_t cols[5];
};
const Glyph5x7 kGlyphs5x7[] = {
    { 'A', { 0x7E, 0x11, 0x11, 0x11, 0x7E } },
    { 'B', { 0x7F, 0x49, 0x49, 0x49, 0x36 } },
    { 'C', { 0x3E, 0x41, 0x41, 0x41, 0x22 } },
    { 'D', { 0x7F, 0x41, 0x41, 0x22, 0x1C } },
    { 'E', { 0x7F, 0x49, 0x49, 0x49, 0x41 } },
    { 'F', { 0x7F, 0x09, 0x09, 0x09, 0x01 } },
    { 'G', { 0x3E, 0x41, 0x49, 0x49, 0x7A } },
    { 'H', { 0x7F, 0x08, 0x08, 0x08, 0x7F } },
    { 'I', { 0x00, 0x41, 0x7F, 0x41, 0x00 } },
    { 'J', { 0x20, 0x40, 0x41, 0x3F, 0x01 } },
    { 'K', { 0x7F, 0x08, 0x14, 0x22, 0x41 } },
    { 'L', { 0x7F, 0x40, 0x40, 0x40, 0x40 } },
    { 'M', { 0x7F, 0x02, 0x0C, 0x02, 0x7F } },
    { 'N', { 0x7F, 0x04, 0x08, 0x10, 0x7F } },
    { 'O', { 0x3E, 0x41, 0x41, 0x41, 0x3E } },
    { 'P', { 0x7F, 0x09, 0x09, 0x09, 0x06 } },
    { 'R', { 0x7F, 0x09, 0x19, 0x29, 0x46 } },
    { 'S', { 0x46, 0x49, 0x49, 0x49, 0x31 } },
    { 'T', { 0x01, 0x01, 0x7F, 0x01, 0x01 } },
    { 'U', { 0x3F, 0x40, 0x40, 0x40, 0x3F } },
    { 'V', { 0x1F, 0x20, 0x40, 0x20, 0x1F } },
    { 'W', { 0x3F, 0x40, 0x38, 0x40, 0x3F } },
    { 'X', { 0x63, 0x14, 0x08, 0x14, 0x63 } },
    { 'Y', { 0x07, 0x08, 0x70, 0x08, 0x07 } },
    { 'Z', { 0x61, 0x51, 0x49, 0x45, 0x43 } },
    { '+', { 0x08, 0x08, 0x3E, 0x08, 0x08 } },
    { '-', { 0x08, 0x08, 0x08, 0x08, 0x08 } },
};
// indexed by character (g++ has no designated array initializers)
struct Font5x7 {
    uint8_t g[128][5];
    Font5x7() {
        memset(g, 0, sizeof g);
        for (const Glyph5x7& e : kGlyphs5x7)
            memcpy(g[(unsigned char)e.c], e.cols, 5);
    }
    const uint8_t* operator[](int i) const { return g[i]; }
};
const Font5x7 kFont5x7;

void drawCharToBuffer(std::vector<uint8_t>& buf, int w, int h, int cx, int cy, char ch, int scale, uint8_t r, uint8_t g, uint8_t b, uint8_t a)
{
    if (ch < 0 || ch >= 128) return;
    const uint8_t* colBits = kFont5x7[(unsigned char)ch];
    int startX = cx - (5 * scale) / 2;
    int startY = cy - (7 * scale) / 2;

    for (int col = 0; col < 5; ++col) {
        uint8_t bits = colBits[col];
        for (int row = 0; row < 7; ++row) {
            if (bits & (1 << row)) {
                for (int sy = 0; sy < scale; ++sy) {
                    for (int sx = 0; sx < scale; ++sx) {
                        int px = startX + col * scale + sx;
                        int py = startY + row * scale + sy;
                        if (px >= 0 && px < w && py >= 0 && py < h) {
                            size_t idx = (size_t(py) * w + px) * 4;
                            buf[idx + 0] = r;
                            buf[idx + 1] = g;
                            buf[idx + 2] = b;
                            buf[idx + 3] = a;
                        }
                    }
                }
            }
        }
    }
}

void drawTextToBuffer(std::vector<uint8_t>& buf, int w, int h, int cx, int cy, const char* str, int scale, uint8_t r, uint8_t g, uint8_t b, uint8_t a)
{
    if (!str) return;
    int len = (int)strlen(str);
    int totalWidth = len * 6 * scale;
    int curX = cx - totalWidth / 2 + (3 * scale);

    for (int i = 0; i < len; ++i) {
        drawCharToBuffer(buf, w, h, curX, cy, str[i], scale, r, g, b, a);
        curX += 6 * scale;
    }
}

// Generate circular button sprite
Sprite makeButtonSprite(int size, uint8_t r, uint8_t g, uint8_t b, const char* label, bool pressed)
{
    Sprite sp;
    sp.w = size;
    sp.h = size;
    sp.pixels.resize(size * size * 4, 0);

    float center = (size - 1) * 0.5f;
    float radius = size * 0.46f;
    float innerR = radius - 3.5f;

    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            float dx = x - center;
            float dy = y - center;
            float dist = std::sqrt(dx * dx + dy * dy);
            size_t idx = (size_t(y) * size + x) * 4;

            if (dist <= radius) {
                float edge = std::max(0.0f, std::min(1.0f, radius - dist));
                float ring = (dist >= innerR) ? 1.0f : 0.0f;

                uint8_t alpha = uint8_t(edge * (pressed ? 240 : 180));
                uint8_t cr = r;
                uint8_t cg = g;
                uint8_t cb = b;

                if (ring > 0.5f) {
                    // Crisp luminous border
                    cr = std::min(255, r + 70);
                    cg = std::min(255, g + 70);
                    cb = std::min(255, b + 70);
                    alpha = uint8_t(edge * 245);
                } else if (pressed) {
                    // Bright pressed core
                    cr = std::min(255, r + 90);
                    cg = std::min(255, g + 90);
                    cb = std::min(255, b + 90);
                } else {
                    // Radial gradient glass depth
                    float grad = 1.0f - (dist / innerR) * 0.35f;
                    cr = uint8_t(r * grad);
                    cg = uint8_t(g * grad);
                    cb = uint8_t(b * grad);
                }

                sp.pixels[idx + 0] = cr;
                sp.pixels[idx + 1] = cg;
                sp.pixels[idx + 2] = cb;
                sp.pixels[idx + 3] = alpha;
            }
        }
    }

    // Draw label in center
    int fontScale = size >= 90 ? 3 : (size >= 55 ? 2 : 1);
    drawTextToBuffer(sp.pixels, size, size, (int)center, (int)center, label, fontScale, 255, 255, 255, 255);

    return sp;
}

// Generate main joystick base ring sprite
Sprite makeStickBaseSprite(int size)
{
    Sprite sp;
    sp.w = size;
    sp.h = size;
    sp.pixels.resize(size * size * 4, 0);

    float center = (size - 1) * 0.5f;
    float outerR = size * 0.47f;
    float innerR = outerR * 0.82f;

    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            float dx = x - center;
            float dy = y - center;
            float dist = std::sqrt(dx * dx + dy * dy);
            size_t idx = (size_t(y) * size + x) * 4;

            if (dist <= outerR) {
                float alpha = 0.0f;
                uint8_t cr = 220, cg = 220, cb = 230;

                if (dist >= innerR) {
                    // Outer ring
                    float edgeOut = std::max(0.0f, std::min(1.0f, outerR - dist));
                    float edgeIn = std::max(0.0f, std::min(1.0f, dist - innerR));
                    alpha = edgeOut * edgeIn * 190.0f;
                } else {
                    // Translucent frosted glass dish
                    alpha = 50.0f * (1.0f - dist / innerR * 0.5f);
                    cr = 80; cg = 100; cb = 120;
                }

                sp.pixels[idx + 0] = cr;
                sp.pixels[idx + 1] = cg;
                sp.pixels[idx + 2] = cb;
                sp.pixels[idx + 3] = uint8_t(alpha);
            }
        }
    }

    // Directional tick marks (Up, Down, Left, Right)
    int tickLen = int(size * 0.08f);
    int mid = int(center);
    for (int i = 0; i < tickLen; ++i) {
        // Up
        drawCharToBuffer(sp.pixels, size, size, mid, mid - int(innerR) - i, '|', 1, 255, 255, 255, 180);
        // Down
        drawCharToBuffer(sp.pixels, size, size, mid, mid + int(innerR) + i, '|', 1, 255, 255, 255, 180);
        // Left
        drawCharToBuffer(sp.pixels, size, size, mid - int(innerR) - i, mid, '-', 1, 255, 255, 255, 180);
        // Right
        drawCharToBuffer(sp.pixels, size, size, mid + int(innerR) + i, mid, '-', 1, 255, 255, 255, 180);
    }

    return sp;
}

// Generate main joystick knob sprite
Sprite makeStickKnobSprite(int size, bool pressed)
{
    Sprite sp;
    sp.w = size;
    sp.h = size;
    sp.pixels.resize(size * size * 4, 0);

    float center = (size - 1) * 0.5f;
    float outerR = size * 0.46f;
    float ringR = outerR * 0.60f;

    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            float dx = x - center;
            float dy = y - center;
            float dist = std::sqrt(dx * dx + dy * dy);
            size_t idx = (size_t(y) * size + x) * 4;

            if (dist <= outerR) {
                float edge = std::max(0.0f, std::min(1.0f, outerR - dist));
                uint8_t cr, cg, cb;
                uint8_t alpha = uint8_t(edge * (pressed ? 245 : 210));

                if (pressed) {
                    cr = 65; cg = 165; cb = 245; // Vibrant glowing blue knob
                } else {
                    cr = 160; cg = 175; cb = 195; // Tactile GameCube grey
                }

                // Inner concentric grip ring
                if (std::abs(dist - ringR) < 1.5f) {
                    cr = std::min(255, cr + 50);
                    cg = std::min(255, cg + 50);
                    cb = std::min(255, cb + 50);
                }

                sp.pixels[idx + 0] = cr;
                sp.pixels[idx + 1] = cg;
                sp.pixels[idx + 2] = cb;
                sp.pixels[idx + 3] = alpha;
            }
        }
    }
    return sp;
}

// Generate C-stick base ring sprite (yellow theme)
Sprite makeCStickBaseSprite(int size)
{
    Sprite sp;
    sp.w = size;
    sp.h = size;
    sp.pixels.resize(size * size * 4, 0);

    float center = (size - 1) * 0.5f;
    float outerR = size * 0.47f;
    float innerR = outerR * 0.82f;

    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            float dx = x - center;
            float dy = y - center;
            float dist = std::sqrt(dx * dx + dy * dy);
            size_t idx = (size_t(y) * size + x) * 4;

            if (dist <= outerR) {
                float alpha = 0.0f;
                uint8_t cr = 241, cg = 196, cb = 15;

                if (dist >= innerR) {
                    float edgeOut = std::max(0.0f, std::min(1.0f, outerR - dist));
                    float edgeIn = std::max(0.0f, std::min(1.0f, dist - innerR));
                    alpha = edgeOut * edgeIn * 200.0f;
                } else {
                    alpha = 45.0f * (1.0f - dist / innerR * 0.5f);
                    cr = 140; cg = 120; cb = 30;
                }

                sp.pixels[idx + 0] = cr;
                sp.pixels[idx + 1] = cg;
                sp.pixels[idx + 2] = cb;
                sp.pixels[idx + 3] = uint8_t(alpha);
            }
        }
    }
    return sp;
}

// Generate C-stick yellow knob sprite
Sprite makeCStickKnobSprite(int size, bool pressed)
{
    Sprite sp;
    sp.w = size;
    sp.h = size;
    sp.pixels.resize(size * size * 4, 0);

    float center = (size - 1) * 0.5f;
    float outerR = size * 0.46f;
    float ringR = outerR * 0.60f;

    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            float dx = x - center;
            float dy = y - center;
            float dist = std::sqrt(dx * dx + dy * dy);
            size_t idx = (size_t(y) * size + x) * 4;

            if (dist <= outerR) {
                float edge = std::max(0.0f, std::min(1.0f, outerR - dist));
                uint8_t cr = 241, cg = 196, cb = 15; // Iconic GameCube C-Stick yellow
                uint8_t alpha = uint8_t(edge * (pressed ? 245 : 210));

                if (pressed) {
                    cr = 255; cg = 230; cb = 50; // Glowing yellow
                } else {
                    float grad = 1.0f - (dist / outerR) * 0.25f;
                    cr = uint8_t(241 * grad);
                    cg = uint8_t(196 * grad);
                    cb = uint8_t(15 * grad);
                }

                if (std::abs(dist - ringR) < 1.5f) {
                    cr = std::min(255, cr + 40);
                    cg = std::min(255, cg + 40);
                    cb = std::min(255, cb + 40);
                }

                sp.pixels[idx + 0] = cr;
                sp.pixels[idx + 1] = cg;
                sp.pixels[idx + 2] = cb;
                sp.pixels[idx + 3] = alpha;
            }
        }
    }

    // Draw 'C' in the center of the knob
    int fontScale = size >= 60 ? 2 : 1;
    drawCharToBuffer(sp.pixels, size, size, (int)center, (int)center, 'C', fontScale, 40, 40, 40, 240);

    return sp;
}

void rebuildSprites()
{
    // new sprites may get the old ones' memory: the cached textures go
    if (GXPC_ForgetOverlayCache) GXPC_ForgetOverlayCache();
    int baseStickSize = int(s_winH * s_stickL.r * 2.0f);
    if (baseStickSize < 48) baseStickSize = 48;
    if (baseStickSize > 400) baseStickSize = 400;
    s_stickBaseSprite = makeStickBaseSprite(baseStickSize);

    int knobSize = int(baseStickSize * 0.44f);
    s_stickKnobSprite = makeStickKnobSprite(knobSize, false);
    s_stickKnobPressedSprite = makeStickKnobSprite(knobSize, true);

    int cstickBaseSize = int(s_winH * s_cstickL.r * 2.0f);
    if (cstickBaseSize < 40) cstickBaseSize = 40;
    if (cstickBaseSize > 360) cstickBaseSize = 360;
    s_cstickBaseSprite = makeCStickBaseSprite(cstickBaseSize);

    int cstickKnobSize = int(cstickBaseSize * 0.46f);
    s_cstickKnobSprite = makeCStickKnobSprite(cstickKnobSize, false);
    s_cstickKnobPressedSprite = makeCStickKnobSprite(cstickKnobSize, true);

    for (int i = 0; i < TBTN_COUNT; ++i) {
        int btnPx = int(s_buttons[i].relRadius * 2.0f * s_winH);
        if (btnPx < 24) btnPx = 24;
        if (btnPx > 320) btnPx = 320;
        s_buttonSprites[i][0] = makeButtonSprite(btnPx, s_buttons[i].baseR, s_buttons[i].baseG, s_buttons[i].baseB, s_buttons[i].label, false);
        s_buttonSprites[i][1] = makeButtonSprite(btnPx, s_buttons[i].baseR, s_buttons[i].baseG, s_buttons[i].baseB, s_buttons[i].label, true);
    }
}

} // namespace

extern "C" {

void touch_controller_init(void)
{
    if (s_inited) return;
    s_inited = true;
    loadLayout();
    s_lastTouch = nowSec();

    s_stick.active = false;
    s_stick.fingerId = -1;
    s_stick.stickX = 0;
    s_stick.stickY = 0;
    s_cstick.active = false;
    s_cstick.fingerId = -1;
    s_cstick.stickX = 0;
    s_cstick.stickY = 0;
    placeSticks();

    rebuildSprites();
}

void touch_controller_resize(int winW, int winH)
{
    if (winW <= 0 || winH <= 0) return;
    if (winW == s_winW && winH == s_winH && s_inited) return;

    s_winW = winW;
    s_winH = winH;
    loadLayout();
    if (!s_inited) s_lastTouch = nowSec();
    placeSticks();

    rebuildSprites();
    s_inited = true;
}

void touch_controller_on_finger_down(int64_t fingerId, float normX, float normY)
{
    if (!s_visible) return;
    if (!s_inited) touch_controller_init();

    float px = normX * s_winW;
    float py = normY * s_winH;
    s_lastTouch = nowSec(); // wakes faded controls

    // Check buttons first
    for (int i = 0; i < TBTN_COUNT; ++i) {
        if (s_buttons[i].hidden) continue;
        float bx = s_buttons[i].relX * s_winW;
        float by = s_buttons[i].relY * s_winH;
        float br = s_buttons[i].relRadius * s_winH;
        float dx = px - bx;
        float dy = py - by;

        if (std::sqrt(dx * dx + dy * dy) <= br * 1.25f) {
            s_buttons[i].pressed = true;
            s_buttons[i].fingerId = fingerId;

            if (s_buttons[i].id == TBTN_OPACITY) {
                s_hideOthers = !s_hideOthers;
                s_buttons[i].label = s_hideOthers ? "SHOW" : "HIDE";
                rebuildSprites(); // its new label
            } else if (s_hideOthers) {
                s_buttons[i].pressed = false;
                s_buttons[i].fingerId = -1;
                continue; // hidden: only the HUD button reacts
            }
            if (s_buttons[i].id == TBTN_MENU) {
                if (port_open_menu) port_open_menu();
            }
            if (s_buttons[i].id == TBTN_SAVE || s_buttons[i].id == TBTN_LOAD) {
                int which = s_buttons[i].id == TBTN_LOAD;
                s_stateSince[which] = nowSec();
                s_stateFired[which] = false;
                if (!s_stateDelay) stateFire(which);
            }
            return;
        }
    }

    if (s_hideOthers) return; // the sticks and the camera are hidden too

    // Main stick: around its place (and, while it sits on the left, the whole
    // lower-left area, as before)
    float hx = s_stickL.x * s_winW, hy = s_stickL.y * s_winH, hr = s_stickL.r * s_winH;
    bool nearStick = std::sqrt((px - hx) * (px - hx) + (py - hy) * (py - hy)) <= hr * 3.0f;
    bool leftArea = s_stickL.x < 0.4f && px < s_winW * 0.40f && py > s_winH * 0.28f;
    if (!s_stickL.hidden && (nearStick || leftArea)) {
        if (!s_stick.active) {
            s_stick.active = true;
            s_stick.fingerId = fingerId;
            s_stick.anchorX = px;
            s_stick.anchorY = py;
            s_stick.currX = px;
            s_stick.currY = py;
            s_stick.stickX = 0;
            s_stick.stickY = 0;
            return;
        }
    }

    // Right-center area: C-Stick touch zone
    float cdx = px - s_cstickL.x * s_winW;
    float cdy = py - s_cstickL.y * s_winH;
    float cdist = std::sqrt(cdx * cdx + cdy * cdy);
    if (!s_cstickL.hidden && cdist <= s_cstickL.r * s_winH * 2.2f) {
        if (!s_cstick.active) {
            s_cstick.active = true;
            s_cstick.fingerId = fingerId;
            s_cstick.anchorX = px;
            s_cstick.anchorY = py;
            s_cstick.currX = px;
            s_cstick.currY = py;
            s_cstick.stickX = 0;
            s_cstick.stickY = 0;
            return;
        }
    }

    // Right-upper/middle area: Camera swipe look
    if (px >= s_winW * 0.45f && px <= s_winW * 0.78f) {
        if (!s_cam.active) {
            s_cam.active = true;
            s_cam.fingerId = fingerId;
            s_cam.lastX = px;
            s_cam.lastY = py;
            s_cam.substickX = 0;
            s_cam.substickY = 0;
            return;
        }
    }
}

void touch_controller_on_finger_move(int64_t fingerId, float normX, float normY)
{
    if (!s_visible) return;
    s_lastTouch = nowSec();
    float px = normX * s_winW;
    float py = normY * s_winH;

    // Update main joystick if finger matches
    if (s_stick.active && s_stick.fingerId == fingerId) {
        s_stick.currX = px;
        s_stick.currY = py;

        float dx = px - s_stick.anchorX;
        float dy = py - s_stick.anchorY;
        float dist = std::sqrt(dx * dx + dy * dy);
        float maxR = s_stick.radius;

        if (dist > maxR && dist > 0.001f) {
            dx = (dx / dist) * maxR;
            dy = (dy / dist) * maxR;
            s_stick.currX = s_stick.anchorX + dx;
            s_stick.currY = s_stick.anchorY + dy;
            dist = maxR;
        }

        float normDist = dist / maxR;
        if (normDist < 0.10f) {
            s_stick.stickX = 0;
            s_stick.stickY = 0;
        } else {
            s_stick.stickX = (int)std::round((dx / maxR) * 100.0f);
            s_stick.stickY = (int)std::round((-dy / maxR) * 100.0f); // Invert Y: up is positive
        }
        return;
    }

    // Update C-stick if finger matches
    if (s_cstick.active && s_cstick.fingerId == fingerId) {
        s_cstick.currX = px;
        s_cstick.currY = py;

        float dx = px - s_cstick.anchorX;
        float dy = py - s_cstick.anchorY;
        float dist = std::sqrt(dx * dx + dy * dy);
        float maxR = s_cstick.radius;

        if (dist > maxR && dist > 0.001f) {
            dx = (dx / dist) * maxR;
            dy = (dy / dist) * maxR;
            s_cstick.currX = s_cstick.anchorX + dx;
            s_cstick.currY = s_cstick.anchorY + dy;
            dist = maxR;
        }

        float normDist = dist / maxR;
        if (normDist < 0.10f) {
            s_cstick.stickX = 0;
            s_cstick.stickY = 0;
        } else {
            s_cstick.stickX = (int)std::round((dx / maxR) * 100.0f);
            s_cstick.stickY = (int)std::round((-dy / maxR) * 100.0f);
        }
        return;
    }

    // Update camera swipe if finger matches
    if (s_cam.active && s_cam.fingerId == fingerId) {
        float dx = px - s_cam.lastX;
        float dy = py - s_cam.lastY;
        s_cam.lastX = px;
        s_cam.lastY = py;

        // Scale swipe delta to C-stick values
        s_cam.substickX = (int)std::max(-100.0f, std::min(100.0f, dx * 4.0f));
        s_cam.substickY = (int)std::max(-100.0f, std::min(100.0f, -dy * 4.0f));
        return;
    }

    // Check sliding into or out of buttons
    for (int i = 0; i < TBTN_COUNT; ++i) {
        if (s_buttons[i].fingerId == fingerId) {
            float bx = s_buttons[i].relX * s_winW;
            float by = s_buttons[i].relY * s_winH;
            float br = s_buttons[i].relRadius * s_winH;
            float dx = px - bx;
            float dy = py - by;
            if (std::sqrt(dx * dx + dy * dy) > br * 1.5f) {
                s_buttons[i].pressed = false;
                s_buttons[i].fingerId = -1;
            }
        }
    }
}

void touch_controller_on_finger_up(int64_t fingerId, float /*normX*/, float /*normY*/)
{
    // Release main joystick
    if (s_stick.active && s_stick.fingerId == fingerId) {
        s_stick.active = false;
        s_stick.fingerId = -1;
        s_stick.currX = s_stick.anchorX;
        s_stick.currY = s_stick.anchorY;
        s_stick.stickX = 0;
        s_stick.stickY = 0;
    }

    // Release C-stick
    if (s_cstick.active && s_cstick.fingerId == fingerId) {
        s_cstick.active = false;
        s_cstick.fingerId = -1;
        s_cstick.currX = s_cstick.anchorX;
        s_cstick.currY = s_cstick.anchorY;
        s_cstick.stickX = 0;
        s_cstick.stickY = 0;
    }

    // Release camera swipe
    if (s_cam.active && s_cam.fingerId == fingerId) {
        s_cam.active = false;
        s_cam.fingerId = -1;
        s_cam.substickX = 0;
        s_cam.substickY = 0;
    }

    // Release buttons
    for (int i = 0; i < TBTN_COUNT; ++i) {
        if (s_buttons[i].fingerId == fingerId) {
            if ((s_buttons[i].id == TBTN_SAVE || s_buttons[i].id == TBTN_LOAD) && s_buttons[i].pressed) {
                int which = s_buttons[i].id == TBTN_LOAD;
                if (!s_stateFired[which] && s_stateSince[which] >= 0)  // let go too soon: say why nothing happened
                    port_savestate_notice(which ? "Hold LOAD to load" : "Hold SAVE to save");
                s_stateSince[which] = -1;
            }
            s_buttons[i].pressed = false;
            s_buttons[i].fingerId = -1;
        }
    }
}

void touch_controller_update_pad(PADStatus* status)
{
    if (!status || !s_visible) return;

    // SAVE and LOAD act once held long enough (kStateHoldSeconds)
    for (int which = 0; which < 2; which++)
        if (s_buttons[which ? TBTN_LOAD : TBTN_SAVE].pressed && !s_stateFired[which] && s_stateSince[which] >= 0
            && nowSec() - s_stateSince[which] >= kStateHoldSeconds)
            stateFire(which);

    // Merge digital buttons
    for (int i = 0; i < TBTN_COUNT; ++i) {
        if (s_buttons[i].pressed && s_buttons[i].padMask != 0) {
            status->button |= s_buttons[i].padMask;
        }
    }

    // Trigger L
    if (s_buttons[TBTN_L].pressed) {
        status->triggerLeft = 255;
        status->button |= PAD_TRIGGER_L;
    }

    // Trigger R modes:
    // TBTN_R: Soft spray (180) -> Mario runs while spraying!
    if (s_buttons[TBTN_R].pressed) {
        if (status->triggerRight < 180) status->triggerRight = 180;
    }
    // TBTN_R_HARD: Full spray (255) -> Lock precision aim spray!
    if (s_buttons[TBTN_R_HARD].pressed) {
        status->triggerRight = 255;
        status->button |= PAD_TRIGGER_R;
    }

    // Analog A / B
    if (s_buttons[TBTN_A].pressed) status->analogA = 255;
    if (s_buttons[TBTN_B].pressed) status->analogB = 255;

    // Left main analog stick
    if (s_stick.active && (s_stick.stickX != 0 || s_stick.stickY != 0)) {
        status->stickX = (int8_t)s_stick.stickX;
        status->stickY = (int8_t)s_stick.stickY;
    }

    // Right C-stick (Camera analog stick)
    if (s_cstick.active && (s_cstick.stickX != 0 || s_cstick.stickY != 0)) {
        status->substickX = (int8_t)port_camera_invert_touch(s_cstick.stickX, 0);
        status->substickY = (int8_t)port_camera_invert_touch(s_cstick.stickY, 1);
    } else if (s_cam.active) {
        status->substickX = (int8_t)s_cam.substickX;
        status->substickY = (int8_t)s_cam.substickY;
        // Decay swipe delta
        s_cam.substickX = s_cam.substickX * 2 / 3;
        s_cam.substickY = s_cam.substickY * 2 / 3;
    }
}

// a sprite: from its cached texture when sms_gx has them
static void drawSprite(const uint8_t* px, int w, int h, int x, int y, int winW, int winH)
{
    if (GXPC_DrawOverlayStatic) GXPC_DrawOverlayStatic(px, w, h, x, y, 1, winW, winH);
    else GXPC_DrawOverlay(px, w, h, x, y, 1, winW, winH);
}

void touch_controller_draw(int winW, int winH)
{
    // the in-game menu bumps SMS_TOUCH_REV after the touch settings changed
    // (opacity, hiding, layout): read them again
    static std::string s_rev;
    if (const char* rev = getenv("SMS_TOUCH_REV")) {
        if (s_rev != rev) {
            s_rev = rev;
            s_layoutLoaded = false;
            loadLayout();
            placeSticks();
            rebuildSprites();
        }
    }
    if (!s_visible || s_hudOpacity <= 0.01f || !GXPC_DrawOverlay) return;
    if (!s_inited || winW != s_winW || winH != s_winH) {
        touch_controller_resize(winW, winH);
    }

    // opacity, and the fade after SMS_TOUCH_AUTOHIDE seconds without a touch
    float alpha = s_hudOpacity / 0.70f; // the sprites are drawn for 70%
    if (s_autoHide > 0) {
        bool held = s_stick.active || s_cstick.active || s_cam.active;
        for (int i = 0; i < TBTN_COUNT && !held; ++i) held = s_buttons[i].pressed;
        if (held) s_lastTouch = nowSec();
        double idle = nowSec() - s_lastTouch;
        if (idle > s_autoHide) alpha *= float(std::max(0.0, 1.0 - (idle - s_autoHide) / 0.6));
    }
    if (alpha <= 0.01f) return;
    if (GXPC_SetOverlayAlpha) GXPC_SetOverlayAlpha(std::min(1.0f, alpha));

    // 1. Draw main virtual joystick (Left)
    float baseX = s_stick.active ? s_stick.anchorX : (s_winW * s_stickL.x);
    float baseY = s_stick.active ? s_stick.anchorY : (s_winH * s_stickL.y);
    int baseW = s_stickBaseSprite.w;
    int baseH = s_stickBaseSprite.h;
    int drawBaseX = int(baseX - baseW * 0.5f);
    int drawBaseY = int(baseY - baseH * 0.5f);

    if (!s_stickL.hidden && !s_hideOthers)
    drawSprite(s_stickBaseSprite.pixels.data(), baseW, baseH, drawBaseX, drawBaseY, winW, winH);

    // Main Knob
    float knobX = s_stick.active ? s_stick.currX : baseX;
    float knobY = s_stick.active ? s_stick.currY : baseY;
    const Sprite& knobSp = s_stick.active ? s_stickKnobPressedSprite : s_stickKnobSprite;
    int drawKnobX = int(knobX - knobSp.w * 0.5f);
    int drawKnobY = int(knobY - knobSp.h * 0.5f);

    if (!s_stickL.hidden && !s_hideOthers)
    drawSprite(knobSp.pixels.data(), knobSp.w, knobSp.h, drawKnobX, drawKnobY, winW, winH);

    // 2. Draw C-stick (Right)
    float cbaseX = s_cstick.active ? s_cstick.anchorX : (s_winW * s_cstickL.x);
    float cbaseY = s_cstick.active ? s_cstick.anchorY : (s_winH * s_cstickL.y);
    int cbaseW = s_cstickBaseSprite.w;
    int cbaseH = s_cstickBaseSprite.h;
    int drawCBaseX = int(cbaseX - cbaseW * 0.5f);
    int drawCBaseY = int(cbaseY - cbaseH * 0.5f);

    if (!s_cstickL.hidden && !s_hideOthers)
    drawSprite(s_cstickBaseSprite.pixels.data(), cbaseW, cbaseH, drawCBaseX, drawCBaseY, winW, winH);

    float cknobX = s_cstick.active ? s_cstick.currX : cbaseX;
    float cknobY = s_cstick.active ? s_cstick.currY : cbaseY;
    const Sprite& cknobSp = s_cstick.active ? s_cstickKnobPressedSprite : s_cstickKnobSprite;
    int drawCKnobX = int(cknobX - cknobSp.w * 0.5f);
    int drawCKnobY = int(cknobY - cknobSp.h * 0.5f);

    if (!s_cstickL.hidden && !s_hideOthers)
    drawSprite(cknobSp.pixels.data(), cknobSp.w, cknobSp.h, drawCKnobX, drawCKnobY, winW, winH);

    // 3. Draw all buttons
    for (int i = 0; i < TBTN_COUNT; ++i) {
        const Sprite& sp = s_buttonSprites[i][s_buttons[i].pressed ? 1 : 0];
        if (sp.pixels.empty() || s_buttons[i].hidden) continue;
        if (s_hideOthers && s_buttons[i].id != TBTN_OPACITY) continue;

        int bx = int(s_buttons[i].relX * winW - sp.w * 0.5f);
        int by = int(s_buttons[i].relY * winH - sp.h * 0.5f);

        drawSprite(sp.pixels.data(), sp.w, sp.h, bx, by, winW, winH);
    }
    if (GXPC_SetOverlayAlpha) GXPC_SetOverlayAlpha(1.0f);
}

void touch_controller_set_opacity(float opacity)
{
    s_hudOpacity = std::max(0.0f, std::min(1.0f, opacity));
}

float touch_controller_get_opacity(void)
{
    return s_hudOpacity;
}

void touch_controller_cycle_opacity(void)
{
    if (s_hudOpacity >= 0.85f) {
        s_hudOpacity = 0.25f;
    } else if (s_hudOpacity >= 0.65f) {
        s_hudOpacity = 0.90f;
    } else if (s_hudOpacity >= 0.40f) {
        s_hudOpacity = 0.70f;
    } else {
        s_hudOpacity = 0.45f;
    }
}

void touch_controller_set_visible(bool visible)
{
    s_visible = visible;
}

bool touch_controller_is_visible(void)
{
    return s_visible;
}

} // extern "C"

void touch_controller_set_state_delay(int on) { s_stateDelay = on != 0; }
