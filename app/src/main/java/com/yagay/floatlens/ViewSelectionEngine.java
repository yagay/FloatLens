package com.yagay.floatlens;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * Clean-room equivalent of FV's dedicated selection layer (m2/g): FloatIconView forwards raw
 * MotionEvents here; this engine owns accessibility hit-testing, selection presentation and release.
 */
public final class ViewSelectionEngine {
    private final Context context;
    private final LensAccessibilityService accessibility;
    private ViewHoverOverlay overlay;
    private boolean active;
    private boolean moved;
    private long lastProbeAt;
    private float lastProbeX = Float.NaN, lastProbeY = Float.NaN;

    public ViewSelectionEngine(Context c) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
    }

    public boolean available() { return accessibility != null; }

    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null || accessibility == null) return;
        final int action = e.getActionMasked();
        final float x = e.getRawX(), y = e.getRawY();
        if (action == MotionEvent.ACTION_DOWN) {
            reset(); active = true;
            DiagnosticLog.i(context,"SELECTION_ENGINE","DOWN raw="+Math.round(x)+","+Math.round(y));
            return;
        }
        if (!active) active = true;
        if (action == MotionEvent.ACTION_MOVE) {
            moved = true;
            if (overlay == null) { overlay = new ViewHoverOverlay(context); overlay.begin(); }
            long now=SystemClock.uptimeMillis();
            float dx=Float.isNaN(lastProbeX)?999f:x-lastProbeX;
            float dy=Float.isNaN(lastProbeY)?999f:y-lastProbeY;
            if(now-lastProbeAt>=40 || dx*dx+dy*dy>=64f){
                lastProbeAt=now;lastProbeX=x;lastProbeY=y;
                overlay.update(x,y);
            }
            return;
        }
        if (action == MotionEvent.ACTION_CANCEL) cancel();
    }

    /** Returns true when View extraction consumes ACTION_UP. */
    public boolean finish(MotionEvent up) {
        if (!active) return false;
        float x=up==null?Float.NaN:up.getRawX();
        boolean extract = moved && isInteriorX(x) && overlay!=null && overlay.hasCandidate();
        boolean consumed = overlay!=null && overlay.finish(extract);
        DiagnosticLog.i(context,"SELECTION_ENGINE","UP moved="+moved+" interior="+isInteriorX(x)+" candidate="+(overlay!=null)+" consumed="+consumed);
        resetReferences();
        return consumed;
    }

    public void cancel() {
        if(overlay!=null)overlay.cancel();
        DiagnosticLog.i(context,"SELECTION_ENGINE","CANCEL");
        resetReferences();
    }

    private boolean isInteriorX(float rawX) {
        if(Float.isNaN(rawX))return false;
        try{
            WindowManager wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
            int width=wm.getCurrentWindowMetrics().getBounds().width();
            float edge=Math.max(dp(56),dp(40));
            return rawX>edge && rawX<width-edge;
        }catch(Throwable t){return true;}
    }

    private void reset(){if(overlay!=null)overlay.cancel();resetReferences();}
    private void resetReferences(){overlay=null;active=false;moved=false;lastProbeAt=0;lastProbeX=lastProbeY=Float.NaN;}
    private float dp(float v){return v*context.getResources().getDisplayMetrics().density;}
}
