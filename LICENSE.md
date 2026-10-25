# Licenses

Mareshine combines work from several projects, each under its own terms.

| Part | Origin | License |
| --- | --- | --- |
| The game port itself since Mareshine 2.0: graphics layer, platform layer, Android loader, port patches and tools | [chasem-dev/sms-pc-port](https://github.com/chasem-dev/sms-pc-port) | MIT, see [below](#sms-pc-port) |
| The decompilation (`decomp/` submodule) | [chasem-dev/sms-english](https://github.com/chasem-dev/sms-english), based on [doldecomp/sms](https://github.com/doldecomp/sms) | CC0 1.0 (public domain dedication) |
| The Android port Mareshine 1.x was based on; the Android app (launcher and settings) started from its launcher | [jbcgames/sms-android](https://github.com/jbcgames/sms-android) | No license stated; all rights remain with its author. A license has been requested ([jbcgames/sms-android#6](https://github.com/jbcgames/sms-android/issues/6)). |
| The GameCube touch button shapes (`platform/pad/touch_gamecube.h`, traced from its pictures in `tools/touch-gamecube/`) | [m-overlay](https://github.com/bkacjios/m-overlay) by Bkacjios | MIT, `tools/touch-gamecube/LICENSE-m-overlay` |
| Mareshine's own changes and additions | this repository | MIT, see [below](#mareshine) |
| Third-party libraries (SDL2, stb, bcdec, Khronos headers) | their projects | their own licenses: SDL2 (zlib) is downloaded at build time and keeps its `LICENSE.txt`; stb and bcdec carry theirs at the end of `platform/gx/src/third_party/stb_image.h` and `bcdec.h`; the Khronos headers in `platform/gx/third_party/` in their files |

*Super Mario Sunshine* and all related names, characters and assets belong to Nintendo. None of
the game's data is part of this repository; the game is read from the player's own disc image.

## Mareshine

MIT License

Copyright (c) 2026 TheM3dium

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

This license covers only Mareshine's own changes and additions, not the parts listed above that
come from other projects.

## sms-pc-port

MIT License

Copyright (c) 2026 SoftwareAura LLC

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
