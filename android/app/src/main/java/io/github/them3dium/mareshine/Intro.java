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
    static final String WINTER_UPDATE = "winter_2_2";       // 2.2: the winter event, Tropical Freeze, what else is new
    // A happy new year, once, from 31 December to the winter event's last day
    // (6 January): its id carries the new year, so it comes again a year on.
    private static final String NEW_YEAR = "new_year_";
    private static final String NEW_YEAR_FORCE = "SMS_LAUNCHER_NEW_YEAR_FORCE";  // "1": any day, for testing

    // in the order they are shown
    private static final String[] ALL = {WELCOME, WINTER_UPDATE, GAMECUBE_TOUCH, EDITOR_FOLD, MOD_MANAGER, SKIP_MOVIES,
        GYRO_AIMING, SWAP_AB, TOUR_AGAIN};
    private static final String[] UPDATES_ONLY = {EDITOR_FOLD, TOUR_AGAIN};
    private static final String[] NEWEST = {WINTER_UPDATE, GYRO_AIMING, SWAP_AB, TOUR_AGAIN};
    private static final String ASK_WELCOME = "SMS_INTRO_WELCOME";

    private static final String SEEN = "SMS_LAUNCHER_INTROS_SEEN";
    private static final String ASK = "SMS_LAUNCHER_INTROS_ASK";
    // A new install that skips the tours still hears of the winter update, in
    // short and at its next start: LATER holds an intro back until then, BRIEF
    // has it leave out what only tells an update's changes.
    private static final String LATER = "SMS_LAUNCHER_INTROS_LATER";
    private static final String BRIEF = "SMS_LAUNCHER_INTROS_BRIEF";

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
            case WINTER_UPDATE: return true;
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
        List<String> seen = list(Settings.read(c), SEEN), later = list(Settings.read(c), LATER);
        for (String id : ALL) if (!seen.contains(id) && !later.contains(id) && applies(c, id)) return id;
        String newYear = newYear(Settings.read(c));
        return newYear != null && !seen.contains(newYear) ? newYear : null;
    }

    // for the launcher's start: what waited for the next start is due now
    static void newStart(Context c) {
        Map<String, String> m = Settings.read(c);
        if (m.remove(LATER) != null) Settings.write(c, m);
    }

    // whether the intro leaves out an update's changes (a new install that skipped the tours)
    static boolean brief(Context c, String id) { return list(Settings.read(c), BRIEF).contains(id); }

    static boolean isNewYear(String id) { return id != null && id.startsWith(NEW_YEAR); }

    // the new year's greeting due today, or null
    private static String newYear(Map<String, String> s) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int m = c.get(java.util.Calendar.MONTH), d = c.get(java.util.Calendar.DAY_OF_MONTH), y = c.get(java.util.Calendar.YEAR);
        if (m == java.util.Calendar.DECEMBER && d == 31) return NEW_YEAR + (y + 1);
        if (m == java.util.Calendar.JANUARY && d <= 6) return NEW_YEAR + y;
        return "1".equals(s.get(NEW_YEAR_FORCE)) ? NEW_YEAR + (y + 1) : null;
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
            // skipped altogether: the winter update comes all the same, in short, at the next start
            if (!show && !all && !newest && WINTER_UPDATE.equals(id)) continue;
            if (!show && !seen.contains(id)) seen.add(id);
        }
        m.put(SEEN, String.join(",", seen));
        if (!all && !newest) { m.put(LATER, WINTER_UPDATE); m.put(BRIEF, WINTER_UPDATE); }
        else { m.remove(LATER); m.remove(BRIEF); }
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
        for (String key : new String[] {LATER, BRIEF}) {
            List<String> l = list(m, key);
            l.remove(id);
            if (l.isEmpty()) m.remove(key);
            else m.put(key, String.join(",", l));
        }
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
