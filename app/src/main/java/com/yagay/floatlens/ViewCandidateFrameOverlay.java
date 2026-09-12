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

/** Large View-candidate frame with FV red TRACKING -> yellow READY feedback. */
final class ViewCandidateFrameOverlay {
    private final Context context;
    private final FlOverlayWindowHost windowHost;
    private final FrameView frame;
    private final WindowManager.LayoutParams lp;
    private boolean attached;

    ViewCandidateFrameOverlay(Context c) {
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
        if (attached) frame.setScreenRect(screenRect);
    }

    void setVisualState(SelectionVisualState state) {
        frame.setVisualState(state);
    }

    void close() {
        frame.clear();
        if (attached) windowHost.remove(frame, "view_candidate_frame");
        attached = false;
    }

    private void ensureAttached() {
        if (attached) return;
        attached = windowHost.add(frame, lp, "view_candidate_frame");
        if (attached) {
            DiagnosticLog.i(context, "VIEW_HOVER", "large_candidate_attach state=" + frame.state()
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
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
            paint.setStrokeJoin(Paint.Join.MITER);
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

        void setScreenRect(Rect rect) {
            if (rect == null || rect.isEmpty()) {
                clear();
                return;
            }
            screenRect.set(rect);
            invalidate();
        }

        void clear() {
            if (screenRect.isEmpty()) return;
            screenRect.setEmpty();
            invalidate();
        }

        private void updateColor() {
            paint.setColor(SelectionVisuals.frameColor(state));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (screenRect.isEmpty()) return;
            getLocationOnScreen(windowOrigin);
            Rect local = new Rect(screenRect);
            local.offset(-windowOrigin[0], -windowOrigin[1]);
            canvas.drawRect(local, paint);
        }
    }
}
