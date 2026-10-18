package io.github.them3dium.mareshine;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static io.github.them3dium.mareshine.L.t;

// Sync between devices through one file (Settings → OTHER → SYNC): a zip the
// player creates or picks once with the system's file picker, so it can live
// in Google Drive, Dropbox, Nextcloud, ... (Google Drive offers single files to
// other apps, not folders). No account, no network code of our own.
//
// It holds the memory card (saves/ without the shader cache), the save states
// (savestates/, can be left out) and the achievements, and a manifest with
// each part's hash, when it changed and on which device. Each device remembers
// the hashes it last synced, so a sync knows which side changed:
//   only this device changed  -> it goes into the file
//   only the file changed     -> it comes to this device
//   both changed              -> the newer one wins, the other is kept in
//                                sync-backups/ of the data folder
// The achievements are always merged (the shared format's rule: union, the
// earliest unlock wins), so they never conflict. Whatever a sync replaces or
// removes on this device is copied to sync-backups/ first (the newest five
// are kept). A sync runs in the main menu: when the app starts (before the
// game), when the game is left, and with "Sync now"; never while the game runs.
final class Sync {
    private static final String PREFS = "sync";
    static final String SETTING_STATES = "SMS_SYNC_STATES"; // settings.txt: 0 leaves the save states out
    private static final String CARD = "card", STATES = "states/", ACH = "achievements/unlocked.json";
    private static final String MANIFEST = "manifest.json";
    private static final int KEEP_BACKUPS = 5;

    private Sync() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Uri uri(Context c) {
        String s = prefs(c).getString("uri", null);
        return s == null ? null : Uri.parse(s);
    }

    static boolean configured(Context c) { return uri(c) != null; }

    static boolean includeStates(Context c) {
        return !"0".equals(Settings.read(c).getOrDefault(SETTING_STATES, "1"));
    }

    // the sync file chosen with ACTION_CREATE_DOCUMENT or ACTION_OPEN_DOCUMENT;
    // a new file starts with no parts known, so the first sync merges both
    static void setUri(Context c, Uri u) {
        try {
            c.getContentResolver().takePersistableUriPermission(u,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        SharedPreferences.Editor e = prefs(c).edit().clear();
        e.putString("uri", u.toString());
        e.apply();
    }

    static void forget(Context c) {
        Uri u = uri(c);
        if (u != null) {
            try {
                c.getContentResolver().releasePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        prefs(c).edit().clear().apply();
    }

    static String fileName(Context c) {
        Uri u = uri(c);
        if (u == null) return null;
        try (Cursor cur = c.getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {}
        return u.getLastPathSegment();
    }

    static long lastSync(Context c) { return prefs(c).getLong("last", 0); }
    static String lastResult(Context c) { return prefs(c).getString("result", ""); }

    // --- running ------------------------------------------------------------

    // changed: something was synced, or it failed (both worth showing)
    interface Done { void done(String message, boolean changed); }

    private static boolean sRunning;

    // In the background; `done` on the main thread (also when nothing is set
    // up or it failed: then message is null or the error).
    static void run(Context c, Done done) {
        final Context app = c.getApplicationContext();
        if (!configured(app) || sRunning) {
            if (done != null) done.done(null, false);
            return;
        }
        sRunning = true;
        new Thread(() -> {
            String msg;
            boolean changed = false;
            try {
                Result r = syncNow(app);
                msg = r.summary(app);
                changed = r.changed();
            } catch (Exception e) {
                msg = t("Sync failed: %s", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                changed = true;
            }
            prefs(app).edit().putLong("last", System.currentTimeMillis()).putString("result", msg).apply();
            final String m = msg;
            final boolean ch = changed;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                sRunning = false;
                if (done != null) done.done(m, ch);
            });
        }, "Sync").start();
    }

    static final class Result {
        int up, down, conflicts;
        boolean achievements;
        boolean changed() { return up + down + conflicts > 0 || achievements; }
        String summary(Context c) {
            if (!changed()) return t("Sync: everything was up to date.");
            List<String> p = new ArrayList<>();
            if (down > 0) p.add(t("%d from the sync file", down));
            if (up > 0) p.add(t("%d to the sync file", up));
            if (conflicts > 0) p.add(t("%d changed on both sides (the newer one kept, the other in sync-backups)", conflicts));
            if (achievements) p.add(t("achievements merged"));
            return t("Synced: %s.", String.join(", ", p));
        }
    }

    // --- one sync -------------------------------------------------------------

    // a part of the data: its files (by their path in the zip) on this device
    private static final class Part {
        String name;
        TreeMap<String, File> files = new TreeMap<>();
        String hash;      // of this device's files, null when there are none
        long modified;    // their newest change
    }

    private static synchronized Result syncNow(Context c) throws Exception {
        Result res = new Result();
        File data = DataDir.get(c);
        File inDir = new File(c.getCacheDir(), "sync-in");
        deleteTree(inDir);
        inDir.mkdirs();

        // the sync file as it is now (empty or new: nothing in it)
        JSONObject remote = new JSONObject();
        Map<String, File> remoteFiles = new HashMap<>();
        try (InputStream in = c.getContentResolver().openInputStream(uri(c))) {
            if (in != null) {
                ZipInputStream z = new ZipInputStream(in);
                ZipEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory() || e.getName().contains("..")) continue;
                    File f = new File(inDir, e.getName());
                    f.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(f)) {
                        int n;
                        while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                    }
                    if (e.getName().equals(MANIFEST)) remote = new JSONObject(readText(f));
                    else remoteFiles.put(e.getName(), f);
                }
            }
        } catch (java.util.zip.ZipException ignored) {
            // an empty new file
        }
        JSONObject remoteParts = remote.optJSONObject("parts");
        if (remoteParts == null) remoteParts = new JSONObject();

        Map<String, Part> local = localParts(c, data);
        SharedPreferences p = prefs(c);
        TreeSet<String> names = new TreeSet<>(local.keySet());
        for (Iterator<String> it = remoteParts.keys(); it.hasNext(); ) {
            String n = it.next();
            if (n.startsWith(STATES) && !includeStates(c)) continue;
            names.add(n);
        }

        boolean writeFile = !remote.has("parts");
        File backup = null;
        // the hashes this sync leaves behind: remembered only once it has gone
        // through (a failed write must not look like an upload next time)
        Map<String, String> bases = new HashMap<>();
        JSONObject outParts = new JSONObject();
        Map<String, Map<String, File>> outFiles = new TreeMap<>();
        for (String n : names) {
            Part lp = local.get(n);
            JSONObject rp = remoteParts.optJSONObject(n);
            String L = lp != null ? lp.hash : null;
            String R = rp != null ? rp.optString("hash", null) : null;
            String B = p.getString("base:" + n, null);
            boolean localChanged = !eq(L, B), remoteChanged = !eq(R, B);
            String take; // "local", "remote", or "same"
            if (eq(L, R)) take = "same";
            else if (localChanged && !remoteChanged) take = "local";
            else if (remoteChanged && !localChanged) take = "remote";
            else { // both changed: the newer wins
                long lm = lp != null ? lp.modified : 0, rm = rp != null ? rp.optLong("modified", 0) : 0;
                take = lm >= rm ? "local" : "remote";
                if (L != null && R != null) res.conflicts++;
            }
            // the memory card is never deleted by a sync (a missing card is a new data folder)
            if (n.equals(CARD) && take.equals("local") && L == null) take = "remote";
            if (n.equals(CARD) && take.equals("remote") && R == null) take = "local";

            if (take.equals("remote")) {
                if (backup == null) backup = newBackupDir(data);
                if (lp != null) for (Map.Entry<String, File> f : lp.files.entrySet()) copy(f.getValue(), new File(backup, f.getKey()));
                if (lp != null) for (File f : lp.files.values()) f.delete();
                Map<String, File> got = new TreeMap<>();
                for (Map.Entry<String, File> f : remoteFiles.entrySet()) {
                    if (!belongsTo(n, f.getKey())) continue;
                    File dst = localFile(data, f.getKey());
                    if (dst == null) continue;
                    copy(f.getValue(), dst);
                    got.put(f.getKey(), dst);
                }
                if (R != null) {
                    outParts.put(n, rp);
                    outFiles.put(n, got);
                }
                bases.put(n, R);
                if (!eq(L, R)) res.down++;
            } else {
                if (L != null) {
                    JSONObject e = new JSONObject();
                    e.put("hash", L);
                    e.put("modified", lp.modified);
                    e.put("device", take.equals("same") && rp != null ? rp.optString("device", device()) : device());
                    outParts.put(n, take.equals("same") && rp != null ? rp : e);
                    outFiles.put(n, lp.files);
                }
                if (take.equals("local") && !eq(L, R)) {
                    res.up++;
                    writeFile = true;
                }
                bases.put(n, L);
            }
        }
        // save states this device leaves out stay in the file as they are
        if (!includeStates(c)) {
            for (Iterator<String> it = remoteParts.keys(); it.hasNext(); ) {
                String n = it.next();
                if (!n.startsWith(STATES)) continue;
                outParts.put(n, remoteParts.getJSONObject(n));
                Map<String, File> keep = new TreeMap<>();
                File f = remoteFiles.get(n);
                if (f != null) keep.put(n, f);
                outFiles.put(n, keep);
            }
        }

        // the achievements: merged both ways
        File achLocal = new File(data, ACH);
        File achRemote = remoteFiles.get(ACH);
        JSONObject a = readJson(achLocal), b = readJson(achRemote);
        JSONObject merged = mergeAchievements(a, b);
        String mergedText = merged.toString(2) + "\n";
        if (merged.optJSONObject("unlocked") != null || merged.optJSONObject("progress") != null) {
            if (!same(a, merged)) {
                achLocal.getParentFile().mkdirs();
                writeText(achLocal, mergedText);
                res.achievements = true;
            }
            if (!same(b, merged)) {
                writeFile = true;
                res.achievements = true;
            }
        }

        if (writeFile) {
            JSONObject man = new JSONObject();
            man.put("format", "mareshine-sync");
            man.put("schema", 1);
            man.put("written", System.currentTimeMillis());
            man.put("by", device());
            man.put("parts", outParts);
            File tmp = new File(c.getCacheDir(), "sync-out.zip");
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp))) {
                put(z, MANIFEST, man.toString(2).getBytes("UTF-8"));
                for (Map<String, File> files : outFiles.values())
                    for (Map.Entry<String, File> f : files.entrySet()) put(z, f.getKey(), f.getValue());
                if (achLocal.isFile()) put(z, ACH, achLocal);
            }
            OutputStream o;
            try {
                o = c.getContentResolver().openOutputStream(uri(c), "wt");
            } catch (Exception e) {
                o = c.getContentResolver().openOutputStream(uri(c), "w");
            }
            if (o == null) throw new IOException(t("the sync file cannot be written"));
            try (OutputStream out = o; InputStream in = new FileInputStream(tmp)) {
                copy(in, out);
            }
            tmp.delete();
        }
        SharedPreferences.Editor ed = p.edit();
        for (Map.Entry<String, String> be : bases.entrySet()) {
            if (be.getValue() == null) ed.remove("base:" + be.getKey());
            else ed.putString("base:" + be.getKey(), be.getValue());
        }
        ed.apply();
        deleteTree(inDir);
        trimBackups(data);
        return res;
    }

    // --- this device's data ---------------------------------------------------

    private static Map<String, Part> localParts(Context c, File data) throws Exception {
        Map<String, Part> m = new TreeMap<>();
        File saves = new File(data, DataDir.SAVES);
        Part card = new Part();
        card.name = CARD;
        File[] fs = saves.listFiles();
        if (fs != null) for (File f : fs)
            if (f.isFile() && !f.getName().startsWith("shader_cache") && !f.getName().endsWith(".tmp"))
                card.files.put(CARD + "/" + f.getName(), f);
        finish(card);
        if (card.hash != null) m.put(CARD, card);
        if (includeStates(c)) {
            File[] ss = new File(data, "savestates").listFiles();
            if (ss != null) for (File f : ss) {
                if (!f.isFile() || !f.getName().endsWith(".smsstate")) continue;
                Part s = new Part();
                s.name = STATES + f.getName();
                s.files.put(s.name, f);
                finish(s);
                m.put(s.name, s);
            }
        }
        return m;
    }

    private static void finish(Part p) throws Exception {
        if (p.files.isEmpty()) return;
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] buf = new byte[1 << 16];
        for (Map.Entry<String, File> f : p.files.entrySet()) {
            md.update(f.getKey().getBytes("UTF-8"));
            try (InputStream in = new FileInputStream(f.getValue())) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            p.modified = Math.max(p.modified, f.getValue().lastModified());
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        p.hash = sb.toString();
    }

    private static boolean belongsTo(String part, String path) {
        return part.equals(CARD) ? path.startsWith(CARD + "/") : path.equals(part);
    }

    // where a file of the sync file goes in the data folder
    private static File localFile(File data, String path) {
        if (path.startsWith(CARD + "/")) return new File(new File(data, DataDir.SAVES), path.substring(CARD.length() + 1));
        if (path.startsWith(STATES)) return new File(new File(data, "savestates"), path.substring(STATES.length()));
        return null;
    }

    private static String device() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    // --- achievements (docs/ACHIEVEMENTS-FORMAT.md) ------------------------------

    static JSONObject mergeAchievements(JSONObject a, JSONObject b) throws Exception {
        JSONObject out = new JSONObject(a.toString());
        if (!out.has("format")) out.put("format", "achievements");
        if (!out.has("schema")) out.put("schema", 1);
        if (!out.has("game")) out.put("game", "sms");
        JSONObject ua = out.optJSONObject("unlocked");
        if (ua == null) { ua = new JSONObject(); out.put("unlocked", ua); }
        JSONObject ub = b.optJSONObject("unlocked");
        if (ub != null) for (Iterator<String> it = ub.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject eb = ub.optJSONObject(id), ea = ua.optJSONObject(id);
            if (eb == null) continue;
            long tb = eb.optLong("at", 0), ta = ea == null ? 0 : ea.optLong("at", 0);
            if (ea == null || (tb > 0 && (ta == 0 || tb < ta))) ua.put(id, eb);
        }
        JSONObject pa = out.optJSONObject("progress");
        if (pa == null) { pa = new JSONObject(); out.put("progress", pa); }
        JSONObject pb = b.optJSONObject("progress");
        if (pb != null) for (Iterator<String> it = pb.keys(); it.hasNext(); ) {
            String id = it.next();
            if (pb.optInt(id, 0) > pa.optInt(id, 0)) pa.put(id, pb.optInt(id, 0));
        }
        return out;
    }

    private static boolean same(JSONObject x, JSONObject merged) {
        JSONObject ux = x.optJSONObject("unlocked"), um = merged.optJSONObject("unlocked");
        JSONObject px = x.optJSONObject("progress"), pm = merged.optJSONObject("progress");
        return String.valueOf(sorted(ux)).equals(String.valueOf(sorted(um)))
            && String.valueOf(sorted(px)).equals(String.valueOf(sorted(pm)));
    }

    private static String sorted(JSONObject o) {
        if (o == null || o.length() == 0) return "";
        TreeMap<String, String> m = new TreeMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = o.opt(k);
            m.put(k, v instanceof JSONObject ? String.valueOf(((JSONObject) v).optLong("at", 0)) : String.valueOf(v));
        }
        return m.toString();
    }

    // --- files ------------------------------------------------------------------

    private static File newBackupDir(File data) {
        File d = new File(new File(data, "sync-backups"),
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
        d.mkdirs();
        return d;
    }

    private static void trimBackups(File data) {
        File[] ds = new File(data, "sync-backups").listFiles(File::isDirectory);
        if (ds == null || ds.length <= KEEP_BACKUPS) return;
        Arrays.sort(ds, (x, y) -> x.getName().compareTo(y.getName()));
        for (int i = 0; i < ds.length - KEEP_BACKUPS; i++) deleteTree(ds[i]);
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static void put(ZipOutputStream z, String name, byte[] bytes) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(bytes);
        z.closeEntry();
    }

    private static void put(ZipOutputStream z, String name, File f) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) { copy(in, z); }
        z.closeEntry();
    }

    private static void copy(File from, File to) throws IOException {
        to.getParentFile().mkdirs();
        File tmp = new File(to.getPath() + ".sync");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            copy(in, out);
        }
        if (!tmp.renameTo(to)) {
            to.delete();
            if (!tmp.renameTo(to)) throw new IOException("cannot write " + to);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            copy(in, b);
            return b.toString("UTF-8");
        }
    }

    private static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes("UTF-8")); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    private static JSONObject readJson(File f) {
        try {
            return f != null && f.isFile() ? new JSONObject(readText(f)) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File x : cs) deleteTree(x);
        f.delete();
    }
}
