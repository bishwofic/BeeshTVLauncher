package com.beeshtv.home;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;

/**
 * Holding a volume button keeps turning the volume. The TV's IR remote sends one press and one
 * release, nothing in between, and Android TV keeps the volume keys from apps, so a held button only
 * ever moves the volume one step. The shell can see the remote, though: this watches the volume keys
 * with getevent through the TV's own network debugging (see Adb) and, while one is held, steps the
 * volume after a moment and then quickly until it is let go. Only the volume key lines leave the
 * shell; nothing else from the remote is read.
 */
final class VolumeHold {
    private static final String TAG = "BeeshVolume";
    private static final String COMMAND =
            "getevent -ql | grep --line-buffered -E 'KEY_VOLUME(UP|DOWN) +(DOWN|UP)'";
    /** Held this long before it starts repeating, then one step this often. */
    private static final long DELAY_MS = 400, STEP_MS = 70;
    /** A release can go missing; stop repeating after this long regardless. */
    private static final long MAX_HOLD_MS = 12_000;

    private final Context c;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private volatile Socket socket;
    private int dir;
    private long since;

    VolumeHold(Context c) {
        this.c = c.getApplicationContext();
        am = c.getSystemService(AudioManager.class);
        Thread t = new Thread(this::watch, "VolumeHold");
        t.setDaemon(true);
        t.start();
    }

    private void watch() {
        long wait = 5_000;
        StringBuilder pending = new StringBuilder();
        while (running) {
            long start = System.currentTimeMillis();
            boolean ok = Adb.stream(c, COMMAND, s -> socket = s, text -> {
                pending.append(text);
                int nl;
                while ((nl = pending.indexOf("\n")) >= 0) {
                    String line = pending.substring(0, nl);
                    pending.delete(0, nl + 1);
                    main.post(() -> onLine(line));
                }
            });
            pending.setLength(0);
            if (!running) return;
            if (!ok && !"network debugging is off".equals(Adb.error)
                    && System.currentTimeMillis() - start > 30_000) {
                // The TV asked to allow debugging and nobody said yes: don't keep asking.
                Log.w(TAG, "Not allowed to watch the volume buttons: " + Adb.error);
                return;
            }
            // Ended or couldn't connect (network debugging off, say): try again later, less often.
            wait = ok ? 5_000 : Math.min(wait * 2, 5 * 60_000);
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
        }
    }

    private void onLine(String line) {
        boolean up = line.contains("KEY_VOLUMEUP");
        if (line.trim().endsWith("DOWN")) {
            // The TV takes the first step itself; this only adds the repeats.
            dir = up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            since = System.currentTimeMillis();
            main.removeCallbacks(step);
            main.postDelayed(step, DELAY_MS);
        } else {
            main.removeCallbacks(step);
        }
    }

    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (System.currentTimeMillis() - since > MAX_HOLD_MS || am == null) return;
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
            main.postDelayed(this, STEP_MS);
        }
    };

    void destroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
