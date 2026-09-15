package com.yagay.floatlens;

import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.List;

/** Pure gesture classification and selection geometry for the new Google-style workflow. */
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
                                float tapSlopPx, float minShapePx,
                                float tapHalfWidthPx, float tapHalfHeightPx) {
        if (points == null || points.isEmpty()) return null;
        PointF first = points.get(0);
        float minX = first.x, maxX = first.x, minY = first.y, maxY = first.y;
        float length = 0f;
        PointF prev = first;
        for (PointF p : points) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
            length += distance(prev, p);
            prev = p;
        }
        PointF last = points.get(points.size() - 1);
        float width = Math.max(1f, maxX - minX);
        float height = Math.max(1f, maxY - minY);
        float diagonal = (float) Math.hypot(width, height);
        float closure = distance(first, last);
        float centreX = (minX + maxX) * 0.5f;
        float centreY = (minY + maxY) * 0.5f;
        PointF focus = new PointF(centreX, centreY);

        boolean tap = length <= tapSlopPx * 1.8f
                && width <= tapSlopPx * 1.25f
                && height <= tapSlopPx * 1.25f;
        Kind kind;
        RectF bounds;
        if (tap) {
            kind = Kind.TAP;
            focus = new PointF(last.x, last.y);
            bounds = new RectF(last.x - tapHalfWidthPx, last.y - tapHalfHeightPx,
                    last.x + tapHalfWidthPx, last.y + tapHalfHeightPx);
        } else {
            boolean highlight = width >= height * 2.35f && height <= minShapePx * 1.35f;
            boolean closed = points.size() >= 8
                    && width >= minShapePx && height >= minShapePx
                    && closure <= Math.max(minShapePx, diagonal * 0.34f);
            if (highlight) kind = Kind.HIGHLIGHT;
            else if (closed) kind = Kind.CIRCLE;
            else kind = Kind.SCRIBBLE;

            float pad = switch (kind) {
                case HIGHLIGHT -> Math.max(8f, minShapePx * 0.30f);
                case CIRCLE -> Math.max(6f, minShapePx * 0.18f);
                case SCRIBBLE -> Math.max(10f, minShapePx * 0.35f);
                default -> 0f;
            };
            bounds = new RectF(minX - pad, minY - pad, maxX + pad, maxY + pad);
        }

        clamp(bounds, bitmapWidth, bitmapHeight);
        if (bounds.width() < 1f || bounds.height() < 1f) return null;
        return new Selection(kind, bounds, focus);
    }

    static Rect ensureMinAndClamp(RectF source, int bitmapWidth, int bitmapHeight, int minPixels) {
        RectF r = new RectF(source);
        float min = Math.max(32f, minPixels);
        if (r.width() < min) {
            float cx = r.centerX();
            r.left = cx - min / 2f;
            r.right = cx + min / 2f;
        }
        if (r.height() < min) {
            float cy = r.centerY();
            r.top = cy - min / 2f;
            r.bottom = cy + min / 2f;
        }
        shiftIntoBounds(r, bitmapWidth, bitmapHeight);
        clamp(r, bitmapWidth, bitmapHeight);
        int left = Math.max(0, Math.min(bitmapWidth - 1, (int) Math.floor(r.left)));
        int top = Math.max(0, Math.min(bitmapHeight - 1, (int) Math.floor(r.top)));
        int right = Math.max(left + 1, Math.min(bitmapWidth, (int) Math.ceil(r.right)));
        int bottom = Math.max(top + 1, Math.min(bitmapHeight, (int) Math.ceil(r.bottom)));
        return new Rect(left, top, right, bottom);
    }

    static RectF clampEditable(RectF source, int bitmapWidth, int bitmapHeight, float minSizePx) {
        RectF r = new RectF(source);
        float min = Math.max(24f, minSizePx);
        if (r.width() < min) r.right = r.left + min;
        if (r.height() < min) r.bottom = r.top + min;
        shiftIntoBounds(r, bitmapWidth, bitmapHeight);
        clamp(r, bitmapWidth, bitmapHeight);
        return r;
    }

    private static void shiftIntoBounds(RectF r, int width, int height) {
        if (r.left < 0) r.offset(-r.left, 0);
        if (r.top < 0) r.offset(0, -r.top);
        if (r.right > width) r.offset(width - r.right, 0);
        if (r.bottom > height) r.offset(0, height - r.bottom);
    }

    private static void clamp(RectF r, int width, int height) {
        r.left = Math.max(0f, Math.min(r.left, Math.max(0, width - 1)));
        r.top = Math.max(0f, Math.min(r.top, Math.max(0, height - 1)));
        r.right = Math.max(r.left + 1f, Math.min(r.right, Math.max(1, width)));
        r.bottom = Math.max(r.top + 1f, Math.min(r.bottom, Math.max(1, height)));
    }

    private static float distance(PointF a, PointF b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    private GoogleCircleSelection() {}
}
