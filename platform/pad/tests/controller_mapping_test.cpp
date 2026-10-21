// Run against the actual input reader, without SDL, a disc or game assets.
#include "../pad.cpp"
#include <cassert>
#undef main

extern "C" void port_log(const char*, ...) {}
extern "C" void port_savestate_hotkey(int) {}
extern "C" void touch_controller_on_finger_down(int64_t, float, float) {}
extern "C" void touch_controller_on_finger_move(int64_t, float, float) {}
extern "C" void touch_controller_on_finger_up(int64_t, float, float) {}
extern "C" void touch_controller_update_pad(PADStatus*) {}
extern "C" void touch_controller_set_visible(bool) {}
extern "C" bool touch_controller_is_visible() { return false; }

int main() {
    setenv("SMS_BINDINGS", "/dev/null", 1);
    unsetenv("SMS_PAD_MAP");
    unsetenv("SMS_CAMERA_INVERT_X");
    unsetenv("SMS_CAMERA_INVERT_Y");
    unsetenv("SMS_FLUDD_INVERT_X");
    unsetenv("SMS_FLUDD_INVERT_Y");
    setenv("SMS_TOUCH_CONTROLS", "off", 1);
    PADStatus s[4];
    PADRead(s); // initialize the normal defaults and connection state
    g_axis[0] = 32767; g_axis[2] = 32767; g_axis[3] = 0;
    PADRead(s);
    assert(s[0].stickX == 100 && s[0].substickX == 100 && s[0].substickY == 0);
    g_invert_cx = true;
    PADRead(s);
    assert(s[0].stickX == 100 && s[0].substickX == -100 && s[0].substickY == 0);
    g_axis[2] = 0; g_axis[3] = -32767;
    g_invert_cy = true;
    PADRead(s);
    assert(s[0].substickX == 0 && s[0].substickY == -100);
    g_axis[2] = 32767; g_axis[3] = 0;
    g_invert_cx = g_invert_cy = false;
    parse_pad_map("STICK=a2 CSTICK=a0");
    g_axis[0] = -32767;
    PADRead(s);
    assert(s[0].stickX > 0 && s[0].substickX == -100);
    parse_pad_map("CSTICK=none");
    PADRead(s);
    assert(s[0].stickX > 0 && s[0].substickX == 0 && s[0].substickY == 0);
    parse_pad_map("STICK=none CSTICK=a2");
    PADRead(s);
    assert(s[0].stickX == 0 && s[0].stickY == 0 && s[0].substickX > 0);
    assert(port_fludd_aim_axis(.5f, 0) == .5f && port_fludd_aim_axis(-.5f, 1) == -.5f);
    g_invert_aim_x = true;
    assert(port_fludd_aim_axis(.5f, 0) == -.5f && port_fludd_aim_axis(-.5f, 1) == -.5f);
    g_invert_aim_y = true;
    assert(port_fludd_aim_axis(.5f, 0) == -.5f && port_fludd_aim_axis(-.5f, 1) == .5f);
    parse_pad_map(kDefaultPadMap);
    g_axis[0] = 32767;
    PADRead(s);
    assert(s[0].stickX == 100); // aim options must not reverse movement
    puts("10 controller mapping/inversion/FLUDD checks passed");
    return 0;
}
