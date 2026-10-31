package com.beeshtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.animation.PathInterpolator;
import android.view.animation.Interpolator;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Palette, type and small view helpers shared by the home screen and its panels. */
final class Ui {
    private Ui() { }

    // Everything sits on the photo or on glass, so text is white in three strengths.
    static final int TEXT = 0xFFFFFFFF, TEXT_2 = 0xCCFFFFFF, TEXT_3 = 0x8CFFFFFF;
    // Focus is a soft white pill over the blur, everywhere; the current tab gets a fainter one.
    static final int FOCUS = 0x3DFFFFFF, CURRENT = 0x1FFFFFFF;
    static final int SEPARATOR = 0x40FFFFFF;
    /** iOS system blue, for the A–Z index in the app library. */
    static final int BLUE = 0xFF0A84FF;

    /** One ease-out curve for every transition (fast start, long soft landing). */
    static final Interpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private static final Map<Integer, Typeface> FACES = new HashMap<>();

    /** Schibsted Grotesk at an exact weight; the system sans if the file fails to load. */
    static Typeface face(Context c, int weight) {
        Typeface t = FACES.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(c.getAssets(), "fonts/SchibstedGrotesk.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null) t = Typeface.create(Typeface.DEFAULT, weight, false);
        FACES.put(weight, t);
        return t;
    }

    /** All-caps label with open tracking, for tabs and section titles. */
    static TextView caps(TextView t, float tracking) {
        t.setAllCaps(true);
        t.setLetterSpacing(tracking);
        return t;
    }

    static TextView text(Context c, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face(c, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    /** Text whose digits keep one width, so clocks and temperatures don't jitter. */
    static TextView figures(Context c, float sp, int weight, int color) {
        TextView t = text(c, sp, weight, color);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    /** White text on glass or photo gets a faint shadow so it holds up on bright skies. */
    static TextView onGlass(TextView t) {
        t.setShadowLayer(dp(t.getContext(), 6), 0, dp(t.getContext(), 1), 0x40000000);
        return t;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Drawable icon(Context c, int res, int color, int sizeDp) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        int s = dp(c, sizeDp);
        d.setBounds(0, 0, s, s);
        return d;
    }

    /**
     * The App Library blur, from a small (1/8 scale) picture of the screen: shrink it 4x, run three
     * box-blur passes (close to a gaussian), lift the saturation so colours glow through the way they
     * do on iOS, then scale back up. amount 0 is a soft blur, 1 a heavy one.
     */
    static Bitmap blur(Bitmap small, float amount) {
        int w = small.getWidth(), h = small.getHeight();
        int sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);
        Bitmap tiny = Bitmap.createScaledBitmap(small, sw, sh, true);
        int[] px = new int[sw * sh];
        tiny.getPixels(px, 0, sw, 0, 0, sw, sh);
        tiny.recycle();
        int r = 1 + Math.round(amount * 4);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, sw, sh, r, true);
            boxBlur(px, sw, sh, r, false);
        }
        saturate(px, 1.35f);
        Bitmap blurred = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createScaledBitmap(blurred, w, h, true);
        blurred.recycle();
        return out;
    }

    /**
     * A light, even blur for the wallpaper: half the size, one box-blur pass, then drawn back up to full
     * screen by the ImageView. Also a quarter of the memory of the full photo.
     */
    static Bitmap soften(Bitmap full) {
        int w = Math.max(1, full.getWidth() / 2), h = Math.max(1, full.getHeight() / 2);
        Bitmap small = Bitmap.createScaledBitmap(full, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        boxBlur(px, w, h, 1, true);
        boxBlur(px, w, h, 1, false);
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** One horizontal or vertical box-blur pass with a running sum, edges clamped. */
    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] line = new int[len];
        int span = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = line[Math.max(0, Math.min(len - 1, i))];
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                px[horizontal ? l * w + i : i * w + l] = 0xFF000000 | (sr / span) << 16 | (sg / span) << 8 | (sb / span);
                int out = line[Math.max(0, i - r)], in = line[Math.min(len - 1, i + r + 1)];
                sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                sb += (in & 255) - (out & 255);
            }
        }
    }

    private static void saturate(int[] px, float s) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            float y = 0.299f * r + 0.587f * g + 0.114f * b;
            r = clamp255(y + (r - y) * s); g = clamp255(y + (g - y) * s); b = clamp255(y + (b - y) * s);
            px[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
    }

    private static int clamp255(float v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }
}
