package com.yagay.floatlens;

import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;

/**
 * Shared coordinate transform between one absolute screen-space frame and one bitmap.
 *
 * All UI/View geometry should stay in screen coordinates for as long as possible. Bitmap-space
 * coordinates exist only at image-processing/crop boundaries.
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
        float sx = screenFrame.width() / (float) bitmapWidth;
        float sy = screenFrame.height() / (float) bitmapHeight;
        int left = screenFrame.left + Math.round(clipped.left * sx);
        int top = screenFrame.top + Math.round(clipped.top * sy);
        int right = screenFrame.left + Math.round(clipped.right * sx);
        int bottom = screenFrame.top + Math.round(clipped.bottom * sy);
        return new Rect(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom));
    }

    Rect screenToBitmap(Rect screenRect) {
        if (screenRect == null || screenRect.isEmpty()) return new Rect();
        Rect clipped = new Rect(screenRect);
        if (!clipped.intersect(screenFrame)) return new Rect();
        float sx = bitmapWidth / (float) Math.max(1, screenFrame.width());
        float sy = bitmapHeight / (float) Math.max(1, screenFrame.height());
        int left = Math.max(0, Math.min(bitmapWidth - 1,
                (int) Math.floor((clipped.left - screenFrame.left) * sx)));
        int top = Math.max(0, Math.min(bitmapHeight - 1,
                (int) Math.floor((clipped.top - screenFrame.top) * sy)));
        int right = Math.max(left + 1, Math.min(bitmapWidth,
                (int) Math.ceil((clipped.right - screenFrame.left) * sx)));
        int bottom = Math.max(top + 1, Math.min(bitmapHeight,
                (int) Math.ceil((clipped.bottom - screenFrame.top) * sy)));
        return new Rect(left, top, right, bottom);
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
        float sx = screenFrame.width() / (float) viewWidth;
        float sy = screenFrame.height() / (float) viewHeight;
        int left = screenFrame.left + Math.round(viewRect.left * sx);
        int top = screenFrame.top + Math.round(viewRect.top * sy);
        int right = screenFrame.left + Math.round(viewRect.right * sx);
        int bottom = screenFrame.top + Math.round(viewRect.bottom * sy);
        Rect out = new Rect(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom));
        if (!out.intersect(screenFrame)) return new Rect();
        return out;
    }

    int viewXToScreen(float viewX, int viewWidth) {
        if (viewWidth <= 0) return screenFrame.left;
        return screenFrame.left + Math.round(viewX * screenFrame.width() / (float) viewWidth);
    }

    int viewYToScreen(float viewY, int viewHeight) {
        if (viewHeight <= 0) return screenFrame.top;
        return screenFrame.top + Math.round(viewY * screenFrame.height() / (float) viewHeight);
    }

    /** Converts a bitmap-space OCR result once, immediately after recognition. */
    OcrDocument documentBitmapToScreen(OcrDocument document) {
        if (document == null) return null;
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
