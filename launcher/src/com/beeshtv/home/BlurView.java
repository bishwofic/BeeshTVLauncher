package com.beeshtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * A blurred window onto whatever is behind it, like the iOS App Library: the pre-blurred screen,
 * kept registered to the screen, under a light dark tint. No border, no highlights. The blur is
 * computed once per photo or per panel, so drawing costs nothing extra.
 */
class BlurView extends FrameLayout {
    private Bitmap backdrop;
    private final float radius;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF(), box = new RectF();
    private final int[] loc = new int[2];
    private float darken = 0.28f;
    private final Path round = new Path();

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

    /**
     * Keep the rounded shape but let children draw past it, so a focused tile can grow over the edge.
     * The blur and tint are clipped to the shape instead of the whole view.
     */
    void letChildrenOut() {
        setClipToOutline(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    void setBackdrop(Bitmap b) {
        backdrop = b;
        invalidate();
    }

    /** A light blur: the blurred photo only partly covers the real one (255 = fully blurred). */
    void setBackdropAlpha(int a) {
        image.setAlpha(a);
        invalidate();
    }

    /** A tint of any colour in place of the usual dark one, e.g. a faint white for frosted glass. */
    void setTint(int argb) {
        tint.setColor(argb);
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
        box.set(0, 0, getWidth(), getHeight());
        int save = c.save();
        if (!getClipToOutline() && radius > 0) {
            round.reset();
            round.addRoundRect(box, radius, radius, Path.Direction.CW);
            c.clipPath(round);
        }
        if (backdrop != null && !backdrop.isRecycled()) {
            getLocationInWindow(loc);
            View root = getRootView();
            // Undo our own scale (panels animate in) so the backdrop stays registered to the screen.
            float sx = getScaleX() == 0 ? 1 : 1 / getScaleX(), sy = getScaleY() == 0 ? 1 : 1 / getScaleY();
            dst.set(-loc[0] * sx, -loc[1] * sy, (root.getWidth() - loc[0]) * sx, (root.getHeight() - loc[1]) * sy);
            c.drawBitmap(backdrop, null, dst, image);
        }
        c.drawRoundRect(box, radius, radius, tint);
        c.restoreToCount(save);
    }
}
