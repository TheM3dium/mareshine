#ifndef SMS_HD_MOVIES_H
#define SMS_HD_MOVIES_H
#include <dolphin/types.h>
#ifdef __cplusplus
extern "C" {
#endif
BOOL port_draw_hd_thp(u8* y, u8* u, u8* v, s16 x, s16 pos_y,
                     s16 width, s16 height, s16 draw_width, s16 draw_height);
// A complete installed pack, when HD textures are enabled; NULL otherwise.
const char* port_hd_cutscene_directory(void);
// The THP player's movie (patch thp-06): an HD picture source may stand in
// for its picture (platform/thp/mp4_movies.cpp); the THP keeps the sound,
// timing, subtitles and rumble.
void port_thp_opened(const char* name, u32 width, u32 height, u32 frames);
void port_thp_closed(void);
void port_thp_draw_frame(s32 frame);
// Draws the HD picture for the frame being drawn into the THP's rectangle;
// FALSE when there is none (the THP's own picture is drawn).
BOOL port_mp4_draw(s16 x, s16 y, s16 draw_width, s16 draw_height);
// The low memory HD movie frames are drawn from (hd_movies.cpp).
u8* port_hd_movie_pool(void);
#ifdef __cplusplus
}
#endif
#endif
