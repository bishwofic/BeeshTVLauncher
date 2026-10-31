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
 */
final class Vignette extends View {
    private final Paint top = new Paint(), bottom = new Paint(), edges = new Paint();

    Vignette(Context c) {
        super(c);
        // Drawn once into a layer, so animations above it never repaint the gradients.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        top.setShader(new LinearGradient(0, 0, 0, h * 0.38f, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
        bottom.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0x4D000000, Shader.TileMode.CLAMP));
        edges.setShader(new RadialGradient(w / 2f, h / 2f, w * 0.72f,
                new int[]{0x00000000, 0x00000000, 0x4D000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, edges);
        c.drawRect(0, 0, w, h * 0.38f, top);
        c.drawRect(0, h * 0.5f, w, h, bottom);
    }
}
