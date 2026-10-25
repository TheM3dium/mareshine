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
        Mods.Release r = Mods.translationOfSize(size);
        if (r == null) {
            a.choice(t("Not a known translation"), t("This file is none of the translations listed. Pick the .7z file "
                + "as downloaded from the translation's page, not unpacked."), new String[] {t("OK")},
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

    static File texturesDir(AppActivity a) { return new File(new File(EclipseMod.dir(a), EclipseMod.HD), "textures"); }
}
