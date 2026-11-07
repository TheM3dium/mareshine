package io.github.them3dium.mareshine;

import android.content.Context;
import android.view.KeyEvent;

// SMS_MENU_SWAP_AB: A and B change places in the app's menus, for a Nintendo
// layout (B confirms, A goes back). The game itself reads its buttons apart
// from these, and the button mapping screen sees the real ones.
final class MenuKeys {
    static final String KEY = "SMS_MENU_SWAP_AB";
    private static volatile boolean sSwap;

    private MenuKeys() {}

    static void load(Context c) { sSwap = "1".equals(Settings.read(c).get(KEY)); }

    static void set(boolean swap) { sSwap = swap; }

    static boolean swapped() { return sSwap; }

    static KeyEvent apply(KeyEvent ev) {
        if (!sSwap) return ev;
        int code = ev.getKeyCode();
        if (code != KeyEvent.KEYCODE_BUTTON_A && code != KeyEvent.KEYCODE_BUTTON_B) return ev;
        return new KeyEvent(ev.getDownTime(), ev.getEventTime(), ev.getAction(),
            code == KeyEvent.KEYCODE_BUTTON_A ? KeyEvent.KEYCODE_BUTTON_B : KeyEvent.KEYCODE_BUTTON_A,
            ev.getRepeatCount(), ev.getMetaState(), ev.getDeviceId(), ev.getScanCode(), ev.getFlags(), ev.getSource());
    }
}
