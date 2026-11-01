package com.beeshtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * The selected app's name in a small pill with a point underneath it, floating over the tile, as in the
 * macOS Dock. It's the launcher's own glass, as on its panels: a blurred picture of the screen behind it,
 * lined up with the screen, under the same light dark tint, with a faint edge. Home moves it along with
 * the tile as it grows and slides.
 */
final class NameTip extends TextView {
    private final Glass glass;

    NameTip(Context c) {
        super(c);
        float point = Ui.dp(c, 4);
        glass = new Glass(this, Ui.dp(c, 7), Ui.dp(c, 9), point, Math.max(1f, Ui.dp(c, 0.75f)));
        setBackground(glass);
        setTypeface(Ui.face(c, 500));
        setTextSize(10f);
        setTextColor(0xFFF2F2F7);
        setSingleLine(true);
        setGravity(Gravity.CENTER);
        setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 4) + Math.round(point));
    }

    /** The blurred photo, for wherever no picture of what's behind the pill has been taken yet. */
    void setBackdrop(Bitmap b) {
        glass.backdrop = b;
        invalidate();
    }

    /** What's behind the pill, frosted: a picture of the patch of screen at `at` (screen pixels). */
    void setBehind(Bitmap b, RectF at) {
        glass.behind = b;
        glass.behindAt.set(at);
        invalidate();
    }

    /** One outline round the pill and its point, so the edge runs round both without a seam. */
    private static final class Glass extends Drawable {
        private final View owner;
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final RectF dst = new RectF();
        private final int[] loc = new int[2];
        private final float radius, pointW, pointH;
        Bitmap backdrop, behind;
        final RectF behindAt = new RectF();

        Glass(View owner, float radius, float pointW, float pointH, float stroke) {
            this.owner = owner;
            this.radius = radius;
            this.pointW = pointW;
            this.pointH = pointH;
            // The panels' tint (BlurView at darken 0.30), a shade deeper for such small text.
            tint.setColor(0x5C000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
            edge.setColor(0x33FFFFFF);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            // Clockwise from the top left.
            float i = edge.getStrokeWidth() / 2f, l = bounds.left + i, t = bounds.top + i, r = bounds.right - i;
            float b = bounds.bottom - pointH, cx = bounds.exactCenterX(), d = 2 * radius;
            shape.reset();
            shape.moveTo(l + radius, t);
            shape.lineTo(r - radius, t);
            shape.arcTo(new RectF(r - d, t, r, t + d), 270, 90);
            shape.lineTo(r, b - radius);
            shape.arcTo(new RectF(r - d, b - d, r, b), 0, 90);
            shape.lineTo(cx + pointW / 2f, b);
            shape.lineTo(cx, bounds.bottom - i);
            shape.lineTo(cx - pointW / 2f, b);
            shape.lineTo(l + radius, b);
            shape.arcTo(new RectF(l, b - d, l + d, b), 90, 90);
            shape.lineTo(l, t + radius);
            shape.arcTo(new RectF(l, t, l + d, t + d), 180, 90);
            shape.close();
        }

        @Override
        public void draw(Canvas c) {
            int save = c.save();
            c.clipPath(shape);
            // Everything kept registered to the screen, so the glass shows what's behind it.
            owner.getLocationInWindow(loc);
            if (backdrop != null && !backdrop.isRecycled()) {
                View root = owner.getRootView();
                dst.set(-loc[0], -loc[1], root.getWidth() - loc[0], root.getHeight() - loc[1]);
                c.drawBitmap(backdrop, null, dst, image);
            }
            if (behind != null && !behind.isRecycled()) {
                dst.set(behindAt);
                dst.offset(-loc[0], -loc[1]);
                c.drawBitmap(behind, null, dst, image);
            }
            c.drawPath(shape, tint);
            c.restoreToCount(save);
            c.drawPath(shape, edge);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
