# Adapted renderer code

Source: https://github.com/chasem-dev/sms-pc-port/tree/bb9fd1d0fbd113cbe37e16a37091238026ff25f0

The fixed-point indirect texture-coordinate calculation in gx_shader.cpp is adapted from this revision under the accompanying MIT license. It preserves signed 24-bit coordinate wrapping, including the large matrix exponent used by Sunshine's goop bubbles.

The paired renderer uniform supplies the exponent rather than a floating multiplier. Mareshine's program-key indirect-scale handling and other Android integration remain separate.

The final unclamped TEV output conversion also follows this revision: RGB and alpha retain their low eight bits before alpha testing and blending. A separate before/after regression test covers the water-material case.

Fog likewise uses the fragment window depth rather than a perspective-interpolated per-vertex depth, following the pinned renderer. Own regression tests cover a receding floor and a floor crossing the camera plane.

## Later adoptions (same project, MIT; decomp patches are copied verbatim)

From `main` and `feat/android-arm64` of the same repository, adapted to Mareshine's renderer and checked with own regression tests:

- `3dfe1ab`: arbitrary ("effect") mipmap detection with a resolution-compensating LOD bias and no anisotropic filtering for such textures (the distant sea's white specks). On GLES, which has no sampler LOD bias, the fragment shaders take the bias as `u_lodbias`, following the GLES adapter of `bb9fd1d`.
- `1de2238`: GX lines drawn GXSetLineWidth wide through a geometry shader (iris wipes). On GLES only with 3.2 or `GL_EXT/OES_geometry_shader`.
- `0e832d4`: GLES peeks drawn into a shader storage buffer and answered from a finished frame instead of reading the EFB back.
- `6d0cf5a`: the idea of rejecting a damaged shader cache record; Mareshine's cache layout differs, so the file is cut back to its last whole record instead.
- `d205537`: `platform/audio/ai.cpp` starts a DMA block only when JAudio's audio thread is idle and grows the output queue after it ran dry.
- Decomp patches `uninit-05`, `fpu-08`, `fpu-09`, `endian-19`, `port-09`, `bounds-05`, `audio-04`, `bounds-04`, `perf-01`, `thp-06`, `widescreen-04` to `-07`, `zz-msl-01`, `zzzz-pause-01`, `zzzz-rate-01` as published there, with `GXPC_SetWide2D` (65b6ad0) for the map and title card.
- `cca8e9c`: the DSP mixer synthesises oscillator voices (square, saw and the setup table's patterns), so sounds such as the Gelato Beach Dune Buds are no longer silent.
- `01ea0e6` (audio part) with decomp patch `audio-05`: separate music and sound effect volumes (`SMS_MUSIC_VOLUME`, `SMS_SFX_VOLUME`) applied per voice class in the mixer and to THP movie audio.
- `0ea3990` (display-list cache and write stamps): later calls of an unchanged display list reuse its decoded vertices and indices, state-only lists replay as decoded register writes, and DVD/ARAM transfers, cache flushes, `DCZeroRange`, copy write-backs and loaded save states stamp the 4 KiB pages they write, so the display-list and texture caches hash their sources only after a stamp (and every 30 or 8 frames regardless). Also its inline write-gather pipe writers and the unchanged-array shortcut. Adapted to Mareshine's chunked vertex reader; the display-list arena, the GL thread, GPU write-back encoding and the FMA math builds are not adopted. `SMS_GX_DL_CACHE=0` and `SMS_GX_HASH_ALWAYS=1` turn the cache and the stamps off.
