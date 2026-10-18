package io.github.them3dium.mareshine;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The achievements (platform/achievements): their definitions with the texts
// in every language (assets/achievements/sms.json) and what the game has
// unlocked, <data folder>/achievements/unlocked.json, which the game writes
// (docs/ACHIEVEMENTS-FORMAT.md). The app writes it only for what happens in
// its own screens (Fine-Tuner), merging as the format says.
final class Achievements {
    static final String SETTING = "SMS_ACHIEVEMENTS";

    private Achievements() {}

    static final class Category {
        String id, icon, title;
        final List<Def> items = new ArrayList<>();
    }

    static final class Def {
        String id, category, title, description;
        int target;
        boolean secret;
        // from unlocked.json
        boolean unlocked;
        long at;
        int progress;
    }

    static boolean enabled(Context c) {
        return !"0".equals(Settings.read(c).getOrDefault(SETTING, "1"));
    }

    static File unlockedFile(Context c) {
        return new File(new File(DataDir.get(c), "achievements"), "unlocked.json");
    }

    private static String text(JSONObject texts, String lang) {
        if (texts == null) return "";
        String s = texts.optString(lang, "");
        return s.isEmpty() ? texts.optString("en", "") : s;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 14];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    // the categories in order, each with its achievements and their state
    static List<Category> load(Context c) {
        List<Category> cats = new ArrayList<>();
        String lang = L.current(c);
        Map<String, Category> byId = new HashMap<>();
        try (InputStream in = c.getAssets().open("achievements/sms.json")) {
            JSONObject j = new JSONObject(read(in));
            JSONArray ca = j.getJSONArray("categories");
            for (int i = 0; i < ca.length(); i++) {
                JSONObject o = ca.getJSONObject(i);
                Category k = new Category();
                k.id = o.getString("id");
                k.icon = o.optString("icon", "");
                k.title = text(o.optJSONObject("title"), lang);
                cats.add(k);
                byId.put(k.id, k);
            }
            JSONArray aa = j.getJSONArray("achievements");
            for (int i = 0; i < aa.length(); i++) {
                JSONObject o = aa.getJSONObject(i);
                Def d = new Def();
                d.id = o.getString("id");
                d.category = o.optString("category", "");
                d.target = o.optInt("target", 0);
                d.secret = o.optBoolean("secret", false);
                d.title = text(o.optJSONObject("title"), lang);
                d.description = text(o.optJSONObject("description"), lang);
                Category k = byId.get(d.category);
                if (k != null) k.items.add(d);
            }
        } catch (Exception e) {
            return cats;
        }
        JSONObject state = state(c);
        JSONObject un = state == null ? null : state.optJSONObject("unlocked");
        JSONObject pr = state == null ? null : state.optJSONObject("progress");
        for (Category k : cats)
            for (Def d : k.items) {
                JSONObject u = un == null ? null : un.optJSONObject(d.id);
                d.unlocked = u != null;
                d.at = u == null ? 0 : u.optLong("at", 0);
                d.progress = pr == null ? 0 : pr.optInt(d.id, 0);
                if (d.unlocked) d.progress = d.target;
            }
        return cats;
    }

    static JSONObject state(Context c) {
        File f = unlockedFile(c);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return null;
        }
    }

    // the ids unlocked now
    static java.util.Set<String> unlockedIds(Context c) {
        java.util.Set<String> s = new java.util.HashSet<>();
        JSONObject state = state(c);
        JSONObject un = state == null ? null : state.optJSONObject("unlocked");
        if (un != null)
            for (java.util.Iterator<String> it = un.keys(); it.hasNext(); ) s.add(it.next());
        return s;
    }

    static int[] count(List<Category> cats) {
        int got = 0, all = 0;
        for (Category k : cats)
            for (Def d : k.items) {
                all++;
                if (d.unlocked) got++;
            }
        return new int[] {got, all};
    }

    // Progress towards an achievement the app itself watches: `delta` more,
    // unlocked at its target. Read again, change, write through a temporary
    // file (the game may write too: it reads again and merges before it
    // writes). True when it was unlocked just now.
    static synchronized boolean addProgress(Context c, String id, int delta, int target) {
        if (!enabled(c)) return false;
        try {
            File f = unlockedFile(c);
            JSONObject j = state(c);
            if (j == null) {
                j = new JSONObject();
                j.put("format", "achievements");
                j.put("schema", 1);
                j.put("game", "sms");
            }
            JSONObject un = j.optJSONObject("unlocked");
            if (un == null) { un = new JSONObject(); j.put("unlocked", un); }
            if (un.has(id)) return false;
            JSONObject pr = j.optJSONObject("progress");
            if (pr == null) { pr = new JSONObject(); j.put("progress", pr); }
            int now = Math.min(target, pr.optInt(id, 0) + delta);
            pr.put(id, now);
            boolean unlocked = now >= target;
            if (unlocked) {
                JSONObject e = new JSONObject();
                e.put("at", System.currentTimeMillis() / 1000);
                e.put("port", "mareshine");
                e.put("version", CrashLog.BuildConfigInfo.version(c));
                e.put("cheats", false);
                e.put("save_states", false);
                un.put(id, e);
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            File tmp = new File(dir, f.getName() + ".tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
                o.write((j.toString(2) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return tmp.renameTo(f) && unlocked;
        } catch (Exception e) {
            return false;
        }
    }

    // the title of an achievement, in the app's language
    static String title(Context c, String id) {
        for (Category k : load(c))
            for (Def d : k.items)
                if (d.id.equals(id)) return d.title;
        return id;
    }
}
