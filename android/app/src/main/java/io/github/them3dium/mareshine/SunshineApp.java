package io.github.them3dium.mareshine;

import android.app.Application;

// Loads the app's language (L) and watches the main thread (HangWatch) in each
// of the app's processes; in the main one, also writes an ANR from the last
// session to the logs (AnrLog).
public class SunshineApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        L.init(this);
        HangWatch.start(this);
        if (android.os.Build.VERSION.SDK_INT >= 28 && getPackageName().equals(getProcessName()))
            AnrLog.collect(this);
    }
}
