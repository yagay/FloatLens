package com.yagay.floatlens;

import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.List;

/** Exact gesture classification and geometry for the Google-style workflow. */
final class GoogleCircleSelection {
    enum Kind { TAP, CIRCLE, HIGHLIGHT, SCRIBBLE }

    static final class Selection {
        final Kind kind;
        final RectF bounds;
        final PointF focus;

        Selection(Kind kind, RectF bounds, PointF focus) {
            this.kind = kind;
            this.bounds = new RectF(bounds);
            this.focus = new PointF(focus.x, focus.y);
        }

        Selection withBounds(RectF newBounds) {
            return new Selection(kind, newBounds, focus);
        }
    }

    static Selection fromStroke(List<PointF> points, int bitmapWidth, int bitmapHeight,
                                float tapSlopPx, float minShapePx) {
        if (points == null || points.isEmpty() || bitmapWidth <= 0 || bitmapHeight <= 0) return null;

        PointF first = points.get(0);
        float minX = first.x;
        float maxX = first.x;
        float minY = first.y;
        float maxY = first.y;
        float length = 0f;
        PointF previous = first;

        for (PointF point : points) {
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
            length += distance(previous, point);
            previous = point;
        }

        PointF last = points.get(points.size() - 1);
        float widthForClassification = Math.max(1f, maxX - minX);
        float heightForClassification = Math.max(1f, maxY - minY);
        float diagonal = (float) Math.hypot(widthForClassification, heightForClassification);
        float closure = distance(first, last);
        PointF focus = new PointF((minX + maxX) * 0.5f, (minY + maxY) * 0.5f);

        boolean tap = length <= tapSlopPx * 1.8f
                && widthForClassification <= tapSlopPx * 1.25f
                && heightForClassification <= tapSlopPx * 1.25f;

        Kind kind;
        RectF bounds;
        if (tap) {
            kind = Kind.TAP;
            focus = new PointF(last.x, last.y);
            // A tap is a point selection. Do not synthesize a surrounding OCR rectangle.
            bounds = new RectF(last.x, last.y, last.x + 1f, last.y + 1f);
        } else {
            boolean highlight = widthForClassification >= heightForClassification * 2.35f
                    && heightForClassification <= minShapePx * 1.35f;
            boolean closed = points.size() >= 8
                    && widthForClassification >= minShapePx
                    && heightForClassification >= minShapePx
                    && closure <= Math.max(minShapePx, diagonal * 0.34f);

            if (highlight) kind = Kind.HIGHLIGHT;
            else if (closed) kind = Kind.CIRCLE;
            else kind = Kind.SCRIBBLE;

            // Exact user stroke bounds. No gesture-specific padding is allowed here.
            bounds = new RectF(minX, minY, maxX, maxY);
        }

        clampExact(bounds, bitmapWidth, bitmapHeight);
        if (bounds.width() < 1f || bounds.height() < 1f) return null;
        return new Selection(kind, bounds, focus);
    }

    /** Convert a user selection to bitmap pixels without growing it to any minimum size. */
    static Rect exactRectAndClamp(RectF source, int bitmapWidth, int bitmapHeight) {
        if (source == null || bitmapWidth <= 0 || bitmapHeight <= 0) return new Rect();
        RectF rect = new RectF(source);
        clampExact(rect, bitmapWidth, bitmapHeight);

        int left = Math.max(0, Math.min(bitmapWidth - 1, (int) Math.floor(rect.left)));
        int top = Math.max(0, Math.min(bitmapHeight - 1, (int) Math.floor(rect.top)));
        int right = Math.max(left + 1, Math.min(bitmapWidth, (int) Math.ceil(rect.right)));
        int bottom = Math.max(top + 1, Math.min(bitmapHeight, (int) Math.ceil(rect.bottom)));
        return new Rect(left, top, right, bottom);
    }

    /** Keep an edited rectangle on-screen. No minimum selection size is imposed. */
    static RectF clampEditable(RectF source, int bitmapWidth, int bitmapHeight) {
        RectF rect = new RectF(source);
        shiftIntoBounds(rect, bitmapWidth, bitmapHeight);
        clampExact(rect, bitmapWidth, bitmapHeight);
        return rect;
    }

    private static void shiftIntoBounds(RectF rect, int width, int height) {
        if (rect.left < 0f) rect.offset(-rect.left, 0f);
        if (rect.top < 0f) rect.offset(0f, -rect.top);
        if (rect.right > width) rect.offset(width - rect.right, 0f);
        if (rect.bottom > height) rect.offset(0f, height - rect.bottom);
    }

    private static void clampExact(RectF rect, int width, int height) {
        rect.left = Math.max(0f, Math.min(rect.left, Math.max(0, width - 1)));
        rect.top = Math.max(0f, Math.min(rect.top, Math.max(0, height - 1)));
        rect.right = Math.max(rect.left + 1f, Math.min(rect.right, Math.max(1, width)));
        rect.bottom = Math.max(rect.top + 1f, Math.min(rect.bottom, Math.max(1, height)));
    }

    private static float distance(PointF a, PointF b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    private GoogleCircleSelection() {}
}
