package io.github.them3dium.mareshine;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.them3dium.mareshine.L.t;

// Looking for mods' updates (ModsActivity: Install updates, and a mod's quick
// menu). Each part that can be updated on its own is one Update, so Super
// Mario Eclipse asks for its game, each language and its HD textures one by
// one. Where they come from:
//   - the online catalog (tools/ratings-worker, /catalog): newer releases of
//     the mods the app offers and of their translations (Mods.loadCatalog)
//   - Eclipse's HD texture pack: the newest release on its GitHub page
//   - any mod: "update" in its mod.json, a URL of a JSON
//     {"version": "1.1", "url": "…zip", "page": "…"} (docs/MODS.md)
final class ModUpdates {
    private ModUpdates() {}

    static final String CATALOG = Ratings.URL + "/catalog";
    static final String TEXTURES_API = "https://api.github.com/repos/iZePlayzYT/SuperMarioEclipse-TexturePacks/releases/latest";

    static final class Update {
        final String mod, title, from, to;
        // what installing it does, on the screen that asked
        final java.util.function.Consumer<ModsActivity> install;

        Update(String mod, String title, String from, String to, java.util.function.Consumer<ModsActivity> install) {
            this.mod = mod; this.title = title; this.from = from; this.to = to; this.install = install;
        }
    }

    // The updates for every installed mod, or one (onlyMod). Runs on a worker
    // thread: it asks the network.
    static List<Update> check(Context ctx, String onlyMod) throws Exception {
        fetchCatalog(ctx);
        List<Update> out = new ArrayList<>();
        for (Mods.Mod m : Mods.list(ctx)) {
            if (onlyMod != null && !onlyMod.equals(m.id)) continue;
            if (m.id.equals(EclipseMod.ID)) eclipse(ctx, out);
            else if (!m.update.isEmpty()) own(ctx, m, out);
        }
        return out;
    }

    private static void fetchCatalog(Context ctx) {
        if (Ratings.URL.isEmpty()) return;
        try {
            String body = get(CATALOG);
            new JSONObject(body);  // only a readable one replaces the last
            File f = Mods.catalogFile(ctx), tmp = new File(f.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(body.getBytes("UTF-8"));
            }
            if (!tmp.renameTo(f)) tmp.delete();
            f.setLastModified(System.currentTimeMillis());
            Mods.loadCatalog(ctx);
        } catch (Exception ignored) {  // offline: the last catalog
        }
    }

    // Eclipse: its game and each language, from the catalog; its HD textures from GitHub
    private static void eclipse(Context ctx, List<Update> out) {
        for (Mods.Release r : EclipseMod.languages()) {
            String have = EclipseMod.installedVersion(ctx, r.id);
            if (have.isEmpty() || !newer(r.version, have)) continue;
            String title = r.base.isEmpty() ? r.name : t("%s (%s)", Mods.ECLIPSE.name, EclipseMod.languageName(r));
            out.add(new Update(EclipseMod.ID, title, have, r.version, a -> {
                if (r.urls.length > 0) ModInstallActivity.start(a, r);
                else ModFlows.translation(a, r);
            }));
        }
        if (ModFlows.texturesDir(ctx).isDirectory()) {
            String have = EclipseMod.installedVersion(ctx, EclipseMod.HD);
            try {
                String tag = new JSONObject(get(TEXTURES_API)).optString("tag_name", "");
                if (!tag.isEmpty() && (have.isEmpty() || newer(tag, have)))
                    out.add(new Update(EclipseMod.ID, t("%s HD textures", Mods.ECLIPSE.name),
                        have.isEmpty() ? "?" : have, tag, ModFlows::textures));
            } catch (Exception ignored) {
            }
        }
    }

    // a mod's own update address
    private static void own(Context ctx, Mods.Mod m, List<Update> out) {
        try {
            JSONObject u = new JSONObject(get(m.update));
            String version = u.optString("version", ""), url = u.optString("url", ""), page = u.optString("page", "");
            if (version.isEmpty() || !newer(version, m.version)) return;
            out.add(new Update(m.id, m.name, m.version, version, a -> {
                if (url.startsWith("https://")) a.downloadAndImport(url, m.name);
                else if (page.startsWith("https://")) ModFlows.openPage(a, page);
            }));
        } catch (Exception ignored) {
        }
    }

    // Downloads a mod's zip into the cache (for Mods.importZip)
    static Uri download(Context ctx, String url) throws Exception {
        File f = new File(ctx.getCacheDir(), "mod-update.zip");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Mareshine");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        return Uri.fromFile(f);
    }

    // a version from a file's name ("…TexturePack-v5.1.0.zip" -> "v5.1.0"), or ""
    static String versionIn(String name) {
        Matcher m = Pattern.compile("v?\\d+(\\.\\d+)+").matcher(name == null ? "" : name);
        String last = "";
        while (m.find()) last = m.group();
        return last;
    }

    // whether version a is newer than b ("v1.2.0" > "1.1.9"; numbers compared one by one)
    static boolean newer(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Mareshine");
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
                if (b.size() > (1 << 20)) throw new java.io.IOException("too large");
            }
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
