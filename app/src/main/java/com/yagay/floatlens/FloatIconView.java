package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;
import java.util.ArrayList;

/**
 * Floating icon touch engine modelled from FV FooViewService$c3.onTouch.
 *
 * FV moves the small icon immediately. Each MOVE derives the target from the gesture-start Window
 * position plus currentRaw-downRaw; it does not integrate rounded per-frame deltas. The independent
 * 15dp circle_focus probe is updated on the same MOVE stream. A ~400ms q Runnable can enter the
 * deeper View-selection state without ever blocking visible movement.
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

    private static final long FV_DIRECT_SELECT_DELAY_MS = 400L;
    private static final float FV_DIRECT_MOVE_START_DP = 3f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GestureSession session = new GestureSession();
    private FloatSettings fs;
    private final Callback cb;
    private final Runnable directSelectionRunnable;
    private final float directRearmSlopPx;
    private long lastTapAt;
    private Runnable longPressRunnable;
    private Runnable singleTapRunnable;
    private Drawable customDrawable;
    private final ArrayList<Drawable> slideDrawables = new ArrayList<>();
    private int slideIndex;
    private boolean followStarted;
    private boolean selectionTookOver;
    private boolean circleActive;
    private boolean longPressActionTriggered;
    private boolean directSelectionActive;
    private boolean directTimerArmed;
    private boolean positionMoveMode;
    private float lastSelectionRawX = Float.NaN, lastSelectionRawY = Float.NaN;
    private float directTimerAnchorX = Float.NaN, directTimerAnchorY = Float.NaN;
    private ViewSelectionEngine selectionEngine;

    private final Runnable slideRunnable = new Runnable() {
        public void run() {
            if (fs.style()==4 && slideDrawables.size()>1) {
                slideIndex=(slideIndex+1)%slideDrawables.size();
                invalidate();
                handler.postDelayed(this, fs.slideIntervalMs());
            }
        }
    };

    public FloatIconView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        // FV q uses a 3dp per-axis dwell box, not Euclidean distance / generic touch slop.
        directRearmSlopPx = Math.max(1f,
                FV_DIRECT_MOVE_START_DP * getResources().getDisplayMetrics().density);
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
                DiagnosticLog.i(getContext(), "FV_DIRECT", "enter failed");
                return;
            }
            DiagnosticLog.i(getContext(), "FV_DIRECT", "ENTER delay="
                    + FV_DIRECT_SELECT_DELAY_MS + "ms raw=" + Math.round(lastSelectionRawX)
                    + "," + Math.round(lastSelectionRawY)
                    + " anchor=" + Math.round(directTimerAnchorX) + "," + Math.round(directTimerAnchorY)
                    + " axisSlopPx=" + Math.round(directRearmSlopPx));
            if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
        };
        DiagnosticLog.init(c);
        fs = new FloatSettings(c);
        loadCustomIcon();
        setClickable(true);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void refreshSettings() { fs = new FloatSettings(getContext()); loadCustomIcon(); invalidate(); }

    @Override protected void onDetachedFromWindow() {
        cancelDirectSelectionTimer();
        if(selectionEngine!=null)selectionEngine.cancel();
        if(circleActive)CircleLiveController.cancel("icon_detached");
        handler.removeCallbacks(slideRunnable);
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private void loadCustomIcon() {
        handler.removeCallbacks(slideRunnable); customDrawable = null; slideDrawables.clear(); slideIndex=0;
        if (fs.style() == 3) {
            String raw = fs.customIconUri(); if (raw == null || raw.isBlank()) return;
            try { ImageDecoder.Source src=ImageDecoder.createSource(getContext().getContentResolver(),Uri.parse(raw)); customDrawable=ImageDecoder.decodeDrawable(src); customDrawable.setCallback(this); if(customDrawable instanceof AnimatedImageDrawable a)a.start(); } catch(Throwable ignored){customDrawable=null;}
        } else if (fs.style() == 4) {
            String raw=fs.slidePics(); if(raw==null||raw.isBlank())return;
            for(String u:raw.split("\\|")) { if(u.isBlank())continue; try{Drawable d=ImageDecoder.decodeDrawable(ImageDecoder.createSource(getContext().getContentResolver(),Uri.parse(u))); d.setCallback(this); slideDrawables.add(d);}catch(Throwable ignored){} }
            if(slideDrawables.size()>1) handler.postDelayed(slideRunnable,fs.slideIntervalMs());
        }
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (directSelectionActive) return;

        float w = getWidth(), h = getHeight(), r = Math.min(w, h) * .47f;
        int style = fs.style();
        if (style == 3 && customDrawable != null) { customDrawable.setBounds(0, 0, getWidth(), getHeight()); customDrawable.draw(c); return; }
        if (style == 4 && !slideDrawables.isEmpty()) { Drawable d=slideDrawables.get(Math.min(slideIndex,slideDrawables.size()-1)); d.setBounds(0,0,getWidth(),getHeight()); d.draw(c); return; }
        boolean pressed = session.phase != GestureSession.Phase.IDLE;
        if (pressed) r *= .90f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(style == 1 ? 0xEE202124 : style == 2 ? 0xCCFFFFFF : 0xDD1976D2);
        c.drawCircle(w / 2f, h / 2f, r, paint);
        paint.setStrokeWidth(Math.max(3f, w * .07f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(style == 2 ? 0xFF1976D2 : Color.WHITE);
        c.drawCircle(w / 2f, h / 2f, r * .53f, paint);
        c.drawLine(w * .68f, h * .68f, w * .83f, h * .83f, paint);
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
                selectionEngine=positionMoveMode?null:new ViewSelectionEngine(getContext());
                selectionTookOver=false;
                circleActive=false;
                longPressActionTriggered=false;
                directSelectionActive=false;
                followStarted=false;
                lastSelectionRawX=lastSelectionRawY=Float.NaN;
                directTimerAnchorX=directTimerAnchorY=Float.NaN;
                session.begin(rx, ry, now);
                if(selectionEngine!=null&&selectionEngine.available())selectionEngine.dispatchTouchEvent(e);
                DiagnosticLog.i(getContext(), "STATE", "DOWN begin="+Math.round(rx)+","+Math.round(ry)
                        +" fvDirectAvailable="+(selectionEngine!=null&&selectionEngine.available())
                        +" directAxisSlopPx="+Math.round(directRearmSlopPx)
                        +" positionMove="+positionMoveMode);
                invalidate();

                // Stationary long press is a normal configurable action. No OCR or region
                // behavior is hard-coded here; action_long_press is the single source of truth.
                if(!positionMoveMode){
                    longPressRunnable = () -> {
                        if (!session.multiTouch && !followStarted && session.phase == GestureSession.Phase.DOWN
                                && session.distance() < dp(FV_DIRECT_MOVE_START_DP)) {
                            session.longPressReady = true;
                            longPressActionTriggered = true;
                            cancelDirectSelectionTimer();
                            if(selectionEngine!=null)selectionEngine.cancel();
                            String configured = fs.action(FloatSettings.K_ACTION_LONG, ActionId.NONE);
                            DiagnosticLog.i(getContext(),"LONG_PRESS","trigger duration="
                                    +session.duration(SystemClock.uptimeMillis())+" action="+configured);
                            if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            if (!ActionId.NONE.equals(configured)) cb.onAction(configured);
                            invalidate();
                        }
                    };
                    handler.postDelayed(longPressRunnable, fs.longPressMs());
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE -> {
                session.add(rx, ry, now);
                if (session.multiTouch) return true;

                // FV uses absolute displacement from DOWN. This also preserves sub-pixel movement:
                // a sequence of tiny MOVE events eventually moves the icon instead of rounding each
                // individual frame to zero.
                int moveDx = Math.round(rx - session.downX);
                int moveDy = Math.round(ry - session.downY);
                float dist=session.distance();

                if(positionMoveMode){
                    if((moveDx!=0||moveDy!=0)&&dist>=dp(1.5f)){
                        if(!followStarted){followStarted=true;cb.onDragStart();DiagnosticLog.i(getContext(),"STATE","explicit position move started");}
                        cb.onMove(moveDx,moveDy);
                        session.moved=true;
                        session.phase=GestureSession.Phase.ICON_DRAG;
                    }
                    return true;
                }

                if(longPressActionTriggered) return true;

                if(directSelectionActive){
                    if(selectionEngine!=null)selectionEngine.updateDirect(rx,ry);
                    return true;
                }

                if(circleActive || session.phase==GestureSession.Phase.CIRCLE){
                    CircleLiveController.move(rx,ry);
                    return true;
                }

                if (dist >= dp(FV_DIRECT_MOVE_START_DP)) cancelLongPress();

                if ((moveDx!=0||moveDy!=0) && dist>=dp(1.5f)) {
                    if(!followStarted){followStarted=true;cb.onDragStart();DiagnosticLog.i(getContext(),"STATE","fvTemporaryFollowStart distance="+Math.round(dist));}
                    cb.onMove(moveDx,moveDy);
                    session.moved=true;
                }

                if(followStarted && selectionEngine!=null && selectionEngine.available()) {
                    // FV sends one transformed Point to circle_focus and to the selection layer on
                    // every MOVE. The 400ms Runnable changes selection state, not movement latency.
                    selectionEngine.showProbe(rx, ry);
                    armOrRearmDirectSelection(rx, ry);
                }

                if(session.phase==GestureSession.Phase.DOWN && dist>=gestureSlopPx()){
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

    /**
     * Mirrors FV FooViewService$c3 q scheduling: the 400ms dwell timer is re-armed only when the
     * pointer leaves a +/-3dp box around the current anchor on either axis. Small diagonal jitter
     * therefore does not accidentally cross a Euclidean-radius threshold and postpone selection.
     */
    private void armOrRearmDirectSelection(float rawX, float rawY) {
        lastSelectionRawX = rawX;
        lastSelectionRawY = rawY;

        if (!directTimerArmed || Float.isNaN(directTimerAnchorX) || Float.isNaN(directTimerAnchorY)) {
            directTimerAnchorX = rawX;
            directTimerAnchorY = rawY;
            directTimerArmed = true;
            handler.removeCallbacks(directSelectionRunnable);
            handler.postDelayed(directSelectionRunnable, FV_DIRECT_SELECT_DELAY_MS);
            DiagnosticLog.i(getContext(), "FV_DIRECT", "ARM anchor="+Math.round(rawX)+","+Math.round(rawY)
                    +" delay="+FV_DIRECT_SELECT_DELAY_MS+" axisSlopPx="+Math.round(directRearmSlopPx));
            return;
        }

        float dx = rawX - directTimerAnchorX;
        float dy = rawY - directTimerAnchorY;
        if (Math.abs(dx) <= directRearmSlopPx && Math.abs(dy) <= directRearmSlopPx) return;

        handler.removeCallbacks(directSelectionRunnable);
        directTimerAnchorX = rawX;
        directTimerAnchorY = rawY;
        handler.postDelayed(directSelectionRunnable, FV_DIRECT_SELECT_DELAY_MS);
        DiagnosticLog.i(getContext(), "FV_DIRECT", "REARM anchor="+Math.round(rawX)+","+Math.round(rawY)
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
        cancelDirectSelectionTimer();
        selectionEngine=null;
        selectionTookOver=false;
        circleActive=false;
        longPressActionTriggered=false;
        directSelectionActive=false;
        positionMoveMode=false;
        followStarted=false;
        lastSelectionRawX=lastSelectionRawY=Float.NaN;
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
        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
        longPressRunnable = null;
    }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
