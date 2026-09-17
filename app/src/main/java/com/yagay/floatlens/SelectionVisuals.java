package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/** Shared FL drag-selection visuals. */
final class SelectionVisuals {
    /** FL normal selection is red. */
    static final int FL_ACTIVE_COLOR = 0xFFFF0000;
    /** FL confirmed/extractable selection is yellow. */
    static final int FL_CONFIRMED_COLOR = 0xFFFFFF00;

    static int frameColor(SelectionVisualState state) {
        return state == SelectionVisualState.READY ? FL_CONFIRMED_COLOR : FL_ACTIVE_COLOR;
    }

    static int edgeThicknessPx(Context c) {
        // FL selection frame uses an exact 2dp Paint stroke width.
        return Math.max(1, Math.round(dp(c, 2f)));
    }

    static void configureFramePaint(Context c, Paint frame, SelectionVisualState state) {
        frame.reset();
        frame.setAntiAlias(true);
        frame.setStyle(Paint.Style.STROKE);
        frame.setColor(frameColor(state));
        frame.setStrokeWidth(dp(c, 2f));
        frame.setStrokeJoin(Paint.Join.MITER);
    }

    /** Labels are FloatLens-only helpers; keep them single-layer and unobtrusive. */
    static void configureTextPaint(Context c, Paint text, float sp) {
        text.reset();
        text.setAntiAlias(true);
        text.setStyle(Paint.Style.FILL);
        text.setColor(Color.WHITE);
        text.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
    }

    static void drawFrame(Canvas c, Rect rect, Paint frame) {
        if (c == null || rect == null || rect.isEmpty()) return;
        c.drawRect(rect, frame);
    }

    static void drawText(Canvas c, String text, float x, float y, Paint fill) {
        if (c == null || text == null || text.isEmpty()) return;
        c.drawText(text, x, y, fill);
    }

    static void configureEdgePaint(Paint frame, SelectionVisualState state) {
        frame.reset();
        frame.setAntiAlias(false);
        frame.setStyle(Paint.Style.FILL);
        frame.setColor(frameColor(state));
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
