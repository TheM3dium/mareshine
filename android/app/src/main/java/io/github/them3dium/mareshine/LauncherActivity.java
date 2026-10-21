package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.Executors;

import static io.github.them3dium.mareshine.L.t;

public class LauncherActivity extends AppActivity {
    // Set by the in-game menu's "Restart game": start the game again at once.
    public static final String EXTRA_AUTOSTART = "autostart";

    private static final int REQUEST_PICK_ISO = 1001;
    private static final int REQUEST_PICK_DATA_DIR = 1002;
    private static final int REQUEST_ALL_FILES = 1003;
    private static final int REQUEST_SETTINGS = 1004;
    // Set by the in-game menu's "Quit": show this menu, do not start the game again.
    public static final String EXTRA_FROM_GAME = "from_game";

    private TextView tvStatus;
    private TextView tvPath;
    private TextView tvProgress;
    private ProgressBar progressBar;
    private Button btnPlay;
    private Button btnSettings;
    private TextView tvDiscInfo;
    private View panelInfo;
    private TextView tvDataDir;

    private File mFoundIsoFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        L.init(this);
        mLang = L.current(this);
        setContentView(R.layout.activity_launcher);
        L.translate(findViewById(android.R.id.content));

        tvStatus = findViewById(R.id.tvStatus);
        tvPath = findViewById(R.id.tvPath);
        tvProgress = findViewById(R.id.tvProgress);
        progressBar = findViewById(R.id.progressBar);
        btnPlay = findViewById(R.id.btnPlay);
        btnSettings = findViewById(R.id.btnSettings);
        tvDataDir = findViewById(R.id.tvDataDir);
        tvDiscInfo = findViewById(R.id.tvDiscInfo);
        panelInfo = findViewById(R.id.panelInfo);
        if (AppActivity.isTouch(this)) {
            // held close: the message panel's text a little larger, the panel wider
            tvPath.setTextSize(16);
            tvDataDir.setTextSize(14);
            tvProgress.setTextSize(16);
            panelInfo.getLayoutParams().width = Math.round(580 * getResources().getDisplayMetrics().density);
        }
        // The logo sits in the middle on the left; while the message panel shows
        // and would reach it (a low screen), it moves up just clear of it.
        View logo = findViewById(R.id.ivLogo);
        View messages = (View) panelInfo.getParent();
        messages.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            float gap = 10 * getResources().getDisplayMetrics().density;
            float overlap = logo.getBottom() + gap - messages.getTop();
            float top = logo.getTop() - gap;  // never above the screen
            logo.setTranslationY(overlap > 0 ? -Math.min(overlap, Math.max(0, top)) : 0);
        });
        ((TextView) findViewById(R.id.tvVersion)).setText(t("Version %s", CrashLog.BuildConfigInfo.version(this)));
        findViewById(R.id.btnQuit).setOnClickListener(v -> { ShaderService.stop(this); finishAffinity(); });

        btnPlay.setOnClickListener(v -> launchGame());

        btnSettings.setOnClickListener(v ->
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS));

        checkIsoAndRefreshUi();
        boolean restarting = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTOSTART, false);
        handleAutostart(getIntent());
        // "Start game automatically" (Settings → GAME): straight into the game
        // when a working disc is set, except when coming back from it.
        boolean fromGame = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FROM_GAME, false);
        if (fromGame) syncAfterGame();
        if (savedInstanceState == null && !fromGame && mFoundIsoFile != null
                && getSharedPreferences("sunshine", MODE_PRIVATE).getBoolean(SettingsActivity.PREF_AUTOSTART, false)
                && DiscCheck.checkHeader(mFoundIsoFile).ok && !restarting) {
            tvStatus.post(this::launchGame);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_FROM_GAME, false)) syncAfterGame();
        handleAutostart(intent);
    }

    private void handleAutostart(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTOSTART, false)) return;
        intent.removeExtra(EXTRA_AUTOSTART);
        CrashLog.takeLastSessionProblem(this); // a restart is not a crash
        // the old game process needs a moment to go away
        tvStatus.postDelayed(() -> {
            checkIsoAndRefreshUi();
            launchGame();
        }, 600);
    }

    private void setInfoPanel(boolean show) {
        panelInfo.setVisibility(show || tvProgress.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    private boolean isDataDirProblem() {
        return DataDir.isCustom(this) && (!hasAllFilesAccess() || !DataDir.get(this).canWrite());
    }

    private String mLang;

    // the background shader compile (ShaderService): its progress at the bottom left
    private final BroadcastReceiver mShaderProgress = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int done = i.getIntExtra(ShaderService.EXTRA_DONE, 0), total = i.getIntExtra(ShaderService.EXTRA_TOTAL, 0);
            View panel = findViewById(R.id.panelShaders);
            if (total <= 0 || done >= total) { panel.setVisibility(View.GONE); return; }
            ((TextView) findViewById(R.id.tvShaders)).setText(t("Compiling shaders: %d of %d", done, total));
            panel.setVisibility(View.VISIBLE);
        }
    };

    // The "new update" box, once per start of the app: its bar runs down for
    // 10 s, then it fades out; focusing or touching it keeps it.
    private static boolean sBadgeShown;
    private android.animation.ObjectAnimator mBadgeTimer;

    private void showUpdateBadge(Updates.Release r) {
        if (sBadgeShown) return;
        sBadgeShown = true;
        View badge = findViewById(R.id.updateBadge);
        ((TextView) findViewById(R.id.updateBadgeText)).setText("⬆  " + t("New update: version %s", Updates.version(r)));
        ProgressBar bar = findViewById(R.id.updateBadgeBar);
        badge.setAlpha(1f);
        badge.setVisibility(View.VISIBLE);
        Runnable keep = () -> { if (mBadgeTimer != null) mBadgeTimer.cancel(); bar.setVisibility(View.GONE); };
        badge.setOnFocusChangeListener((v, has) -> { if (has) keep.run(); });
        badge.setOnClickListener(v -> {
            keep.run();
            Updates.ask(this, r, () -> badge.setVisibility(View.GONE));
        });
        mBadgeTimer = android.animation.ObjectAnimator.ofInt(bar, "progress", 1000, 0);
        mBadgeTimer.setDuration(10000);
        mBadgeTimer.setInterpolator(new android.view.animation.LinearInterpolator());
        mBadgeTimer.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled) badge.animate().alpha(0f).setDuration(400).withEndAction(() -> badge.setVisibility(View.GONE));
            }
        });
        mBadgeTimer.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(mShaderProgress); } catch (IllegalArgumentException ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        L.init(this);
        if (!L.current(this).equals(mLang)) { recreate(); return; } // the app language changed
        IntentFilter progress = new IntentFilter(ShaderService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mShaderProgress, progress, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(mShaderProgress, progress);
        ShaderService.start(this);
        Updates.autoCheck(this, this::showUpdateBadge);
        checkIsoAndRefreshUi();
        View first = btnPlay.getVisibility() == View.VISIBLE && btnPlay.isEnabled() ? btnPlay : btnSettings;
        if (getCurrentFocus() == null || getCurrentFocus().getVisibility() != View.VISIBLE) first.requestFocus();
        String problem = CrashLog.isEnabled(this) ? CrashLog.takeLastSessionProblem(this) : null;
        if (problem != null) {
            new DlDialog(this)
                .setTitle(t("⚠️ The game closed unexpectedly"))
                .setMessage(problem)
                .setPositiveButton(t("OK"), null)
                .show();
        }
    }

    // a European Super Mario Sunshine (GMSP01): only for building language packs
    static boolean isEuropeanDisc(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            byte[] id = new byte[6];
            r.readFully(id);
            return new String(id, java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("GMSP");
        } catch (IOException e) {
            return false;
        }
    }

    static File findIsoFile(android.content.Context ctx) {
        // 0. The data folder (root or iso/): GMSE01.iso, else any .iso/.gcm there
        File dataDir = DataDir.get(ctx);
        for (File dir : new File[] { DataDir.romDir(ctx), dataDir }) {
            File f = new File(dir, "GMSE01.iso");
            if (f.exists() && f.canRead() && f.length() > 10 * 1024 * 1024) return f;
            File[] files = dir.listFiles();
            if (files == null) continue;
            java.util.Arrays.sort(files);
            File any = null;
            for (File c : files) {
                String n = c.getName().toLowerCase(Locale.ROOT);
                if ((n.endsWith(".iso") || n.endsWith(".gcm") || n.endsWith(".ciso") || n.endsWith(".nkit.iso"))
                        && c.canRead() && c.length() > 10 * 1024 * 1024) {
                    // the game's own disc first: a European one may sit here for
                    // building language packs
                    if (DiscCheck.checkHeader(c).ok) return c;
                    if (any == null && !isEuropeanDisc(c)) any = c;
                }
            }
            if (any != null) return any;
        }

        // 1. External files directory (App private storage: Android/data/io.github.them3dium.mareshine/files/GMSE01.iso)
        File extFiles = ctx.getExternalFilesDir(null);
        if (extFiles != null) {
            File f = new File(extFiles, "GMSE01.iso");
            if (f.exists() && f.canRead() && f.length() > 10 * 1024 * 1024) return f;
            f = new File(extFiles, "disc.iso");
            if (f.exists() && f.canRead() && f.length() > 10 * 1024 * 1024) return f;
        }

        // 2. Internal files directory
        File intFiles = ctx.getFilesDir();
        if (intFiles != null) {
            File f = new File(intFiles, "GMSE01.iso");
            if (f.exists() && f.canRead() && f.length() > 10 * 1024 * 1024) return f;
        }

        // 3. Common public storage paths (if readable)
        String[] candidates = new String[] {
            "/sdcard/Download/GMSE01.iso",
            "/sdcard/Download/Super Mario Sunshine (USA).iso",
            "/sdcard/Download/Super Mario Sunshine.iso",
            "/sdcard/sms/GMSE01.iso",
            "/sdcard/sms/Super Mario Sunshine.iso",
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/GMSE01.iso",
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/Super Mario Sunshine.iso",
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/Super Mario Sunshine (USA).iso",
        };

        for (String path : candidates) {
            File f = new File(path);
            if (f.exists() && f.canRead() && f.length() > 10 * 1024 * 1024) {
                return f;
            }
        }

        return null;
    }

    private void refreshDataDirUi() {
        File dir = DataDir.get(this);
        String state = DataDir.isCustom(this) ? "" : t(" (default)");
        String warn = "";
        if (DataDir.isCustom(this) && !hasAllFilesAccess()) {
            warn = "\n⚠️ " + t("\"All files access\" permission missing");
        } else if (!dir.canWrite()) {
            warn = "\n⚠️ " + t("Folder is not writable");
        }
        tvDataDir.setText(t("Data folder") + state + ": " + dir.getAbsolutePath() + warn);
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQUEST_ALL_FILES);
        } catch (Exception e) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQUEST_ALL_FILES);
        }
    }

    private void showDataDirDialog() {
        new DlDialog(this)
            .setTitle(t("📁 Data folder"))
            .setMessage(t("Current:\n%s\n\nCreated automatically in the chosen folder:\n"
                + "• saves/  (save games)\n"
                + "• textures/  (texture packs, Dolphin format)\n"
                + "• iso/  (optional: GMSE01.iso)\n"
                + "• logs/  (game log and crash reports)\n"
                + "• settings.txt\n\n"
                + "Existing settings and saves are copied over; nothing is overwritten.", DataDir.get(this).getAbsolutePath()))
            .setPositiveButton(t("Choose folder"), (d, w) -> {
                if (!hasAllFilesAccess()) {
                    new DlDialog(this)
                        .setTitle(t("All files access"))
                        .setMessage(t("The game needs \"All files access\" to read and write textures and saves in the chosen folder. "
                            + "Please enable it on the next screen and come back."))
                        .setPositiveButton(t("OK"), (d2, w2) -> requestAllFilesAccess())
                        .setNegativeButton(t("Cancel"), null)
                        .show();
                } else {
                    openDataDirPicker();
                }
            })
            .setNeutralButton(t("Use default"), (d, w) -> {
                File old = DataDir.get(this);
                DataDir.reset(this);
                DataDir.ensureLayout(this);
                DataDir.migrate(old, DataDir.get(this));
                refreshDataDirUi();
                checkIsoAndRefreshUi();
            })
            .setNegativeButton(t("Cancel"), null)
            .show();
    }

    private void openDataDirPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_PICK_DATA_DIR);
    }

    private void applyDataDir(Uri treeUri) {
        File dir = DataDir.fromTreeUri(treeUri);
        if (dir == null) {
            Toast.makeText(this, t("This folder is not supported"), Toast.LENGTH_LONG).show();
            return;
        }
        File old = DataDir.get(this);
        DataDir.set(this, dir);
        if (!DataDir.ensureLayout(this)) {
            DataDir.set(this, old);
            new DlDialog(this)
                .setTitle(t("Error"))
                .setMessage(t("Cannot write to this folder:\n%s\n\nCheck \"All files access\" or choose another folder.",
                    dir.getAbsolutePath()))
                .setPositiveButton(t("OK"), null)
                .show();
            return;
        }
        DataDir.migrate(old, dir);
        Toast.makeText(this, t("Data folder:\n%s", dir.getAbsolutePath()), Toast.LENGTH_LONG).show();
        refreshDataDirUi();
        checkIsoAndRefreshUi();
    }

    private void checkIsoAndRefreshUi() {
        refreshDataDirUi();
        if (mCopying) return; // the copy shows its own progress; GMSE01.iso is about to change
        mFoundIsoFile = findIsoFile(this);
        if (mFoundIsoFile != null) {
            DiscCheck.Result check = DiscCheck.checkHeader(mFoundIsoFile);
            String sha1 = DiscCheck.cachedSha1(this, mFoundIsoFile);
            String hashLine;
            boolean quiet = false; // nothing to tell: keep the message panel out of the picture
            if (!check.ok) {
                tvStatus.setText(t("✕  Disc will not work."));
                tvStatus.setTextColor(0xFFF08A7A);
                hashLine = "";
            } else if (sha1 == null) {
                tvStatus.setText(t("✓  Disc ready."));
                tvStatus.setTextColor(0xFFBFD98A);
                hashLine = "\n" + (check.plainIso ? t("Hash: not checked yet (checked once when you press PLAY)")
                                                   : t("Hash: cannot be compared for this format (only plain 1:1 ISO dumps)"));
                quiet = !check.plainIso;
            } else if (sha1.equals(DiscCheck.REDUMP_SHA1)) {
                tvStatus.setText(t("✓  Disc ready (verified)."));
                tvStatus.setTextColor(0xFFBFD98A);
                hashLine = "\n" + t("Hash: ✓ matches the Redump dump (SHA-1 %s…)", sha1.substring(0, 12));
                quiet = true;
            } else {
                tvStatus.setText(t("⚠  Disc hash does not match."));
                tvStatus.setTextColor(0xFFFFC531);
                hashLine = "\n" + t("Hash: ⚠ %s… differs from the Redump dump (modified or bad dump)", sha1.substring(0, 12));
            }
            tvDiscInfo.setText(check.ok ? "GameCube • USA (GMSE01)" : "");
            setInfoPanel(!quiet || isDataDirProblem());
            double sizeGb = mFoundIsoFile.length() / (1024.0 * 1024.0 * 1024.0);
            tvPath.setText(t("File: %s (%.2f GB)\nPath: %s\n%s%s",
                mFoundIsoFile.getName(), sizeGb, mFoundIsoFile.getAbsolutePath(), check.summary, hashLine));
            btnPlay.setVisibility(View.VISIBLE);
            btnPlay.setEnabled(check.ok);
        } else {
            tvStatus.setText(t("⚠  No disc."));
            tvStatus.setTextColor(0xFFFFC531);
            tvDiscInfo.setText(t("Super Mario Sunshine • USA (GMSE01)"));
            tvPath.setText(t("No disc image of Super Mario Sunshine (USA, GMSE01) was found.\n\nOpen SETTINGS → GAME → Disc image to pick it on this device; it is copied into iso/ in your data folder."));
            setInfoPanel(true);
            btnPlay.setVisibility(View.GONE);
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_ISO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS) {
            String action = data != null ? data.getStringExtra(SettingsActivity.EXTRA_ACTION) : null;
            if (SettingsActivity.ACTION_PICK_ISO.equals(action)) openFilePicker();
            else if (SettingsActivity.ACTION_VERIFY_ISO.equals(action)) verifyIso(false);
            else if (SettingsActivity.ACTION_DATA_DIR.equals(action)) showDataDirDialog();
            return;
        }
        if (requestCode == REQUEST_ALL_FILES && mAllFilesForPlayOnly) {
            mAllFilesForPlayOnly = false;
            checkIsoAndRefreshUi();
            return;
        }
        if (requestCode == REQUEST_ALL_FILES) {
            if (hasAllFilesAccess()) {
                openDataDirPicker();
            } else {
                Toast.makeText(this, t("Without \"All files access\" the default folder is kept"), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_PICK_DATA_DIR && resultCode == RESULT_OK && data != null) {
            applyDataDir(data.getData());
            return;
        }
        if (requestCode == REQUEST_PICK_ISO && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                checkThenCopyIso(uri);
            }
        }
    }

    private boolean mCopying;

    private File isoDestDir() {
        return DataDir.isCustom(this) ? DataDir.romDir(this) : DataDir.defaultDir(this);
    }

    // Reads the start of the picked file first: an image the game cannot use
    // (RVZ/WIA/GCZ, another game or region) is refused before anything is
    // copied.
    private void checkThenCopyIso(Uri uri) {
        tvProgress.setVisibility(View.VISIBLE);
        panelInfo.setVisibility(View.VISIBLE);
        tvProgress.setText(t("Checking the selected file..."));
        Executors.newSingleThreadExecutor().execute(() -> {
            long size = -1;
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex != -1 && cursor.moveToFirst()) size = cursor.getLong(sizeIndex);
                }
            } catch (Exception ignored) {}
            DiscCheck.Result check;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                check = in == null ? null : DiscCheck.checkStream(in, size);
            } catch (Exception e) {
                check = null;
            }
            final DiscCheck.Result result = check;
            final long fileSize = size;
            File destDir = isoDestDir();
            destDir.mkdirs();
            // the old GMSE01.iso is replaced only after the copy, so both need room
            final long free = destDir.getUsableSpace();
            runOnUiThread(() -> {
                tvProgress.setVisibility(View.GONE);
                if (result == null) {
                    Toast.makeText(this, t("Could not open the selected file."), Toast.LENGTH_LONG).show();
                } else if (!result.ok) {
                    new DlDialog(this)
                        .setTitle(t("❌ This file will not work"))
                        .setMessage(result.summary + "\n\n" + t("Nothing was copied."))
                        .setPositiveButton(t("OK"), null)
                        .show();
                } else if (fileSize > 0 && free < fileSize + 64L * 1024 * 1024) {
                    new DlDialog(this)
                        .setTitle(t("❌ Not enough storage"))
                        .setMessage(t("Copying the ISO needs %.2f GB, but only %.2f GB are free in\n%s\n\n"
                            + "Free up space or choose a data folder on another drive. Nothing was copied.",
                            fileSize / 1e9, free / 1e9, isoDestDir().getAbsolutePath()))
                        .setPositiveButton(t("OK"), null)
                        .show();
                } else {
                    copyIsoFromUri(uri);
                }
            });
        });
    }

    private void copyIsoFromUri(Uri uri) {
        mCopying = true;
        tvStatus.setText(t("⏳  Copying disc…"));
        tvStatus.setTextColor(0xFFFFC531);
        setInfoPanel(true);
        tvPath.setText(t("The selected file passed the header check and is being copied."));
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        btnPlay.setEnabled(false);
        btnSettings.setEnabled(false);

        Executors.newSingleThreadExecutor().execute(() -> {
            File destDir = isoDestDir();
            destDir.mkdirs();
            File destFile = new File(destDir, "GMSE01.iso");
            File tempFile = new File(destDir, "GMSE01.iso.tmp");

            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tempFile)) {

                if (in == null) {
                    throw new IOException(t("Could not open the selected file."));
                }

                long totalBytes = -1;
                try {
                    Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    if (cursor != null) {
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (sizeIndex != -1 && cursor.moveToFirst()) {
                            totalBytes = cursor.getLong(sizeIndex);
                        }
                        cursor.close();
                    }
                } catch (Exception ignored) {}

                byte[] buffer = new byte[128 * 1024]; // 128 KB
                long bytesCopied = 0;
                int bytesRead;
                long lastUpdate = System.currentTimeMillis();

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    bytesCopied += bytesRead;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 100) {
                        lastUpdate = now;
                        final long current = bytesCopied;
                        final long total = totalBytes;
                        runOnUiThread(() -> {
                            if (total > 0) {
                                int pct = (int) ((current * 100) / total);
                                progressBar.setIndeterminate(false);
                                progressBar.setProgress(pct);
                                tvProgress.setText(t("Copying ISO... %d%% (%.1f MB / %.1f MB)",
                                    pct, current / (1024.0 * 1024.0), total / (1024.0 * 1024.0)));
                            } else {
                                progressBar.setIndeterminate(true);
                                tvProgress.setText(t("Copying ISO... %.1f MB copied",
                                    current / (1024.0 * 1024.0)));
                            }
                        });
                    }
                }
                out.flush();

                if (destFile.exists()) {
                    destFile.delete();
                }
                if (!tempFile.renameTo(destFile)) {
                    throw new IOException(t("Could not replace the final file."));
                }

                runOnUiThread(() -> {
                    mCopying = false;
                    tvProgress.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                    btnSettings.setEnabled(true);
                    Toast.makeText(this, t("ISO copied successfully."), Toast.LENGTH_SHORT).show();
                    checkIsoAndRefreshUi();
                });

            } catch (Exception e) {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                runOnUiThread(() -> {
                    tvProgress.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                    mCopying = false;
                    btnSettings.setEnabled(true);
                    checkIsoAndRefreshUi();
                    new DlDialog(this)
                        .setTitle(t("Error copying the ISO"))
                        .setMessage(t("An error occurred while copying the file: %s", e.getMessage()))
                        .setPositiveButton(t("OK"), null)
                        .show();
                });
            }
        });
    }

    // Before the game loads the ISO: the header must fit, and a plain ISO is
    // hashed once (the result is remembered until the file changes).
    private void launchGame() {
        if (mFoundIsoFile == null || !mFoundIsoFile.exists()) {
            Toast.makeText(this, t("Please select a valid ISO first."), Toast.LENGTH_SHORT).show();
            return;
        }
        DiscCheck.Result check = DiscCheck.checkHeader(mFoundIsoFile);
        if (!check.ok) {
            new DlDialog(this)
                .setTitle(t("❌ This ISO will not work"))
                .setMessage(check.summary)
                .setPositiveButton(t("OK"), null)
                .show();
            return;
        }
        String sha1 = DiscCheck.cachedSha1(this, mFoundIsoFile);
        if (!check.plainIso || DiscCheck.REDUMP_SHA1.equals(sha1)) {
            startGame();
        } else if (sha1 != null) {
            confirmMismatch(sha1);
        } else {
            verifyIso(true);
        }
    }

    private void confirmMismatch(String sha1) {
        new DlDialog(this)
            .setTitle(t("⚠️ ISO hash does not match"))
            .setMessage(t("The header says GMSE01 revision 0, but the file differs from the known good dump.\n\n"
                + "Yours:    %s\nExpected: %s"
                + "\n\nIt may be modified (a mod or patch) or a bad dump. The game may crash or misbehave.",
                sha1, DiscCheck.REDUMP_SHA1))
            .setPositiveButton(t("Play anyway"), (d, w) -> startGame())
            .setNegativeButton(t("Cancel"), null)
            .show();
    }

    private volatile boolean mVerifyCancelled;

    private void verifyIso(boolean thenPlay) {
        final File iso = mFoundIsoFile;
        if (iso == null) return;
        mVerifyCancelled = false;
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        tvProgress.setText(t("Checking ISO hash... %d%% (tap to cancel)", 0));
        panelInfo.setVisibility(View.VISIBLE);
        tvProgress.setOnClickListener(v -> mVerifyCancelled = true);
        btnPlay.setEnabled(false);
        btnSettings.setEnabled(false);
        Executors.newSingleThreadExecutor().execute(() -> {
            String sha1 = null;
            String error = null;
            try {
                final long[] last = {0};
                sha1 = DiscCheck.sha1(iso, new DiscCheck.Progress() {
                    @Override public void onProgress(long done, long total) {
                        long now = System.currentTimeMillis();
                        if (now - last[0] < 150) return;
                        last[0] = now;
                        int pct = (int) (done * 100 / Math.max(1, total));
                        runOnUiThread(() -> {
                            progressBar.setProgress(pct);
                            tvProgress.setText(t("Checking ISO hash... %d%% (tap to cancel)", pct));
                        });
                    }
                    @Override public boolean cancelled() { return mVerifyCancelled; }
                });
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String result = sha1, err = error;
            runOnUiThread(() -> {
                tvProgress.setVisibility(View.GONE);
                tvProgress.setOnClickListener(null);
                progressBar.setVisibility(View.GONE);
                btnSettings.setEnabled(true);
                if (err != null) {
                    checkIsoAndRefreshUi();
                    new DlDialog(this).setTitle(t("Error reading the ISO")).setMessage(err)
                        .setPositiveButton(t("OK"), null).show();
                    return;
                }
                if (result == null) { // cancelled
                    checkIsoAndRefreshUi();
                    return;
                }
                DiscCheck.cacheSha1(this, iso, result);
                checkIsoAndRefreshUi();
                boolean match = result.equals(DiscCheck.REDUMP_SHA1);
                if (match) {
                    Toast.makeText(this, t("✅ ISO verified: matches the Redump dump."), Toast.LENGTH_LONG).show();
                    if (thenPlay) startGame();
                } else {
                    confirmMismatch(result);
                }
            });
        });
    }

    private void startGame() {
        if (mFoundIsoFile != null && mFoundIsoFile.exists()) {
            // A chosen data folder needs "All files access" (it may have been
            // revoked in the Android settings since): without it saves would
            // silently fail.
            if (DataDir.isCustom(this) && (!hasAllFilesAccess() || !DataDir.ensureLayout(this))) {
                new DlDialog(this)
                    .setTitle(t("⚠️ Data folder not writable"))
                    .setMessage(t("The game cannot write to your data folder:\n%s"
                        + "\n\nSave games would be lost. Allow \"All files access\" again, or choose another data folder.",
                        DataDir.get(this).getAbsolutePath()))
                    .setPositiveButton(t("Allow access"), (d, w) -> requestAllFilesAccessOnly())
                    .setNeutralButton(t("Play anyway"), (d, w) -> launchSunshine())
                    .setNegativeButton(t("Cancel"), null)
                    .show();
                return;
            }
            launchSunshine();
        } else {
            Toast.makeText(this, t("Please select a valid ISO first."), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean mAllFilesForPlayOnly;

    private void requestAllFilesAccessOnly() {
        mAllFilesForPlayOnly = true;
        requestAllFilesAccess();
    }

    // with a sync file set (Settings → OTHER → SYNC): synced first, so the
    // game starts with the newest saves of all devices
    private boolean mSyncBeforeStart;

    private void launchSunshine() {
        // the game still running in the background: back into it, without a
        // sync (it would change the saves under the running game)
        if (gameRunning()) {
            startSunshineNow();
            return;
        }
        if (Sync.configured(this) && !mSyncBeforeStart) {
            mSyncBeforeStart = true;
            Toast.makeText(this, t("Syncing…"), Toast.LENGTH_SHORT).show();
            Sync.run(this, (msg, changed) -> {
                if (changed && msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                launchSunshine();
                mSyncBeforeStart = false;
            });
            return;
        }
        startSunshineNow();
    }

    private boolean gameRunning() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
        if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo p : ps)
            if (p.processName != null && p.processName.endsWith(":game")) return true;
        return false;
    }

    // back from the game: what it saved goes to the sync file
    private void syncAfterGame() {
        if (!Sync.configured(this)) return;
        tvStatus.postDelayed(() -> { if (!gameRunning()) Sync.run(this, (msg, changed) -> {
            if (changed && msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }); }, 1500); // the game's process needs a moment to go away
    }

    private void startSunshineNow() {
        if (mFoundIsoFile != null && mFoundIsoFile.exists()) {
            DataDir.ensureLayout(this);
            CrashLog.markGameStarted(this);
            // the shader compile goes on during the game (SunshineActivity shows it)
            findViewById(R.id.panelShaders).setVisibility(View.GONE);
            Intent intent = new Intent(this, SunshineActivity.class);
            intent.putExtra("sms_disc", mFoundIsoFile.getAbsolutePath());
            startActivity(intent);
        } else {
            Toast.makeText(this, t("Please select a valid ISO first."), Toast.LENGTH_SHORT).show();
        }
    }
}
