package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * FV m2/g-style rectangular region indicator.
 *
 * fooView does not compose its selection rectangle from four WindowManager surfaces. m2/g itself
 * is one full-screen transparent selection window and onDraw() paints all four edges on the same
 * Canvas with one 2dp yellow STROKE Paint. Keeping one surface removes the corner seams/gaps that
 * can occur when four independently positioned windows are composed.
 *
 * FV also does not draw a size/coordinate label in this selection layer, so FloatLens intentionally
 * keeps this overlay frame-only. That both matches FV and prevents helper text from leaking into a
 * captured region.
 */
final class FvRegionFrameOverlay {
    private final Context context;
    private final WindowManager wm;
    private final FrameView frame;
    private final WindowManager.LayoutParams lp;
    private boolean attached;

    FvRegionFrameOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        frame = new FrameView(context);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
    }

    void show(Rect screenRect) {
        if (screenRect == null || screenRect.width() < 2 || screenRect.height() < 2) return;
        ensureAttached();
        if (!attached) return;
        frame.setScreenRect(screenRect);
    }

    /**
     * Kept for ViewHoverOverlay compatibility. FV m2/g's region-frame Paint is initialized yellow
     * and is not recolored by confirmation state.
     */
    void setConfirmed(boolean ignored) {
        // No-op by design: exact m2/g frame stays yellow.
    }

    void close() {
        if (!attached) {
            frame.clear();
            return;
        }
        frame.clear();
        try {
            // FV m2/g.s() removes the complete selection layer before the screenshot action is
            // posted. One Window/Surface is removed atomically instead of four independent edges.
            wm.removeView(frame);
        } catch (Throwable ignored) {}
        attached = false;
        DiagnosticLog.i(context, "FV_REGION_FRAME", "DETACH single-window-before-capture");
    }

    private void ensureAttached() {
        if (attached) return;
        try {
            wm.addView(frame, lp);
            attached = true;
            DiagnosticLog.i(context, "FV_REGION_FRAME",
                    "ATTACH fv-m2g-single-window yellow-2dp no-label");
        } catch (Throwable t) {
            attached = false;
            DiagnosticLog.i(context, "FV_REGION_FRAME", "attach failed=" + t);
        }
    }

    private static final class FrameView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect screenRect = new Rect();
        private final int[] windowOrigin = new int[2];

        FrameView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);

            // FV m2/g.t(): Paint is anti-aliased, yellow, STROKE, ROUND join/cap and 2dp wide.
            paint.setAntiAlias(true);
            paint.setColor(0xFFFFFF00);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(c, 2f));
        }

        void setScreenRect(Rect rect) {
            if (rect == null || rect.isEmpty()) {
                clear();
                return;
            }
            if (screenRect.equals(rect)) return;
            screenRect.set(rect);
            invalidate();
        }

        void clear() {
            if (screenRect.isEmpty()) return;
            screenRect.setEmpty();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (screenRect.isEmpty()) return;

            getLocationOnScreen(windowOrigin);
            float left = screenRect.left - windowOrigin[0];
            float top = screenRect.top - windowOrigin[1];
            float right = screenRect.right - windowOrigin[0];
            float bottom = screenRect.bottom - windowOrigin[1];

            // Match FV m2/g.onDraw(): four lines, one Paint, one Canvas. Drawing every corner on
            // the same surface eliminates compositor seams between independent Window surfaces.
            canvas.drawLine(left, top, right, top, paint);
            canvas.drawLine(left, top, left, bottom, paint);
            canvas.drawLine(left, bottom, right, bottom, paint);
            canvas.drawLine(right, top, right, bottom, paint);
        }

        private static float dp(Context c, float value) {
            return value * c.getResources().getDisplayMetrics().density;
        }
    }
}
