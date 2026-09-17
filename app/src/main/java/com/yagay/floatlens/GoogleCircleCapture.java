package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.function.Consumer;

/**
 * Fresh capture boundary for the FloatLens Circle workflow.
 *
 * <p>A Frame owns one frozen screenshot plus the exact absolute SCREEN rectangle represented by
 * that bitmap. {@link ScreenBitmapTransform} is the only owner of coordinate conversion for the
 * frame; overlays and OCR must not calculate screen/bitmap ratios or system-bar offsets directly.</p>
 */
final class FLCircleCapture {
    static final class Frame {
        final Bitmap bitmap;
        final Rect screenBounds;
        final ScreenBitmapTransform transform;

        Frame(Bitmap bitmap, Rect screenBounds) {
            if (bitmap == null) throw new IllegalArgumentException("bitmap required");
            this.bitmap = bitmap;
            Rect bounds = screenBounds == null ? new Rect() : new Rect(screenBounds);
            if (bounds.isEmpty()) {
                bounds.set(0, 0, Math.max(1, bitmap.getWidth()), Math.max(1, bitmap.getHeight()));
            }
            this.screenBounds = bounds;
            transform = new ScreenBitmapTransform(bounds, bitmap.getWidth(), bitmap.getHeight());
        }

        PointF viewToBitmap(float x, float y, int viewWidth, int viewHeight) {
            return transform.viewToBitmap(x, y, viewWidth, viewHeight);
        }

        PointF bitmapToView(float x, float y, int viewWidth, int viewHeight) {
            return transform.bitmapToView(x, y, viewWidth, viewHeight);
        }

        RectF bitmapToView(RectF r, int viewWidth, int viewHeight) {
            return transform.bitmapToView(r, viewWidth, viewHeight);
        }

        Rect bitmapRectToScreen(Rect bitmapRect) {
            return transform.bitmapToScreen(bitmapRect);
        }

        Rect screenRectToBitmap(Rect screenRect) {
            return transform.screenToBitmap(screenRect);
        }

        PointF bitmapPointToScreen(float x, float y) {
            return transform.bitmapToScreen(x, y);
        }

        PointF screenPointToBitmap(float x, float y) {
            return transform.screenToBitmap(x, y);
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    static void capture(Context c, Consumer<Frame> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        ScreenshotController.captureRawFrame(app, raw -> {
            if (raw == null || raw.isRecycled() || raw.getWidth() <= 0 || raw.getHeight() <= 0) {
                fail.accept(new IllegalArgumentException("invalid fl-circle capture"));
                return;
            }

            Rect display = ScreenGeometry.displayBounds(app);
            FloatSettings settings = new FloatSettings(app);
            boolean keepNavigation = settings.keepNavigationBarInScreenshot();
            Rect workspace = CaptureSystemBarsPolicy.captureBounds(
                    app, settings.keepStatusBarInScreenshot(), keepNavigation);
            try {
                Bitmap frozen;
                if (workspace.equals(display)) {
                    frozen = raw;
                } else {
                    frozen = ScreenshotGeometry.cropScreenBounds(app, raw, workspace);
                    if (frozen != raw && !raw.isRecycled()) raw.recycle();
                }
                Frame frame = new Frame(frozen, workspace);
                DiagnosticLog.i(app, "FL_CIRCLE_CAPTURE", "ready display=" + display.toShortString()
                        + " workspace=" + workspace.toShortString()
                        + " bitmap=" + frozen.getWidth() + "x" + frozen.getHeight()
                        + " keepStatusBar=" + settings.keepStatusBarInScreenshot()
                        + " keepNavigationBar=" + keepNavigation
                        + " coordinateSpace=SCREEN_WITH_MATRIX_BOUNDARY");
                ok.accept(frame);
            } catch (Throwable t) {
                if (!raw.isRecycled()) raw.recycle();
                fail.accept(t);
            }
        }, fail);
    }

    private FLCircleCapture() {}
}
