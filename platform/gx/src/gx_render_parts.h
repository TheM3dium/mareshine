// The renderer's parts in files of their own, and what they share with
// gx_render.cpp (inside sms_gx only):
//   gx_post.cpp    the XFB's way to the window: FXAA, the scaler (bilinear,
//                  nearest, sharp, FSR 1 or NIS), sharpening and the
//                  brightness curve (SMS_FXAA, SMS_PRESENT_FILTER,
//                  SMS_SHARPEN, SMS_GAMMA, SMS_ASPECT)
//   gx_layers.cpp  what is drawn over the presented picture: the menus'
//                  panels, the touch controls' sprites, window captures
#pragma once
#include "gl_funcs.h"

namespace gx {

// gx_render.cpp: a vertex and fragment shader linked into a program (0 when not)
GLuint compileProgram(const char* vs, const char* fs);
// gx_render.cpp: GL is set up (GXPC_Init has run, GXPC_Shutdown not yet)
bool rendererReady();
// gx_render.cpp: the vertex array the full-screen and rectangle passes draw with
GLuint quadVao();

// gx_layers.cpp: the menus' panels let go of their textures (GXPC_Shutdown)
void layersShutdown();

// Reads the settings and builds the programs (rendererInit, on the GL thread).
void postInit();
// SMS_ASPECT: 0 keep (letterboxed), 1 stretch, 2 integer multiples of 640x528
int postAspect();
// Draws `tex` (w x h, row 0 at the top) into the viewport (ox, oy, vw, vh) of
// g_presentFbo, through FXAA when it is on. quadVao draws the full-screen
// quad; drawVao is bound again afterwards for the game's draws.
void postPresent(GLuint tex, int w, int h, int ox, int oy, int vw, int vh, GLuint quadVao, GLuint drawVao);

}  // namespace gx
