package com.beeshtv.home;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BEESH TV Suite: the BEESH apps, each installed from its own repo's newest GitHub release. Setup
 * offers the ones that aren't on the TV yet, all ticked; each goes through the system installer,
 * which asks once to confirm. Apps added to the suite later only need a line in APPS.
 */
final class Suite {
    static final class App {
        final String name, pkg, repo;
        App(String name, String pkg, String repo) { this.name = name; this.pkg = pkg; this.repo = repo; }
    }

    static final App[] APPS = {
            new App("BEESH Launcher", "com.beeshtv.home", Updater.REPO),
            new App("BEESH Browser", "com.beeshtv.browser", "bishwofic/BeeshTVBrowser"),
    };

    enum State { IDLE, DOWNLOADING, INSTALLING, FAILED }

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile State state = State.IDLE;
    private volatile String current, error;
    private volatile int progress;
    /** Told about every change, on the main thread. */
    Runnable onChange;

    Suite(Context c) { app = c.getApplicationContext(); }

    State state() { return state; }
    /** The app being downloaded or installed now. */
    String current() { return current; }
    String error() { return error; }
    int progress() { return progress; }

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

    /** Downloads each app's newest release in turn and hands it to the system installer. */
    void install(List<App> apps) {
        if (state == State.DOWNLOADING || state == State.INSTALLING || apps.isEmpty()) return;
        io.execute(() -> {
            for (App a : apps) {
                current = a.name;
                try {
                    File f = download(a);
                    set(State.INSTALLING, null);
                    commit(a, f);
                } catch (Exception e) {
                    set(State.FAILED, a.name + " didn't download");
                    return;
                }
            }
            current = null;
            set(State.IDLE, null);
        });
    }

    private File download(App a) throws Exception {
        set(State.DOWNLOADING, null);
        JSONObject rel = new JSONObject(Updater.get("https://api.github.com/repos/" + a.repo + "/releases/latest"));
        JSONArray assets = rel.getJSONArray("assets");
        String url = null;
        long size = 0;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject x = assets.getJSONObject(i);
            if (x.getString("name").endsWith(".apk")) { url = x.getString("browser_download_url"); size = x.optLong("size"); break; }
        }
        if (url == null) throw new IllegalStateException("no apk");
        File dir = new File(app.getCacheDir(), "suite");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, a.pkg + ".apk"), part = new File(dir, a.pkg + ".apk.part");
        HttpURLConnection c = Updater.open(url);
        long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : size, done = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct != progress) { progress = pct; main.post(this::changed); }
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IllegalStateException("rename");
        return f;
    }

    private void commit(App a, File f) throws Exception {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.pkg);
        params.setSize(f.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent status = new Intent(Updater.ACTION_STATUS).setClass(app, Updater.InstallStatus.class)
                    .putExtra(Updater.EXTRA_WHAT, a.name);
            PendingIntent cb = PendingIntent.getBroadcast(app, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(cb.getIntentSender());
        }
    }

    private void set(State s, String err) {
        state = s;
        error = err;
        if (s != State.DOWNLOADING) progress = 0;
        main.post(this::changed);
    }

    private void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
