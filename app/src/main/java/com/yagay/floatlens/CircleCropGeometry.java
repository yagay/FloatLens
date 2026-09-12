package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import java.util.List;

/** Pure free-hand-to-rectangle and view-to-bitmap crop geometry for Circle Select. */
final class CircleCropGeometry {
    static RectF snapToRectangle(List<PointF> points, int viewWidth, int viewHeight, float density) {
        if (points == null || points.size() < 4 || viewWidth <= 0 || viewHeight <= 0) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (PointF p : points) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        float w = maxX - minX;
        float h = maxY - minY;
        float minSize = 24f * density;
        if (w < minSize || h < minSize) return null;
        float padding = Math.max(6f * density,
                Math.min(18f * density, Math.min(w, h) * 0.08f));
        float left = Math.max(0f, minX - padding);
        float top = Math.max(0f, minY - padding);
        float right = Math.min(viewWidth, maxX + padding);
        float bottom = Math.min(viewHeight, maxY + padding);
        if (right - left < minSize || bottom - top < minSize) return null;
        return new RectF(left, top, right, bottom);
    }

    static Bitmap crop(Bitmap source, RectF viewRect, int viewWidth, int viewHeight) {
        if (source == null || source.isRecycled() || viewRect == null || viewRect.isEmpty()
                || viewWidth <= 0 || viewHeight <= 0) return null;
        float sx = source.getWidth() / (float) viewWidth;
        float sy = source.getHeight() / (float) viewHeight;
        int left = clamp((int) Math.floor(viewRect.left * sx), 0, source.getWidth() - 1);
        int top = clamp((int) Math.floor(viewRect.top * sy), 0, source.getHeight() - 1);
        int right = clamp((int) Math.ceil(viewRect.right * sx), left + 1, source.getWidth());
        int bottom = clamp((int) Math.ceil(viewRect.bottom * sy), top + 1, source.getHeight());
        int w = right - left;
        int h = bottom - top;
        if (w <= 1 || h <= 1) return null;
        return Bitmap.createBitmap(source, left, top, w, h);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private CircleCropGeometry() {}
}
