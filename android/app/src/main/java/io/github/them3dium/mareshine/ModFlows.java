package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.util.List;

import static io.github.them3dium.mareshine.L.t;

// The mod manager's dialogs shared by the list (ModsActivity) and a mod's page
// (ModPageActivity): Super Mario Eclipse's languages, a translation the
// player downloaded and picks, and its HD texture pack.
final class ModFlows {
    private ModFlows() {}

    // the requests the screens pass on here (onActivityResult)
    static final int PICK_TRANSLATION = 11, PICK_TEXTURES = 12;

    // Switches a mod on or off; switching on warns about the mods it does not
    // work with (Mods.conflicts): switch those off, switch it on anyway (not
    // for two games: only one can be played), or leave it. done runs after a change.
    static void setEnabled(AppActivity a, Mods.Mod m, boolean on, Runnable done) {
        List<Mods.Mod> clash = on ? Mods.conflicts(a, m) : new java.util.ArrayList<>();
        if (clash.isEmpty()) {
            Mods.setEnabled(a, m.id, on);
            done.run();
            return;
        }
        StringBuilder names = new StringBuilder();
        boolean games = false;
        for (Mods.Mod o : clash) {
            names.append(names.length() > 0 ? ", " : "").append(o.name);
            if (!m.image.isEmpty() && !o.image.isEmpty()) games = true;
        }
        String msg = t("%s does not work together with: %s.", m.name, names)
            + (games ? "\n" + t("Each is a game of its own, and one game is played at a time.") : "");
        Runnable others = () -> {
            List<Mods.Mod> all = Mods.list(a);
            for (Mods.Mod x : all) {
                if (x.id.equals(m.id)) x.enabled = true;
                for (Mods.Mod o : clash) if (x.id.equals(o.id)) x.enabled = false;
            }
            Mods.save(a, all);
            done.run();
        };
        Runnable anyway = () -> {
            Mods.setEnabled(a, m.id, true);
            done.run();
        };
        if (games) a.choice(t("Mods that do not go together"), msg, new String[] {t("Switch the others off")},
            new Runnable[] {others});
        else a.choice(t("Mods that do not go together"), msg,
            new String[] {t("Switch the others off"), t("Switch it on anyway")}, new Runnable[] {others, anyway});
    }

    // Mareshine's own HD texture pack (Settings → VIDEO): on, with textures in it
    static boolean ownPackOn(android.content.Context c) {
        if ("0".equals(Settings.read(c).getOrDefault("SMS_HD_TEXTURES", "1"))) return false;
        String[] own = DataDir.texturesDir(c).list();
        return own != null && own.length > 0;
    }

    // Super Mario Eclipse's HD textures on or off; on, while Mareshine's own
    // pack is on, warns that the two do not go together
    static void eclipseHd(AppActivity a, Mods.Mod m, Mods.Option o, Runnable done) {
        boolean on = !m.value(o).equals("1");
        if (!on || !ownPackOn(a)) {
            Mods.setOption(a, m, o, on ? "1" : "0");
            done.run();
            return;
        }
        a.choice(t("Texture packs that do not go together"),
            t("Mareshine's HD texture pack (Settings → VIDEO → HD textures) is on too. It is made for Super Mario "
                + "Sunshine: with Super Mario Eclipse's own pack, textures of both get mixed."),
            new String[] {t("Switch Mareshine's pack off"), t("Keep both on")}, new Runnable[] {() -> {
                Settings.put(a, "SMS_HD_TEXTURES", "0");
                Mods.setOption(a, m, o, "1");
                done.run();
            }, () -> {
                Mods.setOption(a, m, o, "1");
                done.run();
            }});
    }

    static void openPage(AppActivity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            a.choice(t("No browser"), url, new String[] {t("OK")}, new Runnable[] {null});
        }
    }

    static void pick(Activity a, int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        a.startActivityForResult(i, request);
    }

    // Which language: the installed ones are switched to, the others installed
    // (English downloaded, a translation from its page).
    static void languages(AppActivity a) {
        List<Mods.Release> langs = EclipseMod.languages();
        Mods.Mod m = Mods.find(a, EclipseMod.ID);
        String cur = "";
        Mods.Option opt = null;
        if (m != null)
            for (Mods.Option o : m.options) if (o.id.equals(EclipseMod.LANGUAGE)) { opt = o; cur = m.value(o); }
        String[] labels = new String[langs.size() + 1];
        Runnable[] acts = new Runnable[langs.size() + 1];
        for (int i = 0; i < langs.size(); i++) {
            Mods.Release r = langs.get(i);
            boolean have = EclipseMod.has(a, r);
            String name = EclipseMod.languageName(r);
            labels[i] = have ? (EclipseMod.valueId(r).equals(cur) ? "✓  " : "") + name
                : name + "  ·  " + t(r.urls.length > 0 ? "download %.1f GB" : "pick the downloaded file", r.size / 1e9);
            final Mods.Mod mod = m;
            final Mods.Option o = opt;
            acts[i] = () -> {
                if (have && mod != null && o != null) {
                    Mods.setOption(a, mod, o, EclipseMod.valueId(r));
                    a.setResult(Activity.RESULT_OK);
                    if (a instanceof ModPageActivity) ((ModPageActivity) a).reloadPage();
                } else if (r.urls.length > 0) {
                    ModInstallActivity.start(a, r);
                } else {
                    translation(a, r);
                }
            };
        }
        labels[langs.size()] = t("Another language…");
        acts[langs.size()] = () -> a.choice(t("Another language"), t("Only the languages listed have a translation "
                + "for Super Mario Eclipse so far. A new one will show up here with an app update."),
            new String[] {t("OK")}, new Runnable[] {null});
        a.choice(t("Which language?"), t("Each language is its own copy of the game (about %.1f GB), made from "
            + "your own disc. Hold Language on Super Mario Eclipse's page to delete one.", Mods.ECLIPSE.imageSize / 1e9),
            labels, acts);
    }

    // A mod the player downloads from its page and picks (Super Mario Sunburn)
    static void manual(AppActivity a, Mods.Release r) {
        if (!r.base.isEmpty()) {
            translation(a, r);
            return;
        }
        a.choice(t("Install %s", r.name),
            t("%s is made by %s. Download it from its page (the .7z file, do not unpack it), then pick the "
                + "downloaded file here. It is made from your own Super Mario Sunshine disc (North American, GMSE01) "
                + "and needs about %.1f GB.", r.name, r.credits, r.imageSize / 1e9),
            new String[] {t("Open download page"), t("Pick downloaded file")},
            new Runnable[] {() -> openPage(a, r.page), () -> pick(a, PICK_TRANSLATION)});
    }

    // A translation: downloaded from its page by the player, then picked
    static void translation(AppActivity a, Mods.Release r) {
        a.choice(t("Install %s", EclipseMod.languageName(r)),
            t("The %s translation is made by %s. Download it from its page (the .7z file, do not unpack it), then "
                + "pick the downloaded file here. It is made from your own disc like the game itself.",
                EclipseMod.languageName(r), r.credits),
            new String[] {t("Open download page"), t("Pick downloaded file")},
            new Runnable[] {() -> openPage(a, r.page), () -> pick(a, PICK_TRANSLATION)});
    }

    // the picked file: which translation it is, by its size
    static void picked(AppActivity a, Uri uri) {
        long size = -1;
        try (android.database.Cursor c = a.getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) size = c.getLong(0);
        } catch (Exception ignored) {
        }
        Mods.Release r = Mods.pickedOfSize(size);
        if (r == null) {
            a.choice(t("Not a known file"), t("This file is none of the mods and translations listed. Pick the .7z "
                + "file as downloaded from its page, not unpacked."), new String[] {t("OK")},
                new Runnable[] {null});
            return;
        }
        ModInstallActivity.start(a, r, uri);
    }

    // Super Mario Eclipse's HD texture pack: from its page, imported into hd/
    static void textures(AppActivity a) {
        a.choice(t("HD textures"), t("The HD texture pack for Super Mario Eclipse is made by iZePlayzYT. Download "
                + "the texture pack's .zip from its page, then import it here. The packs are large (several GB, and "
                + "unpacked about twice that), and big textures need a lot of the device's memory."),
            new String[] {t("Open download page"), t("Import downloaded file")},
            new Runnable[] {() -> openPage(a, EclipseMod.TEXTURES_PAGE), () -> pick(a, PICK_TEXTURES)});
    }

    static File texturesDir(android.content.Context c) { return new File(new File(EclipseMod.dir(c), EclipseMod.HD), "textures"); }
}
