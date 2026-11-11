# Code map

Where things live, for finding the code behind a report quickly. Every file starts with a comment saying what it does; this page is the index to those comments. How the build fits together and where a fix belongs (decomp, `decomp-patches/` or `platform/`) is in [DEVELOPMENT.md](DEVELOPMENT.md), whose *Platform layer* table lists the emulated hardware.

## From a symptom to the code

| What goes wrong | Look first in |
| --- | --- |
| The launcher finds no disc, or the wrong one | `android/.../DiscFinder.java` (the search order), `DiscCheck.java` (header and hash) |
| Picking a disc image fails or the copy breaks off | `LauncherActivity.java` (`checkThenCopyIso`, `copyIsoFromUri`), `UriCopy.java` |
| A setting does not stick, or comes back after a restart | `Settings.java` (settings.txt), `platform/port_runtime.cpp` (`kSettings`: which names the game reads), `SunshineActivity.clearOldSettings` (the game process keeps its environment) |
| The game does not start, or closes at once | `SunshineActivity.java` (arguments, libraries), `android/bootstrap.cpp` (the loader), `platform/port_runtime.cpp` (boot), logs in `<data folder>/logs` (`CrashLog.java`, `StartLog.java`) |
| "Mareshine isn't responding" (a freeze) | `AnrLog.java`, `HangWatch.java`, `StartLog.java` |
| Wrong or missing graphics | `platform/gx/src/gx_render.cpp` (draws, GL state, EFB copies, peeks), `gx_shader.cpp` (TEV/lighting as GLSL), `gx_texture.cpp` (texture formats); `SMS_GX_TRACE_FRAME` (`gx_trace.cpp`) writes a frame's draws |
| Blurry, too sharp or wrongly scaled picture; FSR/NIS/FXAA; brightness | `platform/gx/src/gx_post.cpp` |
| Menus over the game (warp, cheats, state messages), touch controls not drawn | `platform/gx/src/gx_layers.cpp` (drawing), `gx_warp_menu.cpp`, `gx_cheat_menu.cpp`, `gx_savestate_overlay.cpp`, `platform/pad/touch.cpp` |
| Widescreen: HUD in the wrong place, stretched 2D | `gx_render.cpp` (the XMap comment near the top, `drawXMap`), `gx_screen_extent.h` |
| A GPU that crashes or draws wrong on Android (Mali, Adreno, ANGLE) | `platform/gx/src/gx_android.cpp` (GLES adapters), `gx_platform.cpp` (context, ANGLE), `AngleOffer.java` |
| Slow frames, stutter | `SMS_GX_HITCH_MS` and the debug overlay (`gx_overlay.cpp`), `gx_glthread.cpp` (the GL thread), `platform/android/performance*.{cpp,h}` |
| HD textures missing or wrong | `platform/gx/src/gx_hires.cpp`, `TexturePackActivity.java` |
| Controller buttons, sticks, rumble | `platform/pad/pad.cpp`, `gc_adapter.cpp`, `rumble_game.cpp`; app side `ControllerSettingsActivity.java`, `StickCalibrationActivity.java` |
| Touch controls (place, feel) | `platform/pad/touch.cpp`; editor `TouchLayoutActivity.java`, drawing in the app `TouchDraw.java` |
| Gyro aiming | `GyroCalibrateActivity.java`, `platform/pad/pad.cpp` (`port_fludd_aim_axis`, `port_fludd_look_axis`) |
| Save states | `platform/savestate/` ([SAVE-STATES.md](SAVE-STATES.md)), `SaveStates.java`, `SaveStatesActivity.java` |
| Sound | `platform/audio/` (`dsp_mixer.cpp`), `GameAudio.java` (volumes) |
| Cutscenes (HD movies) | `platform/thp/mp4_movies.cpp`, `CutsceneActivity.java`, `CutscenePack.java`, `CutsceneConvert.java` ([HD-CUTSCENES.md](HD-CUTSCENES.md)) |
| Mods: install, switch, update | `Mods.java` (the packages), `ModInstallActivity.java`, `ModUpdates.java`, `ModsActivity.java`, `ModPageActivity.java`, `Vcdiff.java` ([MODS.md](MODS.md)) |
| A mod's code at run time (Eclipse, Sunburn) | `platform/mods/` (`modhooks.cpp`), `decomp-patches/zzzz-modhook-*`, [ECLIPSE.md](ECLIPSE.md) |
| Language packs, mods in another language | `LanguagePackActivity.java`, `LanguagePackBuilder.java`, `ModLanguage.java` |
| The app's own language | `L.java`, `android/app/src/main/assets/lang/*.json` |
| Achievements | `platform/achievements/` ([ACHIEVEMENTS-FORMAT.md](ACHIEVEMENTS-FORMAT.md)), `Achievements.java`, `AchievementsActivity.java` |
| Sync between devices | `Sync.java` (one zip file), `WifiSync.java` (same Wi-Fi) |
| App updates | `Updates.java` |
| Reports and device info sent to us | `ReportSend.java`, `DeviceInfo.java`, `tools/ratings-worker/` |
| Warping in test builds | `Warp.java`, `platform/android/warp_android.cpp`, `gx_warp_menu.cpp` |

## The Android app (`android/app/src/main/java/io/github/them3dium/mareshine/`)

Two processes: the launcher and its screens, and the game (`:game`, `SunshineActivity` on top of SDL). Classes that only work in the game's process say so in their comment (`SaveStates`, `Warp`).

- **Start and the main menu:** `SunshineApp` (each process's start), `LauncherActivity` (the main menu), `DiscFinder`, `DiscCheck`, `UriCopy`, `DataDir` (the data folder), `Pickers`, `Intro`/`IntroActivity`/`Tour` (what's new).
- **The game:** `SunshineActivity`, `GameMenuActivity` (the quick menu over the game), `SaveStates`, `SaveStatesActivity`, `GameAudio`, `Warp`.
- **Settings:** `Settings` (settings.txt), `SettingsActivity` (the settings screen), `ControllerSettingsActivity`, `ControllerStickCapture`, `StickCalibrationActivity`, `StickBehavior`, `GyroCalibrateActivity`, `TouchLayoutActivity`, `TouchDraw`, `GcShapes`, `MenuKeys`.
- **Content:** `TexturePackActivity`, `CutsceneActivity`, `CutscenePack`, `CutsceneConvert`, `LanguagePackActivity`, `LanguagePackBuilder`.
- **Mods:** `Mods`, `ModsActivity`, `ModPageActivity`, `ModViews`, `ModFlows`, `ModInstallActivity`, `ModUpdates`, `ModGame`, `ModLanguage`, `EclipseMod`, `Vcdiff`, `Ratings`.
- **Achievements:** `Achievements`, `AchievementsActivity`.
- **Logs and reports:** `CrashLog`, `StartLog`, `AnrLog`, `HangWatch`, `LogShare`, `ReportSend`, `DeviceInfo`.
- **Syncing and updates:** `Sync`, `WifiSync`, `WifiSyncActivity`, `Updates`, `ShaderService` (shaders compiled while the menu shows).
- **Look and language:** `AppActivity` (every screen's base: layout, system bars), `DlDialog`, `MenuDialog`, `L`.

## The graphics (`platform/gx/src/`)

The GameCube GPU is driven by register writes; sms_gx keeps the registers (`gx_internal.h`) and turns them into OpenGL when something is drawn.

| File | Part |
| --- | --- |
| `gx_api.cpp` | The GX API the game calls, as register writes |
| `gx_fifo.cpp` | The command stream, the vertex loader, display lists and their cache |
| `gx_render.cpp` | The renderer: batches, GL state, uniforms, EFB copies, pixel peeks, presenting the XFB |
| `gx_post.cpp` | The XFB's way to the window: FXAA, scalers (bilinear, sharp, FSR 1, NIS), sharpening, brightness |
| `gx_layers.cpp` | What is drawn over the presented picture: menu panels, the touch controls' sprites, window captures |
| `gx_shader.cpp` | GLSL programs generated from the TEV, lighting and texgen state |
| `gx_texture.cpp`, `gx_hires.cpp`, `gx_bcenc.cpp` | Texture decoding and cache; texture packs; block compression for packs |
| `gx_glthread.cpp`, `gx_glcache.h`, `gl_funcs.h`, `gl_loader.cpp` | The GL thread, the GL state shadow, the GL loader |
| `gx_platform.cpp`, `gx_android.cpp`, `gx_hdr.cpp` | Window and context; GLES adapters for Android; HDR on Windows |
| `gx_overlay.cpp`, `gx_warp_menu.cpp`, `gx_cheat_menu.cpp`, `gx_savestate_overlay.cpp`, `gx_prompts.cpp`, `gx_backdrop.cpp` | The port's own menus and pictures |
| `gx_trace.cpp` | Frame traces for debugging (`SMS_GX_TRACE_FRAME`) |
