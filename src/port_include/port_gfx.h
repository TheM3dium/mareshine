#ifndef SMS_PORT_GFX_H
#define SMS_PORT_GFX_H
#ifdef __cplusplus
extern "C" {
#endif
// Mareshine's graphics settings (platform/misc/port_gfx.cpp), for slower
// phones: read by the game where it draws (decomp-patches/zzzzzz-gfx-01).
//   SMS_DRAW_DISTANCE        how far the game draws and keeps actors in view,
//                            times the original (1.0; 0.05 to 3)
//   SMS_FAR_PLANE            and never further than this, 0 (default) no limit
//   SMS_DISABLE_SHADOWS      1: no shadows (blob, bound and the Shine's volume)
//   SMS_DISABLE_REFLECTIONS  1: no screen after-effects nor mirror copies
//   SMS_DISABLE_WATER_ANIM   1: the sea's waves stand still, 2: not drawn
float port_gfx_draw_distance(void);
float port_gfx_far_plane(void);
int port_gfx_shadows_off(void);
int port_gfx_reflections_off(void);
int port_gfx_water_anim(void); // 0 moving, 1 still, 2 none
#ifdef __cplusplus
}
#endif
#endif
