package io.github.them3dium.mareshine;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.them3dium.mareshine.L.t;

// Sync with a device in the same Wi-Fi (WifiSync), from Settings → OTHER →
// SYNC. While this screen shows, this device can be found by others, with
// its code; below are the other devices that show this screen, to pick one
// and enter its code. Only from the main menu (never while the game runs).
public class WifiSyncActivity extends AppActivity {
    private WifiSync.Host mHost;
    private WifiSync.Search mSearch;
    private TextView mOwn;
    private LinearLayout mList;
    private TextView mEmpty;
    private final Map<String, String[]> mFound = new LinkedHashMap<>(); // name -> host, port

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1B30);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(24), dp(16), dp(24), dp(16));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.CENTER;
        plp.topMargin = plp.bottomMargin = dp(16);
        root.addView(panel, plp);

        TextView title = new TextView(this);
        title.setText(t("WI-FI SYNC"));
        title.setTextColor(0xFFFFC93C);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(24);
        panel.addView(title);
        TextView how = new TextView(this);
        how.setText(t("Open this screen on both devices, in the same Wi-Fi. On one of them, choose the other "
            + "device and enter the code it shows. Both then have the same saves, save states and achievements."));
        how.setTextColor(getColor(R.color.dl_text));
        how.setTextSize(14);
        how.setPadding(0, dp(4), 0, dp(10));
        panel.addView(how);

        panel.addView(heading(t("THIS DEVICE")));
        mOwn = new TextView(this);
        mOwn.setBackgroundResource(R.drawable.dl_row);
        mOwn.setPadding(dp(14), dp(10), dp(14), dp(10));
        mOwn.setTextColor(0xFFFFFFFF);
        mOwn.setTextSize(16);
        mOwn.setText(t("Getting ready…"));
        panel.addView(mOwn);

        panel.addView(heading(t("OTHER DEVICES")));
        ScrollView scroll = new ScrollView(this);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mList);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mEmpty = new TextView(this);
        mEmpty.setText(t("Searching… Open this screen on the other device too."));
        mEmpty.setTextColor(getColor(R.color.dl_dim));
        mEmpty.setTextSize(14);
        mEmpty.setPadding(0, dp(6), 0, dp(6));
        mList.addView(mEmpty);

        TextView manual = row(t("Enter an address instead"), null);
        manual.setOnClickListener(v -> askAddress());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(8);
        panel.addView(manual, mlp);

        setContentView(root);
    }

    private TextView heading(String s) {
        TextView h = new TextView(this);
        h.setText(s);
        h.setTextColor(0xFF5ED3DD);
        h.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        h.setTextSize(17);
        h.setPadding(0, dp(10), 0, dp(6));
        return h;
    }

    private TextView row(String text, String sub) {
        TextView r = new TextView(this);
        r.setBackgroundResource(R.drawable.dl_row);
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        r.setTextColor(0xFFFFFFFF);
        r.setTextSize(16);
        r.setText(sub == null ? text : text + "\n" + sub);
        r.setFocusable(true);
        r.setClickable(true);
        return r;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHost = new WifiSync.Host(this, new WifiSync.HostListener() {
            @Override public void ready(String code, int port) {
                String ip = WifiSync.localAddress();
                mOwn.setText(t("Visible as %s", WifiSync.deviceName()) + "\n" + t("Code: %s", code)
                    + (ip != null ? "\n" + t("Address: %s", ip + ":" + port) : ""));
            }
            @Override public void synced(String message, boolean changed) {
                Toast.makeText(WifiSyncActivity.this, message, Toast.LENGTH_LONG).show();
            }
            @Override public void failed(String message) {
                mOwn.setText(t("Cannot share this device: %s", message));
            }
        });
        mHost.start();
        mSearch = new WifiSync.Search(this, WifiSync.deviceName(), new WifiSync.Found() {
            @Override public void found(String name, String host, int port) {
                mFound.put(name, new String[] {host, Integer.toString(port)});
                showFound();
            }
            @Override public void lost(String name) {
                mFound.remove(name);
                showFound();
            }
        });
        mSearch.start();
    }

    @Override
    protected void onPause() {
        if (mSearch != null) mSearch.stop();
        if (mHost != null) mHost.stop();
        mSearch = null;
        mHost = null;
        mFound.clear();
        showFound();
        super.onPause();
    }

    private void showFound() {
        mList.removeAllViews();
        if (mFound.isEmpty()) {
            mList.addView(mEmpty);
            return;
        }
        for (Map.Entry<String, String[]> e : mFound.entrySet()) {
            final String name = e.getKey();
            final String host = e.getValue()[0];
            final int port = Integer.parseInt(e.getValue()[1]);
            TextView r = row(name, host);
            r.setOnClickListener(v -> askCode(name, host, port));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            mList.addView(r, lp);
        }
    }

    private void askCode(String name, String host, int port) {
        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setHint("123456");
        new DlDialog(this)
            .setTitle(t("Sync with %s", name))
            .setMessage(t("Enter the code %s shows.", name))
            .setView(code)
            .setPositiveButton(t("Sync"), (d, w) -> syncWith(host, port, code.getText().toString().trim()))
            .setNegativeButton(t("Cancel"), null)
            .show();
    }

    private void askAddress() {
        EditText addr = new EditText(this);
        addr.setHint("192.168.1.20:41234");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new DlDialog(this)
            .setTitle(t("Enter an address instead"))
            .setMessage(t("The address the other device shows under This device."))
            .setView(addr)
            .setPositiveButton(t("Next"), (d, w) -> {
                String a = addr.getText().toString().trim();
                int colon = a.lastIndexOf(':');
                try {
                    String h = a.substring(0, colon);
                    int p = Integer.parseInt(a.substring(colon + 1));
                    askCode(h, h, p);
                } catch (Exception e) {
                    Toast.makeText(this, t("That is not an address like 192.168.1.20:41234."), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(t("Cancel"), null)
            .show();
    }

    private void syncWith(String host, int port, String code) {
        if (code.isEmpty()) return;
        Toast.makeText(this, t("Syncing…"), Toast.LENGTH_SHORT).show();
        Sync.run(this, new WifiSync.Peer(host, port, code), (msg, changed) -> {
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }
}
