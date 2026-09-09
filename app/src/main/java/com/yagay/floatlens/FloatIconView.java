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
 * The icon follows MOVE immediately. Raw events are also offered to the delayed FV-style selection
 * engine; ordinary gesture dispatch is bypassed only after that selection container actually opens.
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
    private boolean followStarted;
    private boolean selectionTookOver;
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
        DiagnosticLog.init(c);
        fs = new FloatSettings(c);
        loadCustomIcon();
        setClickable(true);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void refreshSettings() { fs = new FloatSettings(getContext()); loadCustomIcon(); invalidate(); }

    @Override protected void onDetachedFromWindow() {
        if(selectionEngine!=null) selectionEngine.cancel();
        handler.removeCallbacks(slideRunnable);
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private void loadCustomIcon() {
        handler.removeCallbacks(slideRunnable);
        customDrawable = null;
        slideDrawables.clear();
        slideIndex=0;
        if (fs.style() == 3) {
            String raw = fs.customIconUri();
            if (raw == null || raw.isBlank()) return;
            try {
                ImageDecoder.Source src=ImageDecoder.createSource(getContext().getContentResolver(),Uri.parse(raw));
                customDrawable=ImageDecoder.decodeDrawable(src);
                customDrawable.setCallback(this);
                if(customDrawable instanceof AnimatedImageDrawable a)a.start();
            } catch(Throwable ignored){customDrawable=null;}
        } else if (fs.style() == 4) {
            String raw=fs.slidePics();
            if(raw==null||raw.isBlank())return;
            for(String u:raw.split("\\|")) {
                if(u.isBlank())continue;
                try{
                    Drawable d=ImageDecoder.decodeDrawable(ImageDecoder.createSource(getContext().getContentResolver(),Uri.parse(u)));
                    d.setCallback(this);
                    slideDrawables.add(d);
                }catch(Throwable ignored){}
            }
            if(slideDrawables.size()>1) handler.postDelayed(slideRunnable,fs.slideIntervalMs());
        }
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) * .47f;
        int style = fs.style();
        if (style == 3 && customDrawable != null) {
            customDrawable.setBounds(0, 0, getWidth(), getHeight());
            customDrawable.draw(c);
            return;
        }
        if (style == 4 && !slideDrawables.isEmpty()) {
            Drawable d=slideDrawables.get(Math.min(slideIndex,slideDrawables.size()-1));
            d.setBounds(0,0,getWidth(),getHeight());
            d.draw(c);
            return;
        }
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
            DiagnosticLog.i(getContext(), "TOUCH", "action="+action+" pointers="+e.getPointerCount()+" raw="+Math.round(rx)+","+Math.round(ry)+" phase="+session.phase+" longReady="+session.longPressReady);
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            session.multiTouch = true;
            cancelLongPress();
            if(selectionEngine!=null)selectionEngine.cancel();
            cb.onGestureEnd(session.snapshot());
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) return true;

        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                cancelLongPress();
                if(selectionEngine!=null)selectionEngine.cancel();
                selectionEngine=new ViewSelectionEngine(getContext());
                selectionTookOver=false;
                followStarted=false;
                session.begin(rx, ry, now);
                if(selectionEngine.available())selectionEngine.dispatchTouchEvent(e);
                DiagnosticLog.i(getContext(), "STATE", "DOWN begin="+Math.round(rx)+","+Math.round(ry)+" fvSelection="+selectionEngine.available());
                invalidate();

                // Stationary long hold remains a separate FV path (Circle entry). Any real MOVE
                // cancels this timer when the gesture threshold is crossed.
                longPressRunnable = () -> {
                    if (!session.multiTouch && session.phase == GestureSession.Phase.DOWN && session.distance() < gestureSlopPx()) {
                        session.longPressReady = true;
                        DiagnosticLog.i(getContext(),"STATE","stationaryLongHold ready after="+fs.longPressMs());
                        if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                };
                handler.postDelayed(longPressRunnable, fs.longPressMs());
                return true;
            }

            case MotionEvent.ACTION_MOVE -> {
                GesturePointSample prev = session.points.isEmpty() ? null : session.points.get(session.points.size()-1);
                session.add(rx, ry, now);
                if (session.multiTouch) return true;

                int stepDx=prev==null?0:Math.round(rx-prev.x());
                int stepDy=prev==null?0:Math.round(ry-prev.y());
                float dist=session.distance();
                float slop=gestureSlopPx();

                // Confirmed from FV runtime: FloatIconView.c0(...) is called continuously during MOVE.
                if ((stepDx!=0||stepDy!=0) && dist>=dp(1.5f)) {
                    if(!followStarted){
                        followStarted=true;
                        cb.onDragStart();
                        DiagnosticLog.i(getContext(),"STATE","fvFollowStart distance="+Math.round(dist));
                    }
                    cb.onMove(stepDx,stepDy);
                    session.moved=true;
                }

                // FV's selection engine receives the same raw stream, but it decides for itself when
                // to arm/show. We do not start View scanning just because MOVE happened.
                if(selectionEngine!=null&&selectionEngine.available()) selectionEngine.dispatchTouchEvent(e);

                if(session.phase==GestureSession.Phase.DOWN && dist>=slop){
                    cancelLongPress();
                    session.phase=GestureSession.Phase.GESTURE;
                    cb.onGestureStart(session.downX,session.downY);
                }
                if(session.phase==GestureSession.Phase.GESTURE) cb.onGestureMove(rx,ry);
                return true;
            }

            case MotionEvent.ACTION_UP -> {
                cancelLongPress();
                session.add(rx, ry, now);
                selectionTookOver=selectionEngine!=null&&selectionEngine.finish(e);
                finish(now, false);
                return true;
            }

            case MotionEvent.ACTION_CANCEL -> {
                cancelLongPress();
                if(selectionEngine!=null)selectionEngine.cancel();
                selectionTookOver=false;
                finish(now, true);
                return true;
            }
        }
        return true;
    }

    private void finish(long now, boolean cancelled) {
        GestureSession.Phase ended = session.phase;
        DiagnosticLog.i(getContext(), "FINISH", "ended="+ended+" cancelled="+cancelled+" duration="+session.duration(now)+" distance="+Math.round(session.distance())+" points="+session.points.size()+" multi="+session.multiTouch+" longReady="+session.longPressReady+" followed="+followStarted+" fvSelectionTookOver="+selectionTookOver);
        session.phase = GestureSession.Phase.FINISHING;
        cb.onGestureEnd(session.snapshot());

        if (session.multiTouch || cancelled) {
            cb.onRelease(followStarted);
            resetSession();
            return;
        }

        // In FV c3.onTouch, once m2/g is shown, events are dispatched to that container and this
        // branch returns instead of also running the ordinary gesture action path.
        if (selectionTookOver) {
            cb.onRelease(followStarted);
            DiagnosticLog.i(getContext(),"FV_SELECT","release handled by active selection container");
            resetSession();
            return;
        }

        if (ended == GestureSession.Phase.GESTURE) {
            GestureDecision d = GestureClassifier.classify(session, fs, getResources().getDisplayMetrics().density);
            cb.onRelease(followStarted);
            if (!d.isNone()) {
                if (fs.vibrate()) performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
                cb.onGestureDecision(d);
            }
        } else if (session.longPressReady) {
            cb.onRelease(false);
            cb.onGestureDecision(new GestureDecision(GestureCode.ENTER_CIRCLE,false,0f,0f));
        } else {
            cb.onRelease(followStarted);
            if (!followStarted && session.duration(now) <= fs.tapMaxMs() && session.distance() < gestureSlopPx()) handleTap(now);
        }
        resetSession();
    }

    private void resetSession(){
        selectionEngine=null;
        selectionTookOver=false;
        followStarted=false;
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

    private float gestureSlopPx() { return dp(fs.gestureStartDistance()); }

    @Override public void cancelLongPress() {
        super.cancelLongPress();
        if (longPressRunnable != null) handler.removeCallbacks(longPressRunnable);
        longPressRunnable = null;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
