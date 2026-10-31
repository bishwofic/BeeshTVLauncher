package com.beeshtv.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.app.NotificationManager;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.location.LocationManager;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * BeeshTV Launcher. A landscape that follows the time of day, under a soft vignette. Across the top:
 * a world clock and HOME / APPS on the left; Wi-Fi, Bluetooth, Inputs, Notifications, Settings and RAM,
 * then the time, date and local weather, on the right. Below, three
 * sections of tiles: YOUR APPS, OTHER APPS (everything else, A–Z) and INPUTS. The Apps tab is a
 * full-screen A–Z list. Everything that opens is a borderless blur panel.
 *
 * All D-pad movement is handled here, never by the scroll views (their arrow handling page-scrolls
 * instead of focusing an off-screen item). Input switching and the package refresh are adapted from
 * tv-home by Mihir Gupta (MIT), see licenses/tv-home-MIT.txt.
 */
public class MainActivity extends Activity {

    // INSET: the header icon and its gap, so tiles line up with the header text, not its icon.
    static final int EDGE = 40, INSET = 24, GAP = 5, BAR_H = 128, CONTENT_TOP = 244, SECTION_GAP = 68;
    static final float FOCUS_SCALE = 1.18f;
    static final int SEC_YOURS = 0, SEC_OTHER = 1, SEC_INPUTS = 2;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art, icon;
        boolean isBanner, removable;
    }

    /** An input tile: id null means Google TV (this home screen). */
    static final class Input {
        String id, label;
        TvInputInfo info;
    }

    /** One group of tiles on Home, with its own column count for D-pad movement. */
    static final class Section {
        final int kind;
        final List<View> cards = new ArrayList<>();
        Section(int kind) { this.kind = kind; }
    }

    private Prefs prefs;
    private Wallpapers walls;
    private Weather weather;
    private Here here;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ImageView wallBack, wallFront;
    private Bitmap wallBlur;
    private long wallShownAt;
    private String wallKey;
    private int wallSlot = -1;
    private boolean firstWallpaper = true;
    private final List<BlurView> blurs = new ArrayList<>();

    // Menu bar
    private FrameLayout topBar;
    private final List<View> topItems = new ArrayList<>();
    private TextView homeTab, appsTab, clock, date, ram, localWeather;
    private ImageView localIcon, wifiBtn, btBtn, inputsBtn, settingsBtn;
    private BadgeView bellBtn;
    private ClockItem worldClock;
    private View worldRule;
    private View localWeatherRow;

    // Home
    private FrameLayout homeBox;
    private ScrollView homeScroll;
    private LinearLayout homeList;
    private final List<Section> sections = new ArrayList<>();
    private final Map<View, int[]> cardPos = new HashMap<>();
    private int[] lastPos;

    // Apps
    private FrameLayout libBox;
    private ScrollView libScroll;
    private LinearLayout libList, libIndex;
    private final List<View> rows = new ArrayList<>();
    private final List<App> rowApps = new ArrayList<>();
    private final List<String> rowLetters = new ArrayList<>();
    private final Map<String, TextView> indexViews = new HashMap<>();
    private int lastRow;

    private int page;
    private Panel panel;
    private final Map<String, App> apps = new HashMap<>();
    private int packageSeq;
    private boolean firstShow = true, longPressed;

    private final Runnable rotate = () -> { showWallpaper(walls.next()); scheduleRotation(); };
    private final Runnable ramTick = new Runnable() {
        @Override public void run() { updateRam(); handler.postDelayed(this, 5000); }
    };

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClocks(); updateWifiIcon(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); buildPages(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        walls = new Wallpapers(this, prefs);
        weather = new Weather(prefs.raw());
        here = new Here(this, prefs.raw());
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        loadApps();
        applySettings();
        NotificationsService.onChange = () -> {
            updateBadge();
            if (panel != null && "notifications".equals(panel.kind)) panel.refill();
        };

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        tf.addAction(WifiManager.RSSI_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClocks();
        updateWifiIcon();
        updateBadge();
        handler.post(ramTick);
        // After an update Android doesn't always reconnect the notification listener by itself.
        if (!NotificationsService.connected()) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                        new ComponentName(this, NotificationsService.class));
            } catch (RuntimeException ignored) { }
        }
        // Rebuild only when apps changed while another app was in front; rebuilding is what stutters.
        if (getPackageManager().getChangedPackages(packageSeq) != null) { loadApps(); buildPages(); }
        // Posted: on a cold start the window hands out default focus after onResume.
        handler.post(() -> { if (panel == null) focusContent(); });
        if (panel != null && "setup".equals(panel.kind)) panel.refill();
        if (!prefs.raw().getBoolean("setup_shown", false) && !setupComplete()) {
            prefs.raw().edit().putBoolean("setup_shown", true).apply();
            handler.postDelayed(() -> { if (panel == null) openSetup(); }, 900);
        }
        if (firstShow) { firstShow = false; playEntrance(); }
        long interval = prefs.wallMinutes() * 60_000L;
        if (wallKey == null || wallSlot != Wallpapers.slotNow()
                || SystemClock.elapsedRealtime() - wallShownAt >= interval) rotate.run();
        else scheduleRotation();
        walls.refreshIfDue(false, () -> { if (wallKey == null || wallKey.startsWith("asset:")) rotate.run(); });
        here.refreshIfDue(() -> { applyClocks(); refreshWeather(true); });
        refreshWeather(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(ramTick);
        unregisterReceiver(tick);
    }

    @Override
    protected void onDestroy() {
        NotificationsService.onChange = null;
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close any panel, back to the first app.
        if (panel != null) closePanel(false);
        showPage(0);
        homeScroll.smoothScrollTo(0, 0);
        lastPos = null;
        focusContent();
    }

    @Override
    public void onBackPressed() {
        if (panel != null) { closePanel(true); return; }
        if (page != 0) { showPage(0); focusContent(); return; }
        homeScroll.smoothScrollTo(0, 0);
        lastPos = null;
        focusContent();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D14);
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new Vignette(this), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildHome(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildLibrary(), new FrameLayout.LayoutParams(-1, -1));
        libBox.setVisibility(View.GONE);
        root.addView(buildTopBar(), lp(-1, dp(104), Gravity.TOP, dp(EDGE), dp(14), dp(EDGE), 0));
        return root;
    }

    private BlurView blur(float radiusDp, float darken) {
        BlurView v = new BlurView(this, dp(radiusDp));
        v.setDarken(darken);
        blurs.add(v);
        return v;
    }

    /**
     * Left: the world clock (when on), then HOME / APPS. Right: status icons with RAM under them, then
     * the main clock. The middle stays clear for the photo.
     */
    private View buildTopBar() {
        topBar = new FrameLayout(this);

        LinearLayout left = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        worldClock = new ClockItem(this, Gravity.START);
        left.addView(worldClock);
        worldRule = rule();
        left.addView(worldRule, llp(dp(1), dp(36), dp(18), 0, dp(14), 0));
        homeTab = tab("Home", R.drawable.ic_home);
        appsTab = tab("Apps", R.drawable.ic_grid);
        homeTab.setOnClickListener(v -> focusContent());
        appsTab.setOnClickListener(v -> focusContent());
        left.addView(homeTab, llp(-2, dp(32), 0, 0, dp(2), 0));
        left.addView(appsTab, llp(-2, dp(32), 0, 0, 0, 0));
        topBar.addView(left, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));

        // Status icons, with RAM in a quiet line underneath.
        LinearLayout statusCol = new LinearLayout(this);
        statusCol.setOrientation(LinearLayout.VERTICAL);
        statusCol.setGravity(Gravity.END);
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        ram = Ui.onGlass(Ui.text(this, 13, 600, Ui.TEXT_2));
        ram.setLetterSpacing(-0.01f);
        ram.setCompoundDrawables(Ui.icon(this, R.drawable.ic_memory, Ui.TEXT_2, 14), null, null, null);
        ram.setCompoundDrawablePadding(dp(5));
        wifiBtn = iconButton(R.drawable.ic_wifi_3, v -> openWifi());
        btBtn = iconButton(R.drawable.ic_bluetooth, v -> openBluetooth());
        inputsBtn = iconButton(R.drawable.ic_input, v -> openInputs());
        bellBtn = new BadgeView(this);
        styleIconButton(bellBtn, R.drawable.ic_bell, v -> openNotifications());
        settingsBtn = iconButton(R.drawable.ic_settings, v -> openSettings());
        for (View v : new View[]{wifiBtn, btBtn, inputsBtn, bellBtn, settingsBtn}) status.addView(v, llp(dp(34), dp(34), dp(2), 0, 0, 0));
        statusCol.addView(status, new LinearLayout.LayoutParams(-2, -2));
        statusCol.addView(ram, llp(-2, -2, 0, dp(4), dp(8), 0));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(statusCol);
        right.addView(rule(), llp(dp(1), dp(36), dp(16), 0, dp(18), 0));
        right.addView(buildMainClock());
        topBar.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        for (View v : new View[]{homeTab, appsTab, wifiBtn, btBtn, inputsBtn, bellBtn, settingsBtn}) topItems.add(v);
        return topBar;
    }

    private View rule() {
        View r = new View(this);
        r.setBackgroundColor(0x40FFFFFF);
        return r;
    }

    /** Two lines, right-aligned: the big time, then "☀ 28° · 31 Oct, Sat". */
    private View buildMainClock() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.END);
        clock = Ui.onGlass(Ui.text(this, 40, 700, Ui.TEXT));
        clock.setLetterSpacing(0.02f);
        col.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout w = new LinearLayout(this);
        w.setGravity(Gravity.CENTER_VERTICAL);
        localIcon = new ImageView(this);
        localIcon.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        w.addView(localIcon, llp(dp(15), dp(15), 0, 0, dp(5), 0));
        localWeather = Ui.onGlass(Ui.text(this, 14, 600, Ui.TEXT_2));
        w.addView(localWeather);
        date = Ui.onGlass(Ui.text(this, 14, 600, Ui.TEXT));
        w.addView(date);
        localWeatherRow = w;
        col.addView(w, llp(-2, -2, 0, -dp(3), 0, 0));
        return col;
    }

    /** The world clock, top left: flag and time, the weather under it. */
    final class ClockItem extends LinearLayout {
        final TextView name, time, temp;
        final ImageView icon;

        ClockItem(Context c, int gravity) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(gravity);
            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            name = Ui.onGlass(Ui.text(c, 15, 700, Ui.TEXT));
            time = Ui.onGlass(Ui.text(c, 18, 700, Ui.TEXT));
            top.addView(name, llp(-2, -2, 0, 0, dp(6), 0));
            top.addView(time);
            addView(top, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout line = new LinearLayout(c);
            line.setGravity(Gravity.CENTER_VERTICAL);
            icon = new ImageView(c);
            icon.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
            temp = Ui.onGlass(Ui.text(c, 12, 500, Ui.TEXT_2));
            line.addView(icon, llp(dp(14), dp(14), 0, 0, dp(5), 0));
            line.addView(temp);
            addView(line, llp(-2, -2, 0, dp(2), 0, 0));
        }
    }

    private TextView tab(String label, int icon) {
        TextView t = Ui.onGlass(Ui.caps(Ui.text(this, 13, 700, Ui.TEXT), 0.06f));
        t.setText(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(14), 0);
        t.setFocusable(true);
        t.setId(View.generateViewId());
        t.setCompoundDrawables(Ui.icon(this, icon, Ui.TEXT, 15), null, null, null);
        t.setCompoundDrawablePadding(dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(Color.TRANSPARENT);
        t.setBackground(bg);
        // Only the Apps tab (and the list under it) shows the Apps page; anything else shows Home.
        t.setOnFocusChangeListener((v, has) -> {
            if (has) showPage(v == appsTab ? 1 : 0);
            styleTabs();
        });
        return t;
    }

    private void styleTabs() {
        for (TextView t : new TextView[]{homeTab, appsTab}) {
            boolean current = (t == homeTab && page == 0) || (t == appsTab && page == 1);
            ((GradientDrawable) t.getBackground()).setColor(t.hasFocus() ? Ui.FOCUS : current ? Ui.CURRENT : Color.TRANSPARENT);
            t.setTextColor(t.hasFocus() || current ? Ui.TEXT : Ui.TEXT_2);
        }
    }

    private ImageView iconButton(int icon, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        styleIconButton(b, icon, click);
        return b;
    }

    private void styleIconButton(ImageView b, int icon, View.OnClickListener click) {
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setFocusable(true);
        b.setId(View.generateViewId());
        b.setOnClickListener(click);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        b.setBackground(bg);
        b.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Ui.FOCUS : Color.TRANSPARENT);
            if (has) showPage(0);
        });
    }

    private View buildHome() {
        homeBox = new FrameLayout(this);
        homeScroll = new HomeScrollView(this);
        homeScroll.setVerticalScrollBarEnabled(false);
        homeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Room on every side for a focused tile to grow without being cut off.
        homeScroll.setClipToPadding(false);
        homeScroll.setClipChildren(false);
        homeScroll.setPadding(dp(EDGE + INSET), dp(BAR_H), dp(EDGE + INSET), dp(56));
        // Rows scrolled up under the menu bar fade out instead of stopping at a hard edge.
        ((HomeScrollView) homeScroll).fadeUnder(dp(100), dp(176));
        homeList = new LinearLayout(this);
        homeList.setOrientation(LinearLayout.VERTICAL);
        homeList.setClipChildren(false);
        homeList.setClipToPadding(false);
        homeScroll.addView(homeList, new FrameLayout.LayoutParams(-1, -2));
        homeBox.addView(homeScroll, new FrameLayout.LayoutParams(-1, -1));
        // A slight darkening towards the bottom edge, so rows further down read as "more below".
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x2E000000}));
        homeBox.addView(fade, new FrameLayout.LayoutParams(-1, dp(80), Gravity.BOTTOM));
        return homeBox;
    }

    private TextView sectionHeader(int icon, String title) {
        TextView t = Ui.onGlass(Ui.text(this, 23, 700, Ui.TEXT));
        t.setText(title);
        t.setCompoundDrawables(Ui.icon(this, icon, Ui.TEXT, 19), null, null, null);
        t.setCompoundDrawablePadding(dp(INSET - 19));
        t.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** The Apps tab: the whole screen blurred, an A–Z list and a letter index, like the iOS App Library. */
    private View buildLibrary() {
        libBox = new FrameLayout(this);
        libBox.addView(blur(0, 0.30f), new FrameLayout.LayoutParams(-1, -1));
        libScroll = new HomeScrollView(this);
        libScroll.setVerticalScrollBarEnabled(false);
        libScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        libScroll.setPadding(dp(EDGE), dp(BAR_H), dp(EDGE + 48), dp(24));
        libList = new LinearLayout(this);
        libList.setOrientation(LinearLayout.VERTICAL);
        libScroll.addView(libList, new FrameLayout.LayoutParams(-1, -2));
        libBox.addView(libScroll, new FrameLayout.LayoutParams(-1, -1));
        libIndex = new LinearLayout(this);
        libIndex.setOrientation(LinearLayout.VERTICAL);
        libIndex.setGravity(Gravity.CENTER_HORIZONTAL);
        libBox.addView(libIndex, lp(dp(28), -2, Gravity.END | Gravity.CENTER_VERTICAL, 0, dp(56), dp(EDGE - 8), 0));
        return libBox;
    }

    // ---------------------------------------------------------------- settings applied to the layout

    private void applySettings() {
        applyClocks();
        applyBlur();
        buildPages();
        showPage(page);
        updateRam();
    }

    /** The world clock's city (or none), and the weather for here and there. */
    private void applyClocks() {
        String[] c = prefs.city(0);
        worldClock.setVisibility(c == null ? View.GONE : View.VISIBLE);
        worldRule.setVisibility(c == null ? View.GONE : View.VISIBLE);
        if (c != null) worldClock.name.setText(Prefs.flag(c[4]));
        showWeather(weather.cached(places()));
        updateClocks();
    }

    /** Here first, then the side clocks that are on. */
    private String[][] places() {
        List<String[]> p = new ArrayList<>();
        p.add(here.get());
        if (prefs.city(0) != null) p.add(prefs.city(0));
        return p.toArray(new String[0][]);
    }

    /** The App Library blur, at its softest setting, on every blurred surface. */
    private void applyBlur() {
        for (BlurView v : blurs) {
            v.setAmount(0f);
            v.setBackdrop(wallBlur);
        }
    }

    private void showPage(int i) {
        boolean changed = page != i;
        page = i;
        View in = i == 0 ? homeBox : libBox, out = i == 0 ? libBox : homeBox;
        if (changed) {
            out.animate().cancel();
            in.animate().cancel();
            int keep = i;
            out.animate().alpha(0f).setDuration(160).setInterpolator(Ui.EASE).withLayer()
                    .withEndAction(() -> { if (page == keep) out.setVisibility(View.GONE); }).start();
            in.setAlpha(0f);
            in.setVisibility(View.VISIBLE);
            in.animate().alpha(1f).setDuration(220).setInterpolator(Ui.EASE).withLayer().start();
        } else {
            in.setAlpha(1f);
            in.setVisibility(View.VISIBLE);
            out.setVisibility(View.GONE);
        }
        styleTabs();
    }

    private void focusContent() {
        if (page == 0) {
            View v = cardAt(lastPos);
            if (v == null) v = firstCard();
            if (v != null) v.requestFocus();
        } else if (!rows.isEmpty()) {
            rows.get(clamp(lastRow, rows.size())).requestFocus();
        }
    }

    private View cardAt(int[] pos) {
        if (pos == null || pos[0] >= sections.size()) return null;
        List<View> c = sections.get(pos[0]).cards;
        return c.isEmpty() ? null : c.get(Math.min(pos[1], c.size() - 1));
    }

    private View firstCard() {
        for (Section s : sections) if (!s.cards.isEmpty()) return s.cards.get(0);
        return null;
    }

    private static int clamp(int i, int n) { return Math.max(0, Math.min(i, n - 1)); }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        apps.clear();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (Prefs.HIDDEN.contains(ai.packageName) || apps.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.removable = (ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
            a.icon = ri.loadIcon(pm);
            a.art = banner != null ? banner : a.icon;
            apps.put(a.pkg, a);
        }
    }

    /** Letters A–Z, then names starting with anything else, like iOS. */
    private static final Comparator<App> AZ = (x, y) -> {
        boolean ax = Character.isLetter(x.label.charAt(0)), ay = Character.isLetter(y.label.charAt(0));
        if (ax != ay) return ax ? -1 : 1;
        return x.label.compareToIgnoreCase(y.label);
    };

    private void buildPages() {
        List<App> all = new ArrayList<>(apps.values());
        Collections.sort(all, AZ);
        List<App> yours = new ArrayList<>();
        for (String p : prefs.favourites()) if (apps.containsKey(p)) yours.add(apps.get(p));
        List<App> others = new ArrayList<>(all);
        others.removeAll(yours);
        fillHome(yours, others);
        fillLibrary(all);
    }

    /** Six 16:9 tiles to a row, filling the width. */
    private int tileWidth() {
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * dp(EDGE + INSET);
        return (avail - (Prefs.COLUMNS - 1) * dp(GAP)) / Prefs.COLUMNS;
    }

    private void fillHome(List<App> yours, List<App> others) {
        homeList.removeAllViews();
        sections.clear();
        cardPos.clear();
        int w = tileWidth();
        homeList.addView(new View(this), new LinearLayout.LayoutParams(1, dp(CONTENT_TOP - BAR_H)));

        List<View> tiles = new ArrayList<>();
        for (App a : yours) tiles.add(tile(a));
        addSection(SEC_YOURS, R.drawable.ic_grid, "Your Apps:", tiles, w, 0);

        tiles = new ArrayList<>();
        for (App a : others) tiles.add(tile(a));
        addSection(SEC_OTHER, R.drawable.ic_grid, "Other Apps:", tiles, w, dp(SECTION_GAP));

        tiles = new ArrayList<>();
        for (Input in : inputs()) tiles.add(inputTile(in));
        addSection(SEC_INPUTS, R.drawable.ic_input, "Inputs:", tiles, w, dp(SECTION_GAP));
    }

    private void addSection(int kind, int icon, String title, List<View> tiles, int w, int top) {
        Section s = new Section(kind);
        int index = sections.size();
        sections.add(s);
        if (tiles.isEmpty()) return;
        homeList.addView(sectionHeader(icon, title), llp(-2, -2, -dp(INSET), top, 0, dp(14)));
        // One block behind the section's tiles. It reaches out by its padding, so tiles stay aligned
        // under the header whether the block is shown or not.
        int pad = dp(WRAP_PAD);
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        block.setClipToPadding(false);
        block.setPadding(pad, pad, pad, pad);
        if (prefs.sectionWrap()) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12) + pad);
            bg.setColor(Ui.CURRENT);
            block.setBackground(bg);
        }
        homeList.addView(block, llp(-1, -2, -pad, -pad, -pad, -pad));
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % Prefs.COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                block.addView(row, llp(-2, -2, 0, i == 0 ? 0 : dp(GAP), 0, 0));
            }
            View card = tiles.get(i);
            row.addView(card, llp(w, -2, i % Prefs.COLUMNS == 0 ? 0 : dp(GAP), 0, 0, 0));
            s.cards.add(card);
            cardPos.put(card, new int[]{index, i});
        }
    }

    private FrameLayout card() {
        FrameLayout card = new AspectFrame(this, 0.5f, dp(1));
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(roundOutline(12));
        // Z order only, so the grown tile sits above its neighbours; no shadow to render.
        card.setOutlineAmbientShadowColor(Color.TRANSPARENT);
        card.setOutlineSpotShadowColor(Color.TRANSPARENT);
        card.setBackgroundColor(0xFF1C1C1E);
        return card;
    }

    /** Focus: the tile zooms and the rest of its section dims; keeps its row in view. */
    private void addFocus(FrameLayout card) {
        card.setOnFocusChangeListener((v, has) -> {
            magnifyRow(v, has);
            // Focus left the tiles (menu bar, a panel): bring every section back to full brightness.
            if (!has) handler.post(() -> { if (!cardPos.containsKey(getCurrentFocus())) spotlight(null); });
            else spotlight(v);
            if (!has) return;
            int[] pos = cardPos.get(v);
            lastPos = pos;
            // The first row of Your apps keeps the photo in view; anything lower scrolls up.
            if (pos != null && pos[0] == 0 && pos[1] < Prefs.COLUMNS) homeScroll.smoothScrollTo(0, 0);
            else ensureVisible(homeScroll, (View) v.getParent(), dp(64));
        });
    }

    static final float DIMMED = 0.9f;
    static final int WRAP_PAD = 10;

    /** In the focused tile's section every other tile dims; other sections stay as they are. */
    private void spotlight(View focused) {
        int[] fp = focused == null ? null : cardPos.get(focused);
        for (int k = 0; k < sections.size(); k++) {
            for (View c : sections.get(k).cards) {
                float a = fp == null || fp[0] != k || c == focused ? 1f : DIMMED;
                if (c.getAlpha() != a) c.animate().alpha(a).setInterpolator(Ui.EASE).setDuration(220).start();
            }
        }
    }

    /** Only the selected tile grows, drawn above its neighbours; nothing else moves. */
    private void magnifyRow(View v, boolean has) {
        v.setTranslationZ(has ? 2 : 0);
        float scale = has ? FOCUS_SCALE : 1f;
        v.animate().scaleX(scale).scaleY(scale).setInterpolator(Ui.EASE).setDuration(has ? 260 : 200).withLayer().start();
    }

    private View tile(App app) {
        FrameLayout card = card();
        card.setTag(app);
        if (app.isBanner) {
            ImageView img = new ImageView(this);
            img.setImageDrawable(app.art);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(this);
            img.setImageDrawable(app.art);
            box.addView(img, llp(dp(28), dp(28), 0, 0, 0, dp(4)));
            TextView name = Ui.text(this, 11, 500, Ui.TEXT);
            name.setText(app.label);
            name.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(name);
            card.addView(box, new FrameLayout.LayoutParams(-1, -1));
        }
        addFocus(card);
        card.setOnClickListener(v -> launchApp(app));
        return card;
    }

    // ---------------------------------------------------------------- inputs

    /**
     * Google TV (this home screen) first, then HDMI 1, 2, 3… in order, then any other passthrough input
     * such as AV. The TV's Live TV app isn't listed: on these sets it opens the same AV/tuner screen.
     */
    private List<Input> inputs() {
        List<Input> out = new ArrayList<>();
        Input gtv = new Input();
        gtv.label = "Google TV";
        out.add(gtv);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<Input> found = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (!t.isPassthroughInput() || t.isHidden(this)) continue;
            Input in = new Input();
            in.id = t.getId();
            in.info = t;
            in.label = inputLabel(t);
            found.add(in);
        }
        Collections.sort(found, (a, b) -> {
            boolean ha = systemLabel(a.info).toUpperCase(Locale.ROOT).startsWith("HDMI");
            boolean hb = systemLabel(b.info).toUpperCase(Locale.ROOT).startsWith("HDMI");
            if (ha != hb) return ha ? -1 : 1;
            return systemLabel(a.info).compareToIgnoreCase(systemLabel(b.info));
        });
        out.addAll(found);
        return out;
    }

    /** Your name for it on the launcher, else the name given in the TV's settings, else e.g. "HDMI 2". */
    private String inputLabel(TvInputInfo t) {
        String mine = prefs.inputName(t.getId());
        if (mine != null) return mine;
        CharSequence c = t.loadCustomLabel(this);
        if (c != null && c.length() > 0) return c.toString();
        return systemLabel(t);
    }

    private String systemLabel(TvInputInfo t) {
        return t == null ? "" : String.valueOf(t.loadLabel(this));
    }

    private View inputTile(Input in) {
        FrameLayout card = card();
        card.setTag(in);
        card.setBackgroundColor(0xD92C2C2E);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView img = new ImageView(this);
        img.setImageResource(in.id == null ? R.drawable.ic_home : R.drawable.ic_input);
        img.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        box.addView(img, llp(dp(22), dp(22), 0, 0, 0, dp(6)));
        TextView name = Ui.caps(Ui.text(this, 12, 700, Ui.TEXT), 0.04f);
        name.setText(in.label);
        name.setGravity(Gravity.CENTER);
        name.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name, llp(-1, -2, dp(8), 0, dp(8), 0));
        card.addView(box, new FrameLayout.LayoutParams(-1, -1));
        addFocus(card);
        card.setOnClickListener(v -> switchInput(in));
        return card;
    }

    private void switchInput(Input in) {
        if (in.id == null) {
            // Google TV is this screen: go to the top of Home.
            homeScroll.smoothScrollTo(0, 0);
            lastPos = null;
            focusContent();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.id));
        if (!launch(i)) openPackage("com.tcl.suspension");
    }

    private static final String[] INPUT_NAMES = {"PlayStation", "Xbox", "Nintendo Switch", "Apple TV", "Chromecast",
            "Fetch", "Foxtel", "Set-top box", "Soundbar", "Blu-ray", "PC", "Laptop", "Camera"};

    private void openRename(Input in, View anchor) {
        openPanel("rename", anchor, 260, p -> {
            p.header("Rename " + systemLabel(in.info), null);
            p.row(0, "Type a name", null, R.drawable.ic_chevron, () -> typeName(in));
            for (String n : INPUT_NAMES) p.row(0, n, null, 0, () -> renameInput(in, n));
            p.gap();
            p.row(0, "Use the TV's name", null, 0, () -> renameInput(in, null));
        });
    }

    private void typeName(Input in) {
        closePanel(false);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setText(in.label);
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Name for " + systemLabel(in.info))
                .setView(field)
                .setPositiveButton("Save", (d, w) -> renameInput(in, field.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renameInput(Input in, String name) {
        prefs.setInputName(in.id, name);
        closePanel(false);
        buildPages();
        for (View v : sections.get(SEC_INPUTS).cards) {
            Input t = (Input) v.getTag();
            if (t.id != null && t.id.equals(in.id)) focusAfterLayout(v);
        }
    }

    // ---------------------------------------------------------------- library

    private void fillLibrary(List<App> all) {
        libList.removeAllViews();
        rows.clear();
        rowApps.clear();
        rowLetters.clear();
        String section = null;
        for (App a : all) {
            String letter = letterOf(a);
            if (!letter.equals(section)) {
                section = letter;
                TextView h = Ui.onGlass(Ui.text(this, 24, 700, Ui.TEXT));
                h.setText(letter);
                libList.addView(h, llp(-2, -2, dp(12), dp(rows.isEmpty() ? 4 : 24), 0, dp(8)));
            }
            libList.addView(libRow(a, rows.size()), new LinearLayout.LayoutParams(-1, dp(68)));
            View sep = new View(this);
            sep.setBackgroundColor(Ui.SEPARATOR);
            // From where the names start to the edge, as on iOS.
            libList.addView(sep, llp(-1, Math.max(1, dp(0.75f)), dp(12 + 48 + 20), 0, 0, 0));
            rowLetters.add(letter);
        }
        libIndex.removeAllViews();
        indexViews.clear();
        for (char ch = 'A'; ch <= 'Z' + 1; ch++) {
            String s = ch > 'Z' ? "#" : String.valueOf(ch);
            TextView t = Ui.onGlass(Ui.text(this, 12, 600, Ui.TEXT));
            t.setText(s);
            t.setGravity(Gravity.CENTER);
            libIndex.addView(t, new LinearLayout.LayoutParams(dp(20), dp(15)));
            indexViews.put(s, t);
        }
        lastRow = clamp(lastRow, Math.max(1, rows.size()));
    }

    private static String letterOf(App a) {
        char c = Character.toUpperCase(a.label.charAt(0));
        return c >= 'A' && c <= 'Z' ? String.valueOf(c) : "#";
    }

    private View libRow(App app, int index) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(12), 0);
        r.setFocusable(true);
        r.setId(View.generateViewId());
        r.setTag(app);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(roundOutline(11));
        r.addView(icon, llp(dp(48), dp(48), 0, 0, dp(20), 0));
        TextView name = Ui.onGlass(Ui.text(this, 19, 400, Ui.TEXT));
        name.setText(app.label);
        name.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(v -> launchApp(app));
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Ui.FOCUS : Color.TRANSPARENT);
            if (has) {
                lastRow = index;
                ensureVisible(libScroll, v, dp(56));
                markIndex(rowLetters.get(index));
            }
        });
        rows.add(r);
        rowApps.add(app);
        return r;
    }

    private void markIndex(String letter) {
        for (Map.Entry<String, TextView> e : indexViews.entrySet()) {
            e.getValue().setTextColor(e.getKey().equals(letter) ? Ui.BLUE : Ui.TEXT);
        }
    }

    private static void ensureVisible(ScrollView scroll, View v, int margin) {
        int top = 0;
        for (View p = v; p != null && p != scroll; p = (View) p.getParent()) top += p.getTop();
        top -= scroll.getPaddingTop();
        int bottom = top + v.getHeight();
        int y = scroll.getScrollY(), h = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
        if (top - margin < y) scroll.smoothScrollTo(0, Math.max(0, top - margin));
        else if (bottom + margin > y + h) scroll.smoothScrollTo(0, bottom + margin - h);
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (panel != null) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
            panel.key(e);
            return true;
        }
        View f = getCurrentFocus();
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean onCard = f != null && cardPos.containsKey(f);
        int row = f == null ? -1 : rows.indexOf(f);
        boolean hasMenu = onCard && (f.getTag() instanceof App || (f.getTag() instanceof Input && ((Input) f.getTag()).id != null))
                || row >= 0;

        if (code == KeyEvent.KEYCODE_MENU) {
            if (!down) { if (hasMenu) openItemMenu(f, false); else openInputs(); }
            return true;
        }
        // OK on a tile: tap opens it, hold opens its menu.
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && (onCard || row >= 0)) {
            if (down) {
                if (e.getRepeatCount() == 0) longPressed = false;
                else if (!longPressed && hasMenu) { longPressed = true; openItemMenu(f, true); }
            } else if (!longPressed) {
                f.performClick();
            }
            return true;
        }
        if (!down || f == null) return super.dispatchKeyEvent(e);

        int top = topItems.indexOf(f);
        if (top >= 0) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (top > 0) topItems.get(top - 1).requestFocus(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (top < topItems.size() - 1) topItems.get(top + 1).requestFocus(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: showPage(f == appsTab ? 1 : 0); focusContent(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: return true;
            }
        }
        if (onCard) { moveOnHome(f, code); return true; }
        if (row >= 0) { moveInLibrary(row, code); return true; }
        return super.dispatchKeyEvent(e);
    }

    /** Within a section by rows and columns; past its top or bottom row into the next non-empty section. */
    private void moveOnHome(View f, int code) {
        int[] pos = cardPos.get(f);
        List<View> s = sections.get(pos[0]).cards;
        int i = pos[1], n = s.size(), cols = Prefs.COLUMNS, col = i % cols;
        View target = null;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (col > 0) target = s.get(i - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (col < cols - 1 && i + 1 < n) target = s.get(i + 1); break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (i >= cols) { target = s.get(i - cols); break; }
                for (int k = pos[0] - 1; k >= 0 && target == null; k--) {
                    List<View> p = sections.get(k).cards;
                    if (p.isEmpty()) continue;
                    int lastRowStart = (p.size() - 1) / cols * cols;
                    target = p.get(Math.min(lastRowStart + col, p.size() - 1));
                }
                if (target == null) homeTab.requestFocus();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (i + cols < n) { target = s.get(i + cols); break; }
                if (i / cols < (n - 1) / cols) { target = s.get(n - 1); break; }
                for (int k = pos[0] + 1; k < sections.size() && target == null; k++) {
                    List<View> nx = sections.get(k).cards;
                    if (!nx.isEmpty()) target = nx.get(Math.min(col, nx.size() - 1));
                }
                break;
        }
        if (target != null) target.requestFocus();
    }

    /** Up/Down move a row; Left/Right jump to the previous/next letter. */
    private void moveInLibrary(int i, int code) {
        int n = rows.size(), target = -1;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: if (i > 0) target = i - 1; else appsTab.requestFocus(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (i < n - 1) target = i + 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                for (int k = i + 1; k < n; k++) if (!rowLetters.get(k).equals(rowLetters.get(i))) { target = k; break; }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                String cur = rowLetters.get(i);
                int k = i;
                while (k > 0 && rowLetters.get(k - 1).equals(cur)) k--;   // start of this letter
                if (k != i) { target = k; break; }
                if (k > 0) {                                              // start of the previous letter
                    String prev = rowLetters.get(k - 1);
                    while (k > 0 && rowLetters.get(k - 1).equals(prev)) k--;
                    target = k;
                }
                break;
            }
        }
        if (target >= 0) rows.get(target).requestFocus();
    }

    // ---------------------------------------------------------------- panels

    /** A blur panel listing rows. The selection is drawn by the panel, not by view focus. */
    final class Panel {
        final BlurView view;
        final ScrollView scroll;
        final LinearLayout list;
        final List<Row> items = new ArrayList<>();
        final View returnFocus;
        final String kind;
        Consumer<Panel> fill;
        Runnable onClose;
        int sel;
        boolean ignoreNextUp;

        Panel(String kind, View returnFocus) {
            this.kind = kind;
            this.returnFocus = returnFocus;
            view = new BlurView(MainActivity.this, dp(24));
            view.setDarken(0.30f);
            view.setAmount(0f);
            scroll = new HomeScrollView(MainActivity.this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            list = new LinearLayout(MainActivity.this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(dp(8), dp(8), dp(8), dp(8));
            scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
            view.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
        }

        void refill() {
            int keep = sel;
            list.removeAllViews();
            items.clear();
            fill.accept(this);
            select(Math.min(keep, items.size() - 1));
        }

        void header(String title, String status) {
            LinearLayout h = new LinearLayout(MainActivity.this);
            h.setGravity(Gravity.CENTER_VERTICAL);
            h.setPadding(dp(12), dp(8), dp(12), dp(8));
            TextView t = Ui.onGlass(Ui.text(MainActivity.this, 17, 600, Ui.TEXT));
            t.setText(title);
            h.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (status != null) {
                TextView s = Ui.onGlass(Ui.text(MainActivity.this, 13, 500, Ui.TEXT_2));
                s.setText(status);
                h.addView(s);
            }
            list.addView(h);
        }

        void section(String title) {
            TextView t = Ui.onGlass(Ui.caps(Ui.text(MainActivity.this, 11, 700, Ui.TEXT_3), 0.1f));
            t.setText(title);
            list.addView(t, llp(-2, -2, dp(12), dp(14), 0, dp(4)));
        }

        void gap() {
            list.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(-1, dp(8)));
        }

        void note(String text) {
            TextView t = Ui.onGlass(Ui.text(MainActivity.this, 13, 500, Ui.TEXT_2));
            t.setSingleLine(false);
            t.setLineSpacing(dp(3), 1f);
            t.setText(text);
            list.addView(t, llp(-1, -2, dp(12), dp(8), dp(12), dp(8)));
        }

        Row row(int icon, String label, String value, int trail, Runnable click) {
            return add(new Row(icon == 0 ? null : getDrawable(icon), true, label, null, value, trail), click);
        }

        /** A two-line row with the app's own icon, for notifications. */
        Row notice(Drawable icon, String title, String text, String time, Runnable click) {
            return add(new Row(icon, false, title, text, time, 0), click);
        }

        private Row add(Row r, Runnable click) {
            r.click = click;
            items.add(r);
            list.addView(r.view, new LinearLayout.LayoutParams(-1, dp(r.sub == null ? 40 : 60)));
            return r;
        }

        /** A setting changed with Left/Right (OK steps forward). */
        void value(String label, Supplier<String> value, IntConsumer change) {
            Row r = row(0, label, "‹  " + value.get() + "  ›", 0, null);
            r.change = d -> { change.accept(d); r.value.setText("‹  " + value.get() + "  ›"); };
            r.click = () -> r.change.accept(1);
        }

        void toggle(String label, Supplier<Boolean> on, Consumer<Boolean> set) {
            value(label, () -> on.get() ? "On" : "Off", d -> set.accept(!on.get()));
        }

        void select(int i) {
            if (items.isEmpty()) return;
            sel = Math.max(0, Math.min(i, items.size() - 1));
            for (int k = 0; k < items.size(); k++) items.get(k).paint(k == sel);
            View v = items.get(sel).view;
            int y = scroll.getScrollY(), h = scroll.getHeight();
            if (h > 0) {
                if (v.getTop() - dp(8) < y) scroll.smoothScrollTo(0, Math.max(0, v.getTop() - dp(48)));
                else if (v.getBottom() + dp(8) > y + h) scroll.smoothScrollTo(0, v.getBottom() + dp(8) - h);
            }
        }

        void key(KeyEvent e) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                // Act on release, so the press that opened the panel (hold OK) doesn't pick a row.
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (ignoreNextUp) { ignoreNextUp = false; return; }
                    Row r = items.isEmpty() ? null : items.get(sel);
                    if (r != null && r.click != null) r.click.run();
                }
                return;
            }
            if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { closePanel(true); return; }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: select(sel - 1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: select(sel + 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    Row r = items.isEmpty() ? null : items.get(sel);
                    if (r != null && r.change != null) r.change.accept(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    break;
            }
        }
    }

    final class Row {
        final LinearLayout view;
        final TextView label, sub, value;
        final GradientDrawable bg = new GradientDrawable();
        Runnable click;
        IntConsumer change;

        Row(Drawable icon, boolean tint, String text, String subText, String val, int trailIcon) {
            view = new LinearLayout(MainActivity.this);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(12), 0, dp(12), 0);
            bg.setCornerRadius(dp(14));
            view.setBackground(bg);
            if (icon != null) {
                ImageView lead = new ImageView(MainActivity.this);
                lead.setImageDrawable(icon);
                if (tint) lead.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
                else { lead.setClipToOutline(true); lead.setOutlineProvider(roundOutline(8)); }
                int s = subText == null ? 18 : 32;
                view.addView(lead, llp(dp(s), dp(s), 0, 0, dp(12), 0));
            }
            LinearLayout words = new LinearLayout(MainActivity.this);
            words.setOrientation(LinearLayout.VERTICAL);
            label = Ui.onGlass(Ui.text(MainActivity.this, 14, 500, Ui.TEXT));
            label.setText(text);
            label.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(label);
            sub = subText == null ? null : Ui.onGlass(Ui.text(MainActivity.this, 12, 400, Ui.TEXT_2));
            if (sub != null) {
                sub.setText(subText);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(sub, llp(-2, -2, 0, dp(4), 0, 0));
            }
            view.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
            value = Ui.onGlass(Ui.text(MainActivity.this, 13, 500, Ui.TEXT_2));
            if (val != null) {
                value.setText(val);
                view.addView(value, llp(-2, -2, dp(12), 0, 0, 0));
            }
            if (trailIcon != 0) {
                ImageView trail = new ImageView(MainActivity.this);
                trail.setImageResource(trailIcon);
                trail.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
                view.addView(trail, llp(dp(16), dp(16), dp(10), 0, 0, 0));
            }
        }

        void paint(boolean selected) {
            bg.setColor(selected ? Ui.FOCUS : Color.TRANSPARENT);
        }
    }

    /**
     * Opens a panel under its anchor (a menu-bar button or a tile), or down the right side when anchor
     * is null. It blurs a snapshot of the screen taken just before it opens.
     */
    private Panel openPanel(String kind, View anchor, int widthDp, Consumer<Panel> fill) {
        View returnFocus = panel != null ? panel.returnFocus : getCurrentFocus();
        if (panel != null) closePanel(false);
        Panel p = new Panel(kind, returnFocus);
        p.fill = fill;
        p.view.setBackdrop(Ui.blur(snapshot(), 0f));
        int w = dp(widthDp), screenW = root.getWidth(), screenH = root.getHeight();
        FrameLayout.LayoutParams lp;
        if (anchor == null) {
            lp = new FrameLayout.LayoutParams(w, screenH - dp(48), Gravity.TOP | Gravity.START);
            lp.leftMargin = screenW - w - dp(EDGE);
            lp.topMargin = dp(24);
            p.scroll.getLayoutParams().height = -1;
        } else {
            int[] at = new int[2];
            anchor.getLocationInWindow(at);
            int x = at[0] + anchor.getWidth() / 2 - w / 2;
            x = Math.max(dp(EDGE), Math.min(x, screenW - w - dp(EDGE)));
            int y = at[1] + anchor.getHeight() + dp(12);
            if (y + dp(160) > screenH) y = Math.max(dp(24), at[1] - dp(240));
            lp = new FrameLayout.LayoutParams(w, -2, Gravity.TOP | Gravity.START);
            lp.leftMargin = x;
            lp.topMargin = y;
            ((HomeScrollView) p.scroll).max = screenH - y - dp(24);
        }
        root.addView(p.view, lp);
        panel = p;
        fill.accept(p);
        p.select(0);
        p.view.setAlpha(0f);
        p.view.setTranslationY(-dp(8));
        p.view.animate().alpha(1f).translationY(0).setInterpolator(Ui.EASE).setDuration(240).withLayer().start();
        return p;
    }

    private void closePanel(boolean restoreFocus) {
        Panel p = panel;
        if (p == null) return;
        panel = null;
        if (p.onClose != null) p.onClose.run();
        p.view.animate().alpha(0f).translationY(-dp(4)).setInterpolator(Ui.EASE).setDuration(160).withLayer()
                .withEndAction(() -> root.removeView(p.view)).start();
        if (restoreFocus && p.returnFocus != null && p.returnFocus.isAttachedToWindow()) p.returnFocus.requestFocus();
        else if (restoreFocus) focusContent();
    }

    /** A 1/8-scale picture of the screen, for the panel's blur. */
    private Bitmap snapshot() {
        int w = Math.max(1, root.getWidth() / 8), h = Math.max(1, root.getHeight() / 8);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(w / (float) root.getWidth(), h / (float) root.getHeight());
        root.draw(c);
        return b;
    }

    // ---- Wi-Fi

    private BroadcastReceiver scanReceiver;

    private void openWifi() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        Panel p = openPanel("wifi", wifiBtn, 320, x -> fillWifi(x, wm));
        try { wm.startScan(); } catch (RuntimeException ignored) { }
        scanReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { if (panel == p) p.refill(); }
        };
        registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        p.onClose = () -> { unregisterReceiver(scanReceiver); scanReceiver = null; };
    }

    private void fillWifi(Panel p, WifiManager wm) {
        Runnable settings = () -> { closePanel(false); launch(new Intent(Settings.ACTION_WIFI_SETTINGS)); };
        p.header("Wi-Fi", wm.isWifiEnabled() ? "On" : "Off");
        WifiInfo info = wm.getConnectionInfo();
        String current = info == null || info.getNetworkId() == -1 ? null : ssid(info.getSSID());
        if (current != null) {
            String band = info.getFrequency() >= 5000 ? "5 GHz" : "2.4 GHz";
            p.row(wifiLevelIcon(wm, info.getRssi()), current, band, R.drawable.ic_check, settings);
        } else {
            p.row(R.drawable.ic_wifi_0, wm.isWifiEnabled() ? "Not connected" : "Wi-Fi is off", null, 0, settings);
        }
        List<ScanResult> others = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (current != null) seen.add(current);
        List<ScanResult> results;
        try { results = wm.getScanResults(); } catch (SecurityException e) { results = new ArrayList<>(); }
        Collections.sort(results, (a, b) -> Integer.compare(b.level, a.level));
        for (ScanResult r : results) {
            String name = r.SSID;
            if (name == null || name.isEmpty() || !seen.add(name)) continue;
            others.add(r);
            if (others.size() == 6) break;
        }
        if (!others.isEmpty()) {
            p.section("Other networks");
            for (ScanResult r : others) {
                boolean locked = r.capabilities != null && (r.capabilities.contains("WPA") || r.capabilities.contains("WEP") || r.capabilities.contains("SAE"));
                p.row(wifiLevelIcon(wm, r.level), r.SSID, null, locked ? R.drawable.ic_lock : 0, settings);
            }
        }
        p.gap();
        p.row(0, "Wi-Fi settings", null, R.drawable.ic_chevron, settings);
    }

    private static String ssid(String raw) {
        if (raw == null || raw.contains("unknown ssid")) return "Wi-Fi";
        return raw.replace("\"", "");
    }

    private int wifiLevelIcon(WifiManager wm, int rssi) {
        int max = Math.max(1, wm.getMaxSignalLevel()), lvl = wm.calculateSignalLevel(rssi);
        int bars = 1 + Math.round(2f * lvl / max);
        return bars >= 3 ? R.drawable.ic_wifi_3 : bars == 2 ? R.drawable.ic_wifi_2 : R.drawable.ic_wifi_1;
    }

    private void updateWifiIcon() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        wifiBtn.setImageResource(info == null || info.getNetworkId() == -1 ? R.drawable.ic_wifi_0 : wifiLevelIcon(wm, info.getRssi()));
    }

    // ---- Bluetooth

    private void openBluetooth() {
        openPanel("bluetooth", btBtn, 320, this::fillBluetooth);
    }

    private void fillBluetooth(Panel p) {
        Runnable settings = () -> { closePanel(false); launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); };
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        boolean on = bt != null && bt.isEnabled();
        p.header("Bluetooth", bt == null ? "Unavailable" : on ? "On" : "Off");
        List<BluetoothDevice> devices = new ArrayList<>();
        try { if (on) devices.addAll(bt.getBondedDevices()); } catch (SecurityException ignored) { }
        Collections.sort(devices, (a, b) -> Boolean.compare(connected(b), connected(a)));
        if (!devices.isEmpty()) p.section("Devices");
        for (BluetoothDevice d : devices) {
            String name = d.getName() == null ? d.getAddress() : d.getName();
            p.row(deviceIcon(d, name), name, connected(d) ? "Connected" : "Not connected", 0, settings);
        }
        if (devices.isEmpty()) p.note(on ? "No paired devices" : "Turn Bluetooth on in Settings");
        p.gap();
        p.row(0, "Pair new device", null, R.drawable.ic_chevron, settings);
    }

    private static boolean connected(BluetoothDevice d) {
        try {
            return (Boolean) d.getClass().getMethod("isConnected").invoke(d);
        } catch (Exception e) {
            return false;
        }
    }

    private static int deviceIcon(BluetoothDevice d, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("controller") || n.contains("gamepad") || n.contains("xbox") || n.contains("dualsense")
                || n.contains("dualshock")) return R.drawable.ic_gamepad;
        BluetoothClass c = d.getBluetoothClass();
        int major = c == null ? 0 : c.getMajorDeviceClass();
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return R.drawable.ic_headphones;
        if (major == BluetoothClass.Device.Major.PERIPHERAL || n.contains("remote")) return R.drawable.ic_remote;
        return R.drawable.ic_bluetooth;
    }

    // ---- Inputs panel

    private void openInputs() {
        View anchor = inputsBtn.getVisibility() == View.VISIBLE ? inputsBtn : settingsBtn;
        openPanel("inputs", anchor, 260, this::fillInputs);
    }

    private void fillInputs(Panel p) {
        p.header("Inputs", null);
        for (Input in : inputs()) {
            p.row(in.id == null ? R.drawable.ic_home : R.drawable.ic_input, in.label, null, 0,
                    () -> { closePanel(false); switchInput(in); });
        }
    }

    // ---- Notifications

    private void updateBadge() {
        bellBtn.badge = !NotificationsService.visible().isEmpty();
        bellBtn.invalidate();
    }

    private void openNotifications() {
        openPanel("notifications", bellBtn, 380, this::fillNotifications);
    }

    private void fillNotifications(Panel p) {
        List<StatusBarNotification> list = NotificationsService.visible();
        p.header("Notifications", list.isEmpty() ? null : String.valueOf(list.size()));
        if (!NotificationsService.connected()) {
            p.note("Notification access is off. Allow BeeshTV Launcher under "
                    + "Settings → Apps → Special app access → Notification access.");
            p.row(0, "Open settings", null, R.drawable.ic_chevron, () -> { closePanel(false); openListenerSettings(); });
            return;
        }
        if (list.isEmpty()) { p.note("No new notifications"); return; }
        p.row(0, "Clear all", null, 0, () -> { NotificationsService.clearAll(); closePanel(true); });
        p.gap();
        PackageManager pm = getPackageManager();
        long now = System.currentTimeMillis();
        for (StatusBarNotification n : list) {
            Notification no = n.getNotification();
            CharSequence title = no.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = no.extras.getCharSequence(Notification.EXTRA_TEXT);
            Drawable icon;
            String appName;
            try {
                icon = pm.getApplicationIcon(n.getPackageName());
                appName = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(n.getPackageName(), 0)));
            } catch (PackageManager.NameNotFoundException e) {
                icon = getDrawable(R.drawable.ic_bell);
                appName = n.getPackageName();
            }
            String head = title == null || title.length() == 0 ? appName : title.toString();
            String body = text == null || text.length() == 0 ? appName : text.toString();
            String when = String.valueOf(DateUtils.getRelativeTimeSpanString(n.getPostTime(), now, DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE));
            p.notice(icon, head, body, when, () -> {
                closePanel(false);
                PendingIntent pi = no.contentIntent;
                try { if (pi != null) pi.send(); } catch (PendingIntent.CanceledException ignored) { }
                if ((no.flags & Notification.FLAG_AUTO_CANCEL) != 0) NotificationsService.dismiss(n);
            });
        }
    }

    // ---- Settings and About

    private void openSettings() {
        openPanel("settings", settingsBtn, 380, this::fillSettings);
    }

    private void fillSettings(Panel p) {
        p.header("Settings", null);
        p.row(R.drawable.ic_settings, "TV settings", null, R.drawable.ic_chevron,
                () -> { closePanel(false); launch(new Intent(Settings.ACTION_SETTINGS)); });

        p.section("Look");
        p.toggle("Section background", prefs::sectionWrap, v -> { prefs.setSectionWrap(v); buildPages(); focusContent(); });

        p.section("Wallpaper");
        p.value("Style", () -> Prefs.WALL_STYLES[prefs.wallStyle()], d -> {
            prefs.setWallStyle(prefs.wallStyle() + d);
            rotate.run();
            walls.refreshIfDue(false, rotate::run);
        });
        p.value("Change every", () -> prefs.wallMinutes() + " min",
                d -> { prefs.setWallMinutes(Prefs.nextOf(Prefs.WALL_MINUTES, prefs.wallMinutes(), d)); scheduleRotation(); });
        p.row(0, "Next photo", walls.describe(), 0, () -> rotate.run());
        p.row(0, "Get new photos", null, 0, () -> walls.refreshIfDue(true, rotate::run));

        p.section("World clock");
        p.value("City", () -> prefs.cityName(0), d -> { prefs.stepCity(0, d); applyClocks(); refreshWeather(true); });

        p.gap();
        p.row(0, "Permissions", setupComplete() ? "All set" : "Needs attention", R.drawable.ic_chevron, this::openSetup);
        p.row(R.drawable.ic_info, "About", null, R.drawable.ic_chevron, this::openAbout);
    }

    private void openAbout() {
        openPanel("about", settingsBtn, 380, this::fillAbout);
    }

    private void fillAbout(Panel p) {
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) { }
        p.header("BeeshTV Launcher", version);
        p.note("A quiet home screen for Android TV: your apps, the time where your people are, and a "
                + "landscape that changes with the time of day.");
        p.row(0, "Made by Bishwo Bhusal", "github.com/bishwofic", R.drawable.ic_chevron, () -> {
            closePanel(false);
            launch(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bishwofic")));
        });

        p.section("This TV");
        String maker = Build.MANUFACTURER.isEmpty() ? "" : Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1);
        p.row(0, "Model", maker + " " + Build.MODEL.replace('_', ' '), 0, null);
        p.row(0, "Android", Build.VERSION.RELEASE, 0, null);
        p.row(0, "Memory", ramText(), 0, null);
        p.row(0, "Apps", String.valueOf(apps.size()), 0, null);

        p.section("Credits");
        p.row(0, "Photos", "wallhaven.cc", 0, null);
        p.row(0, "Weather", "Open-Meteo.com", 0, null);
        p.row(0, "Typeface", "Schibsted Grotesk", 0, null);
        p.gap();
        p.row(0, "Back to Settings", null, R.drawable.ic_chevron, this::openSettings);
    }

    // ---- First run: permissions and Home

    static final int REQ_LOCATION = 41, REQ_HOME = 42;

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationOn() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    private boolean notificationsAllowed() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(this, NotificationsService.class));
    }

    private boolean isHome() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && getPackageName().equals(r.activityInfo.packageName);
    }

    private boolean setupComplete() {
        return hasLocation() && locationOn() && notificationsAllowed() && isHome();
    }

    private void openSetup() {
        openPanel("setup", settingsBtn, 400, this::fillSetup);
    }

    /** What the launcher needs, each with its state; selecting one opens the TV's own screen for it. */
    private void fillSetup(Panel p) {
        p.header("Set up BeeshTV", null);
        p.note("Allow these so the weather, Wi-Fi panel and notifications work. Each opens the TV's own "
                + "screen; press Back to come back here. The time zone comes from the TV.");
        boolean loc = hasLocation(), on = locationOn(), notes = notificationsAllowed(), home = isHome();
        p.row(0, "Location, for local weather", loc ? "Allowed" : "Allow", loc ? R.drawable.ic_check : R.drawable.ic_chevron,
                loc ? null : () -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION));
        if (loc && !on) p.row(0, "Turn Location on", "Off", R.drawable.ic_chevron, () -> {
            if (!launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))) launch(new Intent(Settings.ACTION_SETTINGS));
        });
        p.row(0, "Notifications, for the bell", notes ? "Allowed" : "Allow", notes ? R.drawable.ic_check : R.drawable.ic_chevron,
                notes ? null : this::openListenerSettings);
        p.row(0, "Use as Home screen", home ? "Yes" : "Set", home ? R.drawable.ic_check : R.drawable.ic_chevron,
                home ? null : this::requestHome);
        if (!home) p.note("On Google TV the built-in home screen can keep the Home button. The README shows "
                + "how to switch it off from a computer.");
        p.gap();
        p.row(0, "Done", null, 0, () -> closePanel(true));
    }

    private void openListenerSettings() {
        String component = new ComponentName(this, NotificationsService.class).flattenToString();
        Intent detail = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component);
        if (launch(detail) || launch(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return;
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void requestHome() {
        RoleManager rm = getSystemService(RoleManager.class);
        try {
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
                return;
            }
        } catch (RuntimeException ignored) { }
        if (!launch(new Intent(Settings.ACTION_HOME_SETTINGS))) launch(new Intent(Settings.ACTION_SETTINGS));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_LOCATION) return;
        // Now that location is allowed, find where the TV is and fetch its weather.
        here.forceRefresh(() -> { applyClocks(); refreshWeather(true); });
        if (panel != null && "setup".equals(panel.kind)) panel.refill();
    }

    // ---- Per-tile menu

    private void openItemMenu(View anchor, boolean fromLongPress) {
        Object tag = anchor.getTag();
        Panel p;
        if (tag instanceof Input) {
            Input in = (Input) tag;
            p = openPanel("input", anchor, 240, x -> {
                x.header(in.label, null);
                x.row(0, "Switch to " + in.label, null, 0, () -> { closePanel(false); switchInput(in); });
                x.row(0, "Rename", null, R.drawable.ic_chevron, () -> openRename(in, anchor));
            });
        } else {
            App app = (App) tag;
            int[] pos = cardPos.get(anchor);
            boolean inYours = prefs.favourites().contains(app.pkg);
            boolean onHomeYours = page == 0 && pos != null && pos[0] == SEC_YOURS;
            p = openPanel("app", anchor, 240, x -> {
                x.header(app.label, null);
                x.row(0, "Open", null, 0, () -> { closePanel(false); launchApp(app); });
                if (onHomeYours) {
                    x.row(0, "Move left", null, 0, () -> moveFavourite(app, -1));
                    x.row(0, "Move right", null, 0, () -> moveFavourite(app, 1));
                }
                x.row(0, inYours ? "Move to Other apps" : "Move to Your apps", null, 0, () -> moveSection(app));
                if (app.removable) x.row(0, "Uninstall", null, 0, () -> {
                    closePanel(false);
                    launch(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)));
                });
                x.row(0, "App info", null, R.drawable.ic_chevron, () -> {
                    closePanel(false);
                    launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)));
                });
            });
        }
        p.ignoreNextUp = fromLongPress;
    }

    /** Your apps ⇄ Other apps. Focus follows the app to where it landed. */
    private void moveSection(App app) {
        List<String> f = prefs.favourites();
        boolean toYours = !f.contains(app.pkg);
        if (toYours) f.add(app.pkg); else f.remove(app.pkg);
        prefs.setFavourites(f);
        closePanel(false);
        buildPages();
        if (page == 1) { lastRow = Math.max(0, rowApps.indexOf(app)); focusContent(); return; }
        Section s = sections.get(toYours ? SEC_YOURS : SEC_OTHER);
        for (View v : s.cards) if (v.getTag() == app) { focusAfterLayout(v); return; }
        focusContent();
    }

    private void moveFavourite(App app, int dir) {
        List<String> f = prefs.favourites();
        // Skip over apps that aren't installed, so every press visibly moves the tile.
        int i = f.indexOf(app.pkg), j = i + dir;
        while (j >= 0 && j < f.size() && !apps.containsKey(f.get(j))) j += dir;
        if (i < 0 || j < 0 || j >= f.size()) return;
        f.remove(i);
        f.add(j, app.pkg);
        prefs.setFavourites(f);
        buildPages();
        for (View v : sections.get(SEC_YOURS).cards) if (v.getTag() == app) { focusAfterLayout(v); break; }
    }

    /** Focus a freshly built tile once it has a position, so scrolling to it lands in the right place. */
    private void focusAfterLayout(View v) {
        lastPos = cardPos.get(v);
        homeList.post(() -> { if (v.isAttachedToWindow()) v.requestFocus(); });
    }

    // ---------------------------------------------------------------- clocks, RAM and weather

    private static CharSequence timeText(Date now, TimeZone tz, boolean is24, float suffix) {
        SimpleDateFormat t = new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault());
        t.setTimeZone(tz);
        SpannableStringBuilder s = new SpannableStringBuilder(t.format(now));
        if (!is24) {
            SimpleDateFormat a = new SimpleDateFormat("a", Locale.ENGLISH);
            a.setTimeZone(tz);
            int start = s.length();
            s.append(" ").append(a.format(now).toLowerCase(Locale.ROOT));
            s.setSpan(new RelativeSizeSpan(suffix), start, s.length(), 0);
            s.setSpan(new ForegroundColorSpan(Ui.TEXT_2), start, s.length(), 0);
        }
        return s;
    }

    private void updateClocks() {
        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(timeText(now, TimeZone.getDefault(), is24, 0.45f));
        date.setText(new SimpleDateFormat("d MMM, EEE", Locale.getDefault()).format(now));
        String[] c = prefs.city(0);
        if (c != null) worldClock.time.setText(timeText(now, TimeZone.getTimeZone(c[1]), is24, 0.65f));
        if (wallSlot >= 0 && wallSlot != Wallpapers.slotNow()) rotate.run();
    }

    /** "0.9/1.8 GB": memory in use out of what Android can use. */
    private String ramText() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gb = 1024.0 * 1024 * 1024;
        return String.format(Locale.US, "%.1f/%.1f GB", (mi.totalMem - mi.availMem) / gb, mi.totalMem / gb);
    }

    private void updateRam() {
        if (ram.getVisibility() == View.VISIBLE) ram.setText(ramText());
    }

    private void refreshWeather(boolean force) {
        weather.refreshIfDue(places(), force, this::showWeather);
    }

    /** Index 0 is here (under the main clock), then the world clock when it's on. */
    private void showWeather(Weather.Now[] now) {
        Weather.Now h = now == null || now.length == 0 ? null : now[0];
        localWeatherRow.setVisibility(View.VISIBLE);
        localIcon.setVisibility(h == null ? View.GONE : View.VISIBLE);
        localIcon.setImageDrawable(h == null ? null : getDrawable(Weather.icon(h.code, h.day)));
        localWeather.setText(h == null ? "" : h.temp + "°  ·  ");
        if (prefs.city(0) != null) {
            Weather.Now n = now == null || now.length < 2 ? null : now[1];
            worldClock.icon.setImageDrawable(n == null ? null : getDrawable(Weather.icon(n.code, n.day)));
            worldClock.temp.setText(n == null ? "" : n.temp + "°  " + Weather.label(n.code));
        }
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Next photo after the interval, or sooner when the time of day changes. */
    private void scheduleRotation() {
        handler.removeCallbacks(rotate);
        long interval = prefs.wallMinutes() * 60_000L;
        long left = wallKey == null ? 0 : interval - (SystemClock.elapsedRealtime() - wallShownAt);
        left = Math.min(left, Wallpapers.msToNextSlot() + 2000);
        handler.postDelayed(rotate, Math.max(1000, left));
    }

    /** Decode and blur off the main thread, then crossfade the new photo in. */
    private void showWallpaper(String key) {
        if (key == null) return;
        wallKey = key;
        wallSlot = Wallpapers.slotNow();
        wallShownAt = SystemClock.elapsedRealtime();
        walls.refreshIfDue(false, () -> { if (wallKey == null || wallKey.startsWith("asset:")) rotate.run(); });
        new Thread(() -> {
            Bitmap full = Wallpapers.decode(this, key);
            // A light blur over the whole photo so the tiles and clock sit forward of it.
            Bitmap bm = full == null ? null : Ui.soften(full);
            if (full != null && bm != full) full.recycle();
            Bitmap blurred = null;
            if (bm != null) {
                Bitmap small = Bitmap.createScaledBitmap(bm, bm.getWidth() / 4, bm.getHeight() / 4, true);
                blurred = Ui.blur(small, 0f);
                small.recycle();
            }
            Bitmap b2 = blurred;
            runOnUiThread(() -> crossfade(bm, b2));
        }).start();
    }

    private void crossfade(Bitmap bm, Bitmap blurred) {
        if (bm == null || isDestroyed()) return;
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 500 : 1600).setInterpolator(Ui.EASE).withLayer()
                .withEndAction(() -> wallBack.setImageDrawable(null)).start();
        firstWallpaper = false;
        wallBlur = blurred;
        applyBlur();
    }

    // ---------------------------------------------------------------- entrance and helpers

    private void playEntrance() {
        View[] parts = {topBar, homeBox};
        for (int i = 0; i < parts.length; i++) {
            View v = parts[i];
            v.setAlpha(0f);
            v.setTranslationY(dp(10));
            v.animate().alpha(1f).translationY(0).setInterpolator(Ui.EASE).setDuration(500)
                    .setStartDelay(80 + i * 80L).withLayer().start();
        }
    }

    private void launchApp(App app) {
        launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private boolean launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private ViewOutlineProvider roundOutline(float radiusDp) {
        float r = dp(radiusDp);
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        };
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** A container with a fixed height-to-width ratio. */
    static final class AspectFrame extends FrameLayout {
        private final float ratio;
        private final int extra;
        AspectFrame(Context c, float ratio, int extra) { super(c); this.ratio = ratio; this.extra = extra; }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.round(width * ratio) + extra, MeasureSpec.EXACTLY));
        }
    }

    /**
     * ScrollView that never auto-scrolls to focus (we scroll ourselves), can cap its height, and can fade
     * its content out towards the top edge once it has scrolled, so rows slip under the menu bar.
     */
    static class HomeScrollView extends ScrollView {
        int max = Integer.MAX_VALUE;
        private int fadeEnd;
        private Paint fadePaint;
        private LinearGradient fadeShader;
        private final Matrix fadeMatrix = new Matrix();
        HomeScrollView(Context c) { super(c); }

        /** Fully hidden above solidUntil, fading back in by clearAt (both from the top edge). */
        void fadeUnder(int solidUntil, int clearAt) {
            fadeEnd = clearAt;
            fadeShader = new LinearGradient(0, solidUntil, 0, clearAt, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
            fadePaint = new Paint();
            fadePaint.setShader(fadeShader);
            fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        }

        @Override protected void dispatchDraw(Canvas c) {
            int y = getScrollY(), w = getWidth();
            if (fadeEnd <= 0 || y <= 0) { super.dispatchDraw(c); return; }
            // Below the band: draw as usual. Inside it: draw into a small layer and erase towards the top.
            c.save();
            c.clipRect(-w, y + fadeEnd, 2 * w, y + getHeight() + w);
            super.dispatchDraw(c);
            c.restore();
            int sc = c.saveLayer(0, y, w, y + fadeEnd, null);
            super.dispatchDraw(c);
            fadeMatrix.setTranslate(0, y);
            fadeShader.setLocalMatrix(fadeMatrix);
            c.drawRect(0, y, w, y + fadeEnd, fadePaint);
            c.restoreToCount(sc);
        }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
        @Override protected void onMeasure(int w, int h) {
            if (max != Integer.MAX_VALUE) h = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
            super.onMeasure(w, h);
        }
    }

    /** An icon button that can show a small dot, for unread notifications. */
    static final class BadgeView extends ImageView {
        boolean badge;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        BadgeView(Context c) {
            super(c);
            dot.setColor(Ui.BLUE);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (!badge) return;
            float r = Ui.dp(getContext(), 3.5f);
            c.drawCircle(getWidth() * 0.7f, getHeight() * 0.28f, r, dot);
        }
    }
}
