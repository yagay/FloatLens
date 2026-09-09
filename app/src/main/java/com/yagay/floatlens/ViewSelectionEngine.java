package com.yagay.floatlens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

/**
 * Clean-room equivalent of FV's m2/g selection activation path.
 *
 * Confirmed from FooViewService$c3.onTouch disassembly:
 * - icon movement itself is handled before this path;
 * - while m2/g is not shown, per-frame |dx|/|dy| are compared with literal 40;
 * - a fast frame (>=40 px on either axis) cancels pending O0 activation;
 * - otherwise movement more than ~3dp from c3.i/j removes/reposts O0;
 * - normal O0 delay is 400 ms (1000 ms is a special secondary-pointer state);
 * - once m2/g is shown, subsequent MotionEvents are routed to it until release/cancel.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, ARMING, ACTIVE }

    private static final long NORMAL_ARM_DELAY_MS = 400L;
    private static final float FV_REARM_DISTANCE_DP = 3f;
    private static final float FV_FAST_FRAME_PX = 40f;

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable activateRunnable = this::activate;

    private ViewHoverOverlay overlay;
    private State state = State.IDLE;
    private float armX = Float.NaN, armY = Float.NaN;
    private float previousX = Float.NaN, previousY = Float.NaN;
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

    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null || accessibility == null) return;
        final int action=e.getActionMasked();
        final float x=e.getRawX(), y=e.getRawY();
        pointerX=x; pointerY=y;

        if(action==MotionEvent.ACTION_DOWN){
            resetInternal(false);
            downAt=SystemClock.uptimeMillis();
            pointerX=previousX=armX=x;
            pointerY=previousY=armY=y;
            DiagnosticLog.i(context,"FV_SELECT","DOWN x="+Math.round(x)+" y="+Math.round(y));
            return;
        }

        if(action==MotionEvent.ACTION_MOVE){
            moved=true;
            if(state==State.ACTIVE){
                if(overlay!=null)overlay.update(x,y);
                previousX=x;previousY=y;
                return;
            }

            float frameDx=Float.isNaN(previousX)?0f:Math.abs(x-previousX);
            float frameDy=Float.isNaN(previousY)?0f:Math.abs(y-previousY);
            previousX=x;previousY=y;

            // FV literals: if current-vs-previous X or Y is >= 40, pending O0 is removed.
            if(frameDx>=FV_FAST_FRAME_PX || frameDy>=FV_FAST_FRAME_PX){
                if(state==State.ARMING){
                    handler.removeCallbacks(activateRunnable);
                    DiagnosticLog.i(context,"FV_SELECT","CANCEL_ARM fastFrame dx="+Math.round(frameDx)+" dy="+Math.round(frameDy));
                }
                state=State.IDLE;
                armX=x;armY=y;
                return;
            }

            float rearm=dp(FV_REARM_DISTANCE_DP);
            float dx=x-armX,dy=y-armY;
            if(dx*dx+dy*dy>rearm*rearm){
                handler.removeCallbacks(activateRunnable);
                armX=x;armY=y;
                state=State.ARMING;
                handler.postDelayed(activateRunnable,NORMAL_ARM_DELAY_MS);
                DiagnosticLog.i(context,"FV_SELECT","ARM x="+Math.round(x)+" y="+Math.round(y)+" delay="+NORMAL_ARM_DELAY_MS+" frame="+Math.round(frameDx)+","+Math.round(frameDy));
            }
            return;
        }

        if(action==MotionEvent.ACTION_CANCEL)cancel();
    }

    private void activate(){
        if(accessibility==null||!moved||state!=State.ARMING)return;
        state=State.ACTIVE;
        overlay=new ViewHoverOverlay(context);
        if(overlay.available()){
            overlay.begin();
            if(!Float.isNaN(pointerX)&&!Float.isNaN(pointerY))overlay.update(pointerX,pointerY);
        }
        DiagnosticLog.i(context,"FV_SELECT","ACTIVE after="+(SystemClock.uptimeMillis()-downAt)+"ms x="+Math.round(pointerX)+" y="+Math.round(pointerY));
    }

    /** True only if the FV-style selection container was actually ACTIVE at release. */
    public boolean finish(MotionEvent up){
        boolean wasActive=state==State.ACTIVE;
        boolean hadCandidate=overlay!=null&&overlay.hasCandidate();
        handler.removeCallbacks(activateRunnable);
        if(overlay!=null)overlay.finish(false);
        DiagnosticLog.i(context,"FV_SELECT","UP active="+wasActive+" candidate="+hadCandidate);
        resetInternal(false);
        return wasActive;
    }

    public void cancel(){
        boolean wasArming=state==State.ARMING,wasActive=state==State.ACTIVE;
        handler.removeCallbacks(activateRunnable);
        if(overlay!=null)overlay.cancel();
        DiagnosticLog.i(context,"FV_SELECT","CANCEL arming="+wasArming+" active="+wasActive);
        resetInternal(false);
    }

    private void resetInternal(boolean closeOverlay){
        handler.removeCallbacks(activateRunnable);
        if(closeOverlay&&overlay!=null)overlay.cancel();
        overlay=null;
        state=State.IDLE;
        armX=armY=previousX=previousY=pointerX=pointerY=Float.NaN;
        downAt=0L;
        moved=false;
    }

    private float dp(float v){return v*context.getResources().getDisplayMetrics().density;}
}
