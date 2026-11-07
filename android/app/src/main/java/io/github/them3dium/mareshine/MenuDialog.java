package io.github.them3dium.mareshine;

import android.app.Dialog;
import android.content.Context;
import android.view.KeyEvent;

// The app's dialogs, with the menus' A and B as they are set (MenuKeys).
class MenuDialog extends Dialog {
    MenuDialog(Context context) { super(context); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        return super.dispatchKeyEvent(MenuKeys.apply(ev));
    }
}
