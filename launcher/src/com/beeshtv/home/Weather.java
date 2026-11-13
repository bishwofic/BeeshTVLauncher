package com.beeshtv.home;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for every world clock place in one open-meteo.com request (free, no key). The last
 * reply is kept, so a cold start shows something straight away.
 */
final class Weather {
    /** At most once an hour: weather changes slowly, and each fetch wakes the network. */
    static final long REFRESH_MS = 60L * 60 * 1000;

    static final class Now {
        int temp, hi, lo, code;
        boolean day;
    }

    interface Listener { void onWeather(Now[] now); }

    private final SharedPreferences sp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean fetching;

    Weather(SharedPreferences sp) { this.sp = sp; }

    private static String key(String[][] places) {
        StringBuilder b = new StringBuilder();
        for (String[] p : places) b.append(p[0]).append('|');
        return b.toString();
    }

    /** The last reply, if it was for these places. */
    Now[] cached(String[][] places) {
        if (!key(places).equals(sp.getString("weather_key", ""))) return null;
        try {
            return parse(new JSONArray(sp.getString("weather", "[]")), places.length);
        } catch (Exception e) {
            return null;
        }
    }

    void refreshIfDue(String[][] places, boolean force, Listener l) {
        String key = key(places);
        boolean same = key.equals(sp.getString("weather_key", ""));
        if (fetching || (!force && same && System.currentTimeMillis() - sp.getLong("weather_at", 0) < REFRESH_MS)) return;
        fetching = true;
        io.execute(() -> {
            try {
                StringBuilder lat = new StringBuilder(), lon = new StringBuilder();
                for (String[] p : places) {
                    if (lat.length() > 0) { lat.append(','); lon.append(','); }
                    lat.append(p[2]);
                    lon.append(p[3]);
                }
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=1";
                String body = new String(Wallpapers.get(url), "UTF-8").trim();
                // One place comes back as an object, several as an array.
                JSONArray arr = body.startsWith("[") ? new JSONArray(body) : new JSONArray().put(new JSONObject(body));
                Now[] now = parse(arr, places.length);
                sp.edit().putString("weather", arr.toString()).putString("weather_key", key)
                        .putLong("weather_at", System.currentTimeMillis()).apply();
                main.post(() -> l.onWeather(now));
            } catch (Exception ignored) {
                // Offline: keep showing the last reply.
            } finally {
                fetching = false;
            }
        });
    }

    private static Now[] parse(JSONArray arr, int count) throws Exception {
        Now[] out = new Now[count];
        for (int i = 0; i < count && i < arr.length(); i++) {
            JSONObject j = arr.getJSONObject(i);
            Now n = new Now();
            JSONObject cur = j.getJSONObject("current");
            n.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            n.code = cur.getInt("weather_code");
            n.day = cur.optInt("is_day", 1) == 1;
            JSONObject d = j.getJSONObject("daily");
            n.hi = (int) Math.round(d.getJSONArray("temperature_2m_max").getDouble(0));
            n.lo = (int) Math.round(d.getJSONArray("temperature_2m_min").getDouble(0));
            out[i] = n;
        }
        return out;
    }

    /** WMO weather codes, as open-meteo reports them. */
    static String label(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        if (code == 2) return day ? R.drawable.ic_w_cloud_sun : R.drawable.ic_w_cloud_moon;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_storm;
        return R.drawable.ic_w_rain;
    }
}
