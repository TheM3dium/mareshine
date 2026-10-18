// PAD: controller 1 from the keyboard and/or an SDL game controller; ports
// 2-4 are empty. Input arrives as SDL2 events through the GX layer's event
// hook (sms_gx_set_event_callback / sms_gx_pump_events, weak here so the port
// still links and runs without platform/gx). Bindings load from bindings.txt
// (see README.md); SMS_BINDINGS=path overrides the location.
//
// Only SDL2's stable event ABI is used (mirrored below), so this file needs no
// SDL headers or library: the 32-bit build works without i386 SDL packages.
#include "port_compat.h"
#include "port_platform.h"
#include <dolphin/pad.h>
#include <string.h>
#include "port_savestate.h"
#include <strings.h>
#include <ctype.h>
#include <dlfcn.h>
#include <vector>
#include <time.h>
#include <math.h>
#include "touch_controller.h"

// --- SDL2 event ABI (SDL_events.h, SDL_scancode.h, SDL_gamecontroller.h) ---
namespace sdl {
enum {
	QUIT = 0x100, KEYDOWN = 0x300, KEYUP = 0x301, MOUSEMOTION = 0x400,
	CAXIS = 0x650, CBUTTONDOWN = 0x651, CBUTTONUP = 0x652,
	FINGERDOWN = 0x700, FINGERUP = 0x701, FINGERMOTION = 0x702
};
struct MouseMotionEvent {
	u32 type, timestamp, windowID, which, state;
	s32 x, y, xrel, yrel;
};
struct KeyboardEvent {
	u32 type, timestamp, windowID;
	u8 state, repeat, pad2, pad3;
	s32 scancode, sym;
	u16 mod;
	u32 unused;
};
struct ControllerAxisEvent {
	u32 type, timestamp;
	s32 which;
	u8 axis, p1, p2, p3;
	s16 value;
	u16 p4;
};
struct ControllerButtonEvent {
	u32 type, timestamp;
	s32 which;
	u8 button, state, p1, p2;
};
struct TouchFingerEvent {
	u32 type, timestamp;
	int64_t touchId;
	int64_t fingerId;
	float x, y, dx, dy, pressure;
	u32 windowID;
};
} // namespace sdl

extern "C" {
__attribute__((weak)) void sms_gx_set_event_callback(void (*cb)(const union SDL_Event* ev));
__attribute__((weak)) void sms_gx_pump_events(void);
__attribute__((weak)) int sms_gx_pad_count(void);
__attribute__((weak)) int GXPC_IsHeadless(void);
__attribute__((weak)) int GXPC_MouseCaptured(void);
}

// PC camera options, read by the camera code (decomp-patches/zzz-pc-camera.patch):
//   SMS_CAMERA_INVERT_X / _Y   flip the C-stick (and mouse) on that axis
//   SMS_CAMERA_SPEED=percent   scales manual camera rotation (100 = retail)
//   SMS_FREE_CAMERA=1          the camera stays where it is put (no auto swing-back)
//   SMS_MOUSE_SENSITIVITY=%    mouse look speed, when the GX layer captures the mouse
#ifdef __ANDROID__
// The in-game menu (SunshineActivity / GameMenuActivity): Esc, Select + Start,
// the controller's Home/Guide button or the MENU touch button ask the Java
// side to open it; the game pauses while it is shown.
extern "C" __attribute__((weak)) int SDL_AndroidSendMessage(uint32_t command, int param);
extern "C" __attribute__((visibility("default"))) void port_open_menu(void)
{
	static uint64_t s_last;
	timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	uint64_t now = (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
	if (now - s_last < 800) // one press, one menu
		return;
	s_last = now;
	port_log("[pad] in-game menu requested\n");
	if (SDL_AndroidSendMessage)
		SDL_AndroidSendMessage(0x8001, 0); // SunshineActivity.COMMAND_OPEN_MENU
}
#endif

extern "C" {
int port_free_camera      = 0;
f32 port_camera_speed_x   = 1.0f;
f32 port_camera_speed_y   = 1.0f;
}

namespace {

enum Control {
	C_A, C_B, C_X, C_Y, C_Z, C_L, C_R, C_START,
	C_DUP, C_DDOWN, C_DLEFT, C_DRIGHT,
	C_UP, C_DOWN, C_LEFT, C_RIGHT,
	C_CUP, C_CDOWN, C_CLEFT, C_CRIGHT,
	C_HALF, C_QUIT, C_SAVESTATE, C_LOADSTATE, C_COUNT
};
const char* const kControlNames[C_COUNT] = {
	"A", "B", "X", "Y", "Z", "L", "R", "START",
	"DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
	"STICK_UP", "STICK_DOWN", "STICK_LEFT", "STICK_RIGHT",
	"CSTICK_UP", "CSTICK_DOWN", "CSTICK_LEFT", "CSTICK_RIGHT",
	"HALF_TILT", "QUIT", "SAVE_STATE", "LOAD_STATE",
};

struct KeyName {
	const char* name;
	int code;
};
// SDL scancodes are USB HID usage IDs.
const KeyName kKeys[] = {
	{ "A", 4 }, { "B", 5 }, { "C", 6 }, { "D", 7 }, { "E", 8 }, { "F", 9 }, { "G", 10 }, { "H", 11 },
	{ "I", 12 }, { "J", 13 }, { "K", 14 }, { "L", 15 }, { "M", 16 }, { "N", 17 }, { "O", 18 }, { "P", 19 },
	{ "Q", 20 }, { "R", 21 }, { "S", 22 }, { "T", 23 }, { "U", 24 }, { "V", 25 }, { "W", 26 }, { "X", 27 },
	{ "Y", 28 }, { "Z", 29 }, { "1", 30 }, { "2", 31 }, { "3", 32 }, { "4", 33 }, { "5", 34 }, { "6", 35 },
	{ "7", 36 }, { "8", 37 }, { "9", 38 }, { "0", 39 }, { "ENTER", 40 }, { "RETURN", 40 }, { "ESCAPE", 41 },
	{ "ESC", 41 }, { "BACKSPACE", 42 }, { "TAB", 43 }, { "SPACE", 44 }, { "MINUS", 45 }, { "EQUALS", 46 },
	{ "LBRACKET", 47 }, { "RBRACKET", 48 }, { "SEMICOLON", 51 }, { "APOSTROPHE", 52 }, { "COMMA", 54 },
	{ "PERIOD", 55 }, { "SLASH", 56 }, { "F1", 58 }, { "F2", 59 }, { "F3", 60 }, { "F4", 61 },
	{ "F5", 62 }, { "F6", 63 },
	{ "RIGHT", 79 }, { "LEFT", 80 }, { "DOWN", 81 }, { "UP", 82 },
	{ "KP_DIVIDE", 84 }, { "KP_MULTIPLY", 85 }, { "KP_MINUS", 86 }, { "KP_PLUS", 87 }, { "KP_ENTER", 88 },
	{ "KP_1", 89 }, { "KP_2", 90 }, { "KP_3", 91 }, { "KP_4", 92 }, { "KP_5", 93 }, { "KP_6", 94 },
	{ "KP_7", 95 }, { "KP_8", 96 }, { "KP_9", 97 }, { "KP_0", 98 },
	{ "LCTRL", 224 }, { "LSHIFT", 225 }, { "LALT", 226 }, { "RCTRL", 228 }, { "RSHIFT", 229 }, { "RALT", 230 },
};

// Default bindings (documented in README.md).
const char* const kDefaultBindings =
    "A = SPACE X\n"
    "B = LSHIFT RSHIFT C\n"
    "X = V\n"
    "Y = F\n"
    "Z = Z\n"
    "L = Q\n"
    "R = E\n"
    "START = ENTER\n"
    "DPAD_UP = 1 KP_8\n"
    "DPAD_DOWN = 2 KP_2\n"
    "DPAD_LEFT = 3 KP_4\n"
    "DPAD_RIGHT = 4 KP_6\n"
    "STICK_UP = UP W\n"
    "STICK_DOWN = DOWN S\n"
    "STICK_LEFT = LEFT A\n"
    "STICK_RIGHT = RIGHT D\n"
    "CSTICK_UP = I\n"
    "CSTICK_DOWN = K\n"
    "CSTICK_LEFT = J\n"
    "CSTICK_RIGHT = L\n"
    "HALF_TILT = LCTRL\n"
    "QUIT = ESCAPE\n"
    "SAVE_STATE = F5\n"
    "LOAD_STATE = F6\n";

const int kMaxKeys = 8;
int g_bind[C_COUNT][kMaxKeys];
int g_nbind[C_COUNT];
bool g_key[512];
// Game controller state (first controller wins).
s16 g_axis[6];
bool g_cbtn[21];
bool g_inited;

// Game controller mapping (SMS_PAD_MAP): which SDL controller button (bN) or
// axis (aN, positive half) drives each GameCube input, e.g.
//   SMS_PAD_MAP=A=b0 B=b1 X=b2 Y=b3 Z=b9,b4 L=a4 R=a5 R_SOFT=b10 START=b6
//               DPAD_UP=b11 DPAD_DOWN=b12 DPAD_LEFT=b13 DPAD_RIGHT=b14 STICK=a0 CSTICK=a2
// Controls left out keep these defaults; "none" unbinds one. STICK and
// CSTICK name the first axis of a stick (a0 left, a2 right). R_SOFT is a
// half-pressed R (spray while moving).
enum PadIn {
	P_A, P_B, P_X, P_Y, P_Z, P_L, P_R, P_RSOFT, P_START,
	P_DUP, P_DDOWN, P_DLEFT, P_DRIGHT, P_STICK, P_CSTICK, P_HOTKEY, P_SAVESTATE, P_LOADSTATE, P_COUNT
};
const char* const kPadNames[P_COUNT] = {
	"A", "B", "X", "Y", "Z", "L", "R", "R_SOFT", "START",
	"DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "STICK", "CSTICK", "HOTKEY", "SAVE_STATE", "LOAD_STATE",
};
struct PadSrc {
	char kind; // 'b' button, 'a' axis, 0 unused
	int idx;
};
const int kMaxPadSrc = 3;
PadSrc g_pmap[P_COUNT][kMaxPadSrc];
const char* const kDefaultPadMap =
    "A=b0 B=b1 X=b2 Y=b3 Z=b9 L=a4 R=a5 R_SOFT=b10 START=b6 "
    "DPAD_UP=b11 DPAD_DOWN=b12 DPAD_LEFT=b13 DPAD_RIGHT=b14 STICK=a0 CSTICK=a2 "
    "HOTKEY=b4 SAVE_STATE=b10 LOAD_STATE=b9"; // Select held: + R1 quick save, + L1 quick load

void parse_pad_map(const char* text)
{
	char buf[1024];
	snprintf(buf, sizeof buf, "%s", text);
	char* save = NULL;
	for (char* tok = strtok_r(buf, " \t;\n", &save); tok; tok = strtok_r(NULL, " \t;\n", &save)) {
		char* eq = strchr(tok, '=');
		if (!eq)
			continue;
		*eq   = 0;
		int c = -1;
		for (int i = 0; i < P_COUNT; i++)
			if (strcasecmp(kPadNames[i], tok) == 0)
				c = i;
		if (c < 0) {
			port_log("[pad] SMS_PAD_MAP: unknown control '%s'\n", tok);
			continue;
		}
		memset(g_pmap[c], 0, sizeof g_pmap[c]);
		int n = 0;
		for (char* v = eq + 1; *v && n < kMaxPadSrc;) {
			char* comma = strchr(v, ',');
			if (comma)
				*comma = 0;
			if ((v[0] == 'b' || v[0] == 'a') && isdigit((unsigned char)v[1])) {
				int idx = atoi(v + 1);
				if ((v[0] == 'b' && idx < 21) || (v[0] == 'a' && idx < 6))
					g_pmap[c][n++] = { v[0], idx };
			}
			if (!comma)
				break;
			v = comma + 1;
		}
	}
}

// How far a mapped input is pressed, 0..255.
// Buttons the game does not see this frame: the hotkey's own, and while it is
// held the buttons it combines with (the save state controls below).
bool g_masked[21];

int pad_level(int c)
{
	const bool hotkeyPart = c == P_HOTKEY || c == P_SAVESTATE || c == P_LOADSTATE;
	int best = 0;
	for (int i = 0; i < kMaxPadSrc; i++) {
		const PadSrc& s = g_pmap[c][i];
		int v = 0;
		if (s.kind == 'b')
			v = g_cbtn[s.idx] && (hotkeyPart || !g_masked[s.idx]) ? 255 : 0;
		else if (s.kind == 'a')
			v = g_axis[s.idx] > 0 ? g_axis[s.idx] * 255 / 32767 : 0;
		if (v > best)
			best = v;
	}
	return best;
}
bool pad_held(int c) { return pad_level(c) >= 128; }
int pad_stick_axis(int c, int fallback)
{
	return g_pmap[c][0].kind == 'a' && (g_pmap[c][0].idx == 0 || g_pmap[c][0].idx == 2) ? g_pmap[c][0].idx : fallback;
}

// SMS_TOUCH_CONTROLS: auto (hidden while a game controller is in use), on, off.
// settings.txt turns on/off into 1/0.
int g_touchMode = 0; // 0 auto, 1 on, 2 off
int parse_touch_mode(const char* t)
{
	if (!t)
		return 0;
	if (!strcasecmp(t, "on") || !strcmp(t, "1") || !strcasecmp(t, "always"))
		return 1;
	if (!strcasecmp(t, "off") || !strcmp(t, "0") || !strcasecmp(t, "never"))
		return 2;
	return 0;
}
// SMS_CAMERA_INVERT_X / SMS_CAMERA_INVERT_Y=1 flip the C-stick, which only
// turns the camera.
bool g_invert_cx, g_invert_cy;
// mouse look (see above): its speed, and the motion not yet given to the camera
float g_mouseSens = 1.0f;
int g_mouseDX, g_mouseDY;

bool env_on(const char* name)
{
	const char* e = getenv(name);
	return e && *e && strcmp(e, "0") != 0;
}
float env_percent(const char* name, float lo, float hi)
{
	const char* v = getenv(name);
	if (!v || !*v)
		return 1.0f;
	float f = (float)atof(v) / 100.0f;
	return f < lo ? lo : f > hi ? hi : f;
}

int key_code(const char* name)
{
	for (size_t i = 0; i < sizeof kKeys / sizeof kKeys[0]; i++)
		if (strcasecmp(kKeys[i].name, name) == 0)
			return kKeys[i].code;
	if (name[0] == '#')
		return atoi(name + 1); // raw scancode
	return -1;
}

void parse_bindings(const char* text, const char* source)
{
	const char* p = text;
	int line      = 0;
	while (*p) {
		line++;
		char buf[256];
		size_t n = strcspn(p, "\n");
		if (n >= sizeof buf)
			n = sizeof buf - 1;
		memcpy(buf, p, n);
		buf[n] = 0;
		p += n + (p[n] == '\n');
		char* hash = strchr(buf, '#');
		if (hash && (hash == buf || hash[-1] == ' ' || hash[-1] == '\t'))
			*hash = 0;
		char* eq = strchr(buf, '=');
		if (!eq)
			continue;
		*eq        = 0;
		char* name = strtok(buf, " \t");
		if (!name)
			continue;
		int c = -1;
		for (int i = 0; i < C_COUNT; i++)
			if (strcasecmp(kControlNames[i], name) == 0)
				c = i;
		if (c < 0) {
			port_log("[pad] %s:%d: unknown control '%s'\n", source, line, name);
			continue;
		}
		g_nbind[c] = 0;
		for (char* k = strtok(eq + 1, " \t,"); k; k = strtok(NULL, " \t,")) {
			int code = key_code(k);
			if (code < 0 || code >= 512)
				port_log("[pad] %s:%d: unknown key '%s'\n", source, line, k);
			else if (g_nbind[c] < kMaxKeys)
				g_bind[c][g_nbind[c]++] = code;
		}
	}
}

void on_event(const union SDL_Event* ev)
{
	u32 type = *(const u32*)ev;
	switch (type) {
	case sdl::KEYDOWN:
	case sdl::KEYUP: {
		const sdl::KeyboardEvent* k = (const sdl::KeyboardEvent*)ev;
		if (k->scancode >= 0 && k->scancode < 512)
			g_key[k->scancode] = type == sdl::KEYDOWN;
		if (type == sdl::KEYDOWN)
			for (int i = 0; i < g_nbind[C_QUIT]; i++)
				if (g_bind[C_QUIT][i] == k->scancode) {
#ifdef __ANDROID__
					if (!k->repeat)
						port_open_menu(); // Esc opens the in-game menu; quitting is there
#else
					port_log("[pad] quit key pressed\n");
					exit(0);
#endif
				}
		break;
	}
	case sdl::MOUSEMOTION: {
		const sdl::MouseMotionEvent* m = (const sdl::MouseMotionEvent*)ev;
		if (GXPC_MouseCaptured && GXPC_MouseCaptured()) {
			g_mouseDX += m->xrel;
			g_mouseDY += m->yrel;
		}
		break;
	}
	case sdl::CAXIS: {
		const sdl::ControllerAxisEvent* a = (const sdl::ControllerAxisEvent*)ev;
		if (a->axis < 6)
			g_axis[a->axis] = a->value;
		break;
	}
	case sdl::CBUTTONDOWN:
	case sdl::CBUTTONUP: {
		const sdl::ControllerButtonEvent* b = (const sdl::ControllerButtonEvent*)ev;
		if (b->button < 21)
			g_cbtn[b->button] = type == sdl::CBUTTONDOWN;
		break;
	}
	case sdl::FINGERDOWN: {
		const sdl::TouchFingerEvent* tf = (const sdl::TouchFingerEvent*)ev;
		touch_controller_on_finger_down(tf->fingerId, tf->x, tf->y);
		break;
	}
	case sdl::FINGERMOTION: {
		const sdl::TouchFingerEvent* tf = (const sdl::TouchFingerEvent*)ev;
		touch_controller_on_finger_move(tf->fingerId, tf->x, tf->y);
		break;
	}
	case sdl::FINGERUP: {
		const sdl::TouchFingerEvent* tf = (const sdl::TouchFingerEvent*)ev;
		touch_controller_on_finger_up(tf->fingerId, tf->x, tf->y);
		break;
	}
	}
}

bool held(int c)
{
	for (int i = 0; i < g_nbind[c]; i++)
		if (g_key[g_bind[c][i]])
			return true;
	return false;
}

// Analog sticks (Settings → INPUT):
//   SMS_STICK_DEADZONE / SMS_CSTICK_DEADZONE   percent of the throw ignored around
//                                              the centre (radial; 8 by default)
//   SMS_STICK_SENSITIVITY / SMS_CSTICK_...     percent; above 100 full deflection
//                                              is reached earlier
//   SMS_CAL_LEFT / SMS_CAL_RIGHT               a physical stick's calibration from
//                                              the app (beta): "cx,cy,minx,maxx,
//                                              miny,maxy" in raw units (-1..1)
// Deadzone and sensitivity belong to the stick's role (move / camera), the
// calibration to the physical stick (axes 0/1 left, 2/3 right).
struct StickCal {
	bool on;
	float cx, cy, minx, maxx, miny, maxy;
};
StickCal g_cal[2];
float g_dz[2]   = { 0.08f, 0.08f }; // [0] move stick, [1] camera stick
float g_sens[2] = { 1.0f, 1.0f };

void stick_init()
{
	const char* dz[2] = { "SMS_STICK_DEADZONE", "SMS_CSTICK_DEADZONE" };
	const char* se[2] = { "SMS_STICK_SENSITIVITY", "SMS_CSTICK_SENSITIVITY" };
	for (int i = 0; i < 2; i++) {
		if (const char* e = getenv(dz[i])) {
			float v = (float)atof(e) / 100.0f;
			g_dz[i] = v < 0 ? 0 : v > 0.6f ? 0.6f : v;
		}
		if (const char* e = getenv(se[i])) {
			float v = (float)atof(e) / 100.0f;
			g_sens[i] = v < 0.25f ? 0.25f : v > 3.0f ? 3.0f : v;
		}
	}
	const char* cal[2] = { "SMS_CAL_LEFT", "SMS_CAL_RIGHT" };
	for (int i = 0; i < 2; i++) {
		StickCal& c = g_cal[i];
		const char* e = getenv(cal[i]);
		c.on = e && sscanf(e, "%f,%f,%f,%f,%f,%f", &c.cx, &c.cy, &c.minx, &c.maxx, &c.miny, &c.maxy) == 6
		       && c.maxx > c.cx && c.minx < c.cx && c.maxy > c.cy && c.miny < c.cy;
		if (c.on)
			port_log("[pad] %s stick calibrated: centre %.3f,%.3f x %.2f..%.2f y %.2f..%.2f\n", i ? "right" : "left",
			         c.cx, c.cy, c.minx, c.maxx, c.miny, c.maxy);
	}
	port_log("[pad] sticks: dead zone %d%%/%d%%, sensitivity %d%%/%d%%\n", (int)(g_dz[0] * 100 + 0.5f),
	         (int)(g_dz[1] * 100 + 0.5f), (int)(g_sens[0] * 100 + 0.5f), (int)(g_sens[1] * 100 + 0.5f));
}

float cal_axis(float v, float c, float mn, float mx)
{
	float r = v >= c ? (v - c) / (mx - c > 0.05f ? mx - c : 0.05f) : (v - c) / (c - mn > 0.05f ? c - mn : 0.05f);
	return r > 1 ? 1 : r < -1 ? -1 : r;
}

// A controller stick as GameCube stick units (about +-100 at the rim, y up).
void read_stick(int base, int role, int* ox, int* oy)
{
	float fx = g_axis[base] / 32767.0f, fy = g_axis[base + 1] / 32767.0f;
	const StickCal& c = g_cal[base >= 2 ? 1 : 0];
	if (c.on) {
		fx = cal_axis(fx, c.cx, c.minx, c.maxx);
		fy = cal_axis(fy, c.cy, c.miny, c.maxy);
	}
	float r = sqrtf(fx * fx + fy * fy);
	float dz = g_dz[role];
	if (r <= dz || r < 1e-4f) {
		*ox = *oy = 0;
		return;
	}
	float nr = (r - dz) / (1.0f - dz) * g_sens[role];
	if (nr > 1.0f)
		nr = 1.0f;
	fx *= nr / r;
	fy *= nr / r;
	*ox = (int)lrintf(fx * 100.0f);
	*oy = (int)lrintf(-fy * 100.0f);
}

void autopress_init();

void init()
{
	if (g_inited)
		return;
	g_inited = true;
	port_free_camera    = env_on("SMS_FREE_CAMERA");
	port_camera_speed_x = port_camera_speed_y = env_percent("SMS_CAMERA_SPEED", 0.1f, 4.0f);
	g_mouseSens         = env_percent("SMS_MOUSE_SENSITIVITY", 0.05f, 10.0f);
	if (port_free_camera || port_camera_speed_x != 1.0f)
		port_log("[pad] camera: free camera %s, speed %d%%\n", port_free_camera ? "on" : "off",
		         (int)(port_camera_speed_x * 100.0f + 0.5f));
	parse_bindings(kDefaultBindings, "defaults");
	parse_pad_map(kDefaultPadMap);
	stick_init();
	if (const char* r = getenv("SMS_R1_SOFT_SPRAY"))
		if (!strcmp(r, "0"))
			parse_pad_map("Z=b9,b4,b10 R_SOFT=none");
	if (const char* m = getenv("SMS_PAD_MAP")) {
		parse_pad_map(m);
		port_log("[pad] controller mapping: %s\n", m);
	}
	g_touchMode = parse_touch_mode(getenv("SMS_TOUCH_CONTROLS"));
	g_invert_cx = env_on("SMS_CAMERA_INVERT_X");
	g_invert_cy = env_on("SMS_CAMERA_INVERT_Y");
	if (g_invert_cx || g_invert_cy)
		port_log("[pad] camera inverted:%s%s\n", g_invert_cx ? " X" : "", g_invert_cy ? " Y" : "");
	const char* path = getenv("SMS_BINDINGS");
	if (!path)
		path = "bindings.txt";
	FILE* f = fopen(path, "r");
	if (!f && !getenv("SMS_BINDINGS")) {
		path = "../../bindings.txt"; // running from build/<os>-<arch>/
		f    = fopen(path, "r");
	}
	if (f) {
		static char text[16384];
		size_t n = fread(text, 1, sizeof text - 1, f);
		text[n]  = 0;
		fclose(f);
		parse_bindings(text, path);
		port_log("[pad] loaded key bindings from %s\n", path);
	}
	if (sms_gx_set_event_callback)
		sms_gx_set_event_callback(on_event);
	else
		port_log("[pad] no GX/SDL event source linked: controller 1 stays idle\n");
}

// --- Test input: SMS_AUTOPRESS=control@field[+hold],... ------------------------
// e.g. SMS_AUTOPRESS=start@600,a@900+20 presses Start at retrace 600 and A at
// 900 (held for 20 retraces; default 8). With the GX layer's SDL window the
// press goes through the real keyboard path: an SDL key event carrying the
// control's first bound key is pushed with SDL_PushEvent. Without SDL the key
// state is set directly.
struct AutoPress {
	int control;
	u32 at, until;
	bool down, done;
};
std::vector<AutoPress> g_auto;
typedef int (*PushEventFn)(void* ev);
PushEventFn g_push;

void autopress_init()
{
	const char* e = getenv("SMS_AUTOPRESS");
	if (!e || !*e)
		return;
	char buf[1024];
	strncpy(buf, e, sizeof buf - 1);
	buf[sizeof buf - 1] = 0;
	for (char* tok = strtok(buf, ","); tok; tok = strtok(NULL, ",")) {
		char* at = strchr(tok, '@');
		if (!at)
			continue;
		*at        = 0;
		u32 hold   = 8;
		char* plus = strchr(at + 1, '+');
		if (plus)
			hold = (u32)atoi(plus + 1);
		int c = -1;
		for (int i = 0; i < C_COUNT; i++)
			if (strcasecmp(kControlNames[i], tok) == 0)
				c = i;
		if (c < 0 || g_nbind[c] == 0) {
			port_log("[pad] SMS_AUTOPRESS: unknown or unbound control '%s'\n", tok);
			continue;
		}
		AutoPress a = { c, (u32)atoi(at + 1), 0, false, false };
		a.until     = a.at + hold;
		g_auto.push_back(a);
	}
	// Headless runs have no SDL event loop: set the key state directly.
	if (!(GXPC_IsHeadless && GXPC_IsHeadless()))
		g_push = (PushEventFn)dlsym(RTLD_DEFAULT, "SDL_PushEvent");
	port_log("[pad] SMS_AUTOPRESS: %u scripted presses via %s\n", (unsigned)g_auto.size(),
	         g_push ? "SDL_PushEvent (keyboard path)" : "direct key state");
}

void send_key(int scancode, bool down)
{
	if (g_push) {
		union {
			sdl::KeyboardEvent k;
			u8 raw[56];
		} ev;
		memset(&ev, 0, sizeof ev);
		ev.k.type     = down ? sdl::KEYDOWN : sdl::KEYUP;
		ev.k.state    = down ? 1 : 0;
		ev.k.scancode = scancode;
		if (g_push(&ev) >= 0)
			return;
	}
	g_key[scancode] = down;
}

} // namespace

extern "C" void port_pad_autopress_field(u32 field)
{
	for (size_t i = 0; i < g_auto.size(); i++) {
		AutoPress& a = g_auto[i];
		if (a.done)
			continue;
		if (!a.down && field >= a.at) {
			a.down = true;
			port_log("[pad] autopress %s down at field %u\n", kControlNames[a.control], field);
			send_key(g_bind[a.control][0], true);
		} else if (a.down && field >= a.until) {
			a.done = true;
			send_key(g_bind[a.control][0], false);
		}
	}
}

extern "C" BOOL PADInit()
{
	init();
	return TRUE;
}
extern "C" int PADReset(unsigned long) { return TRUE; }
extern "C" BOOL PADRecalibrate(u32) { return TRUE; }
extern "C" BOOL PADSync(void) { return TRUE; }
extern "C" void PADSetSpec(u32) {}
extern "C" void PADSetAnalogMode(u32) {}
extern "C" void PADSetSamplingRate(unsigned long) {}
extern "C" void PADControlMotor(s32, u32) {}
extern "C" void PADControlAllMotors(const u32*) {}

extern "C" __attribute__((weak)) int port_trace_pad_read(struct PADStatus* status);

extern "C" u32 PADRead(PADStatus* status)
{
	// A .dtm movie (platform/trace, SMS_MOVIE) replaces live input.
	if (port_trace_pad_read && port_trace_pad_read(status))
		return PAD_CHAN0_BIT;
	init();
	static bool autoInited;
	if (!autoInited) {
		autoInited = true;
		autopress_init();
	}
	if (sms_gx_pump_events)
		sms_gx_pump_events();
	for (int i = 0; i < 4; i++) {
		memset(&status[i], 0, sizeof status[i]);
		status[i].err = i == 0 ? PAD_ERR_NONE : PAD_ERR_NO_CONTROLLER;
	}
	PADStatus& s = status[0];
	u16 b        = 0;
	static const struct {
		int control; // keyboard (bindings.txt)
		int pad;     // game controller (SMS_PAD_MAP)
		u16 bit;
	} map[] = {
		{ C_A, P_A, PAD_BUTTON_A },             { C_B, P_B, PAD_BUTTON_B },
		{ C_X, P_X, PAD_BUTTON_X },             { C_Y, P_Y, PAD_BUTTON_Y },
		{ C_Z, P_Z, PAD_TRIGGER_Z },            { C_START, P_START, PAD_BUTTON_START },
		{ C_DUP, P_DUP, PAD_BUTTON_UP },        { C_DDOWN, P_DDOWN, PAD_BUTTON_DOWN },
		{ C_DLEFT, P_DLEFT, PAD_BUTTON_LEFT },  { C_DRIGHT, P_DRIGHT, PAD_BUTTON_RIGHT },
	};

	// Select + Start (and the Home/Guide button): the in-game menu on
	// Android, quit elsewhere.
#ifdef __ANDROID__
	{
		static bool s_comboWas;
		bool combo = (g_cbtn[4] && g_cbtn[6]) || g_cbtn[5];
		if (combo && !s_comboWas)
			port_open_menu();
		s_comboWas = combo;
		if (combo) { // the menu takes the press; the game does not see Start
			g_cbtn[4] = g_cbtn[5] = g_cbtn[6] = false;
		}
	}
#else
	if (g_cbtn[4] && g_cbtn[6]) {
		port_log("[pad] Exit combo pressed (Select + Start)\n");
		exit(0);
	}
#endif

	// Save states (platform/savestate). On a controller the hotkey (Select
	// by default) turns two buttons into quick save and quick load while it is
	// held (+ R1 saves, + L1 loads); the game sees neither the hotkey nor, while
	// it is held, those two. All three are set in SMS_PAD_MAP like the others.
	// The keys (F5, F6) save and load at once.
	{
		memset(g_masked, 0, sizeof g_masked);
		const bool hotkey = pad_held(P_HOTKEY);
		for (int c : { P_HOTKEY, P_SAVESTATE, P_LOADSTATE })
			for (int i = 0; i < kMaxPadSrc; i++)
				if (g_pmap[c][i].kind == 'b' && (c == P_HOTKEY || hotkey))
					g_masked[g_pmap[c][i].idx] = true;
		static bool s_saveWas, s_loadWas;
		const bool save = held(C_SAVESTATE) || (hotkey && pad_held(P_SAVESTATE));
		const bool load = held(C_LOADSTATE) || (hotkey && pad_held(P_LOADSTATE));
		if (save && !s_saveWas)
			port_savestate_hotkey(PORT_SAVESTATE_HOTKEY_SAVE);
		if (load && !s_loadWas)
			port_savestate_hotkey(PORT_SAVESTATE_HOTKEY_LOAD_NEWEST);
		s_saveWas = save;
		s_loadWas = load;
	}

	// Touch controls: hidden while a game controller is in use (auto), or as set.
	// The in-game menu can change SMS_TOUCH_CONTROLS while the game runs.
	{
		static int s_touchPoll;
		if (++s_touchPoll >= 30) {
			s_touchPoll = 0;
			g_touchMode = parse_touch_mode(getenv("SMS_TOUCH_CONTROLS"));
		}
		int pads = sms_gx_pad_count ? sms_gx_pad_count() : 0;
		static int s_lastPads = -1;
		if (pads != s_lastPads) {
			if (pads == 0) { // a controller went away: release what it held
				memset(g_axis, 0, sizeof g_axis);
				memset(g_cbtn, 0, sizeof g_cbtn);
			}
			s_lastPads = pads;
		}
		bool show = g_touchMode == 1 || (g_touchMode == 0 && pads == 0);
		if (show != touch_controller_is_visible())
			touch_controller_set_visible(show);
	}

	for (size_t i = 0; i < sizeof map / sizeof map[0]; i++)
		if (held(map[i].control) || pad_held(map[i].pad))
			b |= map[i].bit;
	// Triggers: a key is a full press (analog 255 plus the digital click).
	int tl = pad_level(P_L);
	int tr = pad_level(P_R);
	if (held(C_L))
		tl = 255;
	if (held(C_R))
		tr = 255;
	if (pad_held(P_RSOFT)) {
		// Soft R press: spray water while running without locking movement into aim
		if (tr < 180)
			tr = 180;
	}
	if (tl >= 250)
		b |= PAD_TRIGGER_L;
	if (tr >= 250)
		b |= PAD_TRIGGER_R;
	s.triggerLeft  = (u8)tl;
	s.triggerRight = (u8)tr;
	s.analogA      = (b & PAD_BUTTON_A) ? 255 : 0;
	s.analogB      = (b & PAD_BUTTON_B) ? 255 : 0;
	s.button       = b;
	// Sticks are reported raw (a real stick reads about +-100 at the rim);
	// the decomp's PADClamp (libs/dolphin/src/pad/Padclamp.c) then applies the dead
	// zone and the octagon exactly as on hardware. Keys give full deflection
	// (half with HALF_TILT).
	int full = held(C_HALF) ? 50 : 100;
	int x = held(C_RIGHT) * full - held(C_LEFT) * full;
	int y = held(C_UP) * full - held(C_DOWN) * full;
	if (x && y) {
		x = x * 7 / 10;
		y = y * 7 / 10;
	}
	int ms      = pad_stick_axis(P_STICK, 0);
	int cs      = pad_stick_axis(P_CSTICK, 2);
	int sx, sy, scx, scy;
	read_stick(ms, 0, &sx, &sy);
	read_stick(cs, 1, &scx, &scy);
	s.stickX    = (s8)(x || y ? x : sx);
	s.stickY    = (s8)(x || y ? y : sy);
	int cx      = held(C_CRIGHT) * 100 - held(C_CLEFT) * 100;
	int cy      = held(C_CUP) * 100 - held(C_CDOWN) * 100;
	if (!cx && !cy) {
		cx = scx;
		cy = scy;
	}
	s.substickX = (s8)(g_invert_cx ? -cx : cx);
	s.substickY = (s8)(g_invert_cy ? -cy : cy);
	touch_controller_update_pad(&s);
	return PAD_CHAN0_BIT;
}

// Mouse look: the motion since the last call, in C-stick units (1.0 is full
// deflection for one frame), with the camera's inversion applied. Returns 0
// when the mouse has not moved.
extern "C" int port_camera_take_mouse(f32* dx, f32* dy)
{
	*dx = *dy = 0.0f;
	if (!g_mouseDX && !g_mouseDY)
		return 0;
	const float k = 0.02f * g_mouseSens;
	// moving the mouse right turns the view right, as the C-stick pushed left does
	*dx = -(float)g_mouseDX * k;
	*dy = -(float)g_mouseDY * k;
	if (g_invert_cx)
		*dx = -*dx;
	if (g_invert_cy)
		*dy = -*dy;
	g_mouseDX = g_mouseDY = 0;
	return 1;
}

// PADClamp comes from the decomp (libs/dolphin/src/pad/Padclamp.c, see CMakeLists.txt).
