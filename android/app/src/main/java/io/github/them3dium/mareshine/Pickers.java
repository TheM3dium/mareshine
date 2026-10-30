package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.widget.Toast;

import static io.github.them3dium.mareshine.L.t;

// Android's file and folder pickers. Some devices have none for a document
// (the system's Files app switched off or taken out, as on some Samsungs):
// starting one there threw ActivityNotFoundException and closed the app.
// A file is then picked with any app that offers files (GET_CONTENT, such as
// a file manager); with none at all, the player is told.
final class Pickers {
    private Pickers() {}

    // A file of any type, opened for reading (onActivityResult gets its Uri)
    static void openFile(Activity a, int request) {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType("*/*");
        try {
            a.startActivityForResult(open, request);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        Intent get = new Intent(Intent.ACTION_GET_CONTENT);
        get.addCategory(Intent.CATEGORY_OPENABLE);
        get.setType("*/*");
        start(a, get, request);
    }

    // Any picker; false, with a notice, when the device has none
    static boolean start(Activity a, Intent i, int request) {
        try {
            a.startActivityForResult(i, request);
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, t("This device has no file picker."), Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
