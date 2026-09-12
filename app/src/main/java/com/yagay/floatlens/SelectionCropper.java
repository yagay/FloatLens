package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;

import java.util.List;

/** Shared rectangle/freehand view-space -> bitmap-space crop utilities. */
final class SelectionCropper {
    static Bitmap cropRect(Bitmap source, RectF viewRect, int viewWidth, int viewHeight) {
        if (source == null || source.isRecycled() || viewRect == null || viewRect.isEmpty()
                || viewWidth <= 0 || viewHeight <= 0) return null;
        float sx = source.getWidth() / (float) viewWidth;
        float sy = source.getHeight() / (float) viewHeight;
        int left = clamp((int) Math.floor(viewRect.left * sx), 0, source.getWidth() - 1);
        int top = clamp((int) Math.floor(viewRect.top * sy), 0, source.getHeight() - 1);
        int right = clamp((int) Math.ceil(viewRect.right * sx), left + 1, source.getWidth());
        int bottom = clamp((int) Math.ceil(viewRect.bottom * sy), top + 1, source.getHeight());
        if (right - left <= 1 || bottom - top <= 1) return null;
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }

    static Bitmap maskedCrop(Bitmap source, List<PointF> points,
                             int viewWidth, int viewHeight, float minSizePx) {
        if (source == null || source.isRecycled() || points == null || points.size() < 2
                || viewWidth <= 0 || viewHeight <= 0) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (PointF p : points) {
            if (p == null) continue;
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        if (maxX - minX < minSizePx || maxY - minY < minSizePx) return null;

        float sx = source.getWidth() / (float) viewWidth;
        float sy = source.getHeight() / (float) viewHeight;
        int left = clamp(Math.round(minX * sx), 0, source.getWidth() - 1);
        int top = clamp(Math.round(minY * sy), 0, source.getHeight() - 1);
        int right = clamp(Math.round(maxX * sx), left + 1, source.getWidth());
        int bottom = clamp(Math.round(maxY * sy), top + 1, source.getHeight());
        int width = right - left;
        int height = bottom - top;
        if (width <= 1 || height <= 1) return null;

        Bitmap crop = Bitmap.createBitmap(source, left, top, width, height);
        Bitmap masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(masked);
        canvas.drawColor(Color.WHITE);
        Path path = new Path();
        boolean first = true;
        for (PointF p : points) {
            if (p == null) continue;
            float x = p.x * sx - left;
            float y = p.y * sy - top;
            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        canvas.save();
        canvas.clipPath(path);
        canvas.drawBitmap(crop, 0, 0, null);
        canvas.restore();
        crop.recycle();
        return masked;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private SelectionCropper() {}
}
