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
 * Moving the floating icon and then holding the selection hotspot still for the configured dwell
 * delay arms the editable region workflow. If a text/image/full-screen View is under the hotspot it
 * stays highlighted while dwelling; empty screen space can arm the region workflow as well. The
 * touchable full-screen region editor is opened only after ACTION_UP so the current icon pointer
 * stream is never cancelled by a new overlay window.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, ACTIVE }

    private static final long FV_SETTLE_DELAY_MS = 100L;
    private static final float FV_SELECT_START_DP = 3f;
    private static final float FV_FAST_FRAME_PX = 40f;
    private static final float DWELL_STABILITY_DP = 8f;
    private static final String EMPTY_DWELL_KEY = "__EMPTY_REGION__";

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
    private float dwellAnchorX = Float.NaN, dwellAnchorY = Float.NaN;
    private long downAt;
    private long lastFastFrameAt;
    private boolean moved;
    private String dwellCandidateKey = "";
    private boolean dwellConfirmed;
    private boolean regionEditorArmed;

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
                    updateDwellTarget();
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
        updateDwellTarget();
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
            updateDwellTarget();
        }
    }

    /**
     * Dwell is based on both target identity and hotspot stability. Moving around inside one large
     * View therefore keeps restarting the timer instead of falsely counting as a stationary dwell.
     */
    private void updateDwellTarget() {
        if (state != State.ACTIVE || overlay == null) {
            cancelDwell();
            return;
        }

        ScreenCandidate c = overlay.currentCandidate();
        String key = c == null ? EMPTY_DWELL_KEY : c.stableKey();
        float stability = dp(DWELL_STABILITY_DP);
        boolean nearAnchor = !Float.isNaN(dwellAnchorX)
                && distanceSquared(selectionX, selectionY, dwellAnchorX, dwellAnchorY)
                <= stability * stability;

        if (key.equals(dwellCandidateKey) && nearAnchor) return;

        handler.removeCallbacks(dwellRunnable);
        dwellCandidateKey = key;
        dwellAnchorX = selectionX;
        dwellAnchorY = selectionY;
        dwellConfirmed = false;
        regionEditorArmed = false;
        overlay.setConfirmed(false);

        handler.postDelayed(dwellRunnable, dwellConfirmMs);
        DiagnosticLog.i(context, "VIEW_DWELL", "start delay=" + dwellConfirmMs
                + "ms target=" + (c == null ? "empty-region" : c.type())
                + " hotspot=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + (c == null ? "" : " bounds=" + c.bounds()));
    }

    private void confirmDwellCandidate() {
        if (state != State.ACTIVE || overlay == null || dwellCandidateKey.isEmpty()) return;

        float stability = dp(DWELL_STABILITY_DP);
        if (Float.isNaN(dwellAnchorX)
                || distanceSquared(selectionX, selectionY, dwellAnchorX, dwellAnchorY)
                > stability * stability) return;

        ScreenCandidate current = overlay.currentCandidate();
        if (EMPTY_DWELL_KEY.equals(dwellCandidateKey)) {
            if (current != null) return;
        } else if (current == null || !dwellCandidateKey.equals(current.stableKey())) {
            return;
        }

        dwellConfirmed = true;
        regionEditorArmed = true;
        // A concrete View gets the existing stronger lock highlight. Empty space is armed only in
        // state/logging until release because there is no meaningful View rectangle to draw.
        overlay.setConfirmed(current != null);
        DiagnosticLog.i(context, "REGION_DWELL", "armed after=" + dwellConfirmMs
                + "ms target=" + (current == null ? "empty-region" : current.type())
                + " hotspot=" + Math.round(selectionX) + "," + Math.round(selectionY)
                + (current == null ? "" : " bounds=" + current.bounds()));
    }

    private void cancelDwell() {
        handler.removeCallbacks(dwellRunnable);
        dwellCandidateKey = "";
        dwellAnchorX = dwellAnchorY = Float.NaN;
        dwellConfirmed = false;
        regionEditorArmed = false;
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
                updateDwellTarget();
            }
        }

        boolean hadCandidate = overlay != null && overlay.hasCandidate();
        boolean launchRegionEditor = wasActive && dwellConfirmed && regionEditorArmed;

        if (launchRegionEditor) {
            if (overlay != null) overlay.cancel();
            DiagnosticLog.i(context, "REGION_DWELL", "launch editor on release candidate="
                    + hadCandidate + " hotspot=" + Math.round(selectionX) + "," + Math.round(selectionY));
            ScreenshotController.captureForRegionEditor(context);
            resetInternal(false);
            return true;
        }

        // No completed dwell: close the hover overlay without producing a View screenshot. The
        // original gesture remains free to finish normally.
        if (overlay != null) overlay.cancel();
        DiagnosticLog.i(context, "FV_SELECT", "UP active=" + wasActive
                + " candidate=" + hadCandidate + " dwellConfirmed=" + dwellConfirmed
                + " regionArmed=" + regionEditorArmed + " takeover=false hotspot="
                + Math.round(selectionX) + "," + Math.round(selectionY));
        resetInternal(false);
        return false;
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
        dwellAnchorX = dwellAnchorY = Float.NaN;
        downAt = 0L;
        lastFastFrameAt = 0L;
        moved = false;
        dwellCandidateKey = "";
        dwellConfirmed = false;
        regionEditorArmed = false;
    }

    private float distanceSquared(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    private float dp(float v) { return v * context.getResources().getDisplayMetrics().density; }
}
