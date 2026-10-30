package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;

import static io.github.them3dium.mareshine.L.t;

// The logs (CrashLog) for a bug report, without a file manager: Settings →
// OTHER → LOGS shares them to another app or copies game.log to the
// clipboard. Players often cannot open Android/data (Android 11 and later),
// where the data folder usually is.
final class LogShare {
    private static final String AUTHORITY = "io.github.them3dium.mareshine.logs";
    private static final int CRASH_LOGS = 3;          // the newest crash and ANR reports shared with the log
    private static final int CLIPBOARD_BYTES = 200 * 1024; // the clipboard takes the end of a longer log
    private static final int ANR_CLIPBOARD_BYTES = 100 * 1024;

    private LogShare() {}

    private static File shareDir(Context c) { return new File(c.getCacheDir(), "share-logs"); }

    // game.log, game.prev.log and the newest crash and ANR reports in one
    // file, each under a heading, where the provider serves it; null when
    // there is no log
    static File collect(Context c) throws IOException {
        File logs = CrashLog.dir(c);
        File out = shareDir(c);
        File[] old = out.listFiles();
        if (old != null) for (File f : old) f.delete();
        out.mkdirs();
        ArrayList<File> picked = new ArrayList<>();
        for (String n : new String[] {"game.log", "game.prev.log"}) {
            File f = new File(logs, n);
            if (f.isFile() && f.length() > 0) picked.add(f);
        }
        File[] reports = logs.listFiles((d, n) -> (n.startsWith("crash") && n.endsWith(".log"))
            || ((n.startsWith("anr-") || n.startsWith("hang-")) && n.endsWith(".txt")));
        if (reports != null) {
            Arrays.sort(reports, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 0; i < reports.length && i < CRASH_LOGS; i++) picked.add(reports[i]);
        }
        if (picked.isEmpty()) return null;
        File dst = new File(out, "mareshine-logs.txt");
        try (OutputStream o = new FileOutputStream(dst)) {
            o.write(("Mareshine " + CrashLog.BuildConfigInfo.version(c) + ", " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + "\n").getBytes("UTF-8"));
            byte[] buf = new byte[1 << 16];
            for (File f : picked) {
                o.write(("\n===== " + f.getName() + " =====\n\n").getBytes("UTF-8"));
                try (InputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
        return dst;
    }

    static void share(Activity a) {
        File file;
        try {
            file = collect(a);
        } catch (IOException e) {
            file = null;
        }
        if (file == null) {
            Toast.makeText(a, t("There is no log yet. Turn on logs, play until the problem happens, then try again."),
                Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getName()));
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "Mareshine " + CrashLog.BuildConfigInfo.version(a) + " logs");
        send.setClipData(ClipData.newRawUri("log", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(send, t("Share log")));
        } catch (Exception e) {
            Toast.makeText(a, t("No app to share with was found."), Toast.LENGTH_LONG).show();
        }
    }

    // "Save as" (the system's file picker); the file is written in saveTo
    static void save(Activity a, int request) {
        File file;
        try {
            file = collect(a);
        } catch (IOException e) {
            file = null;
        }
        if (file == null) {
            Toast.makeText(a, t("There is no log yet. Turn on logs, play until the problem happens, then try again."),
                Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, file.getName());
        try {
            a.startActivityForResult(i, request);
        } catch (Exception e) {
            Toast.makeText(a, t("The log could not be saved."), Toast.LENGTH_LONG).show();
        }
    }

    static void saveTo(Activity a, Uri target) {
        File file = new File(shareDir(a), "mareshine-logs.txt");
        try (InputStream in = new FileInputStream(file);
             OutputStream o = a.getContentResolver().openOutputStream(target, "wt")) {
            if (o == null) throw new IOException("no output");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            Toast.makeText(a, t("Log saved."), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(a, t("The log could not be saved."), Toast.LENGTH_LONG).show();
        }
    }

    static void copy(Activity a) {
        File log = new File(CrashLog.dir(a), "game.log");
        if (!log.isFile() || log.length() == 0) {
            Toast.makeText(a, t("There is no log yet. Turn on logs, play until the problem happens, then try again."),
                Toast.LENGTH_LONG).show();
            return;
        }
        String text;
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long len = f.length();
            long from = Math.max(0, len - CLIPBOARD_BYTES);
            byte[] b = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(b);
            text = new String(b, "UTF-8");
            if (from > 0) text = "[log] (only the last " + (CLIPBOARD_BYTES / 1024) + " KB)\n" + text.substring(text.indexOf('\n') + 1);
        } catch (IOException e) {
            Toast.makeText(a, t("The log could not be read."), Toast.LENGTH_LONG).show();
            return;
        }
        // the newest ANR report (AnrLog) too, its start: the frozen main thread comes first
        File[] anrs = CrashLog.dir(a).listFiles((d, n) -> n.startsWith("anr-") && n.endsWith(".txt"));
        if (anrs != null && anrs.length > 0) {
            Arrays.sort(anrs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            try (RandomAccessFile f = new RandomAccessFile(anrs[0], "r")) {
                byte[] b = new byte[(int) Math.min(f.length(), ANR_CLIPBOARD_BYTES)];
                f.readFully(b);
                text += "\n\n===== " + anrs[0].getName() + " =====\n\n" + new String(b, "UTF-8");
            } catch (IOException ignored) {}
        }
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Mareshine log", text));
        // Android 13 confirms a copy itself, but not every phone maker's version does
        Toast.makeText(a, t("Log copied."), Toast.LENGTH_SHORT).show();
    }

    // Serves the copies in the cache to the app the logs are shared with
    // (read only, nothing else of the app).
    public static final class Provider extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        private File file(Uri uri) throws FileNotFoundException {
            String name = uri.getLastPathSegment();
            if (name == null || name.contains("/") || name.startsWith(".")) throw new FileNotFoundException();
            File f = new File(shareDir(getContext()), name);
            if (!f.isFile()) throw new FileNotFoundException();
            return f;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
            File f;
            try {
                f = file(uri);
            } catch (FileNotFoundException e) {
                return null;
            }
            MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[] {f.getName(), f.length()});
            return c;
        }

        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues v) { return null; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }
}
