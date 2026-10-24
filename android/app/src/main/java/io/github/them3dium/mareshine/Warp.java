package io.github.them3dium.mareshine;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

// Test builds: warping to any episode from the quick menu
// (platform/android/warp_android.cpp). Only in the game's process (":game").
final class Warp {
    static final class Entry {
        int area, episode;
        String world, name;
    }

    private Warp() {}

    // the quick menu's WARP: in test builds only
    static boolean available(Context c) {
        return CrashLog.BuildConfigInfo.version(c).contains("dev");
    }

    // the game's episodes, in its order (a world's together), or null while the
    // game has not built the list yet
    static List<Entry> list() {
        String[] rows;
        try {
            rows = nativeList();
        } catch (Throwable t) {
            return null;
        }
        if (rows == null) return null;
        List<Entry> out = new ArrayList<>();
        for (String row : rows) {
            if (row == null) continue;
            String[] f = row.split("\t", 4);
            if (f.length < 4) continue;
            Entry e = new Entry();
            e.area = Integer.parseInt(f[0]);
            e.episode = Integer.parseInt(f[1]);
            e.world = f[2];
            e.name = f[3];
            out.add(e);
        }
        return out;
    }

    // whether the game is in a stage, where a warp can start
    static boolean ready() {
        try {
            return nativeReady() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static void go(Entry e) {
        try {
            nativeGo(e.area, e.episode);
        } catch (Throwable ignored) {
        }
    }

    static native String[] nativeList();
    static native int nativeReady();
    static native void nativeGo(int area, int episode);
}
