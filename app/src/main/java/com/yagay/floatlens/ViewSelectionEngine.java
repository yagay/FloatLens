package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

/**
 * FV-style selection engine driven in parallel with the floating-icon MOVE stream.
 *
 * Gesture speed remains based on raw finger MotionEvents, while View/image targeting uses the
 * floating icon's stable hotspot. This prevents the selected rectangle from shifting simply because
 * the user touched the icon near its edge instead of its centre.
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
    private final SelectionPointTransformer pointTransformer;

    private ViewHoverOverlay overlay;
    private State state = State.IDLE;
    private float downRawX = Float.NaN, downRawY = Float.NaN;
    private float previousRawX = Float.NaN, previousRawY = Float.NaN;
    private float selectionX = Float.NaN, selectionY = Float.NaN;
    private float minSelectionX, minSelectionY, maxSelectionX, maxSelectionY;
    private long downAt;
    private long lastFastFrameAt;
    private boolean moved;
    private Bitmap screenSnapshot;
    private Rect snapshotBounds;
    private int captureGeneration;

    public ViewSelectionEngine(Context c) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
        FloatSettings fs = new FloatSettings(context);
        float px = fs.sizeDp() * context.getResources().getDisplayMetrics().density;
        pointTransformer = new SelectionPointTransformer(context, px, px);
    }

    public boolean available() { return accessibility != null; }
    public boolean isActive() { return state == State.ACTIVE; }
    public State state() { return state; }

    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null || accessibility == null) return;
        final int action = e.getActionMasked();
        final float rawX = e.getRawX(), rawY = e.getRawY();
        final long now = SystemClock.uptimeMillis();

        if (action == MotionEvent.ACTION_DOWN) pointTransformer.begin(e);
        PointF p = pointTransformer.transform(e);
        selectionX = p.x;
        selectionY = p.y;

        if (action == MotionEvent.ACTION_DOWN) {
            resetInternal(true);
            pointTransformer.begin(e);
            p = pointTransformer.transform(e);
            selectionX = p.x;
            selectionY = p.y;
            downAt = now;
            downRawX = previousRawX = rawX;
            downRawY = previousRawY = rawY;
            minSelectionX = maxSelectionX = selectionX;
            minSelectionY = maxSelectionY = selectionY;
            requestFrozenSnapshot(++captureGeneration);
            DiagnosticLog.i(context, "FV_SELECT", "DOWN raw=" + Math.round(rawX) + "," + Math.round(rawY)
                    + " hotspot=" + Math.round(selectionX) + "," + Math.round(selectionY));
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            moved = true;
            minSelectionX = Math.min(minSelectionX, selectionX);
            minSelectionY = Math.min(minSelectionY, selectionY);
            maxSelectionX = Math.max(maxSelectionX, selectionX);
            maxSelectionY = Math.max(maxSelectionY, selectionY);

            float frameDx = Float.isNaN(previousRawX) ? 0f : Math.abs(rawX - previousRawX);
            float frameDy = Float.isNaN(previousRawY) ? 0f : Math.abs(rawY - previousRawY);
            previousRawX = rawX;
            previousRawY = rawY;

            if (frameDx >= FV_FAST_FRAME_PX || frameDy >= FV_FAST_FRAME_PX) {
                lastFastFrameAt = now;
                handler.removeCallbacks(settleRunnable);
                if (state == State.ACTIVE) {
                    closeOverlay();
                    state = State.IDLE;
                    DiagnosticLog.i(context, "FV_SELECT", "LEAVE_ACTIVE fastFrame dx="
                            + Math.round(frameDx) + " dy=" + Math.round(frameDy));
                }
                return;
            }

            float start = dp(FV_SELECT_START_DP);
            float totalDx = rawX - downRawX;
            float totalDy = rawY - downRawY;
            boolean meaningfulMove = totalDx * totalDx + totalDy * totalDy >= start * start;

            if (meaningfulMove) {
                if (state != State.ACTIVE && now - lastFastFrameAt >= FV_SETTLE_DELAY_MS) {
                    activateNow(selectionX, selectionY, "controlled_move");
                }
                if (state == State.ACTIVE && overlay != null) overlay.update(selectionX, selectionY);

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
            DiagnosticLog.i(context, "FV_SELECT", "snapshot ready=" + bitmap.getWidth() + "x" + bitmap.getHeight());
        }, error -> {
            if (generation == captureGeneration) DiagnosticLog.i(context, "FV_SELECT", "snapshot failed=" + error);
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
        DiagnosticLog.i(context, "FV_SELECT", "ACTIVE reason=" + reason
                + " after=" + (SystemClock.uptimeMillis() - downAt) + "ms hotspot="
                + Math.round(x) + "," + Math.round(y));
    }

    private void settledRefresh() {
        if (!moved || accessibility == null || Float.isNaN(selectionX) || Float.isNaN(selectionY)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastFastFrameAt < FV_SETTLE_DELAY_MS) {
            handler.postDelayed(settleRunnable, FV_SETTLE_DELAY_MS - (now - lastFastFrameAt));
            return;
        }
        if (state != State.ACTIVE) activateNow(selectionX, selectionY, "settled_100ms");
        else if (overlay != null) overlay.update(selectionX, selectionY);
    }

    public boolean finish(MotionEvent up) {
        boolean wasActive = state == State.ACTIVE;
        handler.removeCallbacks(settleRunnable);
        if (up != null) {
            PointF p = pointTransformer.transform(up);
            selectionX = p.x;
            selectionY = p.y;
            if (overlay != null) overlay.update(selectionX, selectionY);
        }
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
        DiagnosticLog.i(context, "FV_SELECT", "UP active=" + wasActive
                + " candidate=" + hadCandidate + " result=" + producedResult
                + " hotspot=" + Math.round(selectionX) + "," + Math.round(selectionY));
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
        int l = Math.round(Math.min(minSelectionX, maxSelectionX));
        int t = Math.round(Math.min(minSelectionY, maxSelectionY));
        int r = Math.round(Math.max(minSelectionX, maxSelectionX));
        int b = Math.round(Math.max(minSelectionY, maxSelectionY));
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
        downRawX = downRawY = previousRawX = previousRawY = selectionX = selectionY = Float.NaN;
        minSelectionX = minSelectionY = maxSelectionX = maxSelectionY = 0f;
        downAt = 0L;
        lastFastFrameAt = 0L;
        moved = false;
        screenSnapshot = null;
        snapshotBounds = null;
    }

    private float dp(float v) { return v * context.getResources().getDisplayMetrics().density; }
}
