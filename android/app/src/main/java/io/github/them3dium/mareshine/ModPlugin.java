package io.github.them3dium.mareshine;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * A mod's native plugin (src/port_include/mareshine_plugin.h): the mod keeps it
 * as plugin/&lt;abi&gt;/lib&lt;name&gt;.so in its folder. Shared storage is
 * mounted without execute rights, so the game loads a copy in the app's own
 * storage (platform/mods/plugins.cpp, through SMS_PLUGIN).
 *
 * The plugins the app knows (assets/plugins.json: per mod, the game build it
 * was made for and, per ABI, where it is and its SHA-256) are fetched into the
 * mod's folder by {@link #fetch}: after the mod is installed and before it
 * plays, when the one there is missing or not that file. A plugin is native
 * code the game runs, so only a file with that SHA-256 is kept.
 */
final class ModPlugin {
    private ModPlugin() {}

    /** The mod's plugin file for this device, or null. */
    static File find(Mods.Mod mod) {
        if (mod == null || mod.dir == null) return null;
        for (String abi : Build.SUPPORTED_ABIS) {
            File[] libs = new File(mod.dir, "plugin/" + abi).listFiles(
                (d, name) -> name.startsWith("lib") && name.endsWith(".so"));
            if (libs != null && libs.length > 0) return libs[0];
        }
        return null;
    }

    /** The copy the game loads (made or refreshed now), or null when the mod has no plugin. */
    static String prepare(Context ctx, Mods.Mod mod) {
        File src = find(mod);
        if (src == null) return null;
        File dir = new File(ctx.getFilesDir(), "plugins/" + mod.id);
        File dst = new File(dir, src.getName());
        if (dst.isFile() && dst.length() == src.length() && dst.lastModified() == src.lastModified())
            return dst.getAbsolutePath();
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File tmp = new File(dir, src.getName() + ".tmp");
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (IOException e) {
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            return null;
        }
        dst.setLastModified(src.lastModified());
        return dst.getAbsolutePath();
    }

    // --- The plugins the app knows (assets/plugins.json) ---------------------

    /** A known plugin for this device: where it goes in the mod, where it comes from. */
    static final class Known {
        final String id, abi, url, sha256;
        final long size;
        Known(String id, String abi, String url, String sha256, long size) {
            this.id = id; this.abi = abi; this.url = url; this.sha256 = sha256; this.size = size;
        }
        File file(File modDir) { return new File(modDir, "plugin/" + abi + "/lib" + id + ".so"); }
    }

    /** The plugin the app knows for this mod and device, or null. */
    static Known known(Context ctx, String id) {
        try (InputStream in = ctx.getAssets().open("plugins.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONObject mod = new JSONObject(b.toString("UTF-8")).optJSONObject(id);
            JSONObject files = mod != null ? mod.optJSONObject("files") : null;
            if (files == null) return null;
            for (String abi : Build.SUPPORTED_ABIS) {
                JSONObject f = files.optJSONObject(abi);
                if (f == null) continue;
                String url = f.optString("url"), sha = f.optString("sha256").toLowerCase(Locale.ROOT);
                if (url.isEmpty() || sha.length() != 64) continue;
                return new Known(id, abi, base(ctx, url), sha, f.optLong("size"));
            }
        } catch (Exception ignored) {
            // none known (a build without the list)
        }
        return null;
    }

    // Test builds: SMS_PLUGIN_BASE in settings.txt serves the files from elsewhere
    // (a test server or a file:// folder before the plugin is published); the
    // SHA-256 still decides.
    private static String base(Context ctx, String url) {
        if (!CrashLog.BuildConfigInfo.version(ctx).contains("dev")) return url;
        String b = Settings.read(ctx).getOrDefault("SMS_PLUGIN_BASE", "").trim();
        if (b.isEmpty()) return url;
        return (b.endsWith("/") ? b : b + "/") + url.substring(url.lastIndexOf('/') + 1);
    }

    /** True when the mod should have a plugin the app knows, and the one in its folder is not it. */
    static boolean needsFetch(Context ctx, Mods.Mod mod) {
        if (mod == null || mod.dir == null || !mod.code) return false;
        Known k = known(ctx, mod.id);
        return k != null && !k.sha256.equals(sha256(k.file(mod.dir)));
    }

    /**
     * Brings the plugin the app knows for this mod into its folder, when the
     * one there is missing or another file (blocking: not on the UI thread).
     * True when the right one is in place afterwards; on a failure (offline,
     * say) the folder is left as it was, and the next start tries again.
     */
    static boolean fetch(Context ctx, String id, File modDir) {
        Known k = known(ctx, id);
        if (k == null || modDir == null) return false;
        File dst = k.file(modDir);
        if (k.sha256.equals(sha256(dst))) return true;
        File dir = dst.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
        File part = new File(dir, dst.getName() + ".part");
        try {
            download(k.url, part, k.size);
            if (!k.sha256.equals(sha256(part))) throw new IOException("SHA-256 differs");
            dst.delete();
            if (!part.renameTo(dst)) throw new IOException("cannot move to " + dst);
            android.util.Log.i("Mareshine", "plugin " + id + ": " + dst.getName() + " fetched");
            return true;
        } catch (IOException e) {
            android.util.Log.i("Mareshine", "plugin " + id + ": not fetched (" + e.getMessage() + ")");
            part.delete();
            return false;
        }
    }

    private static void download(String url, File to, long size) throws IOException {
        long max = size > 0 ? size : 64L << 20;
        for (int redirects = 0; redirects < 8; redirects++) {
            java.net.URLConnection uc = new URL(url).openConnection();
            uc.setConnectTimeout(15000);
            uc.setReadTimeout(20000);
            HttpURLConnection c = uc instanceof HttpURLConnection ? (HttpURLConnection) uc : null;
            try {
                if (c != null) {
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent", "Mareshine");
                    int code = c.getResponseCode();
                    if (code / 100 == 3) {
                        url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                        continue;
                    }
                    if (code != 200) throw new IOException("HTTP " + code);
                }
                try (InputStream in = uc.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                    byte[] buf = new byte[1 << 16];
                    long got = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        got += n;
                        if (got > max) throw new IOException("larger than expected");
                        out.write(buf, 0, n);
                    }
                }
                return;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    static String sha256(File f) {
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte x : md.digest()) s.append(String.format(Locale.ROOT, "%02x", x));
            return s.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
