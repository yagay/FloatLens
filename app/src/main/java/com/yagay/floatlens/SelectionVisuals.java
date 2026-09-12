package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/** Shared high-contrast drawing rules for every drag-time selection frame. */
final class SelectionVisuals {
    private static final int OUTER_COLOR = 0xE6000000;
    private static final int INNER_COLOR = Color.WHITE;

    static int edgeThicknessPx(Context c) {
        return Math.max(4, Math.round(dp(c, 5f)));
    }

    static int edgeInnerThicknessPx(Context c) {
        return Math.max(1, Math.round(dp(c, 2f)));
    }

    static void configureFramePaints(Context c, Paint outer, Paint inner, boolean confirmed) {
        float outerWidth = dp(c, confirmed ? 7f : 5f);
        float innerWidth = dp(c, confirmed ? 4f : 2f);
        outer.setAntiAlias(true);
        outer.setStyle(Paint.Style.STROKE);
        outer.setColor(OUTER_COLOR);
        outer.setStrokeWidth(outerWidth);
        outer.setStrokeJoin(Paint.Join.ROUND);

        inner.setAntiAlias(true);
        inner.setStyle(Paint.Style.STROKE);
        inner.setColor(INNER_COLOR);
        inner.setStrokeWidth(innerWidth);
        inner.setStrokeJoin(Paint.Join.ROUND);
    }

    static void configureTextPaints(Context c, Paint outline, Paint fill, float sp) {
        float textSize = sp * c.getResources().getDisplayMetrics().scaledDensity;
        outline.setAntiAlias(true);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setStrokeWidth(dp(c, 3f));
        outline.setColor(OUTER_COLOR);
        outline.setTextSize(textSize);

        fill.setAntiAlias(true);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INNER_COLOR);
        fill.setTextSize(textSize);
    }

    static void drawFrame(Canvas c, Rect rect, Paint outer, Paint inner) {
        if (c == null || rect == null || rect.isEmpty()) return;
        c.drawRect(rect, outer);
        c.drawRect(rect, inner);
    }

    static void drawText(Canvas c, String text, float x, float y, Paint outline, Paint fill) {
        if (c == null || text == null || text.isEmpty()) return;
        c.drawText(text, x, y, outline);
        c.drawText(text, x, y, fill);
    }

    static void configureEdgePaints(Paint outer, Paint inner) {
        outer.setAntiAlias(false);
        outer.setStyle(Paint.Style.FILL);
        outer.setColor(OUTER_COLOR);
        inner.setAntiAlias(false);
        inner.setStyle(Paint.Style.FILL);
        inner.setColor(INNER_COLOR);
    }

    static void drawEdge(Canvas c, int width, int height, boolean vertical,
                         int innerThicknessPx, Paint outer, Paint inner) {
        if (c == null || width <= 0 || height <= 0) return;
        c.drawRect(0, 0, width, height, outer);
        if (vertical) {
            int innerW = Math.max(1, Math.min(width, innerThicknessPx));
            float left = (width - innerW) / 2f;
            c.drawRect(left, 0, left + innerW, height, inner);
        } else {
            int innerH = Math.max(1, Math.min(height, innerThicknessPx));
            float top = (height - innerH) / 2f;
            c.drawRect(0, top, width, top + innerH, inner);
        }
    }

    static void drawHorizontalEdge(Canvas c, int width, int edgeHeight, int innerThicknessPx,
                                   Paint outer, Paint inner) {
        if (c == null || width <= 0 || edgeHeight <= 0) return;
        c.drawRect(0, 0, width, edgeHeight, outer);
        int innerH = Math.max(1, Math.min(edgeHeight, innerThicknessPx));
        float top = (edgeHeight - innerH) / 2f;
        c.drawRect(0, top, width, top + innerH, inner);
    }

    private static float dp(Context c, float value) {
        return value * c.getResources().getDisplayMetrics().density;
    }

    private SelectionVisuals() {}
}
