package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/** Shared FV 1.6.4 drag-selection visuals. */
final class SelectionVisuals {
    /** FV o1/n1.onDraw(): normal selection is red. */
    static final int FL_ACTIVE_COLOR = 0xFFFF0000;
    /** FV o1/n1.onDraw(): confirmed/extractable selection is yellow. */
    static final int FL_CONFIRMED_COLOR = 0xFFFFFF00;

    static int frameColor(boolean confirmed) {
        return confirmed ? FL_CONFIRMED_COLOR : FL_ACTIVE_COLOR;
    }

    static int edgeThicknessPx(Context c) {
        // FV calls m5/q.a(2) then uses that as Paint stroke width: exactly 2dp.
        return Math.max(1, Math.round(dp(c, 2f)));
    }

    /**
     * Kept with the old two-Paint signature so callers stay simple, but only one Paint is drawn.
     * This intentionally matches FV's single STROKE Paint rather than the previous double outline.
     */
    static void configureFramePaints(Context c, Paint frame, Paint unused, boolean confirmed) {
        frame.setAntiAlias(true);
        frame.setStyle(Paint.Style.STROKE);
        frame.setColor(frameColor(confirmed));
        frame.setStrokeWidth(dp(c, 2f));
        frame.setStrokeJoin(Paint.Join.MITER);

        if (unused != null) {
            unused.reset();
            unused.setColor(Color.TRANSPARENT);
        }
    }

    /** Labels are FloatLens-only helpers; keep them single-layer and unobtrusive. */
    static void configureTextPaints(Context c, Paint unusedOutline, Paint text, float sp) {
        if (unusedOutline != null) unusedOutline.reset();
        text.reset();
        text.setAntiAlias(true);
        text.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
    }

    static void drawFrame(Canvas c, Rect rect, Paint frame, Paint unused) {
        if (c == null || rect == null || rect.isEmpty()) return;
        c.drawRect(rect, frame);
    }

    static void drawText(Canvas c, String text, float x, float y, Paint unusedOutline, Paint fill) {
        if (c == null || text == null || text.isEmpty()) return;
        c.drawText(text, x, y, fill);
    }

    static void configureEdgePaints(Paint frame, Paint unused, boolean confirmed) {
        frame.reset();
        frame.setAntiAlias(false);
        frame.setStyle(Paint.Style.FILL);
        frame.setColor(frameColor(confirmed));
        if (unused != null) {
            unused.reset();
            unused.setColor(Color.TRANSPARENT);
        }
    }

    static void drawEdge(Canvas c, int width, int height, Paint frame) {
        if (c == null || width <= 0 || height <= 0) return;
        c.drawRect(0, 0, width, height, frame);
    }

    private static float dp(Context c, float value) {
        return value * c.getResources().getDisplayMetrics().density;
    }

    private SelectionVisuals() {}
}
