package com.beeshtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * A blurred window onto whatever is behind it, like the iOS App Library: the pre-blurred screen,
 * kept registered to the screen, under a light dark tint. No border, no highlights. The blur is
 * computed once per photo or per panel, so drawing costs nothing extra.
 */
final class BlurView extends FrameLayout {
    private Bitmap backdrop;
    private final float radius;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF(), box = new RectF();
    private final int[] loc = new int[2];
    private float darken = 0.28f;

    BlurView(Context c, float radius) {
        super(c);
        this.radius = radius;
        setWillNotDraw(false);
        if (radius > 0) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), BlurView.this.radius);
                }
            });
            setClipToOutline(true);
        }
        setAmount(0.5f);
    }

    void setBackdrop(Bitmap b) {
        backdrop = b;
        invalidate();
    }

    /** Extra darkening on top of the usual tint, for surfaces that hold a lot of text. */
    void setDarken(float d) {
        darken = d;
        invalidate();
    }

    /** 0 = light blur, 1 = heavy. Heavier blur gets a touch more tint so text stays easy to read. */
    void setAmount(float a) {
        tint.setColor(((int) ((darken + 0.08f * a) * 255)) << 24);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (backdrop != null && !backdrop.isRecycled()) {
            getLocationInWindow(loc);
            View root = getRootView();
            // Undo our own scale (panels animate in) so the backdrop stays registered to the screen.
            float sx = getScaleX() == 0 ? 1 : 1 / getScaleX(), sy = getScaleY() == 0 ? 1 : 1 / getScaleY();
            dst.set(-loc[0] * sx, -loc[1] * sy, (root.getWidth() - loc[0]) * sx, (root.getHeight() - loc[1]) * sy);
            c.drawBitmap(backdrop, null, dst, image);
        }
        box.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(box, radius, radius, tint);
    }
}
