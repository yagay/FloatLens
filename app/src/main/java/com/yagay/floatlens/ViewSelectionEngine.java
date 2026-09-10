package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

/**
 * FV-style selection engine driven in parallel with the floating-icon MOVE stream.
 * Accessibility candidates update live; a frozen screenshot requested on DOWN supplies NonText
 * visual rectangles without repeatedly taking screenshots while the icon is being dragged.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, ACTIVE }

    private static final long FV_SETTLE_DELAY_MS = 100L;
    private static final float FV_SELECT_START_DP = 3f;
    private static final float FV_FAST_FRAME_PX = 40f;

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable settleRunnable = this::settledRefresh;

    private ViewHoverOverlay overlay;
    private State state = State.IDLE;
    private float downX = Float.NaN, downY = Float.NaN;
    private float previousX = Float.NaN, previousY = Float.NaN;
    private float pointerX = Float.NaN, pointerY = Float.NaN;
    private float minX, minY, maxX, maxY;
    private long downAt;
    private long lastFastFrameAt;
    private boolean moved;
    private Bitmap screenSnapshot;
    private Rect snapshotBounds;
    private int captureGeneration;

    public ViewSelectionEngine(Context c) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
    }

    public boolean available() { return accessibility != null; }
    public boolean isActive() { return state == State.ACTIVE; }
    public State state() { return state; }

    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null || accessibility == null) return;
        final int action = e.getActionMasked();
        final float x = e.getRawX(), y = e.getRawY();
        final long now = SystemClock.uptimeMillis();
        pointerX = x;
        pointerY = y;

        if (action == MotionEvent.ACTION_DOWN) {
            resetInternal(true);
            downAt = now;
            downX = previousX = x;
            downY = previousY = y;
            pointerX = x;
            pointerY = y;
            minX = maxX = x;
            minY = maxY = y;
            requestFrozenSnapshot(++captureGeneration);
            DiagnosticLog.i(context, "FV_SELECT", "DOWN x=" + Math.round(x) + " y=" + Math.round(y) + " snapshot=requested");
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            moved = true;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);

            float frameDx = Float.isNaN(previousX) ? 0f : Math.abs(x - previousX);
            float frameDy = Float.isNaN(previousY) ? 0f : Math.abs(y - previousY);
            previousX = x;
            previousY = y;

            if (frameDx >= FV_FAST_FRAME_PX || frameDy >= FV_FAST_FRAME_PX) {
                lastFastFrameAt = now;
                handler.removeCallbacks(settleRunnable);
                if (state == State.ACTIVE) {
                    closeOverlay();
                    state = State.IDLE;
                    DiagnosticLog.i(context, "FV_SELECT", "LEAVE_ACTIVE fastFrame dx=" + Math.round(frameDx) + " dy=" + Math.round(frameDy));
                }
                return;
            }

            float start = dp(FV_SELECT_START_DP);
            float totalDx = x - downX;
            float totalDy = y - downY;
            boolean meaningfulMove = totalDx * totalDx + totalDy * totalDy >= start * start;

            if (meaningfulMove) {
                if (state != State.ACTIVE && now - lastFastFrameAt >= FV_SETTLE_DELAY_MS) {
                    activateNow(x, y, "controlled_move");
                }
                if (state == State.ACTIVE && overlay != null) overlay.update(x, y);

                handler.removeCallbacks(settleRunnable);
                handler.postDelayed(settleRunnable, FV_SETTLE_DELAY_MS);
            }
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL) cancel();
    }

    private void requestFrozenSnapshot(int generation) {
        if (accessibility == null) return;
        Rect bounds = accessibility.screenBounds();
        accessibility.capture(bitmap -> {
            if (generation != captureGeneration || bitmap == null || bitmap.isRecycled()) return;
            screenSnapshot = bitmap;
            snapshotBounds = new Rect(bounds);
            if (overlay != null) overlay.setScreenSnapshot(screenSnapshot, snapshotBounds);
            DiagnosticLog.i(context,"FV_SELECT","snapshot ready="+bitmap.getWidth()+"x"+bitmap.getHeight());
        }, error -> {
            if (generation == captureGeneration) DiagnosticLog.i(context,"FV_SELECT","snapshot failed="+error);
        });
    }

    private void activateNow(float x, float y, String reason) {
        if (accessibility == null || state == State.ACTIVE) return;
        overlay = new ViewHoverOverlay(context);
        if (!overlay.available()) {
            overlay = null;
            return;
        }
        overlay.begin();
        if (screenSnapshot != null && snapshotBounds != null) overlay.setScreenSnapshot(screenSnapshot, snapshotBounds);
        state = State.ACTIVE;
        overlay.update(x, y);
        DiagnosticLog.i(context, "FV_SELECT", "ACTIVE reason=" + reason + " after=" + (SystemClock.uptimeMillis() - downAt) + "ms x=" + Math.round(x) + " y=" + Math.round(y));
    }

    /** Final settled refresh so visual and Accessibility candidates land on the final point. */
    private void settledRefresh() {
        if (!moved || accessibility == null || Float.isNaN(pointerX) || Float.isNaN(pointerY)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastFastFrameAt < FV_SETTLE_DELAY_MS) {
            handler.postDelayed(settleRunnable, FV_SETTLE_DELAY_MS - (now - lastFastFrameAt));
            return;
        }
        if (state != State.ACTIVE) activateNow(pointerX, pointerY, "settled_100ms");
        else if (overlay != null) overlay.update(pointerX, pointerY);
    }

    public boolean finish(MotionEvent up) {
        boolean wasActive = state == State.ACTIVE;
        handler.removeCallbacks(settleRunnable);
        if (overlay != null && up != null) overlay.update(up.getRawX(), up.getRawY());
        boolean hadCandidate = overlay != null && overlay.hasCandidate();
        boolean producedResult = false;
        if (overlay != null) producedResult = overlay.finish(wasActive);

        if (wasActive && !producedResult && !hadCandidate) {
            Rect r = selectionBounds();
            if (r.width() >= Math.round(dp(8)) && r.height() >= Math.round(dp(8))) {
                ScreenshotController.captureBoundsForOcr(context, r);
                producedResult = true;
                DiagnosticLog.i(context, "FV_SELECT", "fallback bounds OCR=" + r);
            }
        }
        DiagnosticLog.i(context, "FV_SELECT", "UP active=" + wasActive + " candidate=" + hadCandidate + " result=" + producedResult);
        resetInternal(false);
        return wasActive;
    }

    public void cancel() {
        boolean wasActive = state == State.ACTIVE;
        captureGeneration++;
        handler.removeCallbacks(settleRunnable);
        closeOverlay();
        DiagnosticLog.i(context, "FV_SELECT", "CANCEL active=" + wasActive);
        resetInternal(false);
    }

    private Rect selectionBounds() {
        int l = Math.round(Math.min(minX, maxX));
        int t = Math.round(Math.min(minY, maxY));
        int r = Math.round(Math.max(minX, maxX));
        int b = Math.round(Math.max(minY, maxY));
        return new Rect(l, t, r, b);
    }

    private void closeOverlay() {
        if (overlay != null) overlay.cancel();
        overlay = null;
    }

    private void resetInternal(boolean close) {
        handler.removeCallbacks(settleRunnable);
        if (close) closeOverlay();
        overlay = null;
        state = State.IDLE;
        downX = downY = previousX = previousY = pointerX = pointerY = Float.NaN;
        minX = minY = maxX = maxY = 0f;
        downAt = 0L;
        lastFastFrameAt = 0L;
        moved = false;
        screenSnapshot = null;
        snapshotBounds = null;
    }

    private float dp(float v) { return v * context.getResources().getDisplayMetrics().density; }
}
