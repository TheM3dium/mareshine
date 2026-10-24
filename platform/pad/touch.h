// Mareshine's touch controls: on-screen sticks and buttons for phones without
// a controller (touch.cpp). pad.cpp feeds them the screen's finger events and
// lets them add to controller 1's state; the GX layer draws them over each
// presented frame (touch_controls_draw).
#pragma once
#include <dolphin/pad.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// A finger, at x, y as fractions of the window (SDL's finger events).
void touch_controls_finger(int64_t finger, int phase, float x, float y); // phase: 0 down, 1 move, 2 up
// A controller was used: with SMS_TOUCH_CONTROLS=auto the controls hide
// until the screen is touched again.
void touch_controls_controller_used(void);
// Adds what the fingers hold to `status` (after the controller's own).
void touch_controls_pad(PADStatus* status);
// Draws them (the GX layer, while a frame is presented).
void touch_controls_draw(int winW, int winH);

#ifdef __cplusplus
}
#endif
