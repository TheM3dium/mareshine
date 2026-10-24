package io.github.them3dium.mareshine;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

import java.io.File;

// Compiles the game's known shader programs into the shader cache while the
// launcher's menu shows, as Dusklight does (gx_precompile.cpp). It runs in a
// process of its own (":shaders"), which loads the game library for the
// shader code and ends when the work is done or the game starts. Progress goes
// to the launcher as broadcasts (ACTION_PROGRESS: done, total; done == total
// at the end).
public class ShaderService extends Service {
    static final String ACTION_PROGRESS = "io.github.them3dium.mareshine.SHADER_PROGRESS";
    static final String EXTRA_DONE = "done", EXTRA_TOTAL = "total";

    private Thread mThread;
    private volatile boolean mCancelled;

    static void start(Context c) {
        java.util.Map<String, String> s = Settings.read(c);
        // The game library is loaded by its bootstrap (libsmsbootstrap) at a
        // fixed address, not as libmain here; the game warms its shaders itself
        // (SMS_GX_SHADER_WARMUP) until this has a way into the library.
        if (true) return;
        if ("0".equals(s.getOrDefault("shader_cache", "1"))) return; // nothing to keep the programs in
        try {
            c.startService(new Intent(c, ShaderService.class));
        } catch (IllegalStateException e) {
            // not allowed from the background; the game's own warm-up still runs
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, ShaderService.class));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mThread != null) return START_NOT_STICKY;
        String dir = Settings.read(this).get("save_dir");
        if (dir == null || dir.isEmpty()) dir = DataDir.savesDir(this).getAbsolutePath();
        final String saveDir = dir;
        new File(saveDir).mkdirs();
        mThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                System.loadLibrary("SDL2");
                System.loadLibrary("main");
                if (!mCancelled) nativePrecompile(saveDir);
            } catch (Throwable t) {
                android.util.Log.w("SMS", "shader precompile failed", t);
            }
            send(1, 1);
            stopSelf();
            // the game library stays loaded otherwise
            Process.killProcess(Process.myPid());
        }, "ShaderPrecompile");
        mThread.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mCancelled = true;
        if (mThread != null && mThread.isAlive()) {
            try { nativeCancel(); } catch (Throwable ignored) {} // stops after the batch in hand
        }
        super.onDestroy();
    }

    // from nativePrecompile, after each batch
    @SuppressWarnings("unused")
    private void onProgress(int done, int total) {
        if (total > 0) send(done, total);
    }

    private void send(int done, int total) {
        Intent i = new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        i.putExtra(EXTRA_DONE, done).putExtra(EXTRA_TOTAL, total);
        sendBroadcast(i);
    }

    private native int nativePrecompile(String saveDir);
    private native void nativeCancel();
}
