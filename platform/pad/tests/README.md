# Controller input regression checks

From the repository root (no disc or SDL required):

```sh
mkdir -p /tmp/mareshine-pad-tests
cc -c -Isrc -ffp-contract=off platform/misc/msl_math.c -o /tmp/mareshine-pad-tests/msl_math.o
c++ -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer -Isrc/port_include -Isrc -Idecomp/include -Idecomp/libs/dolphin/include platform/pad/tests/controller_mapping_test.cpp /tmp/mareshine-pad-tests/msl_math.o -ldl -o /tmp/mareshine-pad-tests/controller_mapping_test
ASAN_OPTIONS=detect_leaks=0 /tmp/mareshine-pad-tests/controller_mapping_test
javac -d /tmp/mareshine-pad-tests/classes android/app/src/main/java/io/github/them3dium/mareshine/ControllerStickCapture.java tools/tests/ControllerStickCaptureTest.java
java -cp /tmp/mareshine-pad-tests/classes io.github.them3dium.mareshine.ControllerStickCaptureTest
```

The C++ test calls the real `PADRead` with independent X/Y inversion, both stick-pair assignments and explicit unbinding. The Java test covers neutral-before-capture, drift, either movement direction, reset, ambiguous simultaneous movement and invalid axes.

Android Settings exposes the existing `SMS_CAMERA_INVERT_X/Y` flags. They flip input relative to Sunshine. The horizontal UI therefore reads **On** for flag 0 (the original inverted camera) and **Off** for flag 1. Vertical inversion uses flag 1 for **On** and defaults to **Off**. Existing mappings and flags are preserved. Restart the game after changes; touch input is handled separately from physical controller input.

FLUDD settings use separate `SMS_FLUDD_INVERT_X/Y` flags. Both default to Off; flag 1 displays On for either FLUDD axis. `port-05-fludd-aim-inversion.patch` transforms only stationary spray yaw, stationary spray pitch and first-person aim. Walking, strafing, menu navigation and ordinary C-stick camera input stay independent. The original decomp submodule is not edited.

`StickBehaviorTest` covers UI/native inversion conventions, all four defaults, recovery of an existing swapped mapping, preserving other bindings and independently toggling axes:

```sh
javac -d /tmp/mareshine-pad-tests/classes android/app/src/main/java/io/github/them3dium/mareshine/StickBehavior.java tools/tests/StickBehaviorTest.java
java -cp /tmp/mareshine-pad-tests/classes io.github.them3dium.mareshine.StickBehaviorTest
```

The Controls tab groups swap, both sticks' dead zones and sensitivities, C-stick/FLUDD axis inversion and calibration under Stick Behavior. Values and slider ranges are retained. The panel has explicit controller order, automatic focus scrolling and a first-scroll hint with the native side scrollbar. Back from a slider returns to its panel entry.
