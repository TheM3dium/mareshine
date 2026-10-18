package io.github.them3dium.mareshine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import static io.github.them3dium.mareshine.L.t;

// Save states (platform/savestate, savestate_android.cpp): five slots in
// <data folder>/savestates. Only in the game's process (":game"), where the
// game library is loaded. Saves and loads are requests the game carries out at
// the top of its next frame, so the game's moment is the one it was paused at.
final class SaveStates {
    static final int SLOTS = 5;
    static final String QUICK_SLOT_SETTING = "SMS_SAVESTATE_SLOT";

    // The game's messages, as savestate_android.cpp codes them.
    static final int MSG_SAVED = 1, MSG_SAVE_FAILED = 2, MSG_LOADED = 3, MSG_LOADED_POSITION = 4,
        MSG_LOAD_FAILED = 5, MSG_NOT_IN_LEVEL = 6, MSG_BUSY = 7, MSG_EMPTY = 8, MSG_OTHER_GAME = 9,
        MSG_UNREADABLE = 10;

    private SaveStates() {}

    static final class Slot {
        int number;
        boolean used, exact, sameGame;
        String stage = "", episode = "", when = "";
        long created;
    }

    static Slot slot(int n) {
        Slot s = new Slot();
        s.number = n;
        String info = null;
        try { info = nativeSlotInfo(n); } catch (Throwable ignored) {}
        if (info == null) return s;
        String[] f = info.split("\t", -1);
        s.used = true;
        if (f.length > 0) s.stage = f[0];
        if (f.length > 1) s.episode = f[1];
        if (f.length > 3) try { s.created = Long.parseLong(f[3]); } catch (NumberFormatException ignored) {}
        if (f.length > 4) s.exact = "1".equals(f[4]);
        if (f.length > 5) s.sameGame = "1".equals(f[5]);
        // the date and time as the device writes them (the game's "when" is English)
        s.when = s.created > 0
            ? java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(new java.util.Date(s.created * 1000))
            : (f.length > 2 ? f[2] : "");
        return s;
    }

    static Bitmap thumbnail(int n) {
        try {
            byte[] png = nativeThumbnail(n);
            return png == null ? null : BitmapFactory.decodeByteArray(png, 0, png.length);
        } catch (Throwable e) {
            return null;
        }
    }

    static int quickSlot(Context c) {
        try {
            int n = Integer.parseInt(Settings.read(c).getOrDefault(QUICK_SLOT_SETTING, "1"));
            return n >= 1 && n <= SLOTS ? n : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static void setQuickSlot(Context c, int n) {
        Settings.put(c, QUICK_SLOT_SETTING, Integer.toString(n));
        try { nativeSetQuickSlot(n); } catch (Throwable ignored) {}
    }

    // the message a code stands for, in the player's language
    static String message(int code, int slot) {
        switch (code) {
            case MSG_SAVED: return t("Saved to slot %d", slot);
            case MSG_SAVE_FAILED: return t("Could not save the state");
            case MSG_LOADED: return t("Slot %d loaded", slot);
            case MSG_LOADED_POSITION: return t("Slot %d loaded (position only)", slot);
            case MSG_LOAD_FAILED: return t("Could not load the state");
            case MSG_NOT_IN_LEVEL: return t("Save states work in a level, once it has loaded");
            case MSG_BUSY: return t("The game is busy, try again in a moment");
            case MSG_EMPTY: return t("Slot %d is empty", slot);
            case MSG_OTHER_GAME: return t("That save state is from another game");
            case MSG_UNREADABLE: return t("That save state could not be read");
            default: return null;
        }
    }

    static native String nativeSlotInfo(int slot);
    static native byte[] nativeThumbnail(int slot);
    static native void nativeSave(int slot);
    static native int nativeLoad(int slot);
    static native int nativeDelete(int slot);
    static native void nativeSetQuickSlot(int slot);
    static native int nativeQuickSlot();
    static native int nativeReady();
}
