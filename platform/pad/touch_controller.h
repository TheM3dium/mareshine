#ifndef SMS_TOUCH_CONTROLLER_H
#define SMS_TOUCH_CONTROLLER_H

#include <dolphin/pad.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Initialize touch controller layout and state
void touch_controller_init(void);

// Resize touch controller coordinate mappings
void touch_controller_resize(int winW, int winH);

// Handle touch events from SDL
void touch_controller_on_finger_down(int64_t fingerId, float normX, float normY);
void touch_controller_on_finger_move(int64_t fingerId, float normX, float normY);
void touch_controller_on_finger_up(int64_t fingerId, float normX, float normY);

// Merge touch input into PADStatus
void touch_controller_update_pad(PADStatus* status);

// Render the on-screen touch overlay
void touch_controller_draw(int winW, int winH);

// SAVE and LOAD act only once held (kStateHoldSeconds); 0 makes them act at
// once (SMS_SAVESTATE_DELAY, the state menu's switch)
void touch_controller_set_state_delay(int on);

// Opacity controls
void touch_controller_set_opacity(float opacity);
float touch_controller_get_opacity(void);
void touch_controller_cycle_opacity(void);

// Visibility
void touch_controller_set_visible(bool visible);
bool touch_controller_is_visible(void);

#ifdef __cplusplus
}
#endif

#endif // SMS_TOUCH_CONTROLLER_H
