package io.github.them3dium.mareshine;

import android.app.Application;

// Loads the app's language (L) in each of the app's processes.
public class SunshineApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        L.init(this);
    }
}
