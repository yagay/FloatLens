package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import java.util.List;

/** Circle-specific freehand-to-rectangle policy; bitmap cropping is shared by SelectionCropper. */
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
        return SelectionCropper.cropRect(source, viewRect, viewWidth, viewHeight);
    }

    private CircleCropGeometry() {}
}
