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
 * Normal MOVE temporarily follows the finger. Every MOVE restarts the observed FV-style 400 ms
 * selection Runnable. If the finger stops while still down, the SAME FloatIconView is expanded to
 * MATCH_PARENT by FloatService and remains the only touch owner; a separate NOT_TOUCHABLE overlay
 * only draws the current View candidate. ACTION_UP completes selection and restores the icon.
 */
public class FloatIconView extends View {
    public interface Callback {
        void onDragStart();
        void onMove(int dx, int dy);
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
    private long lastTapAt;
    private Runnable longPressRunnable;
    private Runnable singleTapRunnable;
    private Drawable customDrawable;
    private final ArrayList<Drawable> slideDrawables = new ArrayList<>();
    private int slideIndex;
    private boolean followStarted;
    private boolean selectionTookOver;
    private boolean circleActive;
    private boolean regionEditorTriggered;
    private boolean directSelectionActive;
    private boolean positionMoveMode;
    private float lastSelectionRawX = Float.NaN, lastSelectionRawY = Float.NaN;
    private ViewSelectionEngine selectionEngine;

    private final Runnable directSelectionRunnable = () -> {
        if (directSelectionActive || regionEditorTriggered || positionMoveMode || session.multiTouch
                || !followStarted || selectionEngine == null || !selectionEngine.available()
                || Float.isNaN(lastSelectionRawX) || Float.isNaN(lastSelectionRawY)
                || session.phase == GestureSession.Phase.IDLE
                || session.phase == GestureSession.Phase.FINISHING) return;

        cancelLongPress();
        directSelectionActive = true;
        // End the ordinary gesture trail before switching the same touch stream into selection mode.
        cb.onGestureEnd(session.snapshot());
        cb.onDirectSelectionStart();
        boolean ok = selectionEngine.activateDirect(lastSelectionRawX, lastSelectionRawY);
        if (!ok) {
            directSelectionActive = false;
            cb.onDirectSelectionEnd();
            DiagnosticLog.i(getContext(), "FV_DIRECT", "enter failed");
            return;
        }
        DiagnosticLog.i(getContext(), "FV_DIRECT", "ENTER afterMoveIdle="
                + FV_DIRECT_SELECT_DELAY_MS + "ms raw=" + Math.round(lastSelectionRawX)
                + "," + Math.round(lastSelectionRawY));
        if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
    };

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
        DiagnosticLog.init(c);
        fs = new FloatSettings(c);
        loadCustomIcon();
        setClickable(true);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void refreshSettings() { fs = new FloatSettings(getContext()); loadCustomIcon(); invalidate(); }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(directSelectionRunnable);
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
        // During FV direct selection this same View is MATCH_PARENT only to keep ownership of the
        // existing pointer stream. The icon itself is visually hidden; ViewHoverOverlay draws UI.
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
                    +" direct="+directSelectionActive+" regionEditor="+regionEditorTriggered
                    +" positionMove="+positionMoveMode);
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            session.multiTouch = true;
            cancelLongPress();
            cancelDirectSelectionTimer();
            regionEditorTriggered=false;
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
                regionEditorTriggered=false;
                directSelectionActive=false;
                followStarted=false;
                lastSelectionRawX=lastSelectionRawY=Float.NaN;
                session.begin(rx, ry, now);
                if(selectionEngine!=null&&selectionEngine.available())selectionEngine.dispatchTouchEvent(e);
                DiagnosticLog.i(getContext(), "STATE", "DOWN begin="+Math.round(rx)+","+Math.round(ry)
                        +" fvDirectAvailable="+(selectionEngine!=null&&selectionEngine.available())
                        +" positionMove="+positionMoveMode);
                invalidate();

                // Keep the user's stationary long-press region editor as a separate action. Any real
                // drag cancels this timer and switches to the observed FV 400 ms MOVE-idle path.
                if(!positionMoveMode){
                    longPressRunnable = () -> {
                        if (!session.multiTouch && !followStarted && session.phase == GestureSession.Phase.DOWN
                                && session.distance() < dp(FV_DIRECT_MOVE_START_DP)) {
                            session.longPressReady = true;
                            regionEditorTriggered = true;
                            cancelDirectSelectionTimer();
                            if(selectionEngine!=null)selectionEngine.cancel();
                            DiagnosticLog.i(getContext(),"REGION_EDIT","stationary_long_press_armed duration="
                                    +session.duration(SystemClock.uptimeMillis()));
                            if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            invalidate();
                        }
                    };
                    handler.postDelayed(longPressRunnable, fs.longPressMs());
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE -> {
                GesturePointSample prev = session.points.isEmpty() ? null : session.points.get(session.points.size()-1);
                session.add(rx, ry, now);
                if (session.multiTouch) return true;

                int stepDx=prev==null?0:Math.round(rx-prev.x());
                int stepDy=prev==null?0:Math.round(ry-prev.y());
                float dist=session.distance();

                if(positionMoveMode){
                    if((stepDx!=0||stepDy!=0)&&dist>=dp(1.5f)){
                        if(!followStarted){followStarted=true;cb.onDragStart();DiagnosticLog.i(getContext(),"STATE","explicit position move started");}
                        cb.onMove(stepDx,stepDy);
                        session.moved=true;
                        session.phase=GestureSession.Phase.ICON_DRAG;
                    }
                    return true;
                }

                if(regionEditorTriggered) return true;

                if(directSelectionActive){
                    if(selectionEngine!=null)selectionEngine.updateDirect(rx,ry);
                    return true;
                }

                if(circleActive || session.phase==GestureSession.Phase.CIRCLE){
                    CircleLiveController.move(rx,ry);
                    return true;
                }

                if (dist >= dp(FV_DIRECT_MOVE_START_DP)) cancelLongPress();

                if ((stepDx!=0||stepDy!=0) && dist>=dp(1.5f)) {
                    if(!followStarted){followStarted=true;cb.onDragStart();DiagnosticLog.i(getContext(),"STATE","fvTemporaryFollowStart distance="+Math.round(dist));}
                    cb.onMove(stepDx,stepDy);
                    session.moved=true;
                }

                // Confirmed FV behavior: every MOVE restarts the selection Runnable. If no further
                // MOVE arrives for ~400 ms while the finger remains down, direct selection begins.
                if(followStarted && selectionEngine!=null && selectionEngine.available()) {
                    lastSelectionRawX=rx;
                    lastSelectionRawY=ry;
                    handler.removeCallbacks(directSelectionRunnable);
                    handler.postDelayed(directSelectionRunnable,FV_DIRECT_SELECT_DELAY_MS);
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
                if(regionEditorTriggered){
                    if(selectionEngine!=null)selectionEngine.cancel();
                    cb.onGestureEnd(session.snapshot());
                    cb.onRelease(followStarted);
                    DiagnosticLog.i(getContext(),"REGION_EDIT","launch_on_release");
                    ScreenshotController.captureForRegionEditor(getContext());
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
                regionEditorTriggered=false;
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
        regionEditorTriggered=false;
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

    private void cancelDirectSelectionTimer(){handler.removeCallbacks(directSelectionRunnable);}
    private float gestureSlopPx() { return dp(fs.gestureStartDistance()); }
    @Override public void cancelLongPress() {
        super.cancelLongPress();
        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
        longPressRunnable = null;
    }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
