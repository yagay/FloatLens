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
 * Floating icon touch engine modelled from FV FooViewService$c3.onTouch.
 *
 * Visual resource loading and slideshow timing live in FloatIconRenderer. This class owns only FV
 * pointer semantics: immediate temporary-follow movement, 400ms direct-selection dwell, gestures,
 * long press and explicit position-move mode.
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
    // FV FooViewService uses a two-stage long press: arm at T-100 ms, then require a final
    // 100 ms stable window. During that final window, ~3 px movement re-arms only the window.
    private static final long FV_LONG_PRESS_FINAL_STABLE_MS = 100L;
    private static final float FV_LONG_PRESS_REARM_PX = 3f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GestureSession session = new GestureSession();
    private final FloatIconRenderer renderer;
    private FloatSettings fs;
    private final Callback cb;
    private final Runnable directSelectionRunnable;
    private final float directRearmSlopPx;
    private long lastTapAt;
    private Runnable longPressPrimeRunnable;
    private Runnable longPressRunnable;
    private Runnable singleTapRunnable;
    private boolean followStarted;
    private boolean selectionTookOver;
    private boolean circleActive;
    private boolean longPressPrimed;
    private boolean longPressActionTriggered;
    private boolean directSelectionActive;
    private boolean directTimerArmed;
    private boolean positionMoveMode;
    private float lastSelectionRawX = Float.NaN, lastSelectionRawY = Float.NaN;
    private float directTimerAnchorX = Float.NaN, directTimerAnchorY = Float.NaN;
    private float longPressAnchorRawX = Float.NaN, longPressAnchorRawY = Float.NaN;
    private ViewSelectionEngine selectionEngine;

    private boolean fvWindowKnown;
    private int fvWindowStartX, fvWindowStartY;
    private int fvWindowX, fvWindowY;

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

    /** Current owner-window rectangle used by FV FooViewService.D4()-style hint positioning. */
    RectF currentFvWindowBounds() {
        if (!fvWindowKnown) beginFvWindowTracking();
        int w = Math.max(1, getWidth() > 0 ? getWidth() : getMeasuredWidth());
        int h = Math.max(1, getHeight() > 0 ? getHeight() : getMeasuredHeight());
        return new RectF(fvWindowX, fvWindowY, fvWindowX + w, fvWindowY + h);
    }

    private void beginFvWindowTracking() {
        int[] loc = new int[2];
        try {
            getLocationOnScreen(loc);
            fvWindowStartX = fvWindowX = loc[0];
            fvWindowStartY = fvWindowY = loc[1];
            fvWindowKnown = true;
        } catch (Throwable ignored) {
            fvWindowKnown = false;
        }
    }

    private void updateFvWindowTracking(int dxFromDown, int dyFromDown) {
        if (!fvWindowKnown) beginFvWindowTracking();
        if (!fvWindowKnown) return;
        fvWindowX = fvWindowStartX + dxFromDown;
        fvWindowY = fvWindowStartY + dyFromDown;
    }

    @Override protected void onDetachedFromWindow() {
        cancelLongPress();
        cancelDirectSelectionTimer();
        if(selectionEngine!=null)selectionEngine.cancel();
        if(circleActive)CircleLiveController.cancel("icon_detached");
        renderer.detach();
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (directSelectionActive) return;
        renderer.draw(c, getWidth(), getHeight(), session.phase != GestureSession.Phase.IDLE);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        final float rx = e.getRawX(), ry = e.getRawY();
        final long now = SystemClock.uptimeMillis();
        if (action != MotionEvent.ACTION_MOVE || session.points.size() % 4 == 0) {
            DiagnosticLog.i(getContext(), "TOUCH", "action="+action+" pointers="+e.getPointerCount()
                    +" raw="+Math.round(rx)+","+Math.round(ry)+" phase="+session.phase
                    +" direct="+directSelectionActive+" regionEditor="+longPressActionTriggered
                    +" positionMove="+positionMoveMode);
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            session.multiTouch = true;
            cancelLongPress();
            cancelDirectSelectionTimer();
            longPressActionTriggered=false;
            if(directSelectionActive){if(selectionEngine!=null)selectionEngine.cancel();cb.onDirectSelectionEnd();directSelectionActive=false;}
            else if(selectionEngine!=null)selectionEngine.cancel();
            if(circleActive){CircleLiveController.cancel("multitouch");circleActive=false;}
            if(positionMoveMode){FloatService f=FloatService.get();if(f!=null)f.cancelPositionMove();}
            cb.onGestureEnd(session.snapshot());
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) return true;

        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                cancelLongPress();
                cancelDirectSelectionTimer();
                if(selectionEngine!=null)selectionEngine.cancel();
                if(circleActive)CircleLiveController.cancel("new_down");
                FloatService service=FloatService.get();
                positionMoveMode=service!=null&&service.isPositionMoveArmed();
                beginFvWindowTracking();
                selectionEngine=positionMoveMode?null:new ViewSelectionEngine(getContext(), this);
                selectionTookOver=false;
                circleActive=false;
                longPressPrimed=false;
                longPressActionTriggered=false;
                directSelectionActive=false;
                followStarted=false;
                lastSelectionRawX=lastSelectionRawY=Float.NaN;
                directTimerAnchorX=directTimerAnchorY=Float.NaN;
                longPressAnchorRawX=rx;
                longPressAnchorRawY=ry;
                session.begin(rx, ry, now);
                if(selectionEngine!=null&&selectionEngine.available())selectionEngine.dispatchTouchEvent(e);
                DiagnosticLog.i(getContext(), "STATE", "DOWN begin="+Math.round(rx)+","+Math.round(ry)
                        +" fvDirectAvailable="+(selectionEngine!=null&&selectionEngine.available())
                        +" directAxisSlopPx="+Math.round(directRearmSlopPx)
                        +" positionMove="+positionMoveMode);
                invalidate();

                if(!positionMoveMode) armFvLongPress(rx, ry);
                return true;
            }

            case MotionEvent.ACTION_MOVE -> {
                session.add(rx, ry, now);
                if (session.multiTouch) return true;

                int moveDx = Math.round(rx - session.downX);
                int moveDy = Math.round(ry - session.downY);
                float dist=session.distance();

                if(positionMoveMode){
                    if((moveDx!=0||moveDy!=0)&&dist>=dp(1.5f)){
                        if(!followStarted){followStarted=true;cb.onDragStart();DiagnosticLog.i(getContext(),"STATE","explicit position move started");}
                        updateFvWindowTracking(moveDx, moveDy);
                        cb.onMove(moveDx,moveDy);
                        session.moved=true;
                        session.phase=GestureSession.Phase.ICON_DRAG;
                    }
                    return true;
                }

                if(longPressActionTriggered) return true;

                if(directSelectionActive){
                    if ((moveDx!=0||moveDy!=0) && followStarted) {
                        updateFvWindowTracking(moveDx, moveDy);
                        cb.onMove(moveDx, moveDy);
                        session.moved=true;
                    }
                    if(selectionEngine!=null)selectionEngine.updateDirect(rx,ry);
                    return true;
                }

                if(circleActive || session.phase==GestureSession.Phase.CIRCLE){
                    CircleLiveController.move(rx,ry);
                    return true;
                }

                // FV keeps immediate temporary-follow movement independent from long-press
                // eligibility. Once the T-100 ms prime has fired, only the final 100 ms stable
                // window is re-armed when the pointer shifts by roughly 3 raw pixels.
                rearmFvLongPressIfNeeded(rx, ry);

                if ((moveDx!=0||moveDy!=0) && dist>=dp(1.5f)) {
                    if(!followStarted){followStarted=true;cb.onDragStart();DiagnosticLog.i(getContext(),"STATE","fvTemporaryFollowStart distance="+Math.round(dist));}
                    updateFvWindowTracking(moveDx, moveDy);
                    cb.onMove(moveDx,moveDy);
                    session.moved=true;
                }

                if(followStarted && selectionEngine!=null && selectionEngine.available()) {
                    selectionEngine.showProbe(rx, ry);
                    armOrRearmDirectSelection(rx, ry);
                }

                if(session.phase==GestureSession.Phase.DOWN && dist>=gestureSlopPx()){
                    cancelLongPress();
                    session.phase=GestureSession.Phase.GESTURE;
                    cb.onGestureStart(session.downX,session.downY);
                }
                if(session.phase==GestureSession.Phase.GESTURE)cb.onGestureMove(rx,ry);
                return true;
            }

            case MotionEvent.ACTION_UP -> {
                cancelLongPress();
                cancelDirectSelectionTimer();
                session.add(rx, ry, now);
                if(positionMoveMode){
                    cb.onGestureEnd(session.snapshot());
                    cb.onRelease(followStarted);
                    resetSession();
                    return true;
                }
                if(longPressActionTriggered){
                    if(selectionEngine!=null)selectionEngine.cancel();
                    cb.onGestureEnd(session.snapshot());
                    cb.onRelease(followStarted);
                    DiagnosticLog.i(getContext(),"LONG_PRESS","release consumed");
                    resetSession();
                    return true;
                }
                if(directSelectionActive){
                    selectionTookOver=selectionEngine!=null&&selectionEngine.finishDirect(rx,ry);
                    cb.onDirectSelectionEnd();
                    directSelectionActive=false;
                    cb.onGestureEnd(session.snapshot());
                    cb.onRelease(followStarted);
                    DiagnosticLog.i(getContext(),"GESTURE_LAYER","code="+GestureCode.RECOGNIZE
                            +" label="+GestureCode.label(GestureCode.RECOGNIZE)
                            +" source=fv_direct_release result="+selectionTookOver);
                    resetSession();
                    return true;
                }
                if(circleActive || session.phase==GestureSession.Phase.CIRCLE){
                    if(selectionEngine!=null)selectionEngine.cancel();
                    DiagnosticLog.i(getContext(),"GESTURE_LAYER","code="+GestureCode.CIRCLE_FINISH+" label="+GestureCode.label(GestureCode.CIRCLE_FINISH)+" source=mid_touch_release");
                    CircleLiveController.finish(rx,ry);
                    circleActive=false;
                    cb.onGestureEnd(session.snapshot());
                    cb.onRelease(false);
                    resetSession();
                    return true;
                }
                if(selectionEngine!=null)selectionEngine.cancel();
                selectionTookOver=false;
                finish(now, false);
                return true;
            }

            case MotionEvent.ACTION_CANCEL -> {
                cancelLongPress();
                cancelDirectSelectionTimer();
                longPressActionTriggered=false;
                if(directSelectionActive){
                    if(selectionEngine!=null)selectionEngine.cancel();
                    cb.onDirectSelectionEnd();
                    directSelectionActive=false;
                }else if(selectionEngine!=null)selectionEngine.cancel();
                if(circleActive){CircleLiveController.cancel("touch_cancel");circleActive=false;}
                if(positionMoveMode){FloatService f=FloatService.get();if(f!=null)f.cancelPositionMove();}
                selectionTookOver=false;
                finish(now, true);
                return true;
            }
        }
        return true;
    }

    /** Mirror FV's J0 -> I0 long-press timing: prime at T-100 ms, trigger after 100 ms stability. */
    private void armFvLongPress(float rawX, float rawY) {
        cancelLongPress();
        longPressPrimed = false;
        longPressAnchorRawX = rawX;
        longPressAnchorRawY = rawY;

        longPressRunnable = () -> {
            if (session.multiTouch || positionMoveMode || directSelectionActive || circleActive
                    || longPressActionTriggered
                    || session.phase == GestureSession.Phase.IDLE
                    || session.phase == GestureSession.Phase.GESTURE
                    || session.phase == GestureSession.Phase.CIRCLE
                    || session.phase == GestureSession.Phase.FINISHING) return;
            longPressPrimed = false;
            session.longPressReady = true;
            longPressActionTriggered = true;
            cancelDirectSelectionTimer();
            if(selectionEngine!=null)selectionEngine.cancel();
            String configured = fs.action(FloatSettings.K_ACTION_LONG, ActionId.NONE);
            DiagnosticLog.i(getContext(),"LONG_PRESS","trigger duration="
                    +session.duration(SystemClock.uptimeMillis())+" action="+configured
                    +" followed="+followStarted);
            if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (!ActionId.NONE.equals(configured)) cb.onAction(configured);
            invalidate();
        };

        longPressPrimeRunnable = () -> {
            if (session.multiTouch || positionMoveMode || directSelectionActive || circleActive
                    || longPressActionTriggered
                    || session.phase == GestureSession.Phase.IDLE
                    || session.phase == GestureSession.Phase.GESTURE
                    || session.phase == GestureSession.Phase.CIRCLE
                    || session.phase == GestureSession.Phase.FINISHING) return;
            longPressPrimed = true;
            longPressAnchorRawX = session.lastX;
            longPressAnchorRawY = session.lastY;
            handler.removeCallbacks(longPressRunnable);
            handler.postDelayed(longPressRunnable, FV_LONG_PRESS_FINAL_STABLE_MS);
            DiagnosticLog.i(getContext(), "LONG_PRESS", "prime duration="
                    + session.duration(SystemClock.uptimeMillis())
                    + " anchor=" + Math.round(longPressAnchorRawX) + "," + Math.round(longPressAnchorRawY)
                    + " stableMs=" + FV_LONG_PRESS_FINAL_STABLE_MS
                    + " rearmPx=" + Math.round(FV_LONG_PRESS_REARM_PX)
                    + " followed=" + followStarted);
        };

        long primeDelay = Math.max(0L, fs.longPressMs() - FV_LONG_PRESS_FINAL_STABLE_MS);
        handler.postDelayed(longPressPrimeRunnable, primeDelay);
        DiagnosticLog.i(getContext(), "LONG_PRESS", "arm totalMs=" + fs.longPressMs()
                + " primeDelayMs=" + primeDelay
                + " finalStableMs=" + FV_LONG_PRESS_FINAL_STABLE_MS);
    }

    private void rearmFvLongPressIfNeeded(float rawX, float rawY) {
        if (!longPressPrimed || longPressRunnable == null || longPressActionTriggered
                || Float.isNaN(longPressAnchorRawX) || Float.isNaN(longPressAnchorRawY)) return;
        float dx = rawX - longPressAnchorRawX;
        float dy = rawY - longPressAnchorRawY;
        if (Math.abs(dx) < FV_LONG_PRESS_REARM_PX && Math.abs(dy) < FV_LONG_PRESS_REARM_PX) return;

        handler.removeCallbacks(longPressRunnable);
        longPressAnchorRawX = rawX;
        longPressAnchorRawY = rawY;
        handler.postDelayed(longPressRunnable, FV_LONG_PRESS_FINAL_STABLE_MS);
        DiagnosticLog.i(getContext(), "LONG_PRESS", "rearm dx=" + Math.round(dx)
                + " dy=" + Math.round(dy)
                + " anchor=" + Math.round(rawX) + "," + Math.round(rawY)
                + " stableMs=" + FV_LONG_PRESS_FINAL_STABLE_MS);
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
            DiagnosticLog.i(getContext(), "FL_DIRECT", "ARM anchor="+Math.round(rawX)+","+Math.round(rawY)
                    +" delay="+FL_DIRECT_SELECT_DELAY_MS+" axisSlopPx="+Math.round(directRearmSlopPx));
            return;
        }

        float dx = rawX - directTimerAnchorX;
        float dy = rawY - directTimerAnchorY;
        if (Math.abs(dx) <= directRearmSlopPx && Math.abs(dy) <= directRearmSlopPx) return;

        handler.removeCallbacks(directSelectionRunnable);
        directTimerAnchorX = rawX;
        directTimerAnchorY = rawY;
        handler.postDelayed(directSelectionRunnable, FL_DIRECT_SELECT_DELAY_MS);
        DiagnosticLog.i(getContext(), "FL_DIRECT", "REARM anchor="+Math.round(rawX)+","+Math.round(rawY)
                +" dx="+Math.round(dx)+" dy="+Math.round(dy)
                +" axisSlopPx="+Math.round(directRearmSlopPx));
    }

    private void finish(long now, boolean cancelled) {
        GestureSession.Phase ended = session.phase;
        DiagnosticLog.i(getContext(), "FINISH", "ended="+ended+" cancelled="+cancelled
                +" duration="+session.duration(now)+" distance="+Math.round(session.distance())
                +" points="+session.points.size()+" multi="+session.multiTouch
                +" followed="+followStarted+" fvSelectionTookOver="+selectionTookOver);
        session.phase = GestureSession.Phase.FINISHING;
        cb.onGestureEnd(session.snapshot());
        if (session.multiTouch || cancelled) {
            cb.onRelease(followStarted);
            resetSession(); return;
        }

        if (selectionTookOver) {
            cb.onRelease(followStarted);
            resetSession(); return;
        }

        if (ended == GestureSession.Phase.GESTURE) {
            GestureDecision d = GestureClassifier.classify(session, fs, getResources().getDisplayMetrics().density);
            cb.onRelease(followStarted);
            if (!d.isNone()) {
                if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
                cb.onGestureDecision(d);
            }
        } else {
            cb.onRelease(followStarted);
            if (!followStarted && session.duration(now) <= fs.tapMaxMs() && session.distance() < gestureSlopPx()) handleTap(now);
        }
        resetSession();
    }

    private void resetSession(){
        cancelLongPress();
        cancelDirectSelectionTimer();
        selectionEngine=null;
        selectionTookOver=false;
        circleActive=false;
        longPressPrimed=false;
        longPressActionTriggered=false;
        directSelectionActive=false;
        positionMoveMode=false;
        followStarted=false;
        fvWindowKnown=false;
        lastSelectionRawX=lastSelectionRawY=Float.NaN;
        longPressAnchorRawX=longPressAnchorRawY=Float.NaN;
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
                cb.onGestureDecision(new GestureDecision(GestureCode.TAP,false,0f,0f));
            }
            singleTapRunnable = null;
        };
        handler.postDelayed(singleTapRunnable, fs.doubleTapMs());
    }

    private void cancelDirectSelectionTimer(){
        handler.removeCallbacks(directSelectionRunnable);
        directTimerArmed=false;
        directTimerAnchorX=directTimerAnchorY=Float.NaN;
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
