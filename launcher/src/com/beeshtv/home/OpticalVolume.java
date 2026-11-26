package com.beeshtv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.DynamicsProcessing;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Volume for speakers on the optical port. TCL's firmware holds optical output at full volume: the
 * remote still moves the TV's volume, but the sound doesn't change, and the TV says "Audio output
 * through optical fibers does not support volume adjustment". Android TV hands the volume keys to the
 * TV before any accessibility service sees them, so this follows the volume instead: whenever it
 * changes while the sound goes out of the optical port, it turns the sound down by that much with a
 * gain on the TV's whole output mix (a DynamicsProcessing effect on session 0), before it reaches the
 * port; TCL's audio chip has a volume too, but it doesn't reach the optical port. With the speakers'
 * own knob turned all the way up, the remote then sets how loud they are. At full volume the effect
 * is off and the sound is untouched. It also draws its own volume bar over TCL's empty one, and over
 * the toast.
 */
final class OpticalVolume {
    private static final String TAG = "BeeshVolume";
    /** TCL's current output, in Settings.Global; 32 is the optical (S/PDIF) port. */
    private static final String TCL_OUT = "tcl_device_out_type";
    private static final int TCL_OPTICAL = 32;
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    /** TCL's toast stays 3.5 s after the last press; the bar outlasts it so it stays covered. */
    private static final long SHOW_MS = 4000;

    private final Context c;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private DynamicsProcessing dp;
    private int channels;
    /** Each new effect outranks the last, so it takes control of the shared effect straight away. */
    private int priority = 1 << 20;
    private float appliedDb = Float.NaN;
    private Bar bar;

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            int stream = i.getIntExtra(EXTRA_STREAM, AudioManager.STREAM_MUSIC);
            if (stream != AudioManager.STREAM_MUSIC || !active()) return;
            apply();
            show();
        }
    };

    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            // Playback can move to another mixer; make sure the gain is on it, built for its channels.
            if (active()) apply();
        }
    };

    OpticalVolume(Context c) {
        this.c = c;
        am = c.getSystemService(AudioManager.class);
        IntentFilter f = new IntentFilter(VOLUME_CHANGED);
        f.addAction(MUTE_CHANGED);
        c.registerReceiver(changes, f, Context.RECEIVER_EXPORTED);
        if (am != null) am.registerAudioPlaybackCallback(playback, main);
        if (active()) apply();
    }

    /** Whether the TV is sending its sound out of the optical port, where its own volume does nothing. */
    boolean active() {
        return am != null && Settings.Global.getInt(c.getContentResolver(), TCL_OUT, 0) == TCL_OPTICAL;
    }

    /** The TV's volume, 0–100. */
    private int level() {
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
    }

    private boolean muted() {
        return am.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    /** The gain for a level: half the level is about -12 dB, which sounds about half as loud. */
    static float gainDb(int level, boolean muted) {
        if (muted || level <= 0) return -96f;
        return Math.max(-80f, (float) (40 * Math.log10(level / 100.0)));
    }

    /**
     * Sets the gain on the output mix. The effect is set up for the channels of the mixer it is made
     * on (2 for system sounds, 8 for video on this TV). When playback starts on another mixer Android
     * moves it there still turning left and right down, but the extra channels come out garbled, so
     * it is made again for the new count. The new one takes over the same effect before the old one
     * lets go (a higher priority each time), so the gain never drops out in between.
     */
    private void apply() {
        float db = gainDb(level(), muted());
        try {
            if (db >= 0f) {
                release();
                return;
            }
            if (dp == null || !dp.hasControl() || dp.getChannelCount() != channels) {
                // A plain gain stage; Android fits it to the channels of the mixer it lands on.
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        false, 0, false, 0, false, 0, false).build();
                DynamicsProcessing next = new DynamicsProcessing(++priority, 0, cfg);
                next.setInputGainAllChannelsTo(db);
                next.setEnabled(true);
                DynamicsProcessing old = dp;
                dp = next;
                channels = next.getChannelCount();
                appliedDb = db;
                if (old != null) {
                    try { old.release(); } catch (RuntimeException ignored) { }
                }
                return;
            }
            if (db != appliedDb) {
                dp.setInputGainAllChannelsTo(db);
                appliedDb = db;
            }
            if (!dp.getEnabled()) dp.setEnabled(true);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Couldn't set the output gain", ex);
            release();
        }
    }

    private void release() {
        if (dp != null) {
            try { dp.release(); } catch (RuntimeException ignored) { }
            dp = null;
        }
        channels = 0;
        appliedDb = Float.NaN;
    }

    void destroy() {
        try { c.unregisterReceiver(changes); } catch (RuntimeException ignored) { }
        if (am != null) am.unregisterAudioPlaybackCallback(playback);
        main.removeCallbacksAndMessages(null);
        if (bar != null) bar.remove();
        release();
    }

    private void show() {
        if (bar == null) bar = new Bar(c);
        bar.show(level(), muted());
        main.removeCallbacks(hide);
        main.postDelayed(hide, SHOW_MS);
    }

    private final Runnable hide = () -> { if (bar != null) bar.hide(); };

    /**
     * The volume bar, in two parts placed over TCL's own: a pill at the bottom where TCL's toast comes
     * up (icon, track and level), and a column on the right edge where TCL's empty bar sits. Places are
     * fractions of the screen, measured from TCL's layout on a 1920 × 1080 screen.
     */
    private static final class Bar {
        private static final int GLASS = 0xFF1E1E22;
        private final WindowManager wm;
        private final Point screen = new Point();
        private final LinearLayout pill, column;
        private final ImageView icon, columnIcon;
        private final Track track, columnTrack;
        private final TextView value, columnValue;
        private boolean added;

        Bar(Context c) {
            wm = c.getSystemService(WindowManager.class);
            wm.getDefaultDisplay().getRealSize(screen);
            float u = screen.y / 1080f;

            pill = new LinearLayout(c);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(px(56, u), 0, px(56, u), 0);
            pill.setBackground(glass(px(70, u)));
            icon = new ImageView(c);
            pill.addView(icon, new LinearLayout.LayoutParams(px(44, u), px(44, u)));
            track = new Track(c, false);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, px(12, u), 1f);
            tl.setMargins(px(28, u), 0, px(28, u), 0);
            pill.addView(track, tl);
            value = Ui.figures(c, 0, 600, Ui.TEXT);
            value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(38, u));
            value.setGravity(Gravity.END);
            pill.addView(value, new LinearLayout.LayoutParams(px(70, u), LinearLayout.LayoutParams.WRAP_CONTENT));

            column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setPadding(0, px(22, u), 0, px(22, u));
            column.setBackground(glass(px(16, u)));
            columnValue = Ui.figures(c, 0, 600, Ui.TEXT);
            columnValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(26, u));
            columnValue.setGravity(Gravity.CENTER);
            column.addView(columnValue, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            columnTrack = new Track(c, true);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(px(10, u), 0, 1f);
            cl.setMargins(0, px(18, u), 0, px(18, u));
            column.addView(columnTrack, cl);
            columnIcon = new ImageView(c);
            column.addView(columnIcon, new LinearLayout.LayoutParams(px(32, u), px(32, u)));

            pill.setAlpha(0f);
            column.setAlpha(0f);
        }

        private static int px(float v, float u) { return Math.round(v * u); }

        private static GradientDrawable glass(float radius) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(GLASS);
            bg.setCornerRadius(radius);
            return bg;
        }

        private WindowManager.LayoutParams at(int x, int y, int w, int h) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = x;
            lp.y = y;
            return lp;
        }

        void show(int level, boolean muted) {
            boolean off = muted || level == 0;
            int ic = off ? R.drawable.ic_volume_off : R.drawable.ic_volume;
            icon.setImageResource(ic);
            columnIcon.setImageResource(ic);
            float f = muted ? 0 : level / 100f;
            track.set(f);
            columnTrack.set(f);
            String v = muted ? "–" : String.valueOf(level);
            value.setText(v);
            columnValue.setText(v);
            if (!added && wm != null) {
                float sx = screen.x / 1920f, sy = screen.y / 1080f;
                try {
                    // TCL's toast: about x 525–1395, y 900–1015. Cover it with a margin.
                    wm.addView(pill, at(Math.round(470 * sx), Math.round(880 * sy),
                            Math.round(980 * sx), Math.round(150 * sy)));
                    // TCL's bar: about x 1750–1838, y 340–740.
                    wm.addView(column, at(Math.round(1744 * sx), Math.round(334 * sy),
                            Math.round(100 * sx), Math.round(412 * sy)));
                    added = true;
                } catch (RuntimeException ex) {
                    Log.w(TAG, "Couldn't show the volume bar", ex);
                    remove();
                    return;
                }
            }
            for (View v2 : new View[] {pill, column}) {
                v2.animate().cancel();
                v2.setAlpha(1f);
            }
        }

        void hide() {
            for (View v : new View[] {pill, column}) {
                v.animate().alpha(0f).setDuration(250).setInterpolator(Ui.EASE).start();
            }
        }

        void remove() {
            for (View v : new View[] {pill, column}) {
                try { if (v.isAttachedToWindow()) wm.removeView(v); } catch (RuntimeException ignored) { }
            }
            added = false;
        }
    }

    /** A rounded track with a white fill for the level, across or up. */
    private static final class Track extends View {
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final boolean vertical;
        private float f;

        Track(Context c, boolean vertical) {
            super(c);
            this.vertical = vertical;
            back.setColor(0x33FFFFFF);
            fill.setColor(Ui.TEXT);
        }

        void set(float f) {
            this.f = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), rad = Math.min(w, h) / 2;
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, rad, rad, back);
            if (f <= 0) return;
            if (vertical) r.set(0, h - Math.max(w, h * f), w, h);
            else r.set(0, 0, Math.max(h, w * f), h);
            canvas.drawRoundRect(r, rad, rad, fill);
        }
    }
}
