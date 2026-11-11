package io.github.them3dium.mareshine;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static io.github.them3dium.mareshine.L.t;

/** A file the player picked (a content:// Uri) copied into a file of ours. */
final class UriCopy {
    private UriCopy() {}

    interface Progress { void copied(long bytes); }

    /** The size the picker reports for it, or -1 when it does not say. */
    static long size(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c == null || !c.moveToFirst()) return -1;
            int col = c.getColumnIndex(OpenableColumns.SIZE);
            return col < 0 || c.isNull(col) ? -1 : c.getLong(col);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Copies it to {@code dest} through dest.tmp, so an interrupted copy never
     * leaves a half file under the real name; an old {@code dest} is replaced
     * only once the copy is complete. Blocking: not on the UI thread.
     */
    static void copy(ContentResolver cr, Uri uri, File dest, Progress progress) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        try {
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) throw new IOException(t("Could not open the selected file."));
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 20];
                    long done = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        progress.copied(done);
                    }
                }
            }
            if (dest.exists() && !dest.delete() || !tmp.renameTo(dest))
                throw new IOException(t("Could not replace the final file."));
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            throw e;
        }
    }
}
