package com.beeshtv.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * The Google Play build's BEESH TV Suite (built by `build.sh --play` in place of src/.../Suite.java).
 * Apps from Play may not install other apps themselves, so Install opens the app's page in the
 * Play Store instead, where the viewer installs it.
 */
final class Suite {
    static final class App {
        final String name, pkg;
        App(String name, String pkg) { this.name = name; this.pkg = pkg; }
    }

    static final App[] APPS = {
            new App("BEESH Launcher", "com.beeshtv.home"),
            new App("BEESH Browser", "com.beeshtv.browser"),
    };

    enum State { IDLE, DOWNLOADING, INSTALLING, FAILED }

    private final Context app;
    private volatile String error;
    private volatile State state = State.IDLE;
    /** Told about every change, on the main thread. */
    Runnable onChange;

    Suite(Context c) { app = c.getApplicationContext(); }

    State state() { return state; }
    String current() { return null; }
    String error() { return error; }
    int progress() { return 0; }

    boolean installed(App a) {
        try {
            app.getPackageManager().getPackageInfo(a.pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    List<App> missing() {
        List<App> out = new ArrayList<>();
        for (App a : APPS) if (!installed(a)) out.add(a);
        return out;
    }

    /** Opens the first app's Play Store page; the rest stay ticked for next time. */
    void install(List<App> apps) {
        if (apps.isEmpty()) return;
        String pkg = apps.get(0).pkg;
        Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))
                .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(store);
            state = State.IDLE;
            error = null;
        } catch (ActivityNotFoundException e) {
            state = State.FAILED;
            error = "Google Play isn't on this TV";
        }
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
