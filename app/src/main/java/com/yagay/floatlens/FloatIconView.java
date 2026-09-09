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

/** Floating icon touch engine. Recognition is intentionally separated from action execution. */
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
    }

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
    private final Runnable slideRunnable = new Runnable() { public void run() { if (fs.style()==4 && slideDrawables.size()>1) { slideIndex=(slideIndex+1)%slideDrawables.size(); invalidate(); handler.postDelayed(this, fs.slideIntervalMs()); } } };

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
    @Override protected void onDetachedFromWindow() { handler.removeCallbacks(slideRunnable); handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow(); }

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
        if (action != MotionEvent.ACTION_MOVE || session.points.size() % 4 == 0) DiagnosticLog.i(getContext(), "TOUCH", "action="+action+" pointers="+e.getPointerCount()+" raw="+Math.round(rx)+","+Math.round(ry)+" phase="+session.phase+" longReady="+session.longPressReady);

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            session.multiTouch = true;
            DiagnosticLog.i(getContext(), "STATE", "multiTouch=true pointerDown count="+e.getPointerCount());
            cancelLongPress();
            cb.onGestureEnd(session.snapshot());
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) return true;

        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                cancelLongPress();
                session.begin(rx, ry, now);
                DiagnosticLog.i(getContext(), "STATE", "DOWN begin="+Math.round(rx)+","+Math.round(ry));
                invalidate();
                longPressRunnable = () -> {
                    float tolerance = longPressDragTolerancePx();
                    if (!session.multiTouch
                            && session.phase != GestureSession.Phase.ICON_DRAG
                            && session.phase != GestureSession.Phase.FINISHING
                            && session.phase != GestureSession.Phase.IDLE
                            && session.distance() <= tolerance) {
                        session.longPressReady = true;
                        DiagnosticLog.i(getContext(), "STATE", "longPressReady afterMs="+fs.longPressMs()+" distance="+Math.round(session.distance())+" tolerance="+Math.round(tolerance)+" phase="+session.phase);
                        invalidate();
                        if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                };
                handler.postDelayed(longPressRunnable, fs.longPressMs());
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                session.add(rx, ry, now);
                if (session.multiTouch) return true;
                float dist = session.distance();
                float slop = gestureSlopPx();
                float holdTolerance = longPressDragTolerancePx();

                // Long-hold drag wins over gesture recognition. Small natural finger drift is tolerated
                // while the long-press timer is arming, so users don't need to hold perfectly still.
                if (session.longPressReady && fs.longPressDragEnabled()) {
                    if (session.phase != GestureSession.Phase.ICON_DRAG) {
                        session.phase = GestureSession.Phase.ICON_DRAG;
                        DiagnosticLog.i(getContext(), "STATE", "phase=ICON_DRAG distance="+Math.round(dist)+" slop="+Math.round(slop)+" holdTolerance="+Math.round(holdTolerance));
                        cb.onGestureEnd(session.snapshot());
                        cb.onDragStart();
                    }
                    if (session.points.size() >= 2) {
                        GesturePointSample prev = session.points.get(session.points.size() - 2);
                        int dx = Math.round(rx - prev.x());
                        int dy = Math.round(ry - prev.y());
                        if (dx != 0 || dy != 0) cb.onMove(dx, dy);
                    }
                    session.moved = true;
                    return true;
                }

                if (session.phase == GestureSession.Phase.DOWN && dist >= slop) {
                    // If long-press dragging is enabled, keep the long-press timer alive through a
                    // moderate amount of finger drift. A clearly intentional swipe still becomes a gesture.
                    if (!fs.longPressDragEnabled() || dist > holdTolerance) {
                        cancelLongPress();
                        session.phase = GestureSession.Phase.GESTURE;
                        DiagnosticLog.i(getContext(), "STATE", "phase=GESTURE distance="+Math.round(dist)+" slop="+Math.round(slop)+" holdTolerance="+Math.round(holdTolerance));
                        session.moved = true;
                        cb.onGestureStart(session.downX, session.downY);
                    }
                }
                if (session.phase == GestureSession.Phase.GESTURE) cb.onGestureMove(rx, ry);
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                cancelLongPress();
                session.add(rx, ry, now);
                finish(now, false);
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                cancelLongPress();
                finish(now, true);
                return true;
            }
        }
        return true;
    }

    private void finish(long now, boolean cancelled) {
        GestureSession.Phase ended = session.phase;
        DiagnosticLog.i(getContext(), "FINISH", "ended="+ended+" cancelled="+cancelled+" duration="+session.duration(now)+" distance="+Math.round(session.distance())+" points="+session.points.size()+" multi="+session.multiTouch+" longReady="+session.longPressReady);
        session.phase = GestureSession.Phase.FINISHING;
        cb.onGestureEnd(session.snapshot());
        if (session.multiTouch || cancelled) {
            cb.onRelease(ended == GestureSession.Phase.ICON_DRAG);
            session.reset(); invalidate(); return;
        }

        if (ended == GestureSession.Phase.ICON_DRAG) {
            cb.onRelease(true);
        } else if (ended == GestureSession.Phase.GESTURE) {
            cb.onRelease(false);
            GestureDecision d = GestureClassifier.classify(session, fs, getResources().getDisplayMetrics().density);
            DiagnosticLog.i(getContext(), "GESTURE", "decision="+d+" track="+trackSummary());
            if (!d.isNone()) {
                if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
                cb.onGestureDecision(d);
            }
        } else if (session.longPressReady) {
            cb.onRelease(false);
            GestureDecision d=new GestureDecision(GestureCode.ENTER_CIRCLE,false,0f,0f);
            DiagnosticLog.i(getContext(), "GESTURE", "decision="+d+" source=long_hold");
            cb.onGestureDecision(d);
        } else {
            cb.onRelease(false);
            if (session.duration(now) <= fs.tapMaxMs() && session.distance() < gestureSlopPx()) handleTap(now);
        }
        session.reset();
        invalidate();
    }

    private void handleTap(long now) {
        if (lastTapAt != 0 && now - lastTapAt <= fs.doubleTapMs()) {
            DiagnosticLog.i(getContext(), "TAP", "doubleTap gap="+(now-lastTapAt)+" window="+fs.doubleTapMs());
            lastTapAt = 0;
            if (singleTapRunnable != null) handler.removeCallbacks(singleTapRunnable);
            singleTapRunnable = null;
            if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            cb.onAction(fs.action(FloatSettings.K_ACTION_DOUBLE, ActionId.SCREENSHOT));
            return;
        }
        DiagnosticLog.i(getContext(), "TAP", "tapCode="+GestureCode.TAP+" waitWindow="+fs.doubleTapMs());
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

    private String trackSummary() {
        if (session.points.isEmpty()) return "empty";
        GesturePointSample a=session.points.get(0), b=session.points.get(session.points.size()-1);
        return "n="+session.points.size()+" start="+Math.round(a.x())+","+Math.round(a.y())+" end="+Math.round(b.x())+","+Math.round(b.y())+" dur="+(b.timeMs()-a.timeMs());
    }

    private float gestureSlopPx() { return dp(fs.gestureStartDistance()); }
    private float longPressDragTolerancePx() { return Math.max(gestureSlopPx() * 2f, dp(24f)); }
    @Override public void cancelLongPress() {
        super.cancelLongPress();
        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
        longPressRunnable = null;
    }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
