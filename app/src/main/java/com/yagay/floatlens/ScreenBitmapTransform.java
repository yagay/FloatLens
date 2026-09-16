package com.yagay.floatlens;

import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Single mapping owner between frozen screenshot pixels, absolute SCREEN coordinates and overlay
 * View coordinates.
 *
 * <p>All transforms are Matrix-backed through {@link CoordinateMapper}. Callers must not add
 * status/navigation-bar offsets or calculate bitmap/screen scale factors themselves. The only
 * assumption is the one established at capture time: {@code screenFrame} is the exact screen-space
 * rectangle represented by the bitmap.</p>
 */
final class ScreenBitmapTransform {
    private final Rect screenFrame;
    private final int bitmapWidth;
    private final int bitmapHeight;
    private final CoordinateMapper bitmapToScreen;

    ScreenBitmapTransform(Rect screenFrame, int bitmapWidth, int bitmapHeight) {
        Rect frame = screenFrame == null ? new Rect() : new Rect(screenFrame);
        if (frame.isEmpty()) frame.set(0, 0, Math.max(1, bitmapWidth), Math.max(1, bitmapHeight));
        this.screenFrame = frame;
        this.bitmapWidth = Math.max(1, bitmapWidth);
        this.bitmapHeight = Math.max(1, bitmapHeight);
        bitmapToScreen = CoordinateMapper.bitmapToScreen(frame, this.bitmapWidth, this.bitmapHeight);
    }

    Rect screenFrame() { return new Rect(screenFrame); }
    int bitmapWidth() { return bitmapWidth; }
    int bitmapHeight() { return bitmapHeight; }

    Rect bitmapToScreen(Rect bitmapRect) {
        return bitmapToScreen.mapRectOut(bitmapRect);
    }

    Rect screenToBitmap(Rect screenRect) {
        return bitmapToScreen.unmapRectOut(screenRect);
    }

    PointF bitmapToScreen(float x, float y) {
        return bitmapToScreen.mapPointClamped(x, y);
    }

    PointF screenToBitmap(float x, float y) {
        return bitmapToScreen.unmapPointClamped(x, y);
    }

    PointF viewToScreen(float x, float y, int viewWidth, int viewHeight) {
        CoordinateMapper mapper = viewToScreenMapper(viewWidth, viewHeight);
        return mapper.mapPointClamped(x, y);
    }

    PointF screenToView(float x, float y, int viewWidth, int viewHeight) {
        CoordinateMapper mapper = viewToScreenMapper(viewWidth, viewHeight);
        return mapper.unmapPointClamped(x, y);
    }

    PointF viewToBitmap(float x, float y, int viewWidth, int viewHeight) {
        PointF screen = viewToScreen(x, y, viewWidth, viewHeight);
        return screenToBitmap(screen.x, screen.y);
    }

    PointF bitmapToView(float x, float y, int viewWidth, int viewHeight) {
        PointF screen = bitmapToScreen(x, y);
        return screenToView(screen.x, screen.y, viewWidth, viewHeight);
    }

    RectF bitmapToView(RectF bitmapRect, int viewWidth, int viewHeight) {
        if (bitmapRect == null || bitmapRect.isEmpty() || viewWidth <= 0 || viewHeight <= 0) {
            return new RectF();
        }
        RectF screen = bitmapToScreen.mapRectF(bitmapRect);
        if (screen.isEmpty()) return new RectF();
        return viewToScreenMapper(viewWidth, viewHeight).unmapRectF(screen);
    }

    RectF screenToView(Rect screenRect, int viewWidth, int viewHeight) {
        if (screenRect == null || screenRect.isEmpty() || viewWidth <= 0 || viewHeight <= 0) {
            return new RectF();
        }
        return viewToScreenMapper(viewWidth, viewHeight).unmapRectF(new RectF(screenRect));
    }

    Rect viewToScreen(RectF viewRect, int viewWidth, int viewHeight) {
        if (viewRect == null || viewRect.isEmpty() || viewWidth <= 0 || viewHeight <= 0) {
            return new Rect();
        }
        return viewToScreenMapper(viewWidth, viewHeight).mapRectOut(viewRect);
    }

    int viewXToScreen(float viewX, int viewWidth) {
        if (viewWidth <= 0) return screenFrame.left;
        return Math.round(viewToScreen(viewX, 0f, viewWidth, 1).x);
    }

    int viewYToScreen(float viewY, int viewHeight) {
        if (viewHeight <= 0) return screenFrame.top;
        return Math.round(viewToScreen(0f, viewY, 1, viewHeight).y);
    }

    /**
     * Converts a physical SCREEN-space distance to the bitmap-space distance used by gesture
     * classification. X/Y are measured independently through the Matrix and averaged so the result
     * remains stable even when an OEM capture is scaled non-uniformly.
     */
    float screenDistanceToBitmap(float screenPixels) {
        float distance = Math.max(0f, screenPixels);
        PointF origin = bitmapToScreen.unmapPoint(screenFrame.left, screenFrame.top);
        PointF x = bitmapToScreen.unmapPoint(screenFrame.left + distance, screenFrame.top);
        PointF y = bitmapToScreen.unmapPoint(screenFrame.left, screenFrame.top + distance);
        float dx = (float) Math.hypot(x.x - origin.x, x.y - origin.y);
        float dy = (float) Math.hypot(y.x - origin.x, y.y - origin.y);
        return (dx + dy) * 0.5f;
    }

    /** Converts a full bitmap-space OCR result once, immediately after recognition. */
    OcrDocument documentBitmapToScreen(OcrDocument document) {
        if (document == null) return null;
        if (document.isScreenSpace()) return document;
        return bitmapToScreen.mapDocument(document, true,
                Math.max(1, screenFrame.width()), Math.max(1, screenFrame.height()), "");
    }

    /**
     * Converts OCR geometry produced from a crop/variant back to absolute SCREEN coordinates.
     * {@code bitmapRoi} is expressed in the frozen screenshot's bitmap space while
     * {@code localWidth/localHeight} describe the exact image actually given to OCR after any
     * preprocessing has already been normalized back to that local image plane.
     */
    OcrDocument documentLocalToScreen(OcrDocument document, Rect bitmapRoi,
                                      int localWidth, int localHeight, String enginePrefix) {
        if (document == null || bitmapRoi == null || bitmapRoi.isEmpty()
                || localWidth <= 0 || localHeight <= 0) return null;
        RectF screenRoi = bitmapToScreen.mapRectF(new RectF(bitmapRoi));
        if (screenRoi.isEmpty()) return null;
        CoordinateMapper localToScreen = new CoordinateMapper(
                new RectF(0f, 0f, localWidth, localHeight), screenRoi);
        return localToScreen.mapDocument(document, true,
                Math.max(1, screenFrame.width()), Math.max(1, screenFrame.height()),
                enginePrefix == null ? "" : enginePrefix);
    }

    private CoordinateMapper viewToScreenMapper(int viewWidth, int viewHeight) {
        int width = Math.max(1, viewWidth);
        int height = Math.max(1, viewHeight);
        return new CoordinateMapper(new RectF(0f, 0f, width, height), new RectF(screenFrame));
    }
}
