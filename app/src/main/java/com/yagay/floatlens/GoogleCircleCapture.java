package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.function.Consumer;

/**
 * Fresh capture boundary for the Google-style circle workflow.
 *
 * The legacy CircleSelect stack deliberately does not participate here. A Frame owns one frozen
 * screenshot plus the exact absolute-screen rectangle represented by that bitmap. Touch input is
 * converted into bitmap coordinates immediately and never mixed with screen coordinates again.
 */
final class GoogleCircleCapture {
    static final class Frame {
        final Bitmap bitmap;
        final Rect screenBounds;

        Frame(Bitmap bitmap, Rect screenBounds) {
            this.bitmap = bitmap;
            this.screenBounds = new Rect(screenBounds);
        }

        PointF viewToBitmap(float x, float y, int viewWidth, int viewHeight) {
            float vw = Math.max(1f, viewWidth);
            float vh = Math.max(1f, viewHeight);
            float bx = clamp(x * bitmap.getWidth() / vw, 0f, Math.max(0f, bitmap.getWidth() - 1f));
            float by = clamp(y * bitmap.getHeight() / vh, 0f, Math.max(0f, bitmap.getHeight() - 1f));
            return new PointF(bx, by);
        }

        PointF bitmapToView(float x, float y, int viewWidth, int viewHeight) {
            float bx = Math.max(1f, bitmap.getWidth());
            float by = Math.max(1f, bitmap.getHeight());
            return new PointF(x * viewWidth / bx, y * viewHeight / by);
        }

        RectF bitmapToView(RectF r, int viewWidth, int viewHeight) {
            PointF a = bitmapToView(r.left, r.top, viewWidth, viewHeight);
            PointF b = bitmapToView(r.right, r.bottom, viewWidth, viewHeight);
            return new RectF(a.x, a.y, b.x, b.y);
        }

        Rect bitmapRectToScreen(Rect bitmapRect) {
            float sx = screenBounds.width() / (float) Math.max(1, bitmap.getWidth());
            float sy = screenBounds.height() / (float) Math.max(1, bitmap.getHeight());
            int left = screenBounds.left + Math.round(bitmapRect.left * sx);
            int top = screenBounds.top + Math.round(bitmapRect.top * sy);
            int right = screenBounds.left + Math.round(bitmapRect.right * sx);
            int bottom = screenBounds.top + Math.round(bitmapRect.bottom * sy);
            Rect out = new Rect(left, top, right, bottom);
            out.intersect(screenBounds);
            return out;
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    static void capture(Context c, Consumer<Frame> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        ScreenshotController.captureRawFrame(app, raw -> {
            if (raw == null || raw.isRecycled() || raw.getWidth() <= 0 || raw.getHeight() <= 0) {
                fail.accept(new IllegalArgumentException("invalid google-circle capture"));
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
                DiagnosticLog.i(app, "G_CIRCLE_CAPTURE", "ready display=" + display.toShortString()
                        + " workspace=" + workspace.toShortString()
                        + " bitmap=" + frozen.getWidth() + "x" + frozen.getHeight()
                        + " keepStatusBar=" + settings.keepStatusBarInScreenshot()
                        + " keepNavigationBar=" + keepNavigation
                        + " coordinateSpace=BITMAP_ONLY");
                ok.accept(frame);
            } catch (Throwable t) {
                if (!raw.isRecycled()) raw.recycle();
                fail.accept(t);
            }
        }, fail);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    private GoogleCircleCapture() {}
}
