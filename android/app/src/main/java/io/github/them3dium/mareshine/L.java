package io.github.them3dium.mareshine;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// The app's own language (not the game's: that is the `language` setting).
// Every text in the app is written in English as t("..."), and that English
// text is the key into assets/lang/<code>.json, {"English": "translation"};
// a text missing there stays English. The choice is a small file in the
// app's private storage, so the launcher and the game process both see it.
public final class L {
    public static final String SYSTEM = "system";
    public static final String[] CODES = {SYSTEM, "en", "de", "es", "fr", "it"};
    // each language in its own name
    public static final String[] NAMES = {"System", "English", "Deutsch", "Español", "Français", "Italiano"};

    private static Map<String, String> sTable = new HashMap<>();
    private static String sLoaded = "";

    private L() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "app_language"); }

    // the chosen setting: "system" or a language code
    public static String chosen(Context c) {
        try {
            String v = new String(Files.readAllBytes(file(c).toPath()), StandardCharsets.UTF_8).trim();
            for (String k : CODES) if (k.equals(v)) return v;
        } catch (IOException ignored) {}
        return SYSTEM;
    }

    public static void choose(Context c, String code) {
        try (FileOutputStream o = new FileOutputStream(file(c))) {
            o.write(code.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
        init(c);
    }

    // the language actually used
    public static String current(Context c) {
        String v = chosen(c);
        return SYSTEM.equals(v) ? Locale.getDefault().getLanguage() : v;
    }

    // the language loaded by init ("" before it), for texts that come with
    // their own translations (a mod's "description_de", Mods)
    public static String loaded() { return sLoaded; }

    // Loads the table for the current language (again only when it changed).
    public static void init(Context c) {
        String lang = current(c);
        if (lang.equals(sLoaded)) return;
        Map<String, String> m = new HashMap<>();
        if (!"en".equals(lang)) {
            try (InputStream in = c.getAssets().open("lang/" + lang + ".json")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                JSONObject j = new JSONObject(b.toString("UTF-8"));
                for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                    String k = it.next();
                    String v = j.optString(k, "");
                    if (!v.isEmpty()) m.put(k, v);
                }
            } catch (Exception ignored) {} // no table for that language: English
        }
        sTable = m;
        sLoaded = lang;
    }

    // The texts of a layout from XML (buttons, headings) in the app's language.
    public static void translate(android.view.View v) {
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            CharSequence s = tv.getText();
            if (s != null && s.length() > 0) tv.setText(t(s.toString()));
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) translate(g.getChildAt(i));
        }
    }

    // The text in the app's language; with args, a String.format pattern.
    public static String t(String en, Object... args) {
        String s = sTable.get(en);
        if (s == null) s = en;
        return args.length == 0 ? s : String.format(Locale.ROOT, s, args);
    }
}
