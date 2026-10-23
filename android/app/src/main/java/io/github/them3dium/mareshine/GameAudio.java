package io.github.them3dium.mareshine;

import android.content.Context;

import java.util.Map;

// The music and sound effect volumes (platform/audio/dsp_mixer.cpp), kept in
// settings.txt for the next start and, in the game's process, handed to the
// mixer at once: it ramps to a new value within 2.5 ms, so it does not click.
final class GameAudio {
    static final String MUSIC = "SMS_MUSIC_VOLUME", SFX = "SMS_SFX_VOLUME";
    private static final int CLASS_SFX = 0, CLASS_MUSIC = 1;  // PORT_DSPMIX_CLASS_*

    private GameAudio() {}

    static int volume(Context c, String key) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(Settings.read(c).getOrDefault(key, "100"))));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    static void setVolume(Context c, String key, int percent) {
        Settings.put(c, key, Integer.toString(percent));
        apply(key, percent);
    }

    // Settings (another process) may have changed them while the game was paused.
    static void applyAll(Context c) {
        Map<String, String> m = Settings.read(c);
        for (String key : new String[] {MUSIC, SFX}) {
            try { apply(key, Integer.parseInt(m.getOrDefault(key, "100"))); } catch (NumberFormatException ignored) {}
        }
    }

    private static void apply(String key, int percent) {
        try { nativeSetVolume(MUSIC.equals(key) ? CLASS_MUSIC : CLASS_SFX, percent); } catch (Throwable ignored) {}
    }

    private static native void nativeSetVolume(int cls, int percent);
}
