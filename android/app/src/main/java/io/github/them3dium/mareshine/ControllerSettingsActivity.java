package io.github.them3dium.mareshine;

import android.app.Activity;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.them3dium.mareshine.L.t;

// Controller settings: which game controller to use, the button mapping and
// when the touch controls show. Saved to settings.txt in the data folder as
// SMS_PAD_DEVICE, SMS_PAD_MAP and SMS_TOUCH_CONTROLS (read by platform/pad
// and platform/gx). Codes are SDL game controller inputs: bN a button, aN the
// positive half of an axis (a4/a5 the triggers, a0/a2 the left/right stick).
public class ControllerSettingsActivity extends AppActivity implements InputManager.InputDeviceListener {

    private static final String[][] INPUTS = {
        // key in SMS_PAD_MAP, label
        {"A", "A  (jump / confirm)"},
        {"B", "B  (crouch / back)"},
        {"X", "X  (switch nozzle)"},
        {"Y", "Y  (first person)"},
        {"Z", "Z  (map / guide)"},
        {"L", "L  (camera reset)"},
        {"R", "R  (spray, full press)"},
        {"R_SOFT", "R half-press  (spray while running)"},
        {"START", "START  (pause)"},
        {"STICK", "Left stick  (Mario)"},
        {"CSTICK", "C-stick  (camera)"},
        {"STICK_X", "Left stick: horizontal (X)"},
        {"STICK_Y", "Left stick: vertical (Y)"},
        {"CSTICK_X", "C-stick: horizontal (X)"},
        {"CSTICK_Y", "C-stick: vertical (Y)"},
        {"DPAD_UP", "D-pad up"},
        {"DPAD_DOWN", "D-pad down"},
        {"DPAD_LEFT", "D-pad left"},
        {"DPAD_RIGHT", "D-pad right"},
        {"HOTKEY", "Hotkey  (hold it for the two below)"},
        {"SAVE_STATE", "Hotkey + this: quick save"},
        {"LOAD_STATE", "Hotkey + this: quick load"},
        {"MENU", "Quick menu  (tap it; may be the hotkey)"},
    };
    // the sections of the screen: title, an optional note, the inputs
    private static final String[][] GROUPS = {
        {"Buttons", null, "A", "B", "X", "Y", "Z", "L", "R", "R_SOFT", "START"},
        {"D-pad", null, "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"},
        {"Sticks", "Set a stick's axes by hand only if it moves wrongly on Automatic.",
            "STICK_X", "STICK_Y", "CSTICK_X", "CSTICK_Y"},
        {"System buttons", "Please set these up for your controller: the quick menu and the save states. "
            + "Red ones are still on their default and may not suit your controller.",
            "HOTKEY", "SAVE_STATE", "LOAD_STATE", "MENU"},
    };
    // the port's own buttons, in a section of their own: shown red until the
    // player has bound them (SYSTEM_SET lists the ones they have)
    private static final java.util.Set<String> SYSTEM = new java.util.HashSet<>(
        java.util.Arrays.asList("HOTKEY", "SAVE_STATE", "LOAD_STATE", "MENU"));
    // a stick's axis set by hand (jN / -jN, platform/pad/pad.cpp); left out: automatic
    private static final java.util.Set<String> AXES = new java.util.HashSet<>(
        java.util.Arrays.asList("STICK_X", "STICK_Y", "CSTICK_X", "CSTICK_Y"));
    private static final String SYSTEM_SET = "SMS_PAD_SYSTEM_SET";
    private final java.util.Set<String> mSystemSet = new java.util.HashSet<>();

    private static final String DEFAULT_MAP =
        "A=b0 B=b1 X=b2 Y=b3 Z=b9 L=a4 R=a5 R_SOFT=b10 START=b6 "
        + "DPAD_UP=b11 DPAD_DOWN=b12 DPAD_LEFT=b13 DPAD_RIGHT=b14 STICK=a0 CSTICK=a2 "
        + "HOTKEY=b4 SAVE_STATE=b10 LOAD_STATE=b9 MENU=b4";

    private static final String[] TOUCH_OPTIONS = {
        "Auto (hide while a controller is connected)", "Always show", "Always hide"
    };
    private static final String[] TOUCH_VALUES = {"auto", "on", "off"};

    private final Map<String, String> mSettings = new LinkedHashMap<>();
    private final Map<String, String> mMap = new LinkedHashMap<>();
    private final Map<String, View> mRows = new LinkedHashMap<>();
    private final Map<String, TextView> mValues = new LinkedHashMap<>();
    private final List<String> mDeviceNames = new ArrayList<>();

    private FrameLayout overlay;
    private TextView overlayText;

    private final ControllerStickCapture mStickCapture = new ControllerStickCapture();
    private String mCapturing = null; // the input waiting for a button press
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCaptureTimeout = () -> stopCapture("Timed out");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        loadSettings();
        buildUi();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    // --- settings.txt -------------------------------------------------------

    private void loadSettings() {
        File f = DataDir.settingsFile(this);
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) mSettings.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            } catch (IOException ignored) {}
        }
        parseMap(DEFAULT_MAP);
        String set = mSettings.get(SYSTEM_SET);
        if (set != null)
            for (String k : set.split(",")) if (!k.trim().isEmpty()) mSystemSet.add(k.trim());
        String m = mSettings.get("SMS_PAD_MAP");
        if (m != null) {
            parseMap(m);
            // a mapping from before the hotkey: Select was a second Z; it is
            // the hotkey now (the game does not see the hotkey's button)
            if (!m.toUpperCase().contains("HOTKEY=") && mMap.containsKey("Z")) {
                String z = mMap.get("Z").replaceAll("(^|,)b4(?=,|$)", "").replaceAll("^,|,$", "");
                mMap.put("Z", z.isEmpty() ? "b9" : z);
            }
        }
    }

    private void parseMap(String text) {
        for (String tok : text.trim().split("[\\s;]+")) {
            int eq = tok.indexOf('=');
            if (eq > 0) mMap.put(tok.substring(0, eq).toUpperCase(), tok.substring(eq + 1));
        }
    }

    private String mapString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : mMap.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void saveSettings() {
        mSettings.put("SMS_PAD_MAP", mapString());
        mSettings.put(SYSTEM_SET, String.join(",", new java.util.TreeSet<>(mSystemSet)));

        DataDir.ensureLayout(this);
        File f = DataDir.settingsFile(this);
        try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
            pw.println("# Super Mario Sunshine settings generated by Android Launcher");
            for (Map.Entry<String, String> e : mSettings.entrySet()) {
                pw.println(e.getKey() + " = " + e.getValue());
            }
            Toast.makeText(this, t("Controller settings saved."), Toast.LENGTH_SHORT).show();
            finish();
        } catch (IOException e) {
            Toast.makeText(this, t("Error saving settings.txt: %s", e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // --- devices ------------------------------------------------------------

    private static boolean isGamepad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override public void onInputDeviceAdded(int deviceId) {}
    @Override public void onInputDeviceRemoved(int deviceId) {}
    @Override public void onInputDeviceChanged(int deviceId) {}

    // --- button capture -----------------------------------------------------

    private void startCapture(String input, String label) {
        mCapturing = input;
        mStickCapture.reset();
        if (AXES.contains(input)) {
            overlayText.setText(t(input.endsWith("_X")
                ? "Push the stick for\n\n%s\n\nall the way to the RIGHT\n\n(tap here to cancel, long-press a row to set it back to automatic)"
                : "Push the stick for\n\n%s\n\nall the way DOWN\n\n(tap here to cancel, long-press a row to set it back to automatic)",
                t(label)));
        } else if (capturingStick()) {
            overlayText.setText(t("Release both sticks, then move the stick to use for\n\n%s\n\n(tap here to cancel)", t(label)));
        } else {
            overlayText.setText(t("Press a button on your controller for\n\n%s"
                + "\n\n(tap here to cancel, long-press a row to unbind it)", t(label)));
        }
        overlay.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mCaptureTimeout);
        mHandler.postDelayed(mCaptureTimeout, 10000);
    }

    private boolean capturingStick() {
        return "STICK".equals(mCapturing) || "CSTICK".equals(mCapturing) || AXES.contains(mCapturing);
    }

    // The raw axis pushed furthest (SDL's joystick axis number: the device's
    // joystick ranges without the hat, sorted as SDLControllerManager does),
    // "-" when it runs the other way than right / down; null until one is
    // pushed far enough. Only centred axes count (not the triggers).
    private static String pushedAxis(MotionEvent ev) {
        InputDevice d = ev.getDevice();
        if (d == null) return null;
        List<InputDevice.MotionRange> ranges = new ArrayList<>(d.getMotionRanges());
        java.util.Collections.sort(ranges, (a, b) -> sdlOrder(a.getAxis()) - sdlOrder(b.getAxis()));
        int n = 0, best = -1;
        float bestV = 0.7f, sign = 1;
        for (InputDevice.MotionRange r : ranges) {
            if ((r.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) continue;
            int axis = r.getAxis();
            if (axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y) continue;
            float v = ev.getAxisValue(axis);
            if (r.getMin() < 0 && Math.abs(v) > bestV) {
                bestV = Math.abs(v);
                best = n;
                sign = Math.signum(v);
            }
            n++;
        }
        if (best < 0) return null;
        return (sign < 0 ? "-j" : "j") + best;
    }

    // SDLControllerManager.RangeComparator: GAS and BRAKE swapped, Z between RY and RZ
    private static int sdlOrder(int axis) {
        if (axis == MotionEvent.AXIS_GAS) axis = MotionEvent.AXIS_BRAKE;
        else if (axis == MotionEvent.AXIS_BRAKE) axis = MotionEvent.AXIS_GAS;
        if (axis == MotionEvent.AXIS_Z) return MotionEvent.AXIS_RZ - 1;
        if (axis > MotionEvent.AXIS_Z && axis < MotionEvent.AXIS_RZ) return axis - 1;
        return axis;
    }

    private void stopCapture(String toast) {
        mCapturing = null;
        overlay.setVisibility(View.GONE);
        mHandler.removeCallbacks(mCaptureTimeout);
        if (toast != null) Toast.makeText(this, t(toast), Toast.LENGTH_SHORT).show();
    }

    private void assign(String code) {
        String input = mCapturing;
        stopCapture(null);
        if (input == null) return;
        mMap.put(input, code);
        if (SYSTEM.contains(input)) mSystemSet.add(input);
        updateRows();
    }

    private static String keyToCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "b0";
            case KeyEvent.KEYCODE_BUTTON_B: return "b1";
            case KeyEvent.KEYCODE_BUTTON_X: return "b2";
            case KeyEvent.KEYCODE_BUTTON_Y: return "b3";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "b4";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "b5";
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_START: return "b6";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "b7";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "b8";
            case KeyEvent.KEYCODE_BUTTON_L1: return "b9";
            case KeyEvent.KEYCODE_BUTTON_R1: return "b10";
            case KeyEvent.KEYCODE_DPAD_UP: return "b11";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "b12";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "b13";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "b14";
            case KeyEvent.KEYCODE_BUTTON_L2: return "a4";
            case KeyEvent.KEYCODE_BUTTON_R2: return "a5";
            default: return null;
        }
    }

    private static String codeLabel(String code) {
        switch (code) {
            case "b0": return "A / Cross";
            case "b1": return "B / Circle";
            case "b2": return "X / Square";
            case "b3": return "Y / Triangle";
            case "b4": return "Select / Back";
            case "b5": return "Home";
            case "b6": return "Start";
            case "b7": return "L3 (left stick click)";
            case "b8": return "R3 (right stick click)";
            case "b9": return "L1";
            case "b10": return "R1";
            case "b11": return "D-pad up";
            case "b12": return "D-pad down";
            case "b13": return "D-pad left";
            case "b14": return "D-pad right";
            case "a0": return "Left stick";
            case "a2": return "Right stick";
            case "a4": return "L2 (trigger)";
            case "a5": return "R2 (trigger)";
            default: return code;
        }
    }

    private static String mappingLabel(String value) {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("none")) return t("— not bound —");
        StringBuilder sb = new StringBuilder();
        for (String c : value.split(",")) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(t(codeLabel(c.trim())));
        }
        return sb.toString();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mCapturing != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (capturingStick()) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)
                        stopCapture(null);
                    return true;
                }
                String code = keyToCode(event.getKeyCode());
                if (code != null) assign(code);
                else Toast.makeText(this, t("This button cannot be used, try another one."), Toast.LENGTH_SHORT).show();
            }
            return true; // swallow everything (B / Back must not close the screen)
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (mCapturing != null && (ev.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            if (AXES.contains(mCapturing)) {
                if (ev.getAction() != MotionEvent.ACTION_MOVE) return true;
                String code = pushedAxis(ev);
                if (code != null) assign(code);
                return true;
            }
            if (capturingStick()) {
                if (ev.getAction() != MotionEvent.ACTION_MOVE) return true;
                InputDevice device = ev.getDevice();
                boolean hasZ = device != null && device.getMotionRange(MotionEvent.AXIS_Z) != null
                    && device.getMotionRange(MotionEvent.AXIS_RZ) != null;
                String code = mStickCapture.update(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y),
                    ev.getAxisValue(hasZ ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX),
                    ev.getAxisValue(hasZ ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY));
                if (code != null) assign(code);
                return true;
            }
            float lt = Math.max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS));
            float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (lt > 0.6f) assign("a4");
            else if (rt > 0.6f) assign("a5");
            else if (hy < -0.5f) assign("b11");
            else if (hy > 0.5f) assign("b12");
            else if (hx < -0.5f) assign("b13");
            else if (hx > 0.5f) assign("b14");
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // --- UI -----------------------------------------------------------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(t(text));
        t.setTextColor(0xFF5ED3DD);
        t.setTextSize(20);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setAllCaps(true);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView hint(String text) {
        TextView h = new TextView(this);
        h.setText(t(text));
        h.setTextColor(0xFF8FB4D4);
        h.setTextSize(16);
        h.setLineSpacing(0, 1.15f);
        h.setPadding(0, 0, 0, dp(4));
        return h;
    }

    // Rows and buttons in the launcher's (Dusklight) style: rounded, a gold
    // border when focused; color 0 means a plain row.
    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(t(text));
        b.setAllCaps(false);
        b.setTextColor(0xFFF4F8FF);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        b.setTextSize(15);
        b.setStateListAnimator(null);
        b.setBackgroundResource(R.drawable.dl_row);
        if (color == 0xFF2E7D32) { // the main action: gold
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFB020));
            b.setTextColor(0xFF0A1B30);
        }
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        return b;
    }

    private void styleSpinner(Spinner sp) {
        sp.setBackgroundResource(R.drawable.dl_row);
        sp.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xF20D2540));
        sp.setPadding(dp(8), dp(4), dp(8), dp(4));
    }

    private void buildUi() {
        // the app's panel (as Achievements): title and ✕ on top, the rows
        // scroll, Save / Cancel stay below them
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.dl_scrim_strong);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(28), dp(14), dp(28), dp(14));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(dp(16), dp(12), dp(16), dp(12));
        root.addView(panel, plp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, dp(6), dp(8));
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText(t("CONFIGURE BUTTONS"));
        title.setTextColor(0xFFFFC93C);
        title.setTextSize(24);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        // ✕ leaves without saving, like Cancel (for when the screen was opened by mistake)
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton());
        panel.addView(head);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        col.addView(hint("Tap a row, then press a button or move a stick on your controller. Long-press a row to unbind it."));
        for (String[] group : GROUPS) {
            col.addView(heading(group[0]));
            if (group[1] != null) col.addView(hint(group[1]));
            for (int g = 2; g < group.length; g++) {
                final String key = group[g];
                String label = null;
                for (String[] in : INPUTS) if (in[0].equals(key)) label = in[1];
                final String name = label;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.dl_row);
                row.setPadding(dp(18), dp(12), dp(18), dp(12));
                row.setFocusable(true);
                row.setClickable(true);
                TextView l = new TextView(this);
                l.setText(t(name));
                l.setTextColor(0xFFF4F8FF);
                l.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                l.setTextSize(18);
                row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
                TextView v = new TextView(this);
                v.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                v.setTextSize(18);
                v.setGravity(Gravity.END);
                row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setOnClickListener(x -> startCapture(key, name));
                row.setOnLongClickListener(x -> {
                    if (AXES.contains(key)) {  // back to automatic
                        mMap.remove(key);
                        updateRows();
                        return true;
                    }
                    mMap.put(key, "none");
                    if (SYSTEM.contains(key)) mSystemSet.add(key);
                    updateRows();
                    return true;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                col.addView(row, lp);
                mRows.put(key, row);
                mValues.put(key, v);
            }
        }
        updateRows();

        TextView exitNote = hint("Select + Start together and the Home button open the quick menu too.");
        exitNote.setPadding(0, dp(12), 0, 0);
        col.addView(exitNote);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, 0);
        Button reset = button("Reset to defaults", 0xFF455A64);
        Button cancel = button("Cancel", 0xFF455A64);
        Button save = button("💾 Save", 0xFF2E7D32);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wp.setMargins(dp(4), 0, dp(4), 0);
        buttons.addView(reset, wp);
        buttons.addView(cancel, new LinearLayout.LayoutParams(wp));
        buttons.addView(save, new LinearLayout.LayoutParams(wp));
        panel.addView(buttons);
        reset.setOnClickListener(v -> {
            mMap.clear();
            mSystemSet.clear();
            parseMap(DEFAULT_MAP);
            mMap.put("STICK", "a0");
            mMap.put("CSTICK", "a2");
            updateRows();
        });
        cancel.setOnClickListener(v -> finish());
        save.setOnClickListener(v -> saveSettings());

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE6000000);
        overlay.setFocusable(true);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> stopCapture(null));
        overlayText = new TextView(this);
        overlayText.setTextColor(0xFFF4F8FF);
        overlayText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        overlayText.setTextSize(20);
        overlayText.setGravity(Gravity.CENTER);
        overlay.addView(overlayText, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void updateRows() {
        for (String[] in : INPUTS) {
            View row = mRows.get(in[0]);
            TextView val = mValues.get(in[0]);
            if (row == null) continue;
            String v = mMap.get(in[0]);
            String shown = !AXES.contains(in[0]) ? mappingLabel(v)
                : v == null ? t("Automatic")
                : v.startsWith("-") ? t("Axis %s (reversed)", v.substring(2)) : t("Axis %s", v.substring(1));
            val.setText(shown);
            boolean unset = SYSTEM.contains(in[0]) && !mSystemSet.contains(in[0]);
            val.setTextColor(unset ? 0xFFFFE0E0 : 0xFFFFC93C);
            row.setBackgroundTintList(unset ? android.content.res.ColorStateList.valueOf(0xFFB03A3A) : null);
        }
    }
}
