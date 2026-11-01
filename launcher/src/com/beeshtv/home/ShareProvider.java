package com.beeshtv.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets BEESH Browser show the same photo and weather as Home, read-only:
 *   content://com.beeshtv.home.share/wallpaper   the photo on Home now (a JPEG)
 *   content://com.beeshtv.home.share/weather     one row: temp, label, code, day (1 or 0)
 *   content://com.beeshtv.home.share/screen      Home as it last looked, small (a JPEG)
 * Nothing else is shared, and nothing can be written.
 */
public class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "com.beeshtv.home.share";

    /** Called by Home whenever its photo or weather changes. */
    static void remember(Context c, String wallpaperKey, Weather.Now weather) {
        SharedPreferences.Editor e = c.getSharedPreferences("share", Context.MODE_PRIVATE).edit();
        if (wallpaperKey != null) e.putString("wallpaper", wallpaperKey);
        if (weather != null) {
            e.putInt("temp", weather.temp).putInt("code", weather.code).putBoolean("day", weather.day)
                    .putBoolean("has_weather", true);
        }
        e.apply();
    }

    /** Draws Home, at a quarter size, to a file the browser can blur behind its own screens. */
    static void rememberScreen(Context c, android.view.View root) {
        if (root == null || root.getWidth() == 0) return;
        try {
            int w = root.getWidth() / 4, h = root.getHeight() / 4;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            cv.scale(w / (float) root.getWidth(), h / (float) root.getHeight());
            root.draw(cv);
            File tmp = new File(c.getCacheDir(), "screen.tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
            }
            b.recycle();
            tmp.renameTo(new File(c.getCacheDir(), "screen.jpg"));
        } catch (Exception | OutOfMemoryError ignored) { }
    }

    private SharedPreferences sp() {
        return getContext().getSharedPreferences("share", Context.MODE_PRIVATE);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException(uri.toString());
        if ("screen".equals(uri.getLastPathSegment())) {
            File f = new File(getContext().getCacheDir(), "screen.jpg");
            if (!f.exists()) throw new FileNotFoundException("no screen yet");
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            return new AssetFileDescriptor(fd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        if (!"wallpaper".equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        String key = sp().getString("wallpaper", null);
        if (key == null) throw new FileNotFoundException("no wallpaper yet");
        try {
            // Built-in photos are stored uncompressed in the APK (build.sh: -0 jpg), so they open directly.
            if (key.startsWith("asset:")) return getContext().getAssets().openFd(key.substring(6));
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(key), ParcelFileDescriptor.MODE_READ_ONLY);
            return new AssetFileDescriptor(fd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (java.io.IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!"weather".equals(uri.getLastPathSegment())) return null;
        MatrixCursor c = new MatrixCursor(new String[]{"temp", "label", "code", "day"});
        SharedPreferences sp = sp();
        if (sp.getBoolean("has_weather", false)) {
            int code = sp.getInt("code", 0);
            c.addRow(new Object[]{sp.getInt("temp", 0), Weather.label(code), code, sp.getBoolean("day", true) ? 1 : 0});
        }
        return c;
    }

    @Override public String getType(Uri uri) { return "weather".equals(uri.getLastPathSegment()) ? null : "image/jpeg"; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
