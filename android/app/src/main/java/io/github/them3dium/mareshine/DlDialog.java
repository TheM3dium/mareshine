package io.github.them3dium.mareshine;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

// The app's dialog: AlertDialog.Builder's calls (title, message, a list,
// up to three buttons, a view of its own), drawn as the app's panels (dl_panel,
// a gold title, entries that light up when chosen) instead of the system's
// grey dialog. The D-pad or stick moves between the entries, A presses one,
// B (Back) cancels it where it may be cancelled.
final class DlDialog {
    private final Context mContext;
    private CharSequence mTitle, mMessage;
    private CharSequence[] mItems;
    private DialogInterface.OnClickListener mItemClick;
    private final CharSequence[] mButtons = new CharSequence[3];  // negative, neutral, positive
    private final DialogInterface.OnClickListener[] mButtonClicks = new DialogInterface.OnClickListener[3];
    private View mView;
    private boolean mCancelable = true;

    DlDialog(Context c) { mContext = c; }

    DlDialog setTitle(CharSequence t) { mTitle = t; return this; }
    DlDialog setMessage(CharSequence m) { mMessage = m; return this; }
    DlDialog setView(View v) { mView = v; return this; }
    DlDialog setCancelable(boolean c) { mCancelable = c; return this; }

    DlDialog setItems(CharSequence[] items, DialogInterface.OnClickListener l) {
        mItems = items;
        mItemClick = l;
        return this;
    }

    DlDialog setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) { return button(0, text, l); }
    DlDialog setNeutralButton(CharSequence text, DialogInterface.OnClickListener l) { return button(1, text, l); }
    DlDialog setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) { return button(2, text, l); }

    private DlDialog button(int i, CharSequence text, DialogInterface.OnClickListener l) {
        mButtons[i] = text;
        mButtonClicks[i] = l;
        return this;
    }

    Dialog create() {
        final float d = mContext.getResources().getDisplayMetrics().density;
        final Dialog dlg = new Dialog(mContext);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(mCancelable);
        dlg.setCanceledOnTouchOutside(mCancelable);

        LinearLayout panel = new LinearLayout(mContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        int pad = Math.round(20 * d);
        panel.setPadding(pad, Math.round(14 * d), pad, Math.round(12 * d));

        if (mTitle != null) {
            TextView head = new TextView(mContext);
            head.setText(mTitle);
            head.setTextColor(0xFFFFC93C);
            head.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            head.setTextSize(22);
            panel.addView(head);
        }

        // message, list and own view scroll; the buttons stay below them
        LinearLayout body = new LinearLayout(mContext);
        body.setOrientation(LinearLayout.VERTICAL);
        if (mMessage != null) {
            TextView msg = new TextView(mContext);
            msg.setText(mMessage);
            msg.setTextColor(mContext.getColor(R.color.dl_text));
            msg.setTextSize(16);
            msg.setLineSpacing(0, 1.15f);
            msg.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
            body.addView(msg);
        }
        View firstEntry = null;
        if (mItems != null) {
            for (int i = 0; i < mItems.length; i++) {
                final int which = i;
                TextView item = entry(mItems[i], true, d);
                item.setOnClickListener(v -> {
                    dlg.dismiss();
                    if (mItemClick != null) mItemClick.onClick(dlg, which);
                });
                body.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                if (firstEntry == null) firstEntry = item;
            }
        }
        if (mView != null) {
            if (mView.getParent() instanceof ViewGroup) ((ViewGroup) mView.getParent()).removeView(mView);
            body.addView(mView);
        }
        ScrollView scroll = new ScrollView(mContext);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Cancel (negative), then the others, the main action last (rightmost)
        LinearLayout buttons = new LinearLayout(mContext);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        View main = null;
        for (int i = 0; i < 3; i++) {
            if (mButtons[i] == null) continue;
            final DialogInterface.OnClickListener l = mButtonClicks[i];
            final int which = i == 0 ? DialogInterface.BUTTON_NEGATIVE
                : i == 1 ? DialogInterface.BUTTON_NEUTRAL : DialogInterface.BUTTON_POSITIVE;
            TextView b = entry(mButtons[i], i == 2, d);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> {
                dlg.dismiss();
                if (l != null) l.onClick(dlg, which);
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = Math.round(8 * d);
            buttons.addView(b, blp);
            main = b;
        }
        if (buttons.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(8 * d);
            panel.addView(buttons, lp);
        }

        dlg.setContentView(panel);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            int width = Math.min(Math.round(560 * d),
                Math.round(mContext.getResources().getDisplayMetrics().widthPixels * 0.9f));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        final View focus = firstEntry != null ? firstEntry : main;
        dlg.setOnShowListener(x -> {
            if (focus != null && mContext instanceof Activity && ((Activity) mContext).getCurrentFocus() != null)
                focus.requestFocus();  // reached with a controller: an entry is chosen
        });
        return dlg;
    }

    Dialog show() {
        Dialog dlg = create();
        Window w = dlg.getWindow();
        // over the app's immersive screens: keep the system bars hidden
        if (w != null) w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dlg.show();
        if (w != null) {
            if (mContext instanceof Activity)
                w.getDecorView().setSystemUiVisibility(((Activity) mContext).getWindow().getDecorView().getSystemUiVisibility());
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        return dlg;
    }

    private TextView entry(CharSequence text, boolean strong, float d) {
        TextView t = new TextView(mContext);
        t.setText(text);
        t.setTextColor(strong ? 0xFFFFFFFF : mContext.getColor(R.color.dl_dim));
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setTextSize(18);
        t.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(14 * d), Math.round(10 * d));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundResource(R.drawable.dl_menu_item);
        return t;
    }
}
