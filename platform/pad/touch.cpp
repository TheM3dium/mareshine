// Mareshine's touch controls (touch.h), written for this base: a left stick
// and a C-stick that start where the thumb lands, a camera that turns when the
// free part of the screen is swiped, and buttons for A, B, X, Y, L, R, R+, Z,
// START, HIDE (the others hide and stop reacting), MENU (the app's quick
// menu) and SAVE / LOAD (save states, once held). The app's touch settings
// and layout editor set them (settings.txt, read again when SMS_TOUCH_REV
// changes):
//   SMS_TOUCH_CONTROLS    auto (hidden after a controller is used, until the
//                         screen is touched), on, off
//   SMS_TOUCH_LAYOUT      "NAME=x,y,size,shown;..." for the names in kDefs
//                         (SMS_TOUCH_LAYOUT_GAMECUBE for SMS_TOUCH_STYLE=gamecube),
//                         x and y fractions of the screen, size of its height
//   SMS_TOUCH_OPACITY     percent; SMS_TOUCH_AUTOHIDE: seconds without a touch
//                         after which they fade out (0 never)
//   SMS_TOUCH_R_SLIDER    one R button for both stages: out of a circle of
//                         SMS_TOUCH_R_SLIDE (of the screen height) it is R+
//   SMS_TOUCH_HAPTIC      a tick for every button pressed
//   SMS_SWIPE_INVERT_X/Y  the swipe camera's own inversion (X on by default);
//                         the touch C-stick follows SMS_CAMERA_INVERT_X/Y
//   SMS_SAVESTATE_DELAY=0 SAVE and LOAD act at once instead of once held
#include "touch.h"
#include "touch_gamecube.h"
#include "port_savestate.h"
#include <algorithm>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <string>
#include <vector>

extern "C" __attribute__((weak)) int SDL_AndroidSendMessage(uint32_t command, int param);
// The GX layer's sprites (sms_gx/gx_pc.h; weak, so the port links without it)
typedef struct GXPCSprite {
	float x, y, w, h;
	int u, v, uw, vh;
} GXPCSprite;
extern "C" __attribute__((weak)) void GXPC_SpriteAtlas(const uint8_t* rgba, int w, int h);
extern "C" __attribute__((weak)) void GXPC_DrawSprites(const GXPCSprite* sprites, int count, float alpha, int winW,
                                                        int winH);
extern "C" __attribute__((weak)) void port_savestate_notice(const char* text);
extern "C" __attribute__((weak)) int port_savestate_progress(int* kind);
extern "C" __attribute__((weak)) int port_mp4_showing(void); // platform/thp/mp4_movies.cpp

namespace {

enum Id { STICK, CSTICK, BTN_A, BTN_B, BTN_X, BTN_Y, BTN_L, BTN_R, BTN_RPLUS, BTN_Z, BTN_START, BTN_HUD, BTN_MENU,
          BTN_SAVE, BTN_LOAD, ID_COUNT };

struct Def {
	const char* name;  // in SMS_TOUCH_LAYOUT (and the app's layout editor)
	const char* label;
	float x, y, r;     // default place and size
	uint32_t rgb;
};
// The places and colours the app's layout editor knows (TouchLayoutActivity).
const Def kDefs[ID_COUNT] = {
	{ "STICK", "", 0.16f, 0.72f, 0.14f, 0xBFC9D4 },     { "CSTICK", "C", 0.61f, 0.76f, 0.11f, 0xF1C40F },
	{ "A", "A", 0.90f, 0.74f, 0.082f, 0x2ECC71 },       { "B", "B", 0.80f, 0.82f, 0.072f, 0xE74C3C },
	{ "X", "X", 0.82f, 0.62f, 0.062f, 0xF1C40F },       { "Y", "Y", 0.73f, 0.70f, 0.062f, 0x3498DB },
	{ "L", "L", 0.08f, 0.16f, 0.072f, 0x95A5A6 },       { "R", "R", 0.91f, 0.52f, 0.072f, 0x2980B9 },
	{ "RPLUS", "R+", 0.82f, 0.44f, 0.062f, 0x1F4E79 },  { "Z", "Z", 0.90f, 0.30f, 0.062f, 0x9B59B6 },
	{ "START", "START", 0.50f, 0.08f, 0.050f, 0xE67E22 }, { "HUD", "HIDE", 0.40f, 0.08f, 0.045f, 0x7F8C8D },
	{ "MENU", "MENU", 0.60f, 0.08f, 0.050f, 0x455A64 }, { "SAVE", "SAVE", 0.74f, 0.08f, 0.042f, 0x16A085 },
	{ "LOAD", "LOAD", 0.83f, 0.08f, 0.042f, 0xD35400 },
};

// The GameCube style's default places (unless the layout has its own): A big
// in the middle of the right hand, B below left, X right, Y above, Z and R
// just above them on one line, R+ beyond R, L as high as R.
// How far L (mirrored), R and R+ are turned so their flat side falls as Z's
// long side does: Z lies at 28.1 degrees, L's and R's pictures at 21.6 and
// 21.4 (R+ is R pressed in, in R's frame: turned as R).
const float kGcTilt[3] = { (28.1f - 21.6f) * 3.14159f / 180, (28.1f - 21.4f) * 3.14159f / 180,
                           (28.1f - 21.4f) * 3.14159f / 180 };

struct GcPlace {
	int id;
	float x, y, r;
};
const GcPlace kGcPlaces[] = {
	{ STICK, 0.16f, 0.76f, 0.14f },     // as high as the C-stick
	{ BTN_A, 0.86f, 0.70f, 0.090f },    { BTN_B, 0.775f, 0.80f, 0.060f },  { BTN_X, 0.937f, 0.628f, 0.068f },
	{ BTN_Y, 0.85f, 0.52f, 0.068f },    { BTN_Z, 0.95f, 0.45f, 0.055f },
	// R further up Z's line (its tilt, kGcTilt), R+ further still, L as high as R
	{ BTN_R, 0.877f, 0.366f, 0.075f }, { BTN_RPLUS, 0.804f, 0.281f, 0.075f }, { BTN_L, 0.12f, 0.366f, 0.075f },
};

struct Place {
	float x, y, r;
	bool shown;
};

// --- settings ------------------------------------------------------------------

struct Config {
	int mode = 0;  // 0 auto, 1 on, 2 off
	float opacity = 0.7f, autoHide = 0, rSlide = 0.06f;
	bool rSlider = false, haptic = false, stateDelay = true;
	int style = 0;  // 0 classic discs, 1 GameCube outlines (SMS_TOUCH_STYLE=gamecube)
	bool swipeX = true, swipeY = false, camX = false, camY = false;
	Place place[ID_COUNT];
};

bool envOn(const char* name, bool dflt)
{
	const char* e = getenv(name);
	if (!e || !*e)
		return dflt;
	return strcmp(e, "0") != 0 && strcmp(e, "off") != 0;
}

float envFloat(const char* name, float dflt)
{
	const char* e = getenv(name);
	return e && *e ? (float)atof(e) : dflt;
}

void readConfig(Config& c)
{
	c = Config();
	const char* style = getenv("SMS_TOUCH_STYLE");
	c.style = style && !strcmp(style, "gamecube") ? 1 : 0;
	const char* mode = getenv("SMS_TOUCH_CONTROLS");
	if (mode && (!strcmp(mode, "on") || !strcmp(mode, "1")))
		c.mode = 1;
	else if (mode && (!strcmp(mode, "off") || !strcmp(mode, "0")))
		c.mode = 2;
	float pct = envFloat("SMS_TOUCH_OPACITY", 70);
	c.opacity = pct < 5 ? 0.05f : pct > 100 ? 1.0f : pct / 100;
	c.autoHide = envFloat("SMS_TOUCH_AUTOHIDE", 0);
	c.rSlider = envOn("SMS_TOUCH_R_SLIDER", false);
	float slide = envFloat("SMS_TOUCH_R_SLIDE", 0.06f);
	c.rSlide = slide < 0.02f ? 0.02f : slide > 0.4f ? 0.4f : slide;
	c.haptic = envOn("SMS_TOUCH_HAPTIC", false);
	c.stateDelay = envOn("SMS_SAVESTATE_DELAY", true);
	c.swipeX = envOn("SMS_SWIPE_INVERT_X", true);
	c.swipeY = envOn("SMS_SWIPE_INVERT_Y", false);
	c.camX = envOn("SMS_CAMERA_INVERT_X", false);
	c.camY = envOn("SMS_CAMERA_INVERT_Y", false);
	for (int i = 0; i < ID_COUNT; i++)
		c.place[i] = { kDefs[i].x, kDefs[i].y, kDefs[i].r, true };
	if (c.style == 1)  // the GameCube style: the buttons where the controller has them
		for (const GcPlace& g : kGcPlaces)
			c.place[g.id] = { g.x, g.y, g.r, true };
	// each style has its own layout (the app's layout editor switches them)
	const char* layout = getenv(c.style == 1 ? "SMS_TOUCH_LAYOUT_GAMECUBE" : "SMS_TOUCH_LAYOUT");
	for (const char* p = layout; p && *p;) {
		const char* end = strchr(p, ';');
		std::string item(p, end ? (size_t)(end - p) : strlen(p));
		p = end ? end + 1 : p + strlen(p);
		size_t eq = item.find('=');
		if (eq == std::string::npos)
			continue;
		float x, y, r;
		int shown = 1;
		if (sscanf(item.c_str() + eq + 1, "%f,%f,%f,%d", &x, &y, &r, &shown) < 3)
			continue;
		for (int i = 0; i < ID_COUNT; i++)
			if (item.compare(0, eq, kDefs[i].name) == 0) {
				x = x < 0 ? 0 : x > 1 ? 1 : x;
				y = y < 0 ? 0 : y > 1 ? 1 : y;
				r = r < 0.02f ? 0.02f : r > 0.4f ? 0.4f : r;
				c.place[i] = { x, y, r, shown != 0 };
			}
	}
}

// --- state ---------------------------------------------------------------------

double now()
{
	timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return t.tv_sec + t.tv_nsec * 1e-9;
}

enum { NONE = -1, CAMERA = ID_COUNT };

struct Finger {
	int64_t id;
	int holds = NONE;     // an Id, CAMERA or NONE
	float ax, ay;         // where a stick started (pixels)
	float x, y;           // where it is
};

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
Config g_cfg;
std::string g_rev = "?";
float g_w = 1920, g_h = 1080;     // the window, from the last draw
Finger g_fingers[10];
int g_fingerCount;
bool g_hideOthers, g_hiddenByPad, g_rDeep;
uint32_t g_tapped;               // buttons touched since the last pad read (touch_controls_pad)
float g_swipeX, g_swipeY;         // the camera swipe's last movement (C-stick units)
double g_lastTouch, g_stateSince[2] = { -1, -1 };
bool g_stateFired[2];
bool g_spritesDirty = true;

void refreshConfig()
{
	const char* rev = getenv("SMS_TOUCH_REV");
	std::string r = rev ? rev : "";
	if (r == g_rev)
		return;
	g_rev = r;
	readConfig(g_cfg);
	g_spritesDirty = true;
}

bool off(int id)
{
	const Place& p = g_cfg.place[id];
	if (!p.shown)
		return true;
	if (g_cfg.rSlider && id == BTN_RPLUS)
		return true;
	return g_hideOthers && id != BTN_HUD;
}

bool visible()
{
	if (g_cfg.mode == 2)
		return false;
	return g_cfg.mode == 1 || !g_hiddenByPad;
}

void sendMessage(uint32_t command, int param)
{
	if (SDL_AndroidSendMessage)
		SDL_AndroidSendMessage(command, param);
}

// kind 1: a tick, 2: a click (the R slider at R+); off with the phone's vibration at 0%
void haptic(int kind)
{
	const char* v = getenv("SMS_VIBRATION_PHONE");
	if (v && *v && atoi(v) <= 0)
		return;
	sendMessage(0x8007, kind); // SunshineActivity.COMMAND_HAPTIC
}

Finger* finger(int64_t id)
{
	for (int i = 0; i < g_fingerCount; i++)
		if (g_fingers[i].id == id)
			return &g_fingers[i];
	return nullptr;
}

bool held(int id)
{
	for (int i = 0; i < g_fingerCount; i++)
		if (g_fingers[i].holds == id)
			return true;
	return false;
}

float dist(float ax, float ay, float bx, float by) { return sqrtf((ax - bx) * (ax - bx) + (ay - by) * (ay - by)); }

void slideR(Finger& f)
{
	const Place& r = g_cfg.place[BTN_R];
	const float d = dist(f.x, f.y, r.x * g_w, r.y * g_h), reach = g_cfg.rSlide * g_h;
	if (!g_rDeep && d > reach) {
		g_rDeep = true;
		haptic(2);
	} else if (g_rDeep && d < reach * 0.5f) {
		g_rDeep = false;
		haptic(2);
	}
}

void stateButton(int which, bool down)
{
	if (down) {
		g_stateSince[which] = now();
		g_stateFired[which] = false;
		if (!g_cfg.stateDelay) {
			g_stateFired[which] = true;
			port_savestate_hotkey(which ? PORT_SAVESTATE_HOTKEY_LOAD_NEWEST : PORT_SAVESTATE_HOTKEY_SAVE);
		}
		return;
	}
	if (!g_stateFired[which] && g_stateSince[which] >= 0 && port_savestate_notice)
		port_savestate_notice(which ? "Hold LOAD to load" : "Hold SAVE to save");
	g_stateSince[which] = -1;
}

// What a new finger at (x, y) takes hold of.
int pick(float x, float y)
{
	int best = NONE;
	float bestD = 1e9f;
	for (int i = BTN_A; i < ID_COUNT; i++) {
		if (off(i))
			continue;
		const Place& p = g_cfg.place[i];
		const float d = dist(x, y, p.x * g_w, p.y * g_h);
		if (d <= p.r * g_h * 1.25f && d < bestD) {
			best = i;
			bestD = d;
		}
	}
	if (best != NONE || g_hideOthers)
		return best;
	const Place& s = g_cfg.place[STICK];
	if (s.shown && !held(STICK)
	    && (dist(x, y, s.x * g_w, s.y * g_h) <= s.r * g_h * 3
	        || (s.x < 0.4f && x < g_w * 0.4f && y > g_h * 0.28f)))
		return STICK;
	const Place& c = g_cfg.place[CSTICK];
	if (c.shown && !held(CSTICK) && dist(x, y, c.x * g_w, c.y * g_h) <= c.r * g_h * 2.2f)
		return CSTICK;
	if (x >= g_w * 0.45f && x <= g_w * 0.78f && !held(CAMERA))
		return CAMERA;
	return NONE;
}

void down(int64_t id, float x, float y)
{
	if (g_fingerCount >= 10 || finger(id))
		return;
	Finger& f = g_fingers[g_fingerCount++];
	f = Finger();
	f.id = id;
	f.ax = f.x = x;
	f.ay = f.y = y;
	f.holds = pick(x, y);
	if (f.holds >= 0 && f.holds < ID_COUNT)
		g_tapped |= 1u << f.holds;
	switch (f.holds) {
	case BTN_HUD:
		g_hideOthers = !g_hideOthers;
		g_spritesDirty = true;  // its label: HIDE or SHOW
		break;
	case BTN_MENU:
		sendMessage(0x8001, 0); // SunshineActivity.COMMAND_OPEN_MENU
		break;
	case BTN_SAVE:
	case BTN_LOAD:
		stateButton(f.holds == BTN_LOAD, true);
		break;
	case BTN_R:
		g_rDeep = false;
		break;
	default:
		break;
	}
	if (f.holds >= BTN_A && f.holds < ID_COUNT && g_cfg.haptic)
		haptic(1);
}

void move(int64_t id, float x, float y)
{
	Finger* f = finger(id);
	if (!f)
		return;
	const float dx = x - f->x, dy = y - f->y;
	f->x = x;
	f->y = y;
	if (f->holds == CAMERA) {
		g_swipeX = fmaxf(-100, fminf(100, dx * 4));
		g_swipeY = fmaxf(-100, fminf(100, -dy * 4));
	} else if (f->holds == BTN_R && g_cfg.rSlider) {
		slideR(*f);
	} else if (f->holds >= BTN_A && f->holds < ID_COUNT) {
		// a held button lets go only well outside its circle, so a creeping
		// thumb keeps a small one such as R+ pressed
		const Place& p = g_cfg.place[f->holds];
		const float reach = fmaxf(p.r * g_h * 2.5f, g_h * 0.12f);
		if (dist(x, y, p.x * g_w, p.y * g_h) > reach) {
			if (f->holds == BTN_SAVE || f->holds == BTN_LOAD)
				stateButton(f->holds == BTN_LOAD, false);
			f->holds = NONE;
		}
	}
}

void up(int64_t id)
{
	Finger* f = finger(id);
	if (!f)
		return;
	if (f->holds == BTN_SAVE || f->holds == BTN_LOAD)
		stateButton(f->holds == BTN_LOAD, false);
	if (f->holds == BTN_R)
		g_rDeep = false;
	if (f->holds == CAMERA)
		g_swipeX = g_swipeY = 0;
	*f = g_fingers[--g_fingerCount];
}

// A stick's value (-100..100 each way) for the finger holding it, and its knob's place.
bool stickValue(int id, int& vx, int& vy, float& kx, float& ky)
{
	for (int i = 0; i < g_fingerCount; i++) {
		const Finger& f = g_fingers[i];
		if (f.holds != id)
			continue;
		const float reach = g_cfg.place[id].r * g_h * 0.75f;
		float dx = f.x - f.ax, dy = f.y - f.ay, d = sqrtf(dx * dx + dy * dy);
		if (d > reach) {
			dx *= reach / d;
			dy *= reach / d;
			d = reach;
		}
		kx = f.ax + dx;
		ky = f.ay + dy;
		if (d < reach * 0.1f) {
			vx = vy = 0;
		} else {
			vx = (int)lroundf(dx / reach * 100);
			vy = (int)lroundf(-dy / reach * 100);
		}
		return true;
	}
	return false;
}

// --- pictures --------------------------------------------------------------------

// A 5 x 7 font for the labels, one byte per row (bit 4 the left column).
struct Glyph {
	char c;
	uint8_t rows[7];
};
const Glyph kFont[] = {
	{ 'A', { 0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11 } }, { 'B', { 0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E } },
	{ 'C', { 0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E } }, { 'D', { 0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E } },
	{ 'E', { 0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F } }, { 'H', { 0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11 } },
	{ 'I', { 0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E } }, { 'L', { 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F } },
	{ 'M', { 0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11 } }, { 'N', { 0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11 } },
	{ 'O', { 0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E } }, { 'R', { 0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11 } },
	{ 'S', { 0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E } }, { 'T', { 0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04 } },
	{ 'U', { 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E } }, { 'V', { 0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04 } },
	{ 'W', { 0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11 } }, { 'X', { 0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11 } },
	{ 'Y', { 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04 } }, { 'Z', { 0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F } },
	{ '+', { 0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00 } },
	{ 'P', { 0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10 } }, { 'G', { 0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F } },
	{ '0', { 0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E } }, { '1', { 0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E } },
	{ '2', { 0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F } }, { '3', { 0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E } },
	{ '4', { 0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02 } }, { '5', { 0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E } },
	{ '6', { 0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E } }, { '7', { 0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08 } },
	{ '8', { 0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E } }, { '9', { 0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C } },
	{ '%', { 0x18, 0x19, 0x02, 0x04, 0x08, 0x13, 0x03 } },
};

struct Image {
	int w = 0, h = 0;
	std::vector<uint8_t> px;
	void init(int size) { init(size, size); }
	void init(int width, int height)
	{
		w = width;
		h = height;
		px.assign((size_t)w * h * 4, 0);
	}
	void blend(int x, int y, uint32_t rgb, float a)
	{
		if (x < 0 || y < 0 || x >= w || y >= h || a <= 0)
			return;
		uint8_t* p = &px[((size_t)y * w + x) * 4];
		const float da = p[3] / 255.0f, oa = a + da * (1 - a);
		const uint8_t c[3] = { (uint8_t)(rgb >> 16), (uint8_t)(rgb >> 8), (uint8_t)rgb };
		for (int k = 0; k < 3; k++)
			p[k] = (uint8_t)lroundf(oa > 0 ? (c[k] * a + p[k] * da * (1 - a)) / oa : 0);
		p[3] = (uint8_t)lroundf(oa * 255);
	}
};

uint32_t shade(uint32_t rgb, float f)
{
	uint32_t out = 0;
	for (int k = 16; k >= 0; k -= 8) {
		float v = ((rgb >> k) & 0xFF) * f;
		out |= (uint32_t)(v > 255 ? 255 : v) << k;
	}
	return out;
}

// A disc of the given colour: a darker face, a light rim, the label in the
// middle; pressed, it is brighter. ring: only the rim and a faint face (a
// stick's base).
void makeDisc(Image& im, int size, uint32_t rgb, const char* label, bool pressed, bool ring)
{
	im.init(size);
	const float c = (size - 1) * 0.5f, rad = size * 0.47f, rim = fmaxf(2.0f, size * 0.045f);
	for (int y = 0; y < size; y++)
		for (int x = 0; x < size; x++) {
			const float d = dist((float)x, (float)y, c, c), edge = fminf(1.0f, rad - d + 0.5f);
			if (edge <= 0)
				continue;
			if (d >= rad - rim) {
				im.blend(x, y, shade(rgb, pressed ? 1.7f : 1.35f), edge * 0.95f);
			} else if (ring) {
				im.blend(x, y, shade(rgb, 0.6f), 0.28f);
			} else {
				const float depth = 1.0f - 0.3f * d / rad;  // lighter in the middle
				im.blend(x, y, shade(rgb, (pressed ? 1.45f : 0.95f) * depth), pressed ? 0.95f : 0.8f);
			}
		}
	const int n = (int)strlen(label);
	if (!n)
		return;
	// the label as wide as the disc allows, in whole pixels per font dot
	int dot = (int)(size * 0.55f / (n * 6 - 1));
	if (dot > (int)(size * 0.32f / 7))
		dot = (int)(size * 0.32f / 7);
	if (dot < 1)
		dot = 1;
	const int tw = (n * 6 - 1) * dot, th = 7 * dot;
	int x0 = (size - tw) / 2, y0 = (size - th) / 2;
	for (int i = 0; i < n; i++)
		for (const Glyph& g : kFont) {
			if (g.c != label[i])
				continue;
			for (int row = 0; row < 7; row++)
				for (int col = 0; col < 5; col++) {
					if (!(g.rows[row] & (0x10 >> col)))
						continue;
					for (int yy = 0; yy < dot; yy++)
						for (int xx = 0; xx < dot; xx++) {
							// a dark shadow, then the white dot
							im.blend(x0 + i * 6 * dot + col * dot + xx + 1, y0 + row * dot + yy + 1, 0x000000, 0.6f);
							im.blend(x0 + i * 6 * dot + col * dot + xx, y0 + row * dot + yy, 0xFFFFFF, 1.0f);
						}
				}
		}
}

// One character of the font, white with a shadow, `dot` pixels per font dot.
void makeGlyph(Image& im, int dot, char ch)
{
	im.init(5 * dot + 1, 7 * dot + 1);
	for (const Glyph& g : kFont) {
		if (g.c != ch)
			continue;
		for (int row = 0; row < 7; row++)
			for (int col = 0; col < 5; col++) {
				if (!(g.rows[row] & (0x10 >> col)))
					continue;
				for (int yy = 0; yy < dot; yy++)
					for (int xx = 0; xx < dot; xx++) {
						im.blend(col * dot + xx + 1, row * dot + yy + 1, 0x000000, 0.6f);
						im.blend(col * dot + xx, row * dot + yy, 0xFFFFFF, 1.0f);
					}
			}
	}
}

// The GameCube style (SMS_TOUCH_STYLE=gamecube): the controller's own shapes
// (touch_gamecube.h, traced from m-overlay's pictures) as outlines in the
// button's colour with its letter; pressed, filled. Drawn from the polygons at
// the size the button has on screen, so they stay sharp.

// Coverage (0..1) of the polygons, even-odd, scaled by s around the middle of a
// size x size picture and turned by rot; 4 x 4 samples a pixel.
void rasterPoly(std::vector<float>& cov, int size, const GcPoly& p, float s, float ox, float oy, float rot)
{
	cov.assign((size_t)size * size, 0.0f);
	std::vector<float> xs, ys;
	const float c = cosf(rot), sn = sinf(rot), mid = size * 0.5f;
	int at = 0;
	struct Edge {
		float x0, y0, x1, y1;
	};
	std::vector<Edge> edges;
	for (int r = 0; r < p.ringCount; r++) {
		const int n = p.rings[r];
		for (int k = 0; k < n; k++) {
			const float* a = &p.pts[(at + k) * 2];
			const float* b = &p.pts[(at + (k + 1) % n) * 2];
			Edge e = { mid + (a[0] * c - a[1] * sn) * s + ox, mid + (a[0] * sn + a[1] * c) * s + oy,
			           mid + (b[0] * c - b[1] * sn) * s + ox, mid + (b[0] * sn + b[1] * c) * s + oy };
			edges.push_back(e);
		}
		at += n;
	}
	std::vector<float> cross;
	const int sub = 4;
	for (int y = 0; y < size; y++)
		for (int k = 0; k < sub; k++) {
			const float yy = y + (k + 0.5f) / sub;
			cross.clear();
			for (const Edge& e : edges)
				if ((e.y0 <= yy) != (e.y1 <= yy))
					cross.push_back(e.x0 + (yy - e.y0) / (e.y1 - e.y0) * (e.x1 - e.x0));
			std::sort(cross.begin(), cross.end());
			for (size_t i = 0; i + 1 < cross.size(); i += 2) {
				const float x0 = fmaxf(0.0f, cross[i]), x1 = fminf((float)size, cross[i + 1]);
				for (int x = (int)x0; x < size && x < x1; x++) {
					const float l = fmaxf(x0, (float)x), r = fminf(x1, (float)x + 1);
					if (r > l)
						cov[(size_t)y * size + x] += (r - l) / sub;
				}
			}
		}
}

// For each pixel inside, its distance to the outside (3-4 chamfer, in pixels)
void insideDistance(const std::vector<float>& cov, int size, std::vector<float>& dist)
{
	const float big = 1e6f;
	dist.assign(cov.size(), 0.0f);
	for (size_t i = 0; i < cov.size(); i++)
		dist[i] = cov[i] > 0.5f ? big : 0.0f;
	auto at = [&](int x, int y) { return x < 0 || y < 0 || x >= size || y >= size ? 0.0f : dist[(size_t)y * size + x]; };
	for (int y = 0; y < size; y++)
		for (int x = 0; x < size; x++) {
			float& d = dist[(size_t)y * size + x];
			if (d == 0)
				continue;
			d = fminf(d, fminf(at(x - 1, y), at(x, y - 1)) + 1.0f);
			d = fminf(d, fminf(at(x - 1, y - 1), at(x + 1, y - 1)) + 1.4142f);
		}
	for (int y = size - 1; y >= 0; y--)
		for (int x = size - 1; x >= 0; x--) {
			float& d = dist[(size_t)y * size + x];
			if (d == 0)
				continue;
			d = fminf(d, fminf(at(x + 1, y), at(x, y + 1)) + 1.0f);
			d = fminf(d, fminf(at(x + 1, y + 1), at(x - 1, y + 1)) + 1.4142f);
		}
}

// One button picture: shape (turned by rot), its colour, a label (letters
// from kGcLetterChars, "" for none)
void makeGc(Image& im, int size, GcShape shape, uint32_t rgb, const char* label, bool pressed, float rot = 0,
            float letterScale = 1)
{
	im.init(size);
	std::vector<float> cov, dist, let;
	rasterPoly(cov, size, kGcShapes[shape], size * 0.47f, 0, 0, rot);
	insideDistance(cov, size, dist);
	const float stroke = fmaxf(1.5f, size * 0.05f);
	const bool gate = shape == GC_GATE || shape == GC_CGATE;
	// the letter at the widest place inside the shape, as large as it allows
	// (of the places nearly as wide as the widest, the one nearest the middle:
	// a pill is as wide all along)
	float best = 0;
	for (float d : dist)
		best = fmaxf(best, d);
	int bx = size / 2, by = size / 2;
	float near = 1e9f;
	for (int y = 0; y < size; y++)
		for (int x = 0; x < size; x++) {
			const float d2 = (x - size * 0.5f) * (x - size * 0.5f) + (y - size * 0.5f) * (y - size * 0.5f);
			if (dist[(size_t)y * size + x] >= best - 1.0f && d2 < near) {
				near = d2;
				bx = x;
				by = y;
			}
		}
	// the label's glyphs side by side, each as wide as its polygon
	const int n = (int)strlen(label);
	const float gs = fminf(best * (n > 1 ? 0.5f : 0.62f), size * 0.3f) * letterScale, gap = gs * 0.12f;
	std::vector<int> glyph;
	std::vector<float> width;
	float total = 0;
	for (int k = 0; k < n; k++) {
		const char* lp = strchr(kGcLetterChars, label[k]);
		if (!lp)
			continue;
		const GcPoly& g = kGcLetters[lp - kGcLetterChars];
		int pts = 0;
		for (int r = 0; r < g.ringCount; r++)
			pts += g.rings[r];
		float lo = 1, hi = -1;
		for (int q = 0; q < pts; q++)
			lo = fminf(lo, g.pts[q * 2]), hi = fmaxf(hi, g.pts[q * 2]);
		glyph.push_back((int)(lp - kGcLetterChars));
		width.push_back((hi - lo) * gs);
		total += width.back() + (glyph.size() > 1 ? gap : 0);
	}
	const bool lp = !glyph.empty();
	let.assign((size_t)size * size, 0.0f);
	float x = bx + 0.5f - size * 0.5f - total * 0.5f;
	for (size_t k = 0; k < glyph.size(); k++) {
		std::vector<float> one;
		rasterPoly(one, size, kGcLetters[glyph[k]], gs, x + width[k] * 0.5f, by + 0.5f - size * 0.5f, 0);
		for (size_t i = 0; i < one.size(); i++)
			let[i] += one[i];
		x += width[k] + gap;
	}
	for (int y = 0; y < size; y++)
		for (int x = 0; x < size; x++) {
			const size_t i = (size_t)y * size + x;
			const float m = fminf(1.0f, cov[i]);
			if (m <= 0)
				continue;
			const float line = fminf(1.0f, fmaxf(0.0f, stroke + 0.5f - dist[i]));
			if (gate) {  // a stick's gate: only its outline, over a faint face
				im.blend(x, y, 0x0A1420, m * 0.35f);
				im.blend(x, y, rgb, m * (dist[i] == 0 ? 1.0f : line));
			} else {  // a button: filled in its colour; pressed, lighter with a white rim
				im.blend(x, y, pressed ? shade(rgb, 1.25f) : rgb, m * 0.92f);
				if (pressed)
					im.blend(x, y, 0xFFFFFF, m * (dist[i] == 0 ? 1.0f : line));
			}
			if (lp && let[i] > 0)
				im.blend(x, y, 0x14202C, fminf(1.0f, let[i]));
		}
}

// The save state indicator (bottom left while a state loads or saves): a disc
// like the SAVE and LOAD buttons, a ring of dots filling up around it, the
// percentage beside it.
const int kIndicatorDots = 16;
const char kIndicatorChars[] = "0123456789%MP";
float indicatorSize() { return g_h * 0.10f; }

// The atlas: every picture side by side in rows.
enum Pic { PIC_STICK_BASE, PIC_STICK_KNOB, PIC_STICK_KNOB_HELD, PIC_C_BASE, PIC_C_KNOB, PIC_C_KNOB_HELD, PIC_SHOW,
           PIC_R_DEEP, PIC_IND_SAVE, PIC_IND_LOAD, PIC_IND_DOT, PIC_IND_DOT_LIT, PIC_IND_CHAR,
           PIC_BUTTON = PIC_IND_CHAR + 13 };  // then two per button (up, down)
struct Cut {
	int u, v, w, h;
};
std::vector<Cut> g_cuts;
std::vector<uint8_t> g_atlas;
int g_atlasW, g_atlasH;
bool g_atlasSent;

void buildAtlas()
{
	std::vector<Image> pics;
	auto add = [&](int size, uint32_t rgb, const char* label, bool pressed, bool ring) {
		pics.emplace_back();
		makeDisc(pics.back(), size < 16 ? 16 : size > 512 ? 512 : size, rgb, label, pressed, ring);
	};
	const Place& s = g_cfg.place[STICK];
	const Place& c = g_cfg.place[CSTICK];
	const int stickPx = (int)(s.r * 2 * g_h), cPx = (int)(c.r * 2 * g_h);
	const bool gc = g_cfg.style == 1;
	auto gcPic = [&](int size, GcShape sh, uint32_t rgb, const char* letter, bool pressed, float letterScale = 1,
	                 float rot = 0) {
		pics.emplace_back();
		makeGc(pics.back(), size < 16 ? 16 : size > 512 ? 512 : size, sh, rgb, letter, pressed, rot, letterScale);
	};
	if (gc) {
		gcPic(stickPx, GC_GATE, 0xFFFFFF, "", false);
		gcPic((int)(stickPx * 0.44f), GC_KNOB, 0xFFFFFF, "", false);
		gcPic((int)(stickPx * 0.44f), GC_KNOB, 0xFFFFFF, "", true);
		gcPic(cPx, GC_CGATE, 0xFFD21F, "", false);
		gcPic((int)(cPx * 0.46f), GC_CKNOB, 0xFFD21F, "C", false, 0.65f);
		gcPic((int)(cPx * 0.46f), GC_CKNOB, 0xFFD21F, "C", true, 0.65f);
	} else {
		add(stickPx, kDefs[STICK].rgb, "", false, true);
		add((int)(stickPx * 0.44f), kDefs[STICK].rgb, "", false, false);
		add((int)(stickPx * 0.44f), kDefs[STICK].rgb, "", true, false);
		add(cPx, kDefs[CSTICK].rgb, "", false, true);
		add((int)(cPx * 0.46f), kDefs[CSTICK].rgb, "C", false, false);
		add((int)(cPx * 0.46f), kDefs[CSTICK].rgb, "C", true, false);
	}
	add((int)(g_cfg.place[BTN_HUD].r * 2 * g_h), kDefs[BTN_HUD].rgb, "SHOW", false, false);
	if (gc)
		gcPic((int)(g_cfg.place[BTN_R].r * 2 * g_h), GC_TRIGGER_RPLUS, 0xD0D4DA, "R+", true, 0.75f, kGcTilt[2]);
	else
		add((int)(g_cfg.place[BTN_R].r * 2 * g_h), kDefs[BTN_RPLUS].rgb, "R+", true, false);
	const int ind = (int)indicatorSize(), dot = (int)(ind * 0.14f);
	add(ind, kDefs[BTN_SAVE].rgb, "SAVE", false, false);
	add(ind, kDefs[BTN_LOAD].rgb, "LOAD", false, false);
	add(dot, 0x37474F, "", false, false);
	add(dot, 0xFFFFFF, "", true, false);
	for (const char* ch = kIndicatorChars; *ch; ch++) {
		pics.emplace_back();
		makeGlyph(pics.back(), ind / 20 < 1 ? 1 : ind / 20, *ch);
	}
	for (int i = BTN_A; i < ID_COUNT; i++)
		for (int pressed = 0; pressed < 2; pressed++) {
			const int px = (int)(g_cfg.place[i].r * 2 * g_h);
			if (gc && (i == BTN_A || i == BTN_B)) {
				gcPic(px, i == BTN_A ? GC_A : GC_B, i == BTN_A ? 0x2ECC71 : 0xE74C3C, i == BTN_A ? "A" : "B", pressed != 0, 0.65f);
			} else if (gc && (i == BTN_X || i == BTN_Y)) {  // as on the controller: X upright, Y lying
				gcPic(px, i == BTN_X ? GC_X : GC_Y, 0xD0D4DA, i == BTN_X ? "X" : "Y", pressed != 0);
			} else if (gc && i == BTN_Z) {
				gcPic(px, GC_Z, 0x9B59B6, "Z", pressed != 0);
			} else if (gc && (i == BTN_L || i == BTN_R || i == BTN_RPLUS)) {
				// turned to fall as Z does (28 degrees), L the other way
				gcPic(px, i == BTN_L ? GC_TRIGGER_L : i == BTN_R ? GC_TRIGGER_R : GC_TRIGGER_RPLUS, 0xD0D4DA,
				      i == BTN_L ? "L" : i == BTN_R ? "R" : "R+", pressed != 0, 0.75f,
				      i == BTN_L ? -kGcTilt[0] : i == BTN_R ? kGcTilt[1] : kGcTilt[2]);
			} else {
				add(px, kDefs[i].rgb, kDefs[i].label, pressed != 0, false);
			}
		}
	// shelves 2048 wide
	g_cuts.assign(pics.size(), Cut());
	int x = 0, y = 0, rowH = 0;
	g_atlasW = 2048;
	for (size_t i = 0; i < pics.size(); i++) {
		if (x + pics[i].w > g_atlasW) {
			x = 0;
			y += rowH + 2;
			rowH = 0;
		}
		g_cuts[i] = { x, y, pics[i].w, pics[i].h };
		x += pics[i].w + 2;
		rowH = pics[i].h > rowH ? pics[i].h : rowH;
	}
	g_atlasH = y + rowH;
	g_atlas.assign((size_t)g_atlasW * g_atlasH * 4, 0);
	for (size_t i = 0; i < pics.size(); i++)
		for (int row = 0; row < pics[i].h; row++)
			memcpy(&g_atlas[(((size_t)g_cuts[i].v + row) * g_atlasW + g_cuts[i].u) * 4],
			       &pics[i].px[(size_t)row * pics[i].w * 4], (size_t)pics[i].w * 4);
	g_atlasSent = false;
}

void put(std::vector<GXPCSprite>& out, int pic, float cx, float cy)
{
	const Cut& c = g_cuts[pic];
	GXPCSprite s = { cx - c.w * 0.5f, cy - c.h * 0.5f, (float)c.w, (float)c.h, c.u, c.v, c.w, c.h };
	out.push_back(s);
}

struct Lock {
	Lock() { pthread_mutex_lock(&g_lock); }
	~Lock() { pthread_mutex_unlock(&g_lock); }
};

} // namespace

extern "C" void touch_controls_finger(int64_t id, int phase, float x, float y)
{
	Lock lock;
	refreshConfig();
	if (g_cfg.mode == 2)
		return;
	g_lastTouch = now();
	if (g_hiddenByPad && phase == 0) {
		g_hiddenByPad = false;  // a touch brings them back
		return;
	}
	const float px = x * g_w, py = y * g_h;
	if (phase == 0)
		down(id, px, py);
	else if (phase == 1)
		move(id, px, py);
	else
		up(id);
}

extern "C" void touch_controls_controller_used(void)
{
	Lock lock;
	if (g_cfg.mode != 0 || g_hiddenByPad)
		return;
	g_hiddenByPad = true;
	while (g_fingerCount)
		up(g_fingers[0].id);
}

extern "C" void touch_controls_pad(PADStatus* s)
{
	Lock lock;
	refreshConfig();
	if (!visible())
		return;
	// SAVE / LOAD once held half a second
	for (int which = 0; which < 2; which++)
		if (g_stateSince[which] >= 0 && !g_stateFired[which] && now() - g_stateSince[which] >= 0.5) {
			g_stateFired[which] = true;
			port_savestate_hotkey(which ? PORT_SAVESTATE_HOTKEY_LOAD_NEWEST : PORT_SAVESTATE_HOTKEY_SAVE);
		}
	static const struct {
		int id;
		u16 mask;
	} kButtons[] = { { BTN_A, PAD_BUTTON_A }, { BTN_B, PAD_BUTTON_B }, { BTN_X, PAD_BUTTON_X }, { BTN_Y, PAD_BUTTON_Y },
		             { BTN_Z, PAD_TRIGGER_Z }, { BTN_START, PAD_BUTTON_START } };
	// a tap let go before this read still counts once: the game reads the
	// pad once a frame, and a quick tap can start and end between two reads
	const uint32_t tapped = g_tapped;
	g_tapped = 0;
	auto down = [tapped](int id) { return held(id) || (tapped & (1u << id)); };
	for (const auto& b : kButtons)
		if (down(b.id))
			s->button |= b.mask;
	if (down(BTN_A))
		s->analogA = 255;
	if (down(BTN_B))
		s->analogB = 255;
	if (down(BTN_L)) {
		s->button |= PAD_TRIGGER_L;
		s->triggerLeft = 255;
	}
	// R sprays while Mario runs (a part press); R+ (or the slider out) aims
	if (held(BTN_R) && s->triggerRight < 180)
		s->triggerRight = 180;
	if (held(BTN_RPLUS) || (g_cfg.rSlider && g_rDeep && held(BTN_R))) {
		s->button |= PAD_TRIGGER_R;
		s->triggerRight = 255;
	}
	int vx, vy;
	float kx, ky;
	if (stickValue(STICK, vx, vy, kx, ky) && (vx || vy)) {
		s->stickX = (s8)vx;
		s->stickY = (s8)vy;
	}
	if (stickValue(CSTICK, vx, vy, kx, ky) && (vx || vy)) {
		s->substickX = (s8)(g_cfg.camX ? -vx : vx);
		s->substickY = (s8)(g_cfg.camY ? -vy : vy);
	} else if (held(CAMERA)) {
		s->substickX = (s8)(g_cfg.swipeX ? -g_swipeX : g_swipeX);
		s->substickY = (s8)(g_cfg.swipeY ? -g_swipeY : g_swipeY);
		// a swipe turns the camera while the finger moves
		g_swipeX = g_swipeX * 2 / 3;
		g_swipeY = g_swipeY * 2 / 3;
	}
}

namespace {

// Characters of kIndicatorChars from x on, centred on y.
void putText(std::vector<GXPCSprite>& out, const char* text, float x, float y)
{
	for (const char* ch = text; *ch; ch++) {
		const char* at = strchr(kIndicatorChars, *ch);
		if (!at)
			continue;
		const int pic = PIC_IND_CHAR + (int)(at - kIndicatorChars);
		const Cut& c = g_cuts[pic];
		put(out, pic, x + c.w * 0.5f, y);
		x += c.w + c.w / 5;
	}
}

void drawIndicator(int kind, int percent, int winW, int winH)
{
	std::vector<GXPCSprite> out;
	const float size = indicatorSize(), cx = fmaxf(g_w * 0.04f, size * 0.9f) + size * 0.5f, cy = g_h - size * 0.95f;
	put(out, kind ? PIC_IND_SAVE : PIC_IND_LOAD, cx, cy);
	// the dots up to the percentage lit, and one more running round while it is not done
	const int lit = percent * kIndicatorDots / 100, runner = (int)(now() * kIndicatorDots) % kIndicatorDots;
	for (int i = 0; i < kIndicatorDots; i++) {
		const float a = (float)(i * 2 * M_PI / kIndicatorDots - M_PI / 2), r = size * 0.66f;
		put(out, i < lit || (percent < 100 && i == runner) ? PIC_IND_DOT_LIT : PIC_IND_DOT, cx + r * cosf(a),
		    cy + r * sinf(a));
	}
	char text[8];
	snprintf(text, sizeof text, "%d%%", percent);
	putText(out, text, cx + size * 0.85f, cy);
	GXPC_DrawSprites(out.data(), (int)out.size(), 0.95f, winW, winH);
}

// "MP4" in the top left while an MP4 replaces a movie's picture, with the
// performance overlay on (SMS_OVERLAY): to see that the hardware decoder plays
void drawMp4Mark(int winW, int winH)
{
	std::vector<GXPCSprite> out;
	putText(out, "MP4", fmaxf(g_w * 0.04f, indicatorSize() * 0.9f), indicatorSize() * 0.6f);
	GXPC_DrawSprites(out.data(), (int)out.size(), 0.9f, winW, winH);
}

} // namespace

extern "C" void touch_controls_draw(int winW, int winH)
{
	Lock lock;
	refreshConfig();
	if (winW <= 0 || winH <= 0)
		return;
	if (winW != (int)g_w || winH != (int)g_h) {
		g_w = (float)winW;
		g_h = (float)winH;
		g_spritesDirty = true;
	}
	int kind = 0;
	const int percent = port_savestate_progress ? port_savestate_progress(&kind) : -1;
	static int s_overlay = -1;
	if (s_overlay < 0) {
		// with the performance overlay, or always in a test build (SMS_TEST_BUILD, from the app)
		const char* o = getenv("SMS_OVERLAY");
		const char* t = getenv("SMS_TEST_BUILD");
		s_overlay = (o && *o && strcmp(o, "0") != 0) || (t && *t && strcmp(t, "0") != 0);
	}
	const bool mp4 = s_overlay && port_mp4_showing && port_mp4_showing();
	if (!visible() && percent < 0 && !mp4)
		return;
	if (g_spritesDirty) {
		g_spritesDirty = false;
		buildAtlas();
	}
	if (!GXPC_SpriteAtlas || !GXPC_DrawSprites)
		return;
	if (!g_atlasSent) {
		GXPC_SpriteAtlas(g_atlas.data(), g_atlasW, g_atlasH);
		g_atlasSent = true;
	}
	if (percent >= 0)
		drawIndicator(kind, percent, winW, winH);
	if (mp4)
		drawMp4Mark(winW, winH);
	if (!visible())
		return;
	// faded out after SMS_TOUCH_AUTOHIDE seconds without a touch (not while held)
	float alpha = g_cfg.opacity;
	if (g_cfg.autoHide > 0) {
		if (g_fingerCount)
			g_lastTouch = now();
		const double idle = now() - g_lastTouch;
		if (idle > g_cfg.autoHide)
			alpha *= (float)fmax(0.0, 1.0 - (idle - g_cfg.autoHide) / 0.6);
	}
	if (alpha <= 0.01f)
		return;
	std::vector<GXPCSprite> out;
	for (int id = STICK; id <= CSTICK; id++) {
		const Place& p = g_cfg.place[id];
		if (!p.shown || g_hideOthers)
			continue;
		const int base = id == STICK ? PIC_STICK_BASE : PIC_C_BASE;
		int vx, vy;
		float kx = p.x * g_w, ky = p.y * g_h;
		float bx = kx, by = ky;
		const bool active = stickValue(id, vx, vy, kx, ky);
		if (active)
			for (int i = 0; i < g_fingerCount; i++)
				if (g_fingers[i].holds == id) {
					bx = g_fingers[i].ax;
					by = g_fingers[i].ay;
				}
		put(out, base, bx, by);
		put(out, base + (active ? 2 : 1), kx, ky);
	}
	for (int i = BTN_A; i < ID_COUNT; i++) {
		if (off(i))
			continue;
		const Place& p = g_cfg.place[i];
		const bool down = held(i);
		int pic = PIC_BUTTON + (i - BTN_A) * 2 + (down ? 1 : 0);
		if (i == BTN_HUD && g_hideOthers)
			pic = PIC_SHOW;
		if (i == BTN_R && g_cfg.rSlider && g_rDeep && down)
			pic = PIC_R_DEEP;
		put(out, pic, p.x * g_w, p.y * g_h);
	}
	GXPC_DrawSprites(out.data(), (int)out.size(), alpha, winW, winH);
}

// The state menu's "Save/Load delay" switch (savestate_android.cpp); the app
// writes it to settings.txt too, for the next run.
extern "C" void touch_controls_set_state_delay(int on)
{
	setenv("SMS_SAVESTATE_DELAY", on ? "1" : "0", 1);
	Lock l;
	g_cfg.stateDelay = on != 0;
}
