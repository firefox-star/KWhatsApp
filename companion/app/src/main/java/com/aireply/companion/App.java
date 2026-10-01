package com.aireply.companion;

import android.app.Application;

/**
 * Application entry point: installs the global crash recorder before any
 * activity, service or receiver can run. No other global state lives here.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashGuard.install(this);
    }
}
