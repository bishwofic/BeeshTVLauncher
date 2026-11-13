package com.beeshtv.home;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recent apps, like a phone's: press Home twice. The apps used last, newest first, each a card with
 * its banner. OK opens one, Up takes it off the list, and Clear all takes them all off; both stop
 * what the app left running in the background, which is what hands its memory back to the TV.
 *
 * The list comes from Android's usage stats (Usage access, asked for in setup). An app can't see
 * another app's screen, so a card shows the app's own banner rather than where you left it.
 */
final class Recents extends FrameLayout {
    interface Host {
        /** The app on Home for a package, or null when it isn't one the TV can open. */
        MainActivity.App app(String pkg);
        void open(MainActivity.App a);
        /** Take the overlay off the screen; Home is underneath. */
        void dismissed();
    }

    /** How far back the list goes. */
    private static final long WINDOW_MS = 3 * DateUtils.DAY_IN_MILLIS;
    private static final int MAX = 12;
    private static final int CARD_W = 400, ART_H = 225, GAP = 36;

    private final Host host;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MainActivity.App> items = new ArrayList<>();
    private final Map<String, Long> lastUsed = new HashMap<>();
    private final List<View> cards = new ArrayList<>();
    private final HorizontalScrollView scroll;
    private final LinearLayout row;
    private final TextView empty, button, hint;
    private final GradientDrawable buttonBg = new GradientDrawable();
    /** The selected card, or -1 when the button below is selected. */
    private int sel;
    private boolean busy;

    Recents(Context c, Host host, SharedPreferences sp, Bitmap backdrop) {
        super(c);
        this.host = host;
        this.sp = sp;
        BlurView glass = new BlurView(c, 0);
        glass.setDarken(0.42f);
        glass.setAmount(1f);
        glass.setBackdrop(backdrop);
        addView(glass, new LayoutParams(-1, -1));

        TextView title = Ui.onGlass(Ui.text(c, 26, 600, Ui.TEXT));
        title.setText("Recent apps");
        LayoutParams tl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.leftMargin = dp(MainActivity.EDGE + MainActivity.INSET);
        tl.topMargin = dp(56);
        addView(title, tl);

        hint = Ui.onGlass(Ui.text(c, 13, 500, Ui.TEXT_2));
        LayoutParams hl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        hl.rightMargin = dp(MainActivity.EDGE + MainActivity.INSET);
        hl.topMargin = dp(66);
        addView(hint, hl);

        scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        scroll.addView(row, new FrameLayout.LayoutParams(-2, -1));
        LayoutParams sl = new LayoutParams(-1, dp(ART_H + 120), Gravity.CENTER_VERTICAL);
        sl.bottomMargin = dp(40);
        addView(scroll, sl);

        empty = Ui.onGlass(Ui.text(c, 17, 500, Ui.TEXT_2));
        empty.setGravity(Gravity.CENTER);
        empty.setSingleLine(false);
        addView(empty, new LayoutParams(dp(560), -2, Gravity.CENTER));

        button = Ui.onGlass(Ui.text(c, 15, 600, Ui.TEXT));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(28), 0, dp(28), 0);
        buttonBg.setCornerRadius(dp(22));
        button.setBackground(buttonBg);
        LayoutParams bl = new LayoutParams(-2, dp(44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bl.bottomMargin = dp(96);
        addView(button, bl);

        load();
        build();
    }

    // ---------------------------------------------------------------- the list

    /** Whether BeeshTV may read which apps were used (Usage access). */
    static boolean allowed(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        int mode = android.os.Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static Intent permissionScreen(Context c) {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Newest first: each app that came to the front since the list was last cleared, once. */
    private void load() {
        items.clear();
        lastUsed.clear();
        if (!allowed(getContext())) return;
        UsageStatsManager um = getContext().getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        long since = Math.max(now - WINDOW_MS, sp.getLong("recents_cleared", 0));
        UsageEvents ev;
        try { ev = um.queryEvents(since, now); } catch (RuntimeException e) { return; }
        if (ev == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) lastUsed.put(e.getPackageName(), e.getTimeStamp());
        }
        String me = getContext().getPackageName();
        List<Map.Entry<String, Long>> order = new ArrayList<>(lastUsed.entrySet());
        order.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> o : order) {
            String pkg = o.getKey();
            if (pkg.equals(me) || HomeGuard.STOCK.contains(pkg)) continue;
            // Taken off the list since it was last used.
            if (o.getValue() <= sp.getLong("recents_gone_" + pkg, 0)) continue;
            MainActivity.App a = host.app(pkg);
            if (a == null) continue;
            items.add(a);
            if (items.size() == MAX) break;
        }
    }

    // ---------------------------------------------------------------- drawing

    private void build() {
        row.removeAllViews();
        cards.clear();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int side = Math.max(0, (screenW - dp(CARD_W)) / 2);
        row.setPadding(side, 0, side, 0);
        for (MainActivity.App a : items) {
            View card = card(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W), -2);
            lp.rightMargin = dp(GAP);
            row.addView(card, lp);
            cards.add(card);
        }
        boolean ok = allowed(getContext());
        empty.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        empty.setText(ok ? "No recent apps" : "To list the apps you've used, allow Usage access for BeeshTV.");
        button.setText(ok ? "Clear all" : "Allow Usage access");
        button.setVisibility(ok && items.isEmpty() ? GONE : VISIBLE);
        hint.setText(items.isEmpty() ? "" : "OK  open   ·   Up  remove");
        sel = items.isEmpty() ? -1 : Math.max(0, Math.min(sel, items.size() - 1));
        paint(false);
        // Once laid out, the selected card goes to the middle.
        scroll.post(() -> centre(false));
    }

    private View card(MainActivity.App a) {
        Context c = getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(a.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        round(icon, 8);
        head.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.onGlass(Ui.text(c, 15, 600, Ui.TEXT));
        name.setText(a.label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        TextView when = Ui.onGlass(Ui.text(c, 12, 500, Ui.TEXT_2));
        Long t = lastUsed.get(a.pkg);
        when.setText(t == null ? "" : ago(t));
        words.addView(when);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
        wl.leftMargin = dp(10);
        head.addView(words, wl);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.bottomMargin = dp(12);
        card.addView(head, hl);

        FrameLayout art = new FrameLayout(c);
        round(art, 16);
        if (a.isBanner) {
            ImageView banner = new ImageView(c);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(a.art);
            art.addView(banner, new LayoutParams(-1, -1));
        } else {
            art.setBackgroundColor(0x33FFFFFF);
            ImageView big = new ImageView(c);
            big.setImageDrawable(a.icon);
            art.addView(big, new LayoutParams(dp(96), dp(96), Gravity.CENTER));
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(16));
        art.setForeground(ring);
        card.addView(art, new LinearLayout.LayoutParams(-1, dp(ART_H)));
        card.setTag(ring);
        return card;
    }

    private static String ago(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < DateUtils.MINUTE_IN_MILLIS) return "Just now";
        if (d < DateUtils.HOUR_IN_MILLIS) return d / DateUtils.MINUTE_IN_MILLIS + " min ago";
        if (d < DateUtils.DAY_IN_MILLIS) return d / DateUtils.HOUR_IN_MILLIS + " h ago";
        return DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString();
    }

    private void round(View v, float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(r));
            }
        });
        v.setClipToOutline(true);
    }

    /** The selected card full size with a white ring, the rest a little smaller and dimmer. */
    private void paint(boolean animate) {
        for (int i = 0; i < cards.size(); i++) {
            View v = cards.get(i);
            boolean on = i == sel;
            ((GradientDrawable) v.getTag()).setStroke(on ? dp(3) : 0, 0xFFFFFFFF);
            float s = on ? 1f : 0.9f, alpha = on ? 1f : 0.6f;
            if (animate) {
                v.animate().scaleX(s).scaleY(s).alpha(alpha).setInterpolator(Ui.EASE).setDuration(220).start();
            } else {
                v.setScaleX(s);
                v.setScaleY(s);
                v.setAlpha(alpha);
            }
        }
        buttonBg.setColor(sel == -1 ? 0xE6FFFFFF : 0x33FFFFFF);
        button.setTextColor(sel == -1 ? 0xFF0B0D14 : Ui.TEXT);
        button.setShadowLayer(sel == -1 ? 0 : 6, 0, 1, 0x66000000);
    }

    private void centre(boolean smooth) {
        if (sel < 0 || sel >= cards.size()) return;
        View v = cards.get(sel);
        int x = v.getLeft() + v.getWidth() / 2 - scroll.getWidth() / 2;
        if (smooth) scroll.smoothScrollTo(x, 0); else scroll.scrollTo(x, 0);
    }

    // ---------------------------------------------------------------- keys

    void key(KeyEvent e) {
        if (busy) return;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (e.getAction() != KeyEvent.ACTION_UP) return;
            if (sel >= 0) { host.open(items.get(sel)); host.dismissed(); }
            else if (!allowed(getContext())) { getContext().startActivity(permissionScreen(getContext())); host.dismissed(); }
            else clearAll();
            return;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel > 0) { sel--; paint(true); centre(true); }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sel >= 0 && sel < cards.size() - 1) { sel++; paint(true); centre(true); }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sel >= 0 && button.getVisibility() == VISIBLE) { last = sel; sel = -1; paint(true); }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sel == -1 && !cards.isEmpty()) { sel = Math.min(last, cards.size() - 1); paint(true); }
                else if (sel >= 0 && e.getRepeatCount() == 0) remove(sel);
                break;
        }
    }

    /** The card selected before moving down to the button. */
    private int last;

    /** Up on a card: it slides up and away, like a swipe on a phone, and the app is stopped. */
    private void remove(int i) {
        busy = true;
        MainActivity.App a = items.get(i);
        View v = cards.get(i);
        v.animate().translationY(-dp(140)).alpha(0f).setInterpolator(Ui.EASE).setDuration(200).withEndAction(() -> {
            gone(a.pkg);
            stop(getContext(), sp, java.util.Collections.singletonList(a.pkg), null);
            items.remove(i);
            sel = Math.min(i, items.size() - 1);
            busy = false;
            build();
            if (items.isEmpty()) sel = -1;
            paint(false);
        }).start();
    }

    /** Every card slides up and away, then every app is stopped and the list starts again from now. */
    private void clearAll() {
        busy = true;
        long before = availMb(getContext());
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).animate().translationY(-dp(140)).alpha(0f).setInterpolator(Ui.EASE)
                    .setDuration(200).setStartDelay(Math.abs(i - Math.max(sel, 0)) * 40L).start();
        }
        List<String> all = new ArrayList<>();
        for (MainActivity.App a : items) all.add(a.pkg);
        sp.edit().putLong("recents_cleared", System.currentTimeMillis()).apply();
        Context app = getContext().getApplicationContext();
        String n = all.size() == 1 ? "1 app" : all.size() + " apps";
        stop(app, sp, all, stopped -> {
            // Android hands the memory back a moment after the processes go.
            main.postDelayed(() -> {
                long freed = availMb(app) - before;
                Toast.makeText(app, stopped && freed > 10
                        ? String.format(Locale.US, "Cleared %s · %d MB freed", n, freed) : "Cleared " + n,
                        Toast.LENGTH_SHORT).show();
            }, 600);
        });
        main.postDelayed(host::dismissed, 200 + cards.size() * 40L);
    }

    private void gone(String pkg) {
        sp.edit().putLong("recents_gone_" + pkg, System.currentTimeMillis()).apply();
    }

    /**
     * Ends the apps' background processes, which is what frees their memory; Android won't stop one
     * that's on screen or playing. Since Android 14 only the shell may do this for other apps, so it
     * goes through the TV's own network debugging (see Adb). The Google Play build only takes apps
     * off the list: Android reclaims their memory by itself when it runs short.
     * done gets whether the apps were stopped, on the main thread.
     */
    static void stop(Context c, SharedPreferences sp, List<String> pkgs, java.util.function.Consumer<Boolean> done) {
        Handler main = new Handler(Looper.getMainLooper());
        if (Updater.PLAY || pkgs.isEmpty()) {
            if (done != null) done.accept(false);
            return;
        }
        Context app = c.getApplicationContext();
        if (!sp.getBoolean("adb_allowed", false)) {
            Toast.makeText(app, "If the TV asks to allow debugging, choose Always allow for BeeshTV, so "
                    + "Clear all can free memory", Toast.LENGTH_LONG).show();
        }
        StringBuilder cmd = new StringBuilder();
        for (String p : pkgs) {
            // Package names are letters, digits, dots and underscores; anything else isn't one.
            if (!p.matches("[A-Za-z0-9._]+")) continue;
            if (cmd.length() > 0) cmd.append("; ");
            cmd.append("am kill ").append(p);
        }
        new Thread(() -> {
            boolean ok = Adb.shell(app, cmd.toString()) != null;
            if (ok) sp.edit().putBoolean("adb_allowed", true).apply();
            main.post(() -> {
                if (!ok && "network debugging is off".equals(Adb.error)) {
                    Toast.makeText(app, "To free their memory too, turn on Network debugging in Developer options",
                            Toast.LENGTH_LONG).show();
                }
                if (done != null) done.accept(ok);
            });
        }).start();
    }

    private static long availMb(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }
}
