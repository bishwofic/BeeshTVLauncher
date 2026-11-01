package com.beeshtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Neutral vignette over the photo: darker at the top (menu bar, clock), darker at the bottom (world
 * clock) and a little at the corners. Black only, so the photo keeps its own colours.
 *
 * Home paints it into the photo itself (bake), once per photo: as a view over the whole screen it was
 * one more full screen for the TV to fill on every frame of every animation.
 */
final class Vignette extends View {
    /**
     * The photo cut to the screen's shape (as CENTER_CROP would show it) with the vignette painted in,
     * at the photo's own resolution.
     */
    static android.graphics.Bitmap bake(android.graphics.Bitmap photo, int screenW, int screenH) {
        float aspect = screenW / (float) screenH;
        int pw = photo.getWidth(), ph = photo.getHeight();
        int w = pw, h = Math.round(pw / aspect);
        if (h > ph) { h = ph; w = Math.round(ph * aspect); }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int sx = (pw - w) / 2, sy = (ph - h) / 2;
        c.drawBitmap(photo, new android.graphics.Rect(sx, sy, sx + w, sy + h), new android.graphics.Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint top = new Paint(), bottom = new Paint(), edges = new Paint();
        shade(top, bottom, edges, w, h);
        draw(c, top, bottom, edges, w, h);
        return out;
    }

    private static void shade(Paint top, Paint bottom, Paint edges, int w, int h) {
        top.setShader(new LinearGradient(0, 0, 0, h * 0.38f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
        bottom.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0x4D000000, Shader.TileMode.CLAMP));
        edges.setShader(new RadialGradient(w / 2f, h / 2f, w * 0.72f,
                new int[]{0x00000000, 0x00000000, 0x4D000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    private static void draw(Canvas c, Paint top, Paint bottom, Paint edges, int w, int h) {
        c.drawRect(0, 0, w, h, edges);
        c.drawRect(0, 0, w, h * 0.38f, top);
        c.drawRect(0, h * 0.5f, w, h, bottom);
    }

    private final Paint top = new Paint(), bottom = new Paint(), edges = new Paint();

    Vignette(Context c) {
        super(c);
        // Drawn once into a layer, so animations above it never repaint the gradients.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { shade(top, bottom, edges, w, h); }

    @Override
    protected void onDraw(Canvas c) { draw(c, top, bottom, edges, getWidth(), getHeight()); }
}
