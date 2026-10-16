package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

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
}
