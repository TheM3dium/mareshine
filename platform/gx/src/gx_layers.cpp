// What is drawn over the game's picture once it is presented: the menus'
// panels (drawOverlayPanel), Mareshine's touch controls (the sprites), and
// the window captures for tests (SMS_WINDOW_SHOTS). gx_render_parts.h.
#include "gx_internal.h"
#include "gl_funcs.h"
#include "gx_glcache.h"
#include "gx_render_parts.h"
#include "sms_gx/gx_pc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <algorithm>
#include <vector>

namespace gx {

static GLuint s_overlayTex[OVERLAY_SLOTS];
static int s_overlayW[OVERLAY_SLOTS], s_overlayH[OVERLAY_SLOTS];
static GLuint s_overlayProg;
static GLint s_overlayRect, s_overlayWindow;

void layersShutdown() {
    for (int i = 0; i < OVERLAY_SLOTS; i++) {
        if (s_overlayTex[i]) glDeleteTextures(1, &s_overlayTex[i]);
        s_overlayTex[i] = 0;
        s_overlayW[i] = s_overlayH[i] = 0;
    }
    if (s_overlayProg) glDeleteProgram(s_overlayProg);
    s_overlayProg = 0;
}

}  // namespace gx

using namespace gx;

// SMS_WINDOW_SHOTS=frame,frame,...: the window as it is shown, with the menus
// drawn over the game, at those GX frames (GXPC_FrameCount), written to
// SMS_SHOT_DIR (default "shots") as window<frame>.ppm. The XFB captures
// (SMS_SHOTS) have the game's picture only.
void gx::captureWindow(uint32_t frame, int w, int h) {
    static std::vector<uint32_t> frames;
    static bool parsed = false;
    if (!parsed) {
        parsed = true;
        if (const char* e = getenv("SMS_WINDOW_SHOTS"))
            for (const char* p = e; *p;) {
                frames.push_back(uint32_t(strtoul(p, const_cast<char**>(&p), 10)));
                while (*p == ',' || *p == ' ') p++;
            }
    }
    if (w <= 0 || h <= 0 || std::find(frames.begin(), frames.end(), frame) == frames.end()) return;
    std::vector<uint8_t> px(size_t(w) * h * 4);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    const char* dir = getenv("SMS_SHOT_DIR");
    char path[1024];
    snprintf(path, sizeof path, "%s/window%05u.ppm", dir ? dir : "shots", frame);
    if (FILE* f = fopen(path, "wb")) {
        fprintf(f, "P6\n%d %d\n255\n", w, h);
        for (int y = h - 1; y >= 0; y--)  // GL's rows go bottom up
            for (int x = 0; x < w; x++) fwrite(&px[(size_t(y) * w + x) * 4], 1, 3, f);
        fclose(f);
        fprintf(stderr, "[sms_gx] captured the window at frame %u -> %s\n", frame, path);
    }
}

void gx::drawOverlayPanel(OverlaySlot slot, const uint8_t* rgba, int w, int h, int x, int y, int scale, int winW, int winH) {
    if (!rendererReady() || w <= 0 || h <= 0) return;
    if (!rgba && (!s_overlayTex[slot] || w != s_overlayW[slot] || h != s_overlayH[slot])) return;
    flushBatch();
    glcInvalidate();
    // glBlitFramebuffer ignores alpha, so draw the panel with source-alpha blending.
    if (!s_overlayProg) {
        static const char* vs = R"(#version 330 core
uniform vec4 u_rect;
uniform vec2 u_window;
out vec2 v_uv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    v_uv = vec2(p.x, 1.0 - p.y);
    gl_Position = vec4((u_rect.xy + p * u_rect.zw) / u_window * 2.0 - 1.0, 0.0, 1.0);
}
)";
        static const char* fs = R"(#version 330 core
uniform sampler2D u_color;
in vec2 v_uv;
out vec4 o_color;
void main() { o_color = texture(u_color, v_uv); }
)";
        s_overlayProg = compileProgram(vs, fs);
        s_overlayRect = glGetUniformLocation(s_overlayProg, "u_rect");
        s_overlayWindow = glGetUniformLocation(s_overlayProg, "u_window");
    }
    GLint activeUnit = 0, boundTex = 0, rowLength = 0, unpackAlign = 4;
    GLint program = 0, vao = 0, drawFbo = 0, viewport[4], colorMask[4];
    GLint blend = 0, depth = 0, stencil = 0, cull = 0, scissor = 0, logic = 0, clip0 = 0, clip1 = 0;
    GLint srcRGB = 0, dstRGB = 0, srcAlpha = 0, dstAlpha = 0, eqRGB = 0, eqAlpha = 0;
    glGetIntegerv(GL_ACTIVE_TEXTURE, &activeUnit);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &boundTex);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &rowLength);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpackAlign);
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
    glGetIntegerv(GL_VIEWPORT, viewport);
    glGetIntegerv(GL_COLOR_WRITEMASK, colorMask);
    glGetIntegerv(GL_BLEND, &blend);
    glGetIntegerv(GL_DEPTH_TEST, &depth);
    glGetIntegerv(GL_STENCIL_TEST, &stencil);
    glGetIntegerv(GL_CULL_FACE, &cull);
    glGetIntegerv(GL_SCISSOR_TEST, &scissor);
    glGetIntegerv(GL_COLOR_LOGIC_OP, &logic);
    glGetIntegerv(GL_CLIP_DISTANCE0, &clip0);
    glGetIntegerv(GL_CLIP_DISTANCE1, &clip1);
    glGetIntegerv(GL_BLEND_SRC_RGB, &srcRGB);
    glGetIntegerv(GL_BLEND_DST_RGB, &dstRGB);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &srcAlpha);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &dstAlpha);
    glGetIntegerv(GL_BLEND_EQUATION_RGB, &eqRGB);
    glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &eqAlpha);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (!s_overlayTex[slot]) {
        glGenTextures(1, &s_overlayTex[slot]);
        glBindTexture(GL_TEXTURE_2D, s_overlayTex[slot]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    } else {
        glBindTexture(GL_TEXTURE_2D, s_overlayTex[slot]);
    }
    if (rgba) {
        if (w != s_overlayW[slot] || h != s_overlayH[slot]) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            s_overlayW[slot] = w;
            s_overlayH[slot] = h;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        }
    }
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_COLOR_LOGIC_OP);
    glDisable(GL_CLIP_DISTANCE0);
    glDisable(GL_CLIP_DISTANCE1);
    glEnable(GL_BLEND);
    glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, g_presentFbo);
    glViewport(0, 0, winW, winH);
    glUseProgram(s_overlayProg);
    glUniform1i(glGetUniformLocation(s_overlayProg, "u_color"), 0);
    glBindVertexArray(quadVao());
    float rect[4] = {float(x), float(winH - y - h * scale), float(w * scale), float(h * scale)};
    float window[2] = {float(winW), float(winH)};
    glUniform4fv(s_overlayRect, 1, rect);
    glUniform2fv(s_overlayWindow, 1, window);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindTexture(GL_TEXTURE_2D, GLuint(boundTex));
    glActiveTexture(GLenum(activeUnit));
    glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
    glPixelStorei(GL_UNPACK_ALIGNMENT, unpackAlign);
    glBindVertexArray(GLuint(vao));
    glUseProgram(GLuint(program));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, GLuint(drawFbo));
    glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    glBlendFuncSeparate(GLenum(srcRGB), GLenum(dstRGB), GLenum(srcAlpha), GLenum(dstAlpha));
    glBlendEquationSeparate(GLenum(eqRGB), GLenum(eqAlpha));
    glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
    if (!blend) glDisable(GL_BLEND);
    if (depth) glEnable(GL_DEPTH_TEST);
    if (stencil) glEnable(GL_STENCIL_TEST);
    if (cull) glEnable(GL_CULL_FACE);
    if (scissor) glEnable(GL_SCISSOR_TEST);
    if (logic) glEnable(GL_COLOR_LOGIC_OP);
    if (clip0) glEnable(GL_CLIP_DISTANCE0);
    if (clip1) glEnable(GL_CLIP_DISTANCE1);
}

// ------------------------------------------------------------------ sprites
// Images drawn over the presented frame many at a time (Mareshine's touch
// controls): one atlas, uploaded only when it changes, then per frame a list
// of rectangles cut from it, all with one opacity. A frame of them is one
// texture bind and a draw per rectangle, with no upload.
static GLuint s_spriteTex, s_spriteProg;
static GLint s_spriteRect = -1, s_spriteUv = -1, s_spriteWindow = -1, s_spriteAlpha = -1;
static int s_spriteW, s_spriteH;

extern "C" void GXPC_SpriteAtlas(const uint8_t* rgba, int w, int h) {
    if (!rendererReady() || !rgba || w <= 0 || h <= 0) return;
    GLint boundTex = 0, activeUnit = 0, rowLength = 0, unpackAlign = 4;
    glGetIntegerv(GL_ACTIVE_TEXTURE, &activeUnit);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &boundTex);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &rowLength);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpackAlign);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (!s_spriteTex) {
        glGenTextures(1, &s_spriteTex);
        glBindTexture(GL_TEXTURE_2D, s_spriteTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    } else {
        glBindTexture(GL_TEXTURE_2D, s_spriteTex);
    }
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    s_spriteW = w;
    s_spriteH = h;
    glBindTexture(GL_TEXTURE_2D, GLuint(boundTex));
    glActiveTexture(GLenum(activeUnit));
    glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
    glPixelStorei(GL_UNPACK_ALIGNMENT, unpackAlign);
}

// A texture of the caller's (GXPC_UploadTexture: the cutscene frame) or the
// sprite atlas, drawn in rectangles over the presented frame.
extern "C" unsigned GXPC_UploadTexture(const uint8_t* rgba, int w, int h) {
    if (!rendererReady() || !rgba || w <= 0 || h <= 0) return 0;
    GLint boundTex = 0, activeUnit = 0, rowLength = 0, unpackAlign = 4;
    glGetIntegerv(GL_ACTIVE_TEXTURE, &activeUnit);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &boundTex);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &rowLength);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpackAlign);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glBindTexture(GL_TEXTURE_2D, GLuint(boundTex));
    glActiveTexture(GLenum(activeUnit));
    glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
    glPixelStorei(GL_UNPACK_ALIGNMENT, unpackAlign);
    return tex;
}

extern "C" void GXPC_DrawSprites(const GXPCSprite* sprites, int count, float alpha, int winW, int winH) {
    GXPC_DrawTexture(s_spriteTex, s_spriteW, s_spriteH, sprites, count, alpha, winW, winH);
}

extern "C" void GXPC_DrawTexture(unsigned texture, int texW, int texH, const GXPCSprite* sprites, int count,
                                 float alpha, int winW, int winH) {
    if (!rendererReady() || !texture || texW <= 0 || texH <= 0 || !sprites || count <= 0 || alpha <= 0.0f) return;
    flushBatch();
    glcInvalidate();
    if (!s_spriteProg) {
        static const char* vs = R"(#version 330 core
uniform vec4 u_rect;
uniform vec4 u_uv;
uniform vec2 u_window;
out vec2 v_uv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    v_uv = u_uv.xy + p * u_uv.zw;
    gl_Position = vec4((u_rect.xy + vec2(p.x, 1.0 - p.y) * u_rect.zw) / u_window * 2.0 - 1.0, 0.0, 1.0);
}
)";
        static const char* fs = R"(#version 330 core
uniform sampler2D u_color;
uniform float u_alpha;
in vec2 v_uv;
out vec4 o_color;
void main() { vec4 c = texture(u_color, v_uv); o_color = vec4(c.rgb, c.a * u_alpha); }
)";
        s_spriteProg = compileProgram(vs, fs);
        s_spriteRect = glGetUniformLocation(s_spriteProg, "u_rect");
        s_spriteUv = glGetUniformLocation(s_spriteProg, "u_uv");
        s_spriteWindow = glGetUniformLocation(s_spriteProg, "u_window");
        s_spriteAlpha = glGetUniformLocation(s_spriteProg, "u_alpha");
    }
    GLint activeUnit = 0, boundTex = 0, program = 0, vao = 0, drawFbo = 0, viewport[4], colorMask[4];
    GLint blend = 0, depth = 0, stencil = 0, cull = 0, scissor = 0;
    GLint srcRGB = 0, dstRGB = 0, srcAlpha = 0, dstAlpha = 0, eqRGB = 0, eqAlpha = 0;
    glGetIntegerv(GL_ACTIVE_TEXTURE, &activeUnit);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &boundTex);
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
    glGetIntegerv(GL_VIEWPORT, viewport);
    glGetIntegerv(GL_COLOR_WRITEMASK, colorMask);
    glGetIntegerv(GL_BLEND, &blend);
    glGetIntegerv(GL_DEPTH_TEST, &depth);
    glGetIntegerv(GL_STENCIL_TEST, &stencil);
    glGetIntegerv(GL_CULL_FACE, &cull);
    glGetIntegerv(GL_SCISSOR_TEST, &scissor);
    glGetIntegerv(GL_BLEND_SRC_RGB, &srcRGB);
    glGetIntegerv(GL_BLEND_DST_RGB, &dstRGB);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &srcAlpha);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &dstAlpha);
    glGetIntegerv(GL_BLEND_EQUATION_RGB, &eqRGB);
    glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &eqAlpha);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, g_presentFbo);
    glViewport(0, 0, winW, winH);
    glUseProgram(s_spriteProg);
    glUniform1i(glGetUniformLocation(s_spriteProg, "u_color"), 0);
    glBindTexture(GL_TEXTURE_2D, GLuint(texture));
    glBindVertexArray(quadVao());
    const float window[2] = {float(winW), float(winH)};
    glUniform2fv(s_spriteWindow, 1, window);
    glUniform1f(s_spriteAlpha, alpha > 1.0f ? 1.0f : alpha);
    for (int i = 0; i < count; i++) {
        const GXPCSprite& sp = sprites[i];
        // x, y from the window's top left; GL's y goes up
        const float rect[4] = {sp.x, float(winH) - sp.y - sp.h, sp.w, sp.h};
        const float uv[4] = {float(sp.u) / texW, float(sp.v) / texH, float(sp.uw) / texW, float(sp.vh) / texH};
        glUniform4fv(s_spriteRect, 1, rect);
        glUniform4fv(s_spriteUv, 1, uv);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glBindTexture(GL_TEXTURE_2D, GLuint(boundTex));
    glActiveTexture(GLenum(activeUnit));
    glBindVertexArray(GLuint(vao));
    glUseProgram(GLuint(program));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, GLuint(drawFbo));
    glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    glBlendFuncSeparate(GLenum(srcRGB), GLenum(dstRGB), GLenum(srcAlpha), GLenum(dstAlpha));
    glBlendEquationSeparate(GLenum(eqRGB), GLenum(eqAlpha));
    glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
    if (!blend) glDisable(GL_BLEND);
    if (depth) glEnable(GL_DEPTH_TEST);
    if (stencil) glEnable(GL_STENCIL_TEST);
    if (cull) glEnable(GL_CULL_FACE);
    if (scissor) glEnable(GL_SCISSOR_TEST);
}
