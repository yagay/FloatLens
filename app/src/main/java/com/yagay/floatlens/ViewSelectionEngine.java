package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

/**
 * FV-style selection engine driven in parallel with the floating-icon MOVE stream.
 *
 * Only text and image/icon Accessibility Views are selectable. A candidate must remain unchanged
 * for the configured dwell delay before it becomes locked. Releasing before that time leaves the
 * normal icon gesture untouched; releasing after lock captures exactly the highlighted View bounds.
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
    private final Runnable dwellRunnable = this::confirmDwellCandidate;
    private final SelectionPointTransformer pointTransformer;
    private final int dwellConfirmMs;

    private ViewHoverOverlay overlay;
    private State state = State.IDLE;
    private float downRawX = Float.NaN, downRawY = Float.NaN;
    private float previousRawX = Float.NaN, previousRawY = Float.NaN;
    private float selectionX = Float.NaN, selectionY = Float.NaN;
    private long downAt;
    private long lastFastFrameAt;
    private boolean moved;
    private String dwellCandidateKey = "";
    private boolean dwellConfirmed;

    public ViewSelectionEngine(Context c) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
        FloatSettings fs = new FloatSettings(context);
        float px = fs.sizeDp() * context.getResources().getDisplayMetrics().density;
        pointTransformer = new SelectionPointTransformer(context, px, px);
        dwellConfirmMs = fs.viewCaptureDwellMs();
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
            DiagnosticLog.i(context, "FV_SELECT", "DOWN raw=" + Math.round(rawX) + "," + Math.round(rawY)
                    + " hotspot=" + Math.round(selectionX) + "," + Math.round(selectionY)
                    + " dwell=" + dwellConfirmMs + "ms");
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            moved = true;
            float frameDx = Float.isNaN(previousRawX) ? 0f : Math.abs(rawX - previousRawX);
            float frameDy = Float.isNaN(previousRawY) ? 0f : Math.abs(rawY - previousRawY);
            previousRawX = rawX;
            previousRawY = rawY;

            if (frameDx >= FV_FAST_FRAME_PX || frameDy >= FV_FAST_FRAME_PX) {
                lastFastFrameAt = now;
                handler.removeCallbacks(settleRunnable);
                cancelDwell();
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
                if (state == State.ACTIVE && overlay != null) {
                    overlay.update(selectionX, selectionY);
                    updateDwellCandidate();
                }
                handler.removeCallbacks(settleRunnable);
                handler.postDelayed(settleRunnable, FV_SETTLE_DELAY_MS);
            }
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL) cancel();
    }

    private void activateNow(float x, float y, String reason) {
        if (accessibility == null || state == State.ACTIVE) return;
        overlay = new ViewHoverOverlay(context);
        if (!overlay.available()) {
            overlay = null;
            return;
        }
        overlay.begin();
        state = State.ACTIVE;
        overlay.update(x, y);
        updateDwellCandidate();
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
        else if (overlay != null) {
            overlay.update(selectionX, selectionY);
            updateDwellCandidate();
        }
    }

    private void updateDwellCandidate() {
        if (state != State.ACTIVE || overlay == null) {
            cancelDwell();
            return;
        }
        ScreenCandidate c = overlay.currentCandidate();
        String key = c == null ? "" : c.stableKey();
        if (key.equals(dwellCandidateKey)) return;

        handler.removeCallbacks(dwellRunnable);
        dwellCandidateKey = key;
        dwellConfirmed = false;
        overlay.setConfirmed(false);

        if (!key.isEmpty()) {
            handler.postDelayed(dwellRunnable, dwellConfirmMs);
            DiagnosticLog.i(context, "VIEW_DWELL", "start delay=" + dwellConfirmMs
                    + "ms bounds=" + c.bounds() + " type=" + c.type());
        }
    }

    private void confirmDwellCandidate() {
        if (state != State.ACTIVE || overlay == null || dwellCandidateKey.isEmpty()) return;
        ScreenCandidate current = overlay.currentCandidate();
        if (current == null || !dwellCandidateKey.equals(current.stableKey())) return;
        dwellConfirmed = true;
        overlay.setConfirmed(true);
        DiagnosticLog.i(context, "VIEW_DWELL", "confirmed after=" + dwellConfirmMs
                + "ms bounds=" + current.bounds() + " type=" + current.type());
    }

    private void cancelDwell() {
        handler.removeCallbacks(dwellRunnable);
        dwellCandidateKey = "";
        dwellConfirmed = false;
        if (overlay != null) overlay.setConfirmed(false);
    }

    public boolean finish(MotionEvent up) {
        boolean wasActive = state == State.ACTIVE;
        handler.removeCallbacks(settleRunnable);
        if (up != null) {
            PointF p = pointTransformer.transform(up);
            selectionX = p.x;
            selectionY = p.y;
            if (overlay != null) {
                overlay.update(selectionX, selectionY);
                updateDwellCandidate();
            }
        }

        boolean hadCandidate = overlay != null && overlay.hasCandidate();
        boolean locked = wasActive && hadCandidate && dwellConfirmed;
        boolean producedResult = overlay != null && overlay.finish(locked);
        boolean tookOver = locked && producedResult;

        DiagnosticLog.i(context, "FV_SELECT", "UP active=" + wasActive
                + " candidate=" + hadCandidate + " dwellConfirmed=" + dwellConfirmed
                + " result=" + producedResult + " takeover=" + tookOver + " hotspot="
                + Math.round(selectionX) + "," + Math.round(selectionY));
        resetInternal(false);
        return tookOver;
    }

    public void cancel() {
        boolean wasActive = state == State.ACTIVE;
        handler.removeCallbacks(settleRunnable);
        cancelDwell();
        closeOverlay();
        DiagnosticLog.i(context, "FV_SELECT", "CANCEL active=" + wasActive);
        resetInternal(false);
    }

    private void closeOverlay() {
        if (overlay != null) overlay.cancel();
        overlay = null;
    }

    private void resetInternal(boolean close) {
        handler.removeCallbacks(settleRunnable);
        handler.removeCallbacks(dwellRunnable);
        if (close) closeOverlay();
        overlay = null;
        state = State.IDLE;
        downRawX = downRawY = previousRawX = previousRawY = selectionX = selectionY = Float.NaN;
        downAt = 0L;
        lastFastFrameAt = 0L;
        moved = false;
        dwellCandidateKey = "";
        dwellConfirmed = false;
    }

    private float dp(float v) { return v * context.getResources().getDisplayMetrics().density; }
}
