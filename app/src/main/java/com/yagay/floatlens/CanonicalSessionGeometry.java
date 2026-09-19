package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

/**
 * Immutable coordinate contract for one canonical Google CTS frame.
 *
 * <p>Google selection metadata is received in canonical frame/pixel space. FloatLens overlays and
 * menus live in absolute screen space. This object is the only conversion boundary between them.</p>
 */
final class CanonicalSessionGeometry {
    private final Rect screenFrame;
    private final int frameWidth;
    private final int frameHeight;
    private final ScreenBitmapTransform transform;

    private CanonicalSessionGeometry(Rect screenFrame, int frameWidth, int frameHeight) {
        Rect safeScreen = screenFrame == null ? new Rect() : new Rect(screenFrame);
        if (safeScreen.isEmpty()) {
            safeScreen.set(0, 0, Math.max(1, frameWidth), Math.max(1, frameHeight));
        }
        this.screenFrame = safeScreen;
        this.frameWidth = Math.max(1, frameWidth);
        this.frameHeight = Math.max(1, frameHeight);
        this.transform = new ScreenBitmapTransform(
                this.screenFrame, this.frameWidth, this.frameHeight);
    }

    static CanonicalSessionGeometry forFrame(Context context, int width, int height) {
        Rect display = ScreenGeometry.displayBounds(context);
        return new CanonicalSessionGeometry(display, width, height);
    }

    static CanonicalSessionGeometry of(Rect screenFrame, int width, int height) {
        return new CanonicalSessionGeometry(screenFrame, width, height);
    }

    Rect frameBounds() {
        return new Rect(0, 0, frameWidth, frameHeight);
    }

    Rect screenFrame() {
        return new Rect(screenFrame);
    }

    Rect normalizeFrameBounds(Rect candidate) {
        if (candidate == null || candidate.isEmpty()) return null;
        Rect out = new Rect(candidate);
        if (!out.intersect(frameBounds()) || out.isEmpty()) return null;
        return out;
    }

    Rect frameToScreen(Rect frameBounds) {
        Rect normalized = normalizeFrameBounds(frameBounds);
        if (normalized == null) return null;
        Rect out = transform.bitmapToScreen(normalized);
        return out.isEmpty() ? null : out;
    }

    Rect screenToFrame(Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) return null;
        Rect out = transform.screenToBitmap(screenBounds);
        if (out.isEmpty()) return null;
        return normalizeFrameBounds(out);
    }

    int frameWidth() { return frameWidth; }
    int frameHeight() { return frameHeight; }
}
