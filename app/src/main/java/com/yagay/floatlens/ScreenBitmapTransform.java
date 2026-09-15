package com.yagay.floatlens;

import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;

/**
 * Shared Android wrapper around the pure {@link CropMath} screen/bitmap mapping rules.
 *
 * All UI/View geometry stays in absolute screen coordinates. Bitmap-space coordinates only exist at
 * image-processing/crop boundaries and OCR is normalized to screen space immediately afterwards.
 */
final class ScreenBitmapTransform {
    private final Rect screenFrame;
    private final int bitmapWidth;
    private final int bitmapHeight;

    ScreenBitmapTransform(Rect screenFrame, int bitmapWidth, int bitmapHeight) {
        Rect frame = screenFrame == null ? new Rect() : new Rect(screenFrame);
        if (frame.isEmpty()) frame.set(0, 0, Math.max(1, bitmapWidth), Math.max(1, bitmapHeight));
        this.screenFrame = frame;
        this.bitmapWidth = Math.max(1, bitmapWidth);
        this.bitmapHeight = Math.max(1, bitmapHeight);
    }

    Rect screenFrame() { return new Rect(screenFrame); }
    int bitmapWidth() { return bitmapWidth; }
    int bitmapHeight() { return bitmapHeight; }

    Rect bitmapToScreen(Rect bitmapRect) {
        if (bitmapRect == null || bitmapRect.isEmpty()) return new Rect();
        Rect clipped = new Rect(bitmapRect);
        if (!clipped.intersect(0, 0, bitmapWidth, bitmapHeight)) return new Rect();
        CropMath.Bounds b = CropMath.bitmapRectRound(
                clipped.left, clipped.top, clipped.right, clipped.bottom,
                bitmapWidth, bitmapHeight,
                screenFrame.left, screenFrame.top, screenFrame.width(), screenFrame.height());
        return new Rect(b.left, b.top, b.right, b.bottom);
    }

    Rect screenToBitmap(Rect screenRect) {
        if (screenRect == null || screenRect.isEmpty()) return new Rect();
        Rect clipped = new Rect(screenRect);
        if (!clipped.intersect(screenFrame)) return new Rect();
        CropMath.Bounds b = CropMath.screenRectFloorCeil(
                clipped.left, clipped.top, clipped.right, clipped.bottom,
                screenFrame.left, screenFrame.top, screenFrame.width(), screenFrame.height(),
                bitmapWidth, bitmapHeight);
        return new Rect(b.left, b.top, b.right, b.bottom);
    }

    RectF screenToView(Rect screenRect, int viewWidth, int viewHeight) {
        if (screenRect == null || screenRect.isEmpty() || viewWidth <= 0 || viewHeight <= 0) {
            return new RectF();
        }
        float sx = viewWidth / (float) Math.max(1, screenFrame.width());
        float sy = viewHeight / (float) Math.max(1, screenFrame.height());
        return new RectF(
                (screenRect.left - screenFrame.left) * sx,
                (screenRect.top - screenFrame.top) * sy,
                (screenRect.right - screenFrame.left) * sx,
                (screenRect.bottom - screenFrame.top) * sy);
    }

    Rect viewToScreen(RectF viewRect, int viewWidth, int viewHeight) {
        if (viewRect == null || viewRect.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return new Rect();
        int left = CropMath.viewPointToScreen(viewRect.left, viewWidth,
                screenFrame.left, screenFrame.width());
        int top = CropMath.viewPointToScreen(viewRect.top, viewHeight,
                screenFrame.top, screenFrame.height());
        int right = CropMath.viewPointToScreen(viewRect.right, viewWidth,
                screenFrame.left, screenFrame.width());
        int bottom = CropMath.viewPointToScreen(viewRect.bottom, viewHeight,
                screenFrame.top, screenFrame.height());
        Rect out = new Rect(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom));
        if (!out.intersect(screenFrame)) return new Rect();
        return out;
    }

    int viewXToScreen(float viewX, int viewWidth) {
        if (viewWidth <= 0) return screenFrame.left;
        return CropMath.viewPointToScreen(viewX, viewWidth, screenFrame.left, screenFrame.width());
    }

    int viewYToScreen(float viewY, int viewHeight) {
        if (viewHeight <= 0) return screenFrame.top;
        return CropMath.viewPointToScreen(viewY, viewHeight, screenFrame.top, screenFrame.height());
    }

    /** Converts a bitmap-space OCR result once, immediately after recognition. */
    OcrDocument documentBitmapToScreen(OcrDocument document) {
        if (document == null) return null;
        if (document.isScreenSpace()) return document;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int order = 0;
        for (OcrDocument.Line line : document.lines()) {
            if (line == null || line.bounds().isEmpty()) continue;
            Rect lineBounds = bitmapToScreen(line.bounds());
            if (lineBounds.isEmpty()) continue;
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit c : line.chars()) {
                Rect bounds = bitmapToScreen(c.bounds());
                if (bounds.isEmpty()) continue;
                chars.add(new OcrDocument.CharUnit(c.text(), bounds, c.confidence(),
                        lineId, c.group(), order++));
            }
            if (!chars.isEmpty()) {
                lines.add(new OcrDocument.Line(line.text(), lineBounds, line.confidence(), chars));
                lineId++;
            }
        }
        return OcrDocument.screenSpace(document.fullText(), document.blocks(), lines,
                document.engine(), document.confidence(), document.score(),
                Math.max(1, screenFrame.width()), Math.max(1, screenFrame.height()));
    }
}
