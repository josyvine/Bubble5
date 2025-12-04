package com.txtify.app;

import android.content.Context;
import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;

// This is the definitive, corrected version for MultiDex support in AIDE.
public class MyApplication extends MultiDexApplication {

    // This method is called BEFORE onCreate(). It's the safest place to install MultiDex.
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            MultiDex.install(this);
        } catch (Exception e) {
            // This is a critical failure, but we'll let the app continue
            // and potentially crash later with a more detailed message.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // This line sets up our crash handler. It will now be activated
        // after the critical MultiDex setup is complete.
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
    }
}
