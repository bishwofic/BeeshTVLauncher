package com.beeshtv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dynamic wallpapers, like the macOS desktop pictures that change through the day. Photos come from
 * wallhaven.cc (no key needed for safe-for-work search) in three styles, landscapes, cityscapes and
 * the current season, and are kept separately for morning, day, evening and night.
 *
 * Wallhaven's search matches loosely, so every photo's tags are checked: it has to show the style
 * (a landscape, a city...) at the right time of day, and anything tagged as art, a render, a game or
 * a person is skipped. Each style and time keeps up to 30 photos at 1920x1080, topped up every two
 * days. One bundled photo per time of day covers the first run and offline days.
 */
final class Wallpapers {
    static final String API = "https://wallhaven.cc/api/v1/";
    static final String UA = "BeeshTV-Home/3.2 (Android TV launcher)";
    static final long REFRESH_MS = 48L * 60 * 60 * 1000;
    static final int KEEP = 30, PER_FETCH = 8;
    static final String[] SLOTS = {"morning", "day", "evening", "night"};
    static final String[] STYLES = {"landscapes", "cityscapes", "seasons"};

    // Single-word exclusions only: wallhaven drops every result when an excluded tag has a space.
    static final String NOT_ART = " -artwork -anime -illustration -painting -render -women -men";

    static final String[] BAD = {"artwork", "digital art", "illustration", "paint", "render", "anime", "cgi",
            "3d", "fantasy", "science fiction", "video game", "women", "men", "girl", "boy", "infrared", "text",
            "logo", "watermark", "blurred", "war", "fan art", "concept art"};
    static final String[][] PLACE = {
            {"landscape", "nature", "mountains", "lake", "forest", "hills", "valley", "sea", "river", "field",
                    "coast", "beach", "waterfall", "desert", "canyon", "meadow"},
            {"cityscape", "city", "skyline", "skyscraper", "urban", "city lights", "building", "buildings",
                    "street", "bridge", "architecture"},
    };
    static final String[][] SEASON_TAGS = {
            {"spring", "flowers", "blossom", "cherry blossom", "tulips"},
            {"summer", "beach", "sunflower", "green"},
            {"autumn", "fall", "fall foliage", "autumn leaves", "orange leaves"},
            {"winter", "snow", "frost", "snowy"},
    };
    static final String[] SEASONS = {"spring", "summer", "autumn", "winter"};
    static final String[][] SLOT_TAGS = {
            {"sunrise", "dawn", "morning", "morning mist", "mist", "fog"},
            null, // day: anything that isn't night, dusk or dawn
            {"sunset", "dusk", "golden hour", "evening", "sunset glow", "twilight"},
            {"night", "stars", "milky way", "night sky", "starry night", "moon", "city lights", "night lights"},
    };
    static final String[] NOT_DAY = {"night", "stars", "milky way", "night sky", "sunset", "dusk", "sunrise",
            "dawn", "dark", "city lights", "twilight"};

    interface Listener { void onNewPhotos(); }

    private final Context ctx;
    private final SharedPreferences sp;
    private final Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private volatile boolean fetching;

    Wallpapers(Context c, Prefs prefs) {
        ctx = c.getApplicationContext();
        this.prefs = prefs;
        this.sp = prefs.raw();
        // Photos from earlier builds were stored loose in walls/; the folders by style and time replace them.
        File[] old = new File(ctx.getFilesDir(), "walls").listFiles((d, n) -> n.endsWith(".jpg") || n.endsWith(".tmp"));
        //noinspection ResultOfMethodCallIgnored
        if (old != null) for (File f : old) f.delete();
    }

    /** 0 morning (5–11), 1 day (11–17), 2 evening (17–20), 3 night (20–5). */
    static int slotNow() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 5 && h < 11 ? 0 : h >= 11 && h < 17 ? 1 : h >= 17 && h < 20 ? 2 : 3;
    }

    /** Milliseconds until the next change of time of day. */
    static long msToNextSlot() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int next = h < 5 ? 5 : h < 11 ? 11 : h < 17 ? 17 : h < 20 ? 20 : 29;
        Calendar t = (Calendar) c.clone();
        t.set(Calendar.HOUR_OF_DAY, 0);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        t.add(Calendar.HOUR_OF_DAY, next);
        return Math.max(1000, t.getTimeInMillis() - c.getTimeInMillis());
    }

    /** 0 spring … 3 winter, flipped for the southern hemisphere (worked out from the time zone). */
    static int seasonNow() {
        String tz = TimeZone.getDefault().getID();
        boolean south = tz.startsWith("Australia/") || tz.startsWith("Pacific/Auckland") || tz.startsWith("Antarctica/")
                || tz.startsWith("America/Argentina") || tz.equals("America/Sao_Paulo") || tz.equals("America/Santiago")
                || tz.equals("America/Montevideo") || tz.equals("Africa/Johannesburg") || tz.equals("Indian/Mauritius");
        int m = Calendar.getInstance().get(Calendar.MONTH); // 0 = January
        int north = m >= 2 && m <= 4 ? 0 : m >= 5 && m <= 7 ? 1 : m >= 8 && m <= 10 ? 2 : 3;
        return south ? (north + 2) % 4 : north;
    }

    /** "Evening · Autumn" or "Morning", for the Settings panel. */
    String describe() {
        String slot = cap(SLOTS[slotNow()]);
        return prefs.wallStyle() == 2 ? slot + " · " + cap(SEASONS[seasonNow()]) : slot;
    }

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private File folder(int style, int slot) {
        String name = STYLES[style] + (style == 2 ? "-" + SEASONS[seasonNow()] : "") + "-" + SLOTS[slot];
        File f = new File(new File(ctx.getFilesDir(), "walls"), name);
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    private static File[] photos(File dir) {
        File[] f = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        return f == null ? new File[0] : f;
    }

    /** Photos for now: this style and time of day, or every time of day when not following the clock. */
    List<String> pool() {
        int style = prefs.wallStyle();
        boolean dynamic = true;
        List<String> out = new ArrayList<>();
        int[] slots = dynamic ? new int[]{slotNow()} : new int[]{0, 1, 2, 3};
        for (int s : slots) for (File f : photos(folder(style, s))) out.add(f.getAbsolutePath());
        if (out.size() < 3) for (int s : slots) out.add("asset:wallpapers/" + SLOTS[s] + ".jpg");
        return out;
    }

    /** A random photo that wasn't among the last few shown. */
    String next() {
        List<String> pool = pool();
        if (pool.isEmpty()) return null;
        int memory = Math.min(pool.size() / 2, 10);
        List<String> fresh = new ArrayList<>(pool);
        fresh.removeAll(recent);
        if (fresh.isEmpty()) fresh = pool;
        String pick = fresh.get(rnd.nextInt(fresh.size()));
        recent.addLast(pick);
        while (recent.size() > memory) recent.removeFirst();
        return pick;
    }

    static Bitmap decode(Context c, String key) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = key.startsWith("asset:")
                ? c.getAssets().open(key.substring(6)) : new java.io.FileInputStream(key)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) {
            return null;
        }
    }

    /** Tops up the current style and time of day (or the emptiest time when not following the clock). */
    void refreshIfDue(boolean force, Listener l) {
        if (fetching) return;
        int style = prefs.wallStyle(), slot = slotNow();
        if (!true) {
            int fewest = Integer.MAX_VALUE;
            for (int s = 0; s < 4; s++) {
                int n = photos(folder(style, s)).length;
                if (n < fewest) { fewest = n; slot = s; }
            }
        }
        File dir = folder(style, slot);
        String key = "walls_at_" + dir.getName();
        boolean due = photos(dir).length < 8 || System.currentTimeMillis() - sp.getLong(key, 0) > REFRESH_MS;
        if (!force && !due) return;
        fetching = true;
        int s = slot;
        io.execute(() -> {
            int added = 0;
            try {
                added = fetch(style, s, dir);
            } catch (Exception ignored) {
                // Offline or the API changed: keep what we have, try again next time.
            } finally {
                fetching = false;
            }
            if (added > 0) {
                sp.edit().putLong(key, System.currentTimeMillis()).apply();
                main.post(l::onNewPhotos);
            }
        });
    }

    private String[] queries(int style, int slot) {
        if (style == 0) switch (slot) {
            case 0: return new String[]{"+landscape +sunrise", "+mountains +sunrise", "+nature +sunrise"};
            case 1: return new String[]{"+landscape +mountains", "+landscape +lake", "+nature +lake"};
            case 2: return new String[]{"+landscape +sunset", "+mountains +sunset"};
            default: return new String[]{"+landscape +night", "+landscape +stars", "+mountains +stars"};
        }
        if (style == 1) switch (slot) {
            case 0: return new String[]{"+cityscape +sunrise", "+city +morning", "+skyline -night"};
            case 1: return new String[]{"+cityscape -night -sunset", "+skyline -night"};
            case 2: return new String[]{"+cityscape +sunset", "+city +sunset"};
            default: return new String[]{"+cityscape +night", "+city +lights"};
        }
        String season = SEASONS[seasonNow()];
        switch (slot) {
            case 0: return new String[]{"+" + season + " +sunrise", "+" + season + " +landscape"};
            case 1: return new String[]{"+" + season + " +landscape", "+" + season + " +nature"};
            case 2: return new String[]{"+" + season + " +sunset", "+" + season + " +landscape"};
            default: return new String[]{"+" + season + " +night"};
        }
    }

    private int fetch(int style, int slot, File dir) throws Exception {
        String[] qs = queries(style, slot);
        String q = qs[rnd.nextInt(qs.length)] + NOT_ART;
        String seed = Integer.toString(100000 + rnd.nextInt(900000), 36);
        String url = API + "search?q=" + URLEncoder.encode(q, "UTF-8")
                + "&categories=100&purity=100&atleast=1920x1080&ratios=16x9&sorting=random&seed=" + seed;
        JSONArray data = new JSONObject(new String(get(url), "UTF-8")).getJSONArray("data");
        int added = 0;
        for (int i = 0; i < data.length() && added < PER_FETCH; i++) {
            JSONObject w = data.getJSONObject(i);
            String id = w.getString("id");
            File out = new File(dir, id + ".jpg");
            if (out.exists() || !"sfw".equals(w.optString("purity"))) continue;
            Thread.sleep(1500); // stay well under the API's 45 requests a minute
            if (!fits(id, style, slot)) continue;
            Bitmap bm = download(w.getString("path"));
            if (bm == null) continue;
            File tmp = new File(dir, id + ".tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                bm.compress(Bitmap.CompressFormat.JPEG, 88, fo);
            }
            bm.recycle();
            if (tmp.renameTo(out)) added++;
        }
        prune(dir);
        return added;
    }

    /** True when the tags say: a real photo, of this style, at this time of day. */
    private boolean fits(String id, int style, int slot) throws Exception {
        JSONObject d = new JSONObject(new String(get(API + "w/" + id), "UTF-8")).getJSONObject("data");
        if (!"general".equals(d.optString("category"))) return false;
        JSONArray arr = d.optJSONArray("tags");
        List<String> tags = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) tags.add(arr.getJSONObject(i).optString("name").toLowerCase(Locale.ROOT));
        for (String t : tags) for (String b : BAD) if (t.contains(b)) return false;
        String[] place = style == 2 ? SEASON_TAGS[seasonNow()] : PLACE[style];
        if (!any(tags, place)) return false;
        if (slot == 1) return !any(tags, NOT_DAY);
        return any(tags, SLOT_TAGS[slot]);
    }

    private static boolean any(List<String> tags, String[] wanted) {
        for (String w : wanted) if (tags.contains(w)) return true;
        return false;
    }

    /** Downloads and center-crops to exactly 1920x1080, decoding big originals at a reduced size. */
    private Bitmap download(String url) throws Exception {
        byte[] bytes = get(url);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth < 1920 || o.outHeight < 1080) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 1920 && o.outHeight / (sample * 2) >= 1080) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        float scale = Math.max(1920f / src.getWidth(), 1080f / src.getHeight());
        int cw = Math.round(1920 / scale), ch = Math.round(1080 / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, 1920, 1080),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    private void prune(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg") || n.endsWith(".tmp"));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int kept = 0;
        // The photo kept while shuffle is off is never cleared away.
        String pinned = prefs.wallPinned();
        for (File f : files) {
            if (f.getPath().equals(pinned)) continue;
            //noinspection ResultOfMethodCallIgnored
            if (f.getName().endsWith(".tmp") || ++kept > KEEP) f.delete();
        }
    }

    /** Whether a photo (a file, or one built into the app) is still there to show. */
    static boolean exists(Context c, String key) {
        if (key == null) return false;
        if (!key.startsWith("asset:")) return new File(key).exists();
        try (InputStream in = c.getAssets().open(key.substring(6))) { return true; } catch (IOException e) { return false; }
    }

    static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
