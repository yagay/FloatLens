package com.yagay.floatlens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

/**
 * Clean-room equivalent of FV's m2/g selection activation path.
 *
 * Observed FV behaviour:
 * - the icon itself follows MOVE immediately;
 * - the selection container is NOT shown on the first MOVE;
 * - significant pointer movement resets a delayed O0 runnable;
 * - when movement settles, the runnable fires (normally 400 ms) and shows m2/g;
 * - only while m2/g is shown are MotionEvents routed into the selection container.
 *
 * This class deliberately does not invent an "inside screen" rule and does not auto-extract merely
 * because an Accessibility candidate exists.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, ARMING, ACTIVE }

    private static final long NORMAL_ARM_DELAY_MS = 400L;
    private static final float FV_REARM_DISTANCE_DP = 3f;

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable activateRunnable = this::activate;
    private ViewHoverOverlay overlay;
    private State state = State.IDLE;
    private float anchorX = Float.NaN, anchorY = Float.NaN;
    private float pointerX = Float.NaN, pointerY = Float.NaN;
    private long downAt;
    private boolean moved;

    public ViewSelectionEngine(Context c) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
    }

    public boolean available() { return accessibility != null; }
    public boolean isActive() { return state == State.ACTIVE; }
    public State state() { return state; }

    /** Receives the same raw MotionEvent stream as the floating icon. */
    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null || accessibility == null) return;
        final int action = e.getActionMasked();
        final float x = e.getRawX(), y = e.getRawY();
        pointerX = x; pointerY = y;

        if (action == MotionEvent.ACTION_DOWN) {
            resetInternal(false);
            downAt = SystemClock.uptimeMillis();
            pointerX = anchorX = x;
            pointerY = anchorY = y;
            DiagnosticLog.i(context, "FV_SELECT", "DOWN x="+Math.round(x)+" y="+Math.round(y));
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            moved = true;
            if (state == State.ACTIVE) {
                if (overlay != null) overlay.update(x, y);
                return;
            }

            if (Float.isNaN(anchorX) || Float.isNaN(anchorY)) {
                anchorX = x; anchorY = y;
                return;
            }

            float dx = x - anchorX, dy = y - anchorY;
            float rearm = dp(FV_REARM_DISTANCE_DP);
            if (dx*dx + dy*dy > rearm*rearm) {
                // FV c3 removes and reposts O0 whenever the pointer has moved more than ~3dp
                // from the last arm point. If movement stops, the pending callback is allowed to fire.
                handler.removeCallbacks(activateRunnable);
                anchorX = x; anchorY = y;
                state = State.ARMING;
                handler.postDelayed(activateRunnable, NORMAL_ARM_DELAY_MS);
                DiagnosticLog.i(context, "FV_SELECT", "ARM x="+Math.round(x)+" y="+Math.round(y)+" delay="+NORMAL_ARM_DELAY_MS);
            }
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL) cancel();
    }

    private void activate() {
        if (accessibility == null || !moved || state != State.ARMING) return;
        state = State.ACTIVE;
        overlay = new ViewHoverOverlay(context);
        if (overlay.available()) {
            overlay.begin();
            if (!Float.isNaN(pointerX) && !Float.isNaN(pointerY)) overlay.update(pointerX, pointerY);
        }
        DiagnosticLog.i(context, "FV_SELECT", "ACTIVE after="+(SystemClock.uptimeMillis()-downAt)+"ms x="+Math.round(pointerX)+" y="+Math.round(pointerY));
    }

    /**
     * Ends the FV-style selection container. The return value means the selection container had
     * actually become active, so the caller should not also execute the ordinary gesture path.
     * Candidate existence alone never changes this decision.
     */
    public boolean finish(MotionEvent up) {
        boolean wasActive = state == State.ACTIVE;
        handler.removeCallbacks(activateRunnable);
        if (overlay != null) overlay.finish(false);
        DiagnosticLog.i(context, "FV_SELECT", "UP active="+wasActive+" state="+state+" candidate="+(overlay!=null && overlay.hasCandidate()));
        resetInternal(false);
        return wasActive;
    }

    public void cancel() {
        boolean wasArming = state == State.ARMING;
        boolean wasActive = state == State.ACTIVE;
        handler.removeCallbacks(activateRunnable);
        if (overlay != null) overlay.cancel();
        DiagnosticLog.i(context, "FV_SELECT", "CANCEL arming="+wasArming+" active="+wasActive);
        resetInternal(false);
    }

    private void resetInternal(boolean closeOverlay) {
        handler.removeCallbacks(activateRunnable);
        if (closeOverlay && overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        anchorX = anchorY = Float.NaN;
        pointerX = pointerY = Float.NaN;
        downAt = 0L;
        moved = false;
    }

    private float dp(float v) { return v * context.getResources().getDisplayMetrics().density; }
}
