package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * Floating-icon touch owner.
 *
 * One touch can end as tap/double-tap, configured long press, gesture, Direct selection or explicit
 * position move. The obsolete mid-touch CircleLive branch is intentionally gone; Circle Select is a
 * normal action routed through ActionRegistry. A long-press timer is armed only when a real long
 * action is configured, so ActionId.NONE can never steal the 400 ms Direct dwell.
 */
public class FloatIconView extends View {
    public interface Callback {
        void onDragStart();
        /** Absolute displacement from ACTION_DOWN, not a per-frame delta. */
        void onMove(int dxFromDown, int dyFromDown);
        void onRelease(boolean dragged);
        void onGestureDecision(GestureDecision decision);
        void onAction(String action);
        void onGestureStart(float rawX, float rawY);
        void onGestureMove(float rawX, float rawY);
        void onGestureEnd(List<GesturePointSample> points);
        void onDirectSelectionStart();
        void onDirectSelectionEnd();
    }

    private static final long FL_DIRECT_SELECT_DELAY_MS = 400L;
    private static final float FL_DIRECT_MOVE_START_DP = 3f;
    private static final long FL_LONG_PRESS_FINAL_STABLE_MS = 100L;
    private static final float FL_LONG_PRESS_REARM_PX = 3f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GestureSession session = new GestureSession();
    private final FloatIconRenderer renderer;
    private final Callback cb;
    private final Runnable directSelectionRunnable;
    private final float directRearmSlopPx;

    private FloatSettings fs;
    private long lastTapAt;
    private Runnable longPressPrimeRunnable;
    private Runnable longPressRunnable;
    private Runnable singleTapRunnable;
    private boolean followStarted;
    private boolean longPressPrimed;
    private boolean longPressActionTriggered;
    private boolean directSelectionActive;
    private boolean directTimerArmed;
    private boolean positionMoveMode;
    private float lastSelectionRawX = Float.NaN, lastSelectionRawY = Float.NaN;
    private float directTimerAnchorX = Float.NaN, directTimerAnchorY = Float.NaN;
    private float longPressAnchorRawX = Float.NaN, longPressAnchorRawY = Float.NaN;
    private ViewSelectionEngine selectionEngine;

    private boolean flWindowKnown;
    private int flWindowStartX, flWindowStartY;
    private int flWindowX, flWindowY;

    public FloatIconView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        renderer = new FloatIconRenderer(this);
        directRearmSlopPx = Math.max(1f,
                FL_DIRECT_MOVE_START_DP * getResources().getDisplayMetrics().density);
        directSelectionRunnable = () -> {
            directTimerArmed = false;
            if (directSelectionActive || longPressActionTriggered || positionMoveMode || session.multiTouch
                    || !followStarted || selectionEngine == null || !selectionEngine.available()
                    || Float.isNaN(lastSelectionRawX) || Float.isNaN(lastSelectionRawY)
                    || session.phase == GestureSession.Phase.IDLE
                    || session.phase == GestureSession.Phase.FINISHING) return;

            cancelLongPress();
            directSelectionActive = true;
            cb.onGestureEnd(session.snapshot());
            cb.onDirectSelectionStart();
            boolean ok = selectionEngine.activateDirect(lastSelectionRawX, lastSelectionRawY);
            if (!ok) {
                directSelectionActive = false;
                cb.onDirectSelectionEnd();
                DiagnosticLog.i(getContext(), "FL_DIRECT", "enter failed");
                return;
            }
            DiagnosticLog.i(getContext(), "FL_DIRECT", "ENTER delay="
                    + FL_DIRECT_SELECT_DELAY_MS + "ms raw=" + Math.round(lastSelectionRawX)
                    + "," + Math.round(lastSelectionRawY)
                    + " anchor=" + Math.round(directTimerAnchorX) + "," + Math.round(directTimerAnchorY)
                    + " axisSlopPx=" + Math.round(directRearmSlopPx));
            if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
        };
        DiagnosticLog.init(c);
        fs = new FloatSettings(c);
        renderer.refresh(fs);
        setClickable(true);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void refreshSettings() {
        fs = new FloatSettings(getContext());
        renderer.refresh(fs);
        invalidate();
    }

    RectF currentFlWindowBounds() {
        if (!flWindowKnown) beginFlWindowTracking();
        int w = Math.max(1, getWidth() > 0 ? getWidth() : getMeasuredWidth());
        int h = Math.max(1, getHeight() > 0 ? getHeight() : getMeasuredHeight());
        return new RectF(flWindowX, flWindowY, flWindowX + w, flWindowY + h);
    }

    private void beginFlWindowTracking() {
        int[] loc = new int[2];
        try {
            getLocationOnScreen(loc);
            flWindowStartX = flWindowX = loc[0];
            flWindowStartY = flWindowY = loc[1];
            flWindowKnown = true;
        } catch (Throwable ignored) {
            flWindowKnown = false;
        }
    }

    private void updateFlWindowTracking(int dxFromDown, int dyFromDown) {
        if (!flWindowKnown) beginFlWindowTracking();
        if (!flWindowKnown) return;
        flWindowX = flWindowStartX + dxFromDown;
        flWindowY = flWindowStartY + dyFromDown;
    }

    @Override protected void onDetachedFromWindow() {
        cancelLongPress();
        cancelDirectSelectionTimer();
        if (selectionEngine != null) selectionEngine.cancel();
        renderer.detach();
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (directSelectionActive) return;
        renderer.draw(canvas, getWidth(), getHeight(), session.phase != GestureSession.Phase.IDLE);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        final float rx = e.getRawX(), ry = e.getRawY();
        final long now = SystemClock.uptimeMillis();
        if (action != MotionEvent.ACTION_MOVE || session.points.size() % 4 == 0) {
            DiagnosticLog.i(getContext(), "TOUCH", "action=" + action + " pointers=" + e.getPointerCount()
                    + " raw=" + Math.round(rx) + "," + Math.round(ry) + " phase=" + session.phase
                    + " direct=" + directSelectionActive + " long=" + longPressActionTriggered
                    + " positionMove=" + positionMoveMode);
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            session.multiTouch = true;
            cancelLongPress();
            cancelDirectSelectionTimer();
            longPressActionTriggered = false;
            if (directSelectionActive) {
                if (selectionEngine != null) selectionEngine.cancel();
                cb.onDirectSelectionEnd();
                directSelectionActive = false;
            } else if (selectionEngine != null) {
                selectionEngine.cancel();
            }
            if (positionMoveMode) {
                FloatService service = FloatService.get();
                if (service != null) service.cancelPositionMove();
            }
            cb.onGestureEnd(session.snapshot());
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) return true;

        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                beginTouch(e, rx, ry, now);
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                moveTouch(rx, ry, now);
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                endTouch(rx, ry, now);
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                cancelTouch(now);
                return true;
            }
        }
        return true;
    }

    private void beginTouch(MotionEvent e, float rx, float ry, long now) {
        cancelLongPress();
        cancelDirectSelectionTimer();
        if (selectionEngine != null) selectionEngine.cancel();

        FloatService service = FloatService.get();
        positionMoveMode = service != null && service.isPositionMoveArmed();
        beginFlWindowTracking();
        selectionEngine = positionMoveMode ? null : new ViewSelectionEngine(getContext());
        longPressPrimed = false;
        longPressActionTriggered = false;
        directSelectionActive = false;
        followStarted = false;
        lastSelectionRawX = lastSelectionRawY = Float.NaN;
        directTimerAnchorX = directTimerAnchorY = Float.NaN;
        longPressAnchorRawX = rx;
        longPressAnchorRawY = ry;
        session.begin(rx, ry, now);
        if (selectionEngine != null && selectionEngine.available()) selectionEngine.dispatchTouchEvent(e);

        String longAction = fs.action(FloatSettings.K_ACTION_LONG, ActionId.NONE);
        DiagnosticLog.i(getContext(), "STATE", "DOWN begin=" + Math.round(rx) + "," + Math.round(ry)
                + " directAvailable=" + (selectionEngine != null && selectionEngine.available())
                + " directAxisSlopPx=" + Math.round(directRearmSlopPx)
                + " longAction=" + longAction
                + " positionMove=" + positionMoveMode);
        invalidate();
        if (!positionMoveMode && !ActionId.NONE.equals(longAction)) armFlLongPress(rx, ry, longAction);
    }

    private void moveTouch(float rx, float ry, long now) {
        session.add(rx, ry, now);
        if (session.multiTouch) return;

        int moveDx = Math.round(rx - session.downX);
        int moveDy = Math.round(ry - session.downY);
        float dist = session.distance();

        if (positionMoveMode) {
            if ((moveDx != 0 || moveDy != 0) && dist >= dp(1.5f)) {
                if (!followStarted) {
                    followStarted = true;
                    cb.onDragStart();
                    DiagnosticLog.i(getContext(), "STATE", "explicit position move started");
                }
                updateFlWindowTracking(moveDx, moveDy);
                cb.onMove(moveDx, moveDy);
                session.moved = true;
                session.phase = GestureSession.Phase.ICON_DRAG;
            }
            return;
        }

        if (longPressActionTriggered) return;

        if (directSelectionActive) {
            if ((moveDx != 0 || moveDy != 0) && followStarted) {
                updateFlWindowTracking(moveDx, moveDy);
                cb.onMove(moveDx, moveDy);
                session.moved = true;
            }
            if (selectionEngine != null) selectionEngine.updateDirect(rx, ry);
            return;
        }

        rearmFlLongPressIfNeeded(rx, ry);

        if ((moveDx != 0 || moveDy != 0) && dist >= dp(1.5f)) {
            if (!followStarted) {
                followStarted = true;
                cb.onDragStart();
                DiagnosticLog.i(getContext(), "STATE", "temporary follow start distance=" + Math.round(dist));
            }
            updateFlWindowTracking(moveDx, moveDy);
            cb.onMove(moveDx, moveDy);
            session.moved = true;
        }

        if (followStarted && selectionEngine != null && selectionEngine.available()) {
            selectionEngine.showProbe(rx, ry);
            armOrRearmDirectSelection(rx, ry);
        }

        if (session.phase == GestureSession.Phase.DOWN && dist >= gestureSlopPx()) {
            cancelLongPress();
            session.phase = GestureSession.Phase.GESTURE;
            cb.onGestureStart(session.downX, session.downY);
        }
        if (session.phase == GestureSession.Phase.GESTURE) cb.onGestureMove(rx, ry);
    }

    private void endTouch(float rx, float ry, long now) {
        cancelLongPress();
        cancelDirectSelectionTimer();
        session.add(rx, ry, now);

        if (positionMoveMode) {
            cb.onGestureEnd(session.snapshot());
            cb.onRelease(followStarted);
            resetSession();
            return;
        }
        if (longPressActionTriggered) {
            if (selectionEngine != null) selectionEngine.cancel();
            cb.onGestureEnd(session.snapshot());
            cb.onRelease(followStarted);
            DiagnosticLog.i(getContext(), "LONG_PRESS", "release consumed");
            resetSession();
            return;
        }
        if (directSelectionActive) {
            boolean result = selectionEngine != null && selectionEngine.finishDirect(rx, ry);
            cb.onDirectSelectionEnd();
            directSelectionActive = false;
            cb.onGestureEnd(session.snapshot());
            cb.onRelease(followStarted);
            DiagnosticLog.i(getContext(), "GESTURE_LAYER", "source=direct_release result=" + result);
            resetSession();
            return;
        }

        if (selectionEngine != null) selectionEngine.cancel();
        finish(now, false);
    }

    private void cancelTouch(long now) {
        cancelLongPress();
        cancelDirectSelectionTimer();
        longPressActionTriggered = false;
        if (directSelectionActive) {
            if (selectionEngine != null) selectionEngine.cancel();
            cb.onDirectSelectionEnd();
            directSelectionActive = false;
        } else if (selectionEngine != null) {
            selectionEngine.cancel();
        }
        if (positionMoveMode) {
            FloatService service = FloatService.get();
            if (service != null) service.cancelPositionMove();
        }
        finish(now, true);
    }

    /** Two-stage FL long press: prime at T-100 ms, then require 100 ms stability. */
    private void armFlLongPress(float rawX, float rawY, String configuredAction) {
        cancelLongPress();
        if (ActionId.NONE.equals(configuredAction)) return;
        longPressPrimed = false;
        longPressAnchorRawX = rawX;
        longPressAnchorRawY = rawY;

        longPressRunnable = () -> {
            if (session.multiTouch || positionMoveMode || directSelectionActive
                    || longPressActionTriggered
                    || session.phase == GestureSession.Phase.IDLE
                    || session.phase == GestureSession.Phase.GESTURE
                    || session.phase == GestureSession.Phase.FINISHING) return;
            longPressPrimed = false;
            session.longPressReady = true;
            longPressActionTriggered = true;
            cancelDirectSelectionTimer();
            if (selectionEngine != null) selectionEngine.cancel();
            DiagnosticLog.i(getContext(), "LONG_PRESS", "trigger duration="
                    + session.duration(SystemClock.uptimeMillis()) + " action=" + configuredAction
                    + " followed=" + followStarted);
            if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            cb.onAction(configuredAction);
            invalidate();
        };

        longPressPrimeRunnable = () -> {
            if (session.multiTouch || positionMoveMode || directSelectionActive
                    || longPressActionTriggered
                    || session.phase == GestureSession.Phase.IDLE
                    || session.phase == GestureSession.Phase.GESTURE
                    || session.phase == GestureSession.Phase.FINISHING) return;
            longPressPrimed = true;
            longPressAnchorRawX = session.lastX;
            longPressAnchorRawY = session.lastY;
            handler.removeCallbacks(longPressRunnable);
            handler.postDelayed(longPressRunnable, FL_LONG_PRESS_FINAL_STABLE_MS);
            DiagnosticLog.i(getContext(), "LONG_PRESS", "prime duration="
                    + session.duration(SystemClock.uptimeMillis())
                    + " anchor=" + Math.round(longPressAnchorRawX) + "," + Math.round(longPressAnchorRawY)
                    + " stableMs=" + FL_LONG_PRESS_FINAL_STABLE_MS
                    + " rearmPx=" + Math.round(FL_LONG_PRESS_REARM_PX)
                    + " followed=" + followStarted);
        };

        long primeDelay = Math.max(0L, fs.longPressMs() - FL_LONG_PRESS_FINAL_STABLE_MS);
        handler.postDelayed(longPressPrimeRunnable, primeDelay);
        DiagnosticLog.i(getContext(), "LONG_PRESS", "arm action=" + configuredAction
                + " totalMs=" + fs.longPressMs() + " primeDelayMs=" + primeDelay);
    }

    private void rearmFlLongPressIfNeeded(float rawX, float rawY) {
        if (!longPressPrimed || longPressRunnable == null || longPressActionTriggered
                || Float.isNaN(longPressAnchorRawX) || Float.isNaN(longPressAnchorRawY)) return;
        float dx = rawX - longPressAnchorRawX;
        float dy = rawY - longPressAnchorRawY;
        if (Math.abs(dx) < FL_LONG_PRESS_REARM_PX && Math.abs(dy) < FL_LONG_PRESS_REARM_PX) return;
        handler.removeCallbacks(longPressRunnable);
        longPressAnchorRawX = rawX;
        longPressAnchorRawY = rawY;
        handler.postDelayed(longPressRunnable, FL_LONG_PRESS_FINAL_STABLE_MS);
        DiagnosticLog.i(getContext(), "LONG_PRESS", "rearm dx=" + Math.round(dx)
                + " dy=" + Math.round(dy) + " stableMs=" + FL_LONG_PRESS_FINAL_STABLE_MS);
    }

    private void armOrRearmDirectSelection(float rawX, float rawY) {
        lastSelectionRawX = rawX;
        lastSelectionRawY = rawY;
        if (!directTimerArmed || Float.isNaN(directTimerAnchorX) || Float.isNaN(directTimerAnchorY)) {
            directTimerAnchorX = rawX;
            directTimerAnchorY = rawY;
            directTimerArmed = true;
            handler.removeCallbacks(directSelectionRunnable);
            handler.postDelayed(directSelectionRunnable, FL_DIRECT_SELECT_DELAY_MS);
            DiagnosticLog.i(getContext(), "FL_DIRECT", "ARM anchor=" + Math.round(rawX) + ","
                    + Math.round(rawY) + " delay=" + FL_DIRECT_SELECT_DELAY_MS);
            return;
        }
        float dx = rawX - directTimerAnchorX;
        float dy = rawY - directTimerAnchorY;
        if (Math.abs(dx) <= directRearmSlopPx && Math.abs(dy) <= directRearmSlopPx) return;
        handler.removeCallbacks(directSelectionRunnable);
        directTimerAnchorX = rawX;
        directTimerAnchorY = rawY;
        handler.postDelayed(directSelectionRunnable, FL_DIRECT_SELECT_DELAY_MS);
        DiagnosticLog.i(getContext(), "FL_DIRECT", "REARM anchor=" + Math.round(rawX) + ","
                + Math.round(rawY) + " dx=" + Math.round(dx) + " dy=" + Math.round(dy));
    }

    private void finish(long now, boolean cancelled) {
        GestureSession.Phase ended = session.phase;
        DiagnosticLog.i(getContext(), "FINISH", "ended=" + ended + " cancelled=" + cancelled
                + " duration=" + session.duration(now) + " distance=" + Math.round(session.distance())
                + " points=" + session.points.size() + " multi=" + session.multiTouch
                + " followed=" + followStarted);
        session.phase = GestureSession.Phase.FINISHING;
        cb.onGestureEnd(session.snapshot());
        if (session.multiTouch || cancelled) {
            cb.onRelease(followStarted);
            resetSession();
            return;
        }

        if (ended == GestureSession.Phase.GESTURE) {
            GestureDecision decision = GestureClassifier.classify(
                    session, fs, getResources().getDisplayMetrics().density);
            cb.onRelease(followStarted);
            if (!decision.isNone()) {
                if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
                cb.onGestureDecision(decision);
            }
        } else {
            cb.onRelease(followStarted);
            if (!followStarted && session.duration(now) <= fs.tapMaxMs()
                    && session.distance() < gestureSlopPx()) handleTap(now);
        }
        resetSession();
    }

    private void resetSession() {
        cancelLongPress();
        cancelDirectSelectionTimer();
        selectionEngine = null;
        longPressPrimed = false;
        longPressActionTriggered = false;
        directSelectionActive = false;
        positionMoveMode = false;
        followStarted = false;
        flWindowKnown = false;
        lastSelectionRawX = lastSelectionRawY = Float.NaN;
        longPressAnchorRawX = longPressAnchorRawY = Float.NaN;
        session.reset();
        invalidate();
    }

    private void handleTap(long now) {
        if (lastTapAt != 0 && now - lastTapAt <= fs.doubleTapMs()) {
            lastTapAt = 0;
            if (singleTapRunnable != null) handler.removeCallbacks(singleTapRunnable);
            singleTapRunnable = null;
            if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            cb.onAction(fs.action(FloatSettings.K_ACTION_DOUBLE, ActionId.SCREENSHOT));
            return;
        }
        lastTapAt = now;
        singleTapRunnable = () -> {
            if (lastTapAt == now) {
                lastTapAt = 0;
                cb.onGestureDecision(new GestureDecision(GestureCode.TAP, false, 0f, 0f));
            }
            singleTapRunnable = null;
        };
        handler.postDelayed(singleTapRunnable, fs.doubleTapMs());
    }

    private void cancelDirectSelectionTimer() {
        handler.removeCallbacks(directSelectionRunnable);
        directTimerArmed = false;
        directTimerAnchorX = directTimerAnchorY = Float.NaN;
    }

    private float gestureSlopPx() { return dp(fs.gestureStartDistance()); }

    @Override public void cancelLongPress() {
        super.cancelLongPress();
        if (longPressPrimeRunnable != null) handler.removeCallbacks(longPressPrimeRunnable);
        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
        longPressPrimeRunnable = null;
        longPressRunnable = null;
        longPressPrimed = false;
        longPressAnchorRawX = longPressAnchorRawY = Float.NaN;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
