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
        CropMath.Bounds bounds = CropMath.viewRectFloorCeil(
                viewRect.left, viewRect.top, viewRect.right, viewRect.bottom,
                source.getWidth(), source.getHeight(), viewWidth, viewHeight);
        if (bounds.width() <= 1 || bounds.height() <= 1) return null;
        Bitmap crop = Bitmap.createBitmap(source, bounds.left, bounds.top,
                bounds.width(), bounds.height());
        // Bitmap.createBitmap may legally return the source when the requested rectangle is the
        // complete bitmap. Selection workspaces recycle their frozen source when closing, so every
        // crop handed to a result/OCR owner must be an independent bitmap.
        if (crop == source) {
            Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
            return copy == null ? null : copy;
        }
        return crop;
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

        CropMath.Bounds bounds = CropMath.viewRectRound(
                minX, minY, maxX, maxY,
                source.getWidth(), source.getHeight(), viewWidth, viewHeight);
        int width = bounds.width();
        int height = bounds.height();
        if (width <= 1 || height <= 1) return null;

        float sx = source.getWidth() / (float) viewWidth;
        float sy = source.getHeight() / (float) viewHeight;
        Bitmap crop = Bitmap.createBitmap(source, bounds.left, bounds.top, width, height);
        Bitmap masked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(masked);
        canvas.drawColor(Color.WHITE);
        Path path = new Path();
        boolean first = true;
        for (PointF p : points) {
            if (p == null) continue;
            float x = p.x * sx - bounds.left;
            float y = p.y * sy - bounds.top;
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
        if (crop != source && !crop.isRecycled()) crop.recycle();
        return masked;
    }

    private SelectionCropper() {}
}
