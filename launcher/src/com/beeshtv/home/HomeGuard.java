package com.beeshtv.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;

import java.util.Arrays;
import java.util.List;

/**
 * Keeps the Home button on BeeshTV. Google TV gives its own home screen a higher priority than any
 * other, so Home goes back to it even when BeeshTV is the Home app. Only a computer (adb) can switch
 * it off; without one, this catches the Home key and, should the stock home screen still come up (after
 * a restart, say), opens BeeshTV over it. It does nothing while the stock home screen is switched off.
 */
public class HomeGuard extends AccessibilityService {
    /** Stock home screens that take the Home button back while they're enabled. */
    static final List<String> STOCK = Arrays.asList(
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher");

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || !stockEnabled(this)) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) goHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        // Only the stock home screen itself, not its other windows (the settings side panel, say).
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || e.getPackageName() == null || e.getClassName() == null) return;
        String cls = e.getClassName().toString();
        if (STOCK.contains(e.getPackageName().toString()) && (cls.endsWith(".HomeActivity")
                || cls.endsWith(".MainActivity") || cls.endsWith(".LauncherActivity"))) goHome();
    }

    @Override public void onInterrupt() { }

    private void goHome() {
        try {
            startActivity(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException ignored) { }
    }

    /** Whether a stock home screen is installed and enabled, so it would take Home back. */
    static boolean stockEnabled(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String p : STOCK) {
            try {
                if (pm.getApplicationInfo(p, 0).enabled) return true;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return false;
    }

    static boolean enabled(Context c) {
        AccessibilityManager am = c.getSystemService(AccessibilityManager.class);
        if (am == null) return false;
        ComponentName me = new ComponentName(c, HomeGuard.class);
        for (AccessibilityServiceInfo i : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (me.equals(ComponentName.unflattenFromString(i.getId()))) return true;
        }
        return false;
    }
}
