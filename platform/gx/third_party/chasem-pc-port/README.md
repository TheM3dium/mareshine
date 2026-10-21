# Adapted renderer code

Source: https://github.com/chasem-dev/sms-pc-port/tree/bb9fd1d0fbd113cbe37e16a37091238026ff25f0

The fixed-point indirect texture-coordinate calculation in gx_shader.cpp is adapted from this revision under the accompanying MIT license. It preserves signed 24-bit coordinate wrapping, including the large matrix exponent used by Sunshine's goop bubbles.

The paired renderer uniform supplies the exponent rather than a floating multiplier. Mareshine's program-key indirect-scale handling and other Android integration remain separate.

The final unclamped TEV output conversion also follows this revision: RGB and alpha retain their low eight bits before alpha testing and blending. A separate before/after regression test covers the water-material case.

Fog likewise uses the fragment window depth rather than a perspective-interpolated per-vertex depth, following the pinned renderer. Own regression tests cover a receding floor and a floor crossing the camera plane.
