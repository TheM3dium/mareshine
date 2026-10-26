package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

// The app's screens (not the game's): the same layout on every display.
//
// They are laid out for a 960 x 540 dp landscape screen (an NVIDIA Shield at
// 1080p). A screen with less room, such as a phone with a large display size
// or font setting, gets a smaller density for this app's views, so everything
// keeps its proportions instead of overlapping; larger screens stay as they
// are. Text follows the same scale (the system font size is not added on top,
// as the rows are sized for the text).
//
// The screens are full screen like the game (system bars hidden, back by a
// swipe from the edge) and keep their content out of a camera cutout and any
// bars that stay visible: since Android 15 an app draws behind them.
public class AppActivity extends Activity {
    static final int REF_WIDTH_DP = 960, REF_HEIGHT_DP = 540;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration c = scaledConfiguration(base);
        if (c != null) applyOverrideConfiguration(c);
    }

    // On a touch device the content also keeps a small margin from the edges
    // (rounded display corners, a hand at the edge); a TV keeps its full area.
    static final int TOUCH_MARGIN_DP = 12;
    static final float TOUCH_SIZE = 0.8f;

    static boolean isTouch(Context c) {
        return c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN);
    }

    // The room to leave on each side (left, top, right, bottom), in pixels: the
    // system bars' place even while they are hidden (they come back with a
    // swipe), a camera cutout, and on a touch device the margin and the
    // rounded corners.
    static int[] safeArea(Context c, WindowInsets insets, float density) {
        int[] s = new int[4];
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets i = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            s[0] = i.left; s[1] = i.top; s[2] = i.right; s[3] = i.bottom;
        } else {
            s[0] = insets.getStableInsetLeft(); s[1] = insets.getStableInsetTop();
            s[2] = insets.getStableInsetRight(); s[3] = insets.getStableInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                DisplayCutout cut = insets.getDisplayCutout();
                s[0] = Math.max(s[0], cut.getSafeInsetLeft()); s[1] = Math.max(s[1], cut.getSafeInsetTop());
                s[2] = Math.max(s[2], cut.getSafeInsetRight()); s[3] = Math.max(s[3], cut.getSafeInsetBottom());
            }
        }
        if (isTouch(c)) {
            int margin = Math.round(TOUCH_MARGIN_DP * density);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int radius = 0;
                for (int pos : new int[] {android.view.RoundedCorner.POSITION_TOP_LEFT, android.view.RoundedCorner.POSITION_TOP_RIGHT,
                        android.view.RoundedCorner.POSITION_BOTTOM_LEFT, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT}) {
                    android.view.RoundedCorner rc = insets.getRoundedCorner(pos);
                    if (rc != null) radius = Math.max(radius, rc.getRadius());
                }
                margin = Math.max(margin, Math.round(radius * 0.3f)); // a corner's curve, clear of panel corners
            }
            for (int k = 0; k < 4; k++) s[k] = Math.max(s[k], margin);
        }
        return s;
    }

    // null when the display has room for the reference layout as it is
    static Configuration scaledConfiguration(Context base) {
        Configuration cur = base.getResources().getConfiguration();
        DisplayMetrics dm = base.getResources().getDisplayMetrics();
        int w, h;
        WindowManager wm = base.getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            android.view.WindowMetrics m = wm.getMaximumWindowMetrics();
            Rect b = m.getBounds();
            int[] safe = safeArea(base, m.getWindowInsets(), dm.density);
            w = b.width() - safe[0] - safe[2];
            h = b.height() - safe[1] - safe[3];
            if (b.height() > b.width()) { // asked in portrait: the sides turn with the screen
                w = b.width() - safe[1] - safe[3];
                h = b.height() - safe[0] - safe[2];
            }
        } else {
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        int longSide = Math.max(w, h), shortSide = Math.min(w, h); // the app runs in landscape
        float density = dm.density;
        // A phone or tablet is held close: its layout is a quarter larger than
        // a TV's for the same share of the screen (768 x 432 dp instead of 960 x 540).
        float ref = isTouch(base) ? TOUCH_SIZE : 1f;
        float fit = Math.min(density, Math.min(longSide / (REF_WIDTH_DP * ref), shortSide / (REF_HEIGHT_DP * ref)));
        if (fit >= density - 0.001f && cur.fontScale <= 1.0f) return null;
        Configuration c = new Configuration();
        c.densityDpi = Math.max(1, Math.round(fit * DisplayMetrics.DENSITY_DEFAULT));
        c.fontScale = Math.min(cur.fontScale, 1.0f);
        c.screenWidthDp = Math.round(longSide / fit);
        c.screenHeightDp = Math.round(shortSide / fit);
        c.smallestScreenWidthDp = c.screenHeightDp;
        return c;
    }

    // false for a screen that must cover the whole display (the touch layout
    // editor, which shows the game's screen)
    protected boolean keepClearOfSystemUi() { return true; }

    @Override
    protected void onPostCreate(android.os.Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        View content = findViewById(android.R.id.content);
        if (content != null && keepClearOfSystemUi()) {
            // the screen's background goes on under the cutout and the bars
            if (content instanceof android.view.ViewGroup && ((android.view.ViewGroup) content).getChildCount() > 0) {
                android.graphics.drawable.Drawable bg = ((android.view.ViewGroup) content).getChildAt(0).getBackground();
                if (bg != null && bg.getConstantState() != null) content.setBackground(bg.getConstantState().newDrawable());
            }
            content.setOnApplyWindowInsetsListener((v, insets) -> {
                int[] safe = safeArea(this, insets, getResources().getDisplayMetrics().density);
                v.setPadding(safe[0], safe[1], safe[2], safe[3]);
                return insets;
            });
            content.requestApplyInsets();
        }
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
        else hideCursor();  // a dialog on top: its own entries, by the D-pad
    }

    protected void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // The ✕ of a panel over the game (as in Settings): closes it. A tap on the
    // dimmed game around the panel closes it too (closeOnOutsideTap).
    TextView closeButton() {
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(24);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        close.setPadding(pad * 2, pad, pad * 2, pad);
        close.setFocusable(false);  // B closes with a controller; the entries keep the focus
        close.setBackgroundResource(R.drawable.dl_tab);
        close.setContentDescription(L.t("Close"));
        close.setOnClickListener(v -> finish());
        return close;
    }

    // A choice in the app's look (dl_panel, gold title, entries that light up
    // when focused), instead of the system dialog: a title, an optional
    // message and the entries; Cancel, Back or a tap beside it closes it.
    android.app.Dialog choice(String title, String message, String[] labels, Runnable[] actions) {
        return choice(title, message, labels, actions, null);
    }

    // `dimmed[i]`: the entry shows greyed out (its action says why it cannot be done now)
    android.app.Dialog choice(String title, String message, String[] labels, Runnable[] actions, boolean[] dimmed) {
        final float d = getResources().getDisplayMetrics().density;
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        int pad = Math.round(20 * d);
        panel.setPadding(pad, Math.round(14 * d), pad, Math.round(14 * d));
        TextView head = new TextView(this);
        head.setText(title);
        head.setTextColor(0xFFFFC93C);
        head.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        head.setTextSize(22);
        panel.addView(head);
        if (message != null) {
            TextView msg = new TextView(this);
            msg.setText(message);
            msg.setTextColor(getColor(R.color.dl_dim));
            msg.setTextSize(14);
            msg.setPadding(0, Math.round(4 * d), 0, Math.round(6 * d));
            msg.setTag(CHOICE_MESSAGE);  // for a dialog that shows progress (choiceProgress)
            panel.addView(msg);
        }
        String[] all = java.util.Arrays.copyOf(labels, labels.length + 1);
        all[labels.length] = L.t("Cancel");
        // from five entries on they scroll, about four and a half in view
        android.widget.LinearLayout entries = panel;
        if (all.length > 5) {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            entries = new android.widget.LinearLayout(this);
            entries.setOrientation(android.widget.LinearLayout.VERTICAL);
            sv.addView(entries);
            sv.setScrollbarFadingEnabled(false);
            panel.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Math.round(4.5f * 48 * d)));
        }
        View first = null;
        for (int i = 0; i < all.length; i++) {
            final Runnable action = i < actions.length ? actions[i] : null;
            TextView item = new TextView(this);
            item.setText(all[i]);
            boolean dim = i >= labels.length || (dimmed != null && i < dimmed.length && dimmed[i]);
            item.setTextColor(dim ? getColor(R.color.dl_dim) : 0xFFFFFFFF);
            if (dimmed != null && i < dimmed.length && dimmed[i]) item.setAlpha(0.5f);
            item.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
            item.setTextSize(18);
            item.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(14 * d), Math.round(10 * d));
            item.setFocusable(true);
            item.setBackgroundResource(R.drawable.dl_menu_item);
            item.setOnClickListener(v -> {
                dlg.dismiss();
                if (action != null) action.run();
            });
            entries.addView(item, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            if (first == null) first = item;
        }
        dlg.setContentView(panel);
        dlg.setCanceledOnTouchOutside(true);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(Math.round(420 * d), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            // over the immersive game: keep the bars hidden while it shows
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dlg.show();
        if (w != null) {
            w.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        if (first != null) first.requestFocus();
        return dlg;
    }

    static final String CHOICE_MESSAGE = "choice_message";

    // "  ·  37 %" after a progress text (nothing when the total is unknown)
    static String pct(long done, long total) {
        return total > 0 ? "  ·  " + Math.min(100, done * 100 / total) + " %" : "";
    }

    // A choice() dialog's message, changed while it shows (progress), and a
    // bar under it at `fraction` (0 to 1; below 0: none)
    static void choiceProgress(android.app.Dialog dlg, String message, float fraction) {
        if (dlg == null || dlg.getWindow() == null) return;
        View v = dlg.getWindow().getDecorView().findViewWithTag(CHOICE_MESSAGE);
        if (!(v instanceof TextView)) return;
        ((TextView) v).setText(message);
        if (fraction < 0) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) v.getParent();
        android.widget.ProgressBar bar = parent.findViewWithTag("choice_bar");
        if (bar == null) {
            bar = new android.widget.ProgressBar(dlg.getContext(), null, android.R.attr.progressBarStyleHorizontal);
            bar.setTag("choice_bar");
            bar.setMax(1000);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFC531));
            parent.addView(bar, parent.indexOfChild(v) + 1, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(14 * dlg.getContext().getResources().getDisplayMetrics().density)));
        }
        bar.setProgress(Math.round(Math.max(0, Math.min(1, fraction)) * 1000));
    }

    static void closeOnOutsideTap(Activity a, View root, View panel) {
        panel.setClickable(true);  // taps on the panel stay there
        root.setOnClickListener(v -> a.finish());
    }

    // --- controller navigation ---------------------------------------------
    // One scheme for the app's menus (SettingsActivity, AchievementsActivity,
    // GameMenuActivity, SaveStatesActivity): L1/R1, L2/R2 and the D-pad's
    // left/right switch tabs with one press; the D-pad's and the left stick's
    // up/down move through the entries, straight down the list; the stick's
    // left/right does nothing, so a slip of the thumb cannot switch tabs; A
    // presses the entry, B goes back. The D-pad of many controllers (8BitDo)
    // is a hat axis, which Android would turn into D-pad keys together with
    // the stick, so the axes are read here and not passed on.

    // Whether this screen uses the scheme (StickCalibration needs the raw stick).
    protected boolean usesPadNav() { return false; }

    // A tab to the left (-1) or right (+1); `shoulder` for L/R, else the D-pad.
    // false lets the key go on (a panel that takes left/right itself).
    protected boolean onPadTab(int dir, boolean shoulder) { return false; }

    // The entries up/down moves through, top to bottom, or null to let
    // Android search for the next one.
    protected java.util.List<View> padItems() { return null; }

    // The entry a controller starts on when nothing is chosen (touch mode
    // keeps the focus from being set), or null for the first
    protected View padDefault() { return null; }

    // A on `focused` before it is clicked: true when the screen handled it
    // (a text field, whose keyboard a controller cannot work)
    protected boolean onPadA(View focused) { return false; }

    // Moves the focus one entry up (-1) or down (+1); with nothing focused,
    // to the first entry.
    protected boolean onPadMove(int dir) {
        java.util.List<View> items = padItems();
        View cur = getCurrentFocus();
        if (items == null) {
            if (cur == null) return false;
            View next = cur.focusSearch(dir < 0 ? View.FOCUS_UP : View.FOCUS_DOWN);
            if (next != null) next.requestFocusFromTouch();
            return true;
        }
        java.util.List<View> live = new java.util.ArrayList<>();
        for (View v : items) if (v != null && v.isShown() && v.isFocusable()) live.add(v);
        if (live.isEmpty()) return true;
        int at = -1;
        for (int i = 0; i < live.size() && cur != null; i++) {
            View v = live.get(i);
            if (v == cur || (v instanceof android.view.ViewGroup && isDescendant((android.view.ViewGroup) v, cur))) at = i;
        }
        View main = padDefault();
        if (at < 0 && main != null && live.contains(main)) {  // nothing chosen yet: the screen's main entry
            main.requestFocusFromTouch();
            return true;
        }
        int next = at < 0 ? 0 : Math.max(0, Math.min(live.size() - 1, at + dir));
        live.get(next).requestFocusFromTouch();
        return true;
    }

    private static boolean isDescendant(android.view.ViewGroup g, View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == g) return true;
        return false;
    }

    // focus for a screen that just appeared: its first entry
    protected void padFocusFirst() {
        java.util.List<View> items = padItems();
        if (items == null) return;
        for (View v : items) if (v != null && v.isShown() && v.isFocusable()) { v.requestFocusFromTouch(); return; }
    }

    private int mPadHatX, mPadVert;  // the axes' last directions (-1, 0, 1)
    private final android.os.Handler mPadRepeat = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mPadRepeatStep = new Runnable() {
        @Override public void run() {
            if (mPadVert == 0) return;
            onPadMove(mPadVert);
            mPadRepeat.postDelayed(this, 120);
        }
    };

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        if (usesPadNav() && ev.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)
                && ev.getAction() == android.view.MotionEvent.ACTION_MOVE) {
            int hx = Math.round(ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_X));
            int hy = Math.round(ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y));
            float sy = ev.getAxisValue(android.view.MotionEvent.AXIS_Y);
            int vert = hy != 0 ? hy : sy < -0.6f ? -1 : sy > 0.6f ? 1 : 0;
            // the right stick moves the pointer where a screen has one; the D-pad and left stick the focus
            if (usesPadCursor()) {  // Z/RZ on most controllers, RX/RY on some
                float zx = ev.getAxisValue(android.view.MotionEvent.AXIS_Z), zy = ev.getAxisValue(android.view.MotionEvent.AXIS_RZ);
                float rx = ev.getAxisValue(android.view.MotionEvent.AXIS_RX), ry = ev.getAxisValue(android.view.MotionEvent.AXIS_RY);
                boolean z = Math.abs(zx) + Math.abs(zy) >= Math.abs(rx) + Math.abs(ry);
                cursorStick(z ? zx : rx, z ? zy : ry);
            }
            if (hx != 0 || vert != 0) hideCursor();
            if (hx != mPadHatX) {
                mPadHatX = hx;
                if (hx != 0 && !onPadTab(hx, false)) {  // a panel's own left/right
                    View f = getCurrentFocus();
                    if (f != null) {
                        int code = hx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
                        boolean used = f.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                        f.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                        // not the entry's own (a slider's): to the one beside it, as the D-pad's keys go
                        View next = used ? null : f.focusSearch(hx < 0 ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                        if (next != null) next.requestFocusFromTouch();
                    }
                }
            }
            if (vert != mPadVert) {
                mPadVert = vert;
                mPadRepeat.removeCallbacks(mPadRepeatStep);
                if (vert != 0) {
                    onPadMove(vert);
                    mPadRepeat.postDelayed(mPadRepeatStep, 400);
                }
            }
            return true;  // not turned into D-pad keys (the stick's left/right among them)
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // --- the pointer: a controller's right stick as a mouse ---------------------------
    // (the mod screens: long pages with buttons below their text)
    protected boolean usesPadCursor() { return false; }

    // A round, half-clear spot over the screen; A touches where it is (held: a
    // long press, moved while held: a drag that scrolls). It goes after a few
    // seconds without use, or when the D-pad moves the focus again.

    private View mCursor;
    private boolean mCursorShown, mCursorDown;
    private float mCursorX = -1, mCursorY, mStickX, mStickY;
    private long mCursorDownTime, mCursorLastUse, mCursorFrame;

    private final Runnable mCursorStep = new Runnable() {
        @Override public void run() {
            if (!mCursorShown) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - mCursorFrame) / 1000f);
            mCursorFrame = now;
            View root = getWindow().getDecorView();
            if (mStickX != 0 || mStickY != 0) {
                // faster the further it is pushed: a screen's width in about a second at full tilt
                float speed = root.getWidth() * 1.1f;
                mCursorX = Math.max(0, Math.min(root.getWidth() - 1, mCursorX + mStickX * Math.abs(mStickX) * speed * dt));
                mCursorY = Math.max(0, Math.min(root.getHeight() - 1, mCursorY + mStickY * Math.abs(mStickY) * speed * dt));
                placeCursor();
                if (mCursorDown) cursorTouch(android.view.MotionEvent.ACTION_MOVE);
                mCursorLastUse = now;
            } else if (!mCursorDown && now - mCursorLastUse > 4000) {
                hideCursor();
                return;
            }
            mPadRepeat.postDelayed(this, 16);
        }
    };

    private void cursorStick(float x, float y) {
        final float dead = 0.2f;
        mStickX = Math.abs(x) < dead ? 0 : (x - Math.signum(x) * dead) / (1 - dead);
        mStickY = Math.abs(y) < dead ? 0 : (y - Math.signum(y) * dead) / (1 - dead);
        if ((mStickX != 0 || mStickY != 0) && !mCursorShown) showCursor();
    }

    private void showCursor() {
        android.view.ViewGroup root = (android.view.ViewGroup) getWindow().getDecorView();
        if (mCursor == null) {
            final float r = getResources().getDisplayMetrics().density * 14;
            mCursor = new View(this) {
                final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                {
                    fill.setColor(0x73FFFFFF);
                    ring.setColor(0x99000000);
                    ring.setStyle(android.graphics.Paint.Style.STROKE);
                    ring.setStrokeWidth(r / 6);
                }
                @Override protected void onDraw(android.graphics.Canvas c) {
                    float h = getWidth() / 2f;
                    c.drawCircle(h, h, r, fill);
                    c.drawCircle(h, h, r, ring);
                }
            };
            mCursor.setFocusable(false);
            mCursor.setClickable(false);
            mCursor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int size = Math.round(r * 2 + r / 3);
            root.addView(mCursor, new android.widget.FrameLayout.LayoutParams(size, size));
            mCursor.setElevation(1000);
        }
        if (mCursorX < 0) {  // first time: the middle of the screen
            mCursorX = root.getWidth() / 2f;
            mCursorY = root.getHeight() / 2f;
        }
        mCursor.bringToFront();
        mCursor.setVisibility(View.VISIBLE);
        mCursorShown = true;
        placeCursor();
        mCursorFrame = mCursorLastUse = android.os.SystemClock.uptimeMillis();
        mPadRepeat.removeCallbacks(mCursorStep);
        mPadRepeat.post(mCursorStep);
    }

    private void placeCursor() {
        if (mCursor == null) return;
        mCursor.setTranslationX(mCursorX - mCursor.getWidth() / 2f);
        mCursor.setTranslationY(mCursorY - mCursor.getHeight() / 2f);
    }

    private void hideCursor() {
        if (mCursorDown) cursorTouch(android.view.MotionEvent.ACTION_CANCEL);
        mCursorShown = false;
        mPadRepeat.removeCallbacks(mCursorStep);
        if (mCursor != null) mCursor.setVisibility(View.GONE);
    }

    // a finger at the pointer: down, moved, up (or cancelled)
    private void cursorTouch(int action) {
        long now = android.os.SystemClock.uptimeMillis();
        if (action == android.view.MotionEvent.ACTION_DOWN) mCursorDownTime = now;
        android.view.MotionEvent e = android.view.MotionEvent.obtain(mCursorDownTime, now, action, mCursorX, mCursorY, 0);
        e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        mCursorDown = action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE;
        mCursorLastUse = now;
        super.dispatchTouchEvent(e);
        e.recycle();
    }

    // the entry A is held on, until it is let go or held long enough
    private View mPadHeld;
    private final Runnable mPadHold = () -> {
        View held = mPadHeld;
        mPadHeld = null;
        if (held != null && !held.performLongClick() && held.isClickable()) held.performClick();  // nothing on a hold: a tap
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (!usesPadNav()) return super.dispatchKeyEvent(ev);
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN, first = down && ev.getRepeatCount() == 0;
        boolean shoulder = code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1
            || code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2;
        if (shoulder) {
            int dir = code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_L2 ? -1 : 1;
            if (first) onPadTab(dir, true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (!down) return super.dispatchKeyEvent(ev);
            if (onPadTab(dir, false)) return true;
            return super.dispatchKeyEvent(ev);
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A && (mCursorShown || mCursorDown)) {  // a touch where the pointer is
            if (first) cursorTouch(android.view.MotionEvent.ACTION_DOWN);
            else if (!down && mCursorDown) cursorTouch(android.view.MotionEvent.ACTION_UP);
            return true;
        }
        if (down && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) hideCursor();
        if (code == KeyEvent.KEYCODE_BUTTON_A) {  // presses the entry; held, as a long press does
            View f = getCurrentFocus();
            if (!down) {
                if (mPadHeld != null) {
                    mPadRepeat.removeCallbacks(mPadHold);
                    View held = mPadHeld;
                    mPadHeld = null;
                    if (held.isClickable()) held.performClick();
                }
                return true;
            }
            if (first && f != null && onPadA(f)) return true;
            if (first && f != null && f.isLongClickable()) {
                mPadHeld = f;  // a tap on release, the long press after a while
                mPadRepeat.postDelayed(mPadHold, android.view.ViewConfiguration.getLongPressTimeout());
            } else if (first && f != null && f.isClickable()) f.performClick();
            else if (first) onPadMove(1);  // nothing chosen yet: the first entry
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B) {
            if (first) onBackPressed();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down && onPadMove(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)) return true;
            if (!down) return true;
        }
        return super.dispatchKeyEvent(ev);
    }
}
