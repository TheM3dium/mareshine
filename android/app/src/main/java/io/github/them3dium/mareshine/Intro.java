package io.github.them3dium.mareshine;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Introductions: a few pages that show something new once, when the launcher
// starts (IntroActivity shows them, one after another). Each has an id; the
// ones seen are kept in settings.txt (so a reinstall with the same data folder
// does not show them again), and an update can ask for an intro's choice page
// (Settings.upgrade), which a new install never sees. An intro in UPDATES_ONLY
// tells only those who used the app before: a new install counts it as seen.
//
// A new intro: an id here, in ALL (and UPDATES_ONLY if so), its condition in
// applies() and its pages in IntroActivity.pages().
final class Intro {
    private Intro() {}

    static final String GAMECUBE_TOUCH = "gamecube_touch";  // 2.1: the GameCube button style, the R+ slider
    static final String EDITOR_FOLD = "editor_fold";        // 2.1: the layout editor's toolbar folds up
    static final String MOD_MANAGER = "mod_manager";        // 2.1: mods, where they are, finding and managing them
    static final String SKIP_MOVIES = "skip_movies";        // 2.1.1: the movies can be skipped altogether
    static final String GYRO_AIMING = "gyro_aiming";        // the phone's gyroscope aims FLUDD and looks around
    static final String SWAP_AB = "swap_ab";                // A and B can change places in the menus
    static final String TOUR_AGAIN = "tour_again";          // every tour can be watched again
    static final String WELCOME = "welcome";                // a new install: which tours to see

    // in the order they are shown
    private static final String[] ALL = {WELCOME, GAMECUBE_TOUCH, EDITOR_FOLD, MOD_MANAGER, SKIP_MOVIES, GYRO_AIMING,
        SWAP_AB, TOUR_AGAIN};
    private static final String[] UPDATES_ONLY = {EDITOR_FOLD, TOUR_AGAIN};
    private static final String[] NEWEST = {GYRO_AIMING, SWAP_AB, TOUR_AGAIN};
    private static final String ASK_WELCOME = "SMS_INTRO_WELCOME";

    private static final String SEEN = "SMS_LAUNCHER_INTROS_SEEN";
    private static final String ASK = "SMS_LAUNCHER_INTROS_ASK";

    private static boolean applies(Context c, String id) {
        switch (id) {
            case GAMECUBE_TOUCH:
            case EDITOR_FOLD: return AppActivity.isTouch(c);  // touch buttons only on a touch screen
            case MOD_MANAGER: return Mods.MENU;               // everyone: touch and controller
            case SKIP_MOVIES: return true;
            case GYRO_AIMING:                                 // only with a gyroscope
                return c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_SENSOR_GYROSCOPE);
            case SWAP_AB: return controllerConnected();       // shown once one is there
            case TOUR_AGAIN: return true;
            case WELCOME: return "1".equals(Settings.read(c).get(ASK_WELCOME));
            default: return false;
        }
    }

    private static boolean controllerConnected() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && d.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD)) return true;
        }
        return false;
    }

    // the next intro to show, or null
    static String pending(Context c) {
        List<String> seen = list(Settings.read(c), SEEN);
        for (String id : ALL) if (!seen.contains(id) && applies(c, id)) return id;
        return null;
    }

    // whether the intro asks for a choice (an update), not only shows
    static boolean asks(Context c, String id) { return list(Settings.read(c), ASK).contains(id); }

    // for Settings.ensureDefaults: a new install has nothing to be told of an update
    static void newInstall(Map<String, String> m) {
        for (String id : UPDATES_ONLY) add(m, SEEN, id);
        m.put(ASK_WELCOME, "1");
    }

    // The tours to see from now on: all of them, only the newest, or (neither) none.
    // Editor fold is for those who used the app before, which a repeat counts as.
    static void choose(Context c, boolean all, boolean newest) {
        Map<String, String> m = Settings.read(c);
        List<String> seen = new ArrayList<>();
        seen.add(WELCOME);
        for (String id : ALL) {
            boolean show = all || (newest && Arrays.asList(NEWEST).contains(id));
            if (!show && !seen.contains(id)) seen.add(id);
        }
        m.put(SEEN, String.join(",", seen));
        m.remove(ASK);
        m.remove(ASK_WELCOME);
        Settings.write(c, m);
    }

    // for Settings' Reset all: the intros seen stay seen
    static void keepSeen(Map<String, String> from, Map<String, String> to) {
        if (from.containsKey(SEEN)) to.put(SEEN, from.get(SEEN));
    }

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
