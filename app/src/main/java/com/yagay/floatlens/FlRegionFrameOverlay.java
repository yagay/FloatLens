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

/** FloatLens region frame; drawing geometry follows the behavior observed in FV m2/g. */
final class FlRegionFrameOverlay {
    private final Context context;
    private final FlOverlayWindowHost windowHost;
    private final FrameView frame;
    private final WindowManager.LayoutParams lp;
    private boolean attached;

    FlRegionFrameOverlay(Context c) {
        context = c.getApplicationContext();
        windowHost = new FlOverlayWindowHost(context);
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

    void setVisualState(SelectionVisualState state) {
        frame.setVisualState(state);
    }

    void close() {
        if (!attached) {
            frame.clear();
            return;
        }
        frame.clear();
        windowHost.remove(frame, "region_frame");
        attached = false;
        DiagnosticLog.i(context, "FL_REGION_FRAME", "DETACH single-window-before-capture");
    }

    private void ensureAttached() {
        if (attached) return;
        attached = windowHost.add(frame, lp, "region_frame");
        if (attached) {
            DiagnosticLog.i(context, "FL_REGION_FRAME",
                    "ATTACH single-window 2dp state=" + frame.state()
                            + " accessibilityHost=" + windowHost.isAccessibilityHosted());
        }
    }

    private static final class FrameView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect screenRect = new Rect();
        private final int[] windowOrigin = new int[2];
        private SelectionVisualState state = SelectionVisualState.TRACKING;

        FrameView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(c, 2f));
            updateColor();
        }

        SelectionVisualState state() { return state; }

        void setVisualState(SelectionVisualState next) {
            if (next == null) next = SelectionVisualState.TRACKING;
            if (state == next) return;
            state = next;
            updateColor();
            invalidate();
        }

        private void updateColor() {
            paint.setColor(SelectionVisuals.frameColor(state));
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
