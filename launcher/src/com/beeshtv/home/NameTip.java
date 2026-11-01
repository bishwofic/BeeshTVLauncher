package com.beeshtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.TextView;

/**
 * The selected app's name in a small dark pill with a point underneath it, floating over the tile, as
 * in the macOS Dock. Home moves it along with the tile as it grows and slides.
 */
final class NameTip extends TextView {
    NameTip(Context c) {
        super(c);
        float point = Ui.dp(c, 6);
        setBackground(new Pill(Ui.dp(c, 11), Ui.dp(c, 14), point, Math.max(1f, Ui.dp(c, 0.75f))));
        setTypeface(Ui.face(c, 500));
        setTextSize(15);
        setTextColor(0xFFF2F2F7);
        setSingleLine(true);
        setGravity(Gravity.CENTER);
        setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 8) + Math.round(point));
    }

    /** Dark glass with a faint edge: one outline round the pill and its point, so the edge has no seam. */
    private static final class Pill extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final float radius, pointW, pointH;

        Pill(float radius, float pointW, float pointH, float stroke) {
            this.radius = radius;
            this.pointW = pointW;
            this.pointH = pointH;
            fill.setColor(0xF52A2A2E);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
            edge.setColor(0x38FFFFFF);
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
            c.drawPath(shape, fill);
            c.drawPath(shape, edge);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
