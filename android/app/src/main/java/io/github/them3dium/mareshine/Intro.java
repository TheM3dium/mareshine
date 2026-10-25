package io.github.them3dium.mareshine;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Introductions: a few pages that show something new once, when the launcher
// starts (IntroActivity shows them). Each has an id; the ones seen are kept in
// settings.txt (so a reinstall with the same data folder does not show them
// again), and an update can ask for an intro's choice page (Settings.upgrade),
// which a new install never sees.
//
// A new intro: an id here, in ALL, its condition in applies() and its pages in
// IntroActivity.pages().
final class Intro {
    private Intro() {}

    static final String GAMECUBE_TOUCH = "gamecube_touch";  // 2.1: the GameCube button style, the R+ slider

    // in the order they are shown
    private static final String[] ALL = {GAMECUBE_TOUCH};

    private static final String SEEN = "SMS_LAUNCHER_INTROS_SEEN";
    private static final String ASK = "SMS_LAUNCHER_INTROS_ASK";

    private static boolean applies(Context c, String id) {
        switch (id) {
            case GAMECUBE_TOUCH: return AppActivity.isTouch(c);  // touch buttons only on a touch screen
            default: return false;
        }
    }

    // the next intro to show, or null
    static String pending(Context c) {
        List<String> seen = list(Settings.read(c), SEEN);
        for (String id : ALL) if (!seen.contains(id) && applies(c, id)) return id;
        return null;
    }

    // whether the intro asks for a choice (an update), not only shows
    static boolean asks(Context c, String id) { return list(Settings.read(c), ASK).contains(id); }

    // for Settings.upgrade: this intro asks
    static void ask(Map<String, String> m, String id) { add(m, ASK, id); }

    static void done(Context c, String id) {
        Map<String, String> m = Settings.read(c);
        add(m, SEEN, id);
        List<String> ask = list(m, ASK);
        ask.remove(id);
        if (ask.isEmpty()) m.remove(ASK);
        else m.put(ASK, String.join(",", ask));
        Settings.write(c, m);
    }

    private static List<String> list(Map<String, String> m, String key) {
        String v = m.get(key);
        List<String> l = new ArrayList<>();
        if (v != null) for (String s : Arrays.asList(v.split(","))) if (!s.trim().isEmpty()) l.add(s.trim());
        return l;
    }

    private static void add(Map<String, String> m, String key, String id) {
        List<String> l = list(m, key);
        if (!l.contains(id)) l.add(id);
        m.put(key, String.join(",", l));
    }
}
