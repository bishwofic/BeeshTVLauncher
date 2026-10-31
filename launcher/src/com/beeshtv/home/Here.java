package com.beeshtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Where the TV is, for the weather under the main clock. Uses the TV's own last known location (Wi-Fi
 * based, granted at install) and names it with the geocoder; if there's no location it asks ipwho.is,
 * and failing that picks the city from the time zone. Cached for six hours.
 * The result has the same shape as a Prefs.CITIES entry: name, time zone, latitude, longitude, country.
 */
final class Here {
    static final long REFRESH_MS = 6L * 60 * 60 * 1000;

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    Here(Context c, SharedPreferences sp) {
        ctx = c.getApplicationContext();
        this.sp = sp;
    }

    String[] get() {
        String s = sp.getString("here", null);
        if (s != null) {
            String[] p = s.split("\\|", -1);
            if (p.length == 5) return p;
        }
        String tz = TimeZone.getDefault().getID();
        for (String[] c : Prefs.CITIES) if (c[1].equals(tz)) return c;
        return Prefs.CITIES[0];
    }

    /** Look again now, e.g. right after location permission was granted. */
    void forceRefresh(Runnable changed) {
        sp.edit().putLong("here_at", 0).apply();
        refreshIfDue(changed);
    }

    void refreshIfDue(Runnable changed) {
        if (busy || System.currentTimeMillis() - sp.getLong("here_at", 0) < REFRESH_MS) return;
        busy = true;
        new Thread(() -> {
            try {
                String[] found = fromDevice();
                if (found == null) found = fromIp();
                if (found != null) {
                    String old = sp.getString("here", "");
                    String now = String.join("|", found);
                    sp.edit().putString("here", now).putLong("here_at", System.currentTimeMillis()).apply();
                    if (!now.equals(old)) main.post(changed);
                }
            } catch (Exception ignored) {
                // Offline: keep the last place.
            } finally {
                busy = false;
            }
        }).start();
    }

    private String[] fromDevice() {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String p : new String[]{"fused", LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER,
                LocationManager.GPS_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (RuntimeException ignored) { }
        }
        if (best == null) return null;
        String name = null, cc = "";
        try {
            if (Geocoder.isPresent()) {
                List<Address> a = new Geocoder(ctx, Locale.getDefault()).getFromLocation(best.getLatitude(), best.getLongitude(), 1);
                if (a != null && !a.isEmpty()) {
                    name = a.get(0).getLocality();
                    if (name == null) name = a.get(0).getSubAdminArea();
                    cc = a.get(0).getCountryCode() == null ? "" : a.get(0).getCountryCode();
                }
            }
        } catch (Exception ignored) { }
        if (name == null) {
            // No geocoder on many TVs: name it after the nearest known city, or the IP lookup's city.
            String[] ip = fromIp();
            if (ip != null && distanceKm(best, ip) < 80) { name = ip[0]; cc = ip[4]; }
            else {
                String[] near = nearest(best);
                name = near[0];
                cc = near[4];
            }
        }
        return new String[]{name, TimeZone.getDefault().getID(), fmt(best.getLatitude()), fmt(best.getLongitude()), cc};
    }

    private String[] fromIp() {
        try {
            JSONObject j = new JSONObject(new String(Wallpapers.get(
                    "https://ipwho.is/?fields=success,city,latitude,longitude,country_code"), "UTF-8"));
            if (!j.optBoolean("success")) return null;
            return new String[]{j.getString("city"), TimeZone.getDefault().getID(),
                    fmt(j.getDouble("latitude")), fmt(j.getDouble("longitude")), j.optString("country_code")};
        } catch (Exception e) {
            return null;
        }
    }

    private static String[] nearest(Location l) {
        String[] best = Prefs.CITIES[0];
        double d = Double.MAX_VALUE;
        for (String[] c : Prefs.CITIES) {
            double k = distanceKm(l, c);
            if (k < d) { d = k; best = c; }
        }
        return best;
    }

    private static double distanceKm(Location l, String[] c) {
        float[] r = new float[1];
        Location.distanceBetween(l.getLatitude(), l.getLongitude(), Double.parseDouble(c[2]), Double.parseDouble(c[3]), r);
        return r[0] / 1000.0;
    }

    private static String fmt(double v) { return String.format(Locale.US, "%.3f", v); }
}
