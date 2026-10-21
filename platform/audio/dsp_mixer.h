// Software replacement for the JAudio DSP microcode's mixer (see PROTOCOL.md).
//
// The game (JASystem::DSPInterface) writes per-voice parameter blocks
// ("DSPBuffer", 0x180 bytes, 64 of them) and four FX-line blocks ("FXBuffer",
// 0x20 bytes) in main memory, in host byte order (the CPU code writes them
// field by field). This module reads those blocks and renders one DSP
// subframe at a time into two s16 output buffers, the way the microcode fills
// the buffers named by the 0x82 "sync frame" command.
//
// No game headers are used, so the WAV test (tests/audio_test.cc) can link it
// on its own.
#ifndef SMS_PORT_AUDIO_DSP_MIXER_H
#define SMS_PORT_AUDIO_DSP_MIXER_H
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Resolves an ARAM byte address to host memory (NULL when out of range). */
typedef const uint8_t* (*port_dspmix_aram_fn)(uint32_t addr);

/* The 0x81 setup command: voice count, the voice block array, the setup
 * table (DSPRES_FILTER, s16 packed two per u32: 64 phases x 4 resampling taps,
 * then four 64-sample oscillator patterns; 256 u32 are read), the ADPCM
 * predictor table (DSPADPCM_FILTER, 16 u32 = coef1<<16|coef2) and the four
 * FX-line blocks. Tables may be NULL: built-in defaults are used. */
void port_dspmix_setup(uint32_t nvoices, void* voices, const uint32_t* res_filter, const uint32_t* adpcm_filter,
                       void* fx_lines);
void port_dspmix_set_aram(port_dspmix_aram_fn fn);

/* Optional main-memory resolver for offline FX tests. NULL uses the game
 * buffer address directly; production delay buffers live in mapped MEM1. */
typedef int16_t* (*port_dspmix_mram_fn)(uint32_t addr, uint32_t samples);
void port_dspmix_set_mram(port_dspmix_mram_fn fn);

/* Render one subframe of `n` samples (80 in SMS) into outA/outB (bus 1 and
 * bus 2, see PROTOCOL.md). `master` is the mixer level from the 0x82
 * command (Q15; SMS uses 0x5000 = 0.625). Voice blocks are updated in place (done/endReached/volumes). */
void port_dspmix_render(int16_t* outA, int16_t* outB, int n, uint16_t master);

/* Volume classes: a voice follows either the music or the SFX setting
 * (README.md, Music and SFX volume). The game writes the class into the voice
 * block's byte at 0x006 when it starts the voice (decomp-patches/audio-05:
 * 1 for a voice a BGM sequence plays, 0 for everything else); direct-PCM
 * stream voices are music whatever the byte says. */
enum { PORT_DSPMIX_CLASS_SFX = 0, PORT_DSPMIX_CLASS_MUSIC = 1, PORT_DSPMIX_CLASSES = 2 };

/* A class's gain in percent, 0..100 (default 100: the samples are not touched).
 * Safe to call from any thread: the mixer reads it once per subframe and ramps
 * to a new value over that subframe. */
void port_dspmix_set_class_percent(int cls, int percent);
int port_dspmix_class_percent(int cls);

/* The same as a Q15 factor (32768 = unity), for mixing that runs outside this
 * module and carries music (THP movie audio). */
int port_dspmix_class_gain(int cls);

/* Diagnostics: voices rendered in the last subframe. */
int port_dspmix_active_voices(void);

/* Test hooks: decode `count` samples of a wave (format 0 ADPCM4, 1 ADPCM2,
 * 2 PCM8, 3 PCM16) starting at ARAM address `base` into `out`. */
void port_dspmix_decode_wave(int format, uint32_t base, int count, int16_t* out);

#ifdef __cplusplus
}
#endif
#endif
