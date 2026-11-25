package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.Map;

/**
 * The winter event of the app's menus: a winter logo, light snowfall and
 * Christmas music (three tracks, one after the other), from 24 November to 6 January.
 *
 * <p>At the first start in a season the launcher asks whether to turn it on
 * ({@link #needsAsking}). Snow and music each have their own switch (by the
 * logo, and in Settings → OTHER); the winter logo stays while the event is on.
 * Nothing of it shows over the game: not in the game's process, and not in
 * the settings opened from the quick menu.
 *
 * <p>All of it is kept in settings.txt (so Sync carries it to other devices):
 * {@link #ON}, {@link #SNOW} and {@link #LIGHTS} ("1"/"0"), {@link #MUSIC} (the music's own
 * volume in percent, one of {@link #MUSIC_STEPS}) and {@link #ASKED}, the
 * season the question was answered in. {@link #FORCE} = 1 shows the season out
 * of its dates, for testing.
 */
final class Winter {
    private Winter() {}

    static final String ON = "SMS_LAUNCHER_WINTER";
    static final String SNOW = "SMS_LAUNCHER_WINTER_SNOW";
    static final String LIGHTS = "SMS_LAUNCHER_WINTER_LIGHTS";
    /** The switches under the logo folded away ("1"). */
    static final String FOLDED = "SMS_LAUNCHER_WINTER_FOLDED";
    static final String MUSIC = "SMS_LAUNCHER_WINTER_MUSIC";
    static final String ASKED = "SMS_LAUNCHER_WINTER_ASKED";
    static final String FORCE = "SMS_LAUNCHER_WINTER_FORCE";

    // The season: 24 November to 6 January (both included).
    private static final int START_MONTH = Calendar.NOVEMBER, START_DAY = 24;
    private static final int END_MONTH = Calendar.JANUARY, END_DAY = 6;

    /** The year the current season began in, or -1 outside the season. */
    static int season(Map<String, String> s) {
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.MONTH), d = c.get(Calendar.DAY_OF_MONTH), y = c.get(Calendar.YEAR);
        if ((m == START_MONTH && d >= START_DAY) || m == Calendar.DECEMBER) return y;
        if (m == END_MONTH && d <= END_DAY) return y - 1;
        return "1".equals(s.get(FORCE)) ? y : -1;
    }

    static boolean inSeason(Context c) { return season(Settings.read(c)) >= 0; }

    /** The event is on: in the season and turned on. */
    static boolean on(Map<String, String> s) { return season(s) >= 0 && "1".equals(s.get(ON)); }

    static boolean snow(Map<String, String> s) { return on(s) && !"0".equals(s.get(SNOW)); }

    /** The lights that play with the music: the logo's chain and the glow round the Settings window. */
    static boolean lights(Map<String, String> s) { return on(s) && !"0".equals(s.get(LIGHTS)); }

    /**
     * The music's button steps through these: full, muted, then up again in
     * three steps. Older settings wrote "1" (full) and "0" (off).
     */
    static final int[] MUSIC_STEPS = {100, 0, 33, 66};

    /** The music's volume in percent; 0 while it or the event is off. */
    static int musicLevel(Map<String, String> s) {
        if (!on(s)) return 0;
        String v = s.get(MUSIC);
        if (v == null || v.equals("1")) return 100;
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    /** The next step after a level (one not in the list goes to muted). */
    static int nextMusicLevel(int level) {
        for (int i = 0; i < MUSIC_STEPS.length; i++)
            if (MUSIC_STEPS[i] == level) return MUSIC_STEPS[(i + 1) % MUSIC_STEPS.length];
        return 0;
    }

    static boolean music(Map<String, String> s) { return musicLevel(s) > 0; }

    /** In the season, and the question not yet answered in it. */
    static boolean needsAsking(Context c) {
        Map<String, String> s = Settings.read(c);
        int season = season(s);
        return season >= 0 && !Integer.toString(season).equals(s.get(ASKED));
    }

    /** The answer to the question: the event on (snow and music too) or off for this season. */
    static void answer(Context c, boolean on) {
        Map<String, String> s = Settings.read(c);
        s.put(ON, on ? "1" : "0");
        if (on) { s.put(SNOW, "1"); s.put(LIGHTS, "1"); s.put(MUSIC, "100"); }
        s.put(ASKED, Integer.toString(season(s)));
        Settings.write(c, s);
    }

    static void set(Context c, String key, boolean on) {
        Settings.put(c, key, on ? "1" : "0");
    }

    static void setMusicLevel(Context c, int percent) {
        Settings.put(c, MUSIC, Integer.toString(percent));
    }

    /** Whether a screen shows the event: none over the game (its process, or Settings from the quick menu). */
    static boolean shownOn(Activity a) {
        if (a.getIntent() != null && a.getIntent().getBooleanExtra(SettingsActivity.EXTRA_IN_GAME, false)) return false;
        return !inGameProcess(a);
    }

    private static Boolean sGameProcess;

    private static boolean inGameProcess(Context c) {
        if (sGameProcess == null) {
            String name = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) name = android.app.Application.getProcessName();
            else {
                try {
                    byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/cmdline"));
                    int n = 0;
                    while (n < b.length && b[n] != 0) n++;
                    name = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
            sGameProcess = name != null && name.endsWith(":game");
        }
        return sGameProcess;
    }

    // --- music -------------------------------------------------------------

    // One player for all menu screens: it plays while at least one of them is
    // in front, and stops a moment after the last one leaves (so it carries on
    // from one screen to the next). Its volume is its own (MUSIC), not the game's.
    // The tracks play one after the other, round and round.

    /** The tracks, in order; each has its light organ track at the same index (WinterLogoView). */
    static final int[] TRACKS = {R.raw.winter_music_1, R.raw.winter_music_2, R.raw.winter_music_3};

    private static MediaPlayer sPlayer;
    private static int sTrack;
    // paused with the play / pause button (for this run of the app only; muting is the volume's step)
    private static boolean sUserPaused;
    private static int sScreens;
    private static Context sAppCtx;
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    // paused, not released: back in the menu it goes on where it stopped
    private static final Runnable sStop = () -> {
        if (sPlayer != null && sPlayer.isPlaying()) sPlayer.pause();
    };

    /**
     * The game starts: the music stops at once, not after the short wait
     * meant for going from one menu screen to the next (it would play into
     * the game's first sounds).
     */
    static void gameStarting() {
        sMain.removeCallbacks(sStop);
        sStop.run();
    }

    /** A menu screen came to the front (onResume). */
    static void screenShown(Activity a) {
        sScreens++;
        sMain.removeCallbacks(sStop);
        updateMusic(a);
    }

    /** A menu screen left the front (onPause). */
    static void screenHidden() {
        sScreens = Math.max(0, sScreens - 1);
        if (sScreens == 0) {
            sMain.removeCallbacks(sStop);
            sMain.postDelayed(sStop, 400);
        }
    }

    /** Starts, stops or re-levels the music after a change of the switches or the volume. */
    static void updateMusic(Context c) {
        sAppCtx = c.getApplicationContext();
        Map<String, String> s = Settings.read(c);
        if (!on(s)) {
            release();
            return;
        }
        // muted is a pause: the next step goes on where it stopped
        if (musicLevel(s) <= 0) {
            if (sPlayer != null && sPlayer.isPlaying()) sPlayer.pause();
            return;
        }
        if (sScreens == 0) return;
        if (sPlayer == null && !open()) return;
        if (sUserPaused) {
            if (sPlayer.isPlaying()) sPlayer.pause();
            return;
        }
        if (!sPlayer.isPlaying()) sPlayer.start();
        float v = gain(musicLevel(s));
        sPlayer.setVolume(v, v);
    }

    private static boolean open() {
        MediaPlayer p = MediaPlayer.create(sAppCtx, TRACKS[sTrack],
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(), 0);
        if (p == null) return false;
        // at its end the next track, if the menu still plays
        p.setOnCompletionListener(done -> {
            if (done != sPlayer) return;
            release();
            sTrack = (sTrack + 1) % TRACKS.length;
            if (sAppCtx != null) updateMusic(sAppCtx);
        });
        sPlayer = p;
        return true;
    }

    /** Skips to the next track (from its start); while muted it is the one played next. */
    static void nextTrack(Context c) {
        goTo(c, (sTrack + 1) % TRACKS.length);
    }

    /** Back to the start of the track, or (within its first 3 s) to the one before. */
    static void previousTrack(Context c) {
        int pos = musicTime();
        if (pos > 3000) seekTo(c, 0);
        else goTo(c, (sTrack + TRACKS.length - 1) % TRACKS.length);
    }

    private static void goTo(Context c, int track) {
        release();
        sTrack = track;
        updateMusic(c);
        if (sPlayer == null && sAppCtx != null && on(Settings.read(c))) open();  // muted or paused: ready at its start
    }

    /** The play / pause button. */
    static void togglePause(Context c) {
        sUserPaused = !sUserPaused;
        updateMusic(c);
    }

    static boolean userPaused() { return sUserPaused; }

    /** Play while muted: the music back on at the first step after muted ({@link #MUSIC_STEPS}). */
    static void unmute(Context c) {
        sUserPaused = false;
        setMusicLevel(c, nextMusicLevel(0));
        updateMusic(c);
    }

    /** Jumps within the track (also while paused). */
    static void seekTo(Context c, int ms) {
        sAppCtx = c.getApplicationContext();
        if (sPlayer == null && (!on(Settings.read(c)) || !open())) return;
        try {
            sPlayer.seekTo(Math.max(0, Math.min(ms, sPlayer.getDuration() - 500)));
        } catch (IllegalStateException ignored) {}
    }

    /** Where the track stands (ms), playing or not; 0 without a player. */
    static int musicTime() {
        try {
            return sPlayer != null ? sPlayer.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /** The track's length (ms); 0 without a player. */
    static int musicLength() {
        try {
            return sPlayer != null ? sPlayer.getDuration() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /** Which of {@link #TRACKS} plays (or would play next). */
    static int musicTrack() { return sTrack; }

    /** Where the music is now (ms), or -1 while it does not play. */
    static int musicPosition() {
        try {
            return sPlayer != null && sPlayer.isPlaying() ? sPlayer.getCurrentPosition() : -1;
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    // The tracks are mastered loud (brought to about -14.6 LUFS, like a streamed song) and
    // is meant to stay in the background: the full step is 18 dB down, the two
    // lower ones 6 dB apart below it (26 dB down, chosen on 20.11.2026, proved
    // too quiet: the lowest step was hardly heard at full media volume).
    static float gain(int level) {
        if (level <= 0) return 0f;
        float db = level <= 33 ? -30f : level <= 66 ? -24f : -18f;
        return (float) Math.pow(10, db / 20);
    }

    private static void release() {
        if (sPlayer != null) {
            try { sPlayer.stop(); } catch (IllegalStateException ignored) {}
            sPlayer.release();
            sPlayer = null;
        }
    }
}
