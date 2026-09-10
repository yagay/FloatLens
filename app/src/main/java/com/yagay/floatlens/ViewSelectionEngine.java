package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.view.MotionEvent;

/**
 * FV-style same-touch selection engine.
 *
 * FloatIconView owns the whole pointer stream. The FV move-idle q trigger expands that same icon
 * window to full screen, while this engine keeps two parallel selection outcomes:
 * 1) a point-based Accessibility View candidate while the pointer remains near the trigger point;
 * 2) a live region once the same finger deliberately moves away from that point.
 */
public final class ViewSelectionEngine {
    public enum State { IDLE, DIRECT }

    private final Context context;
    private final LensAccessibilityService accessibility;
    private final SelectionPointTransformer pointTransformer;

    private ViewHoverOverlay overlay;
    private State state = State.IDLE;
    private float selectionX = Float.NaN, selectionY = Float.NaN;

    public ViewSelectionEngine(Context c) {
        context = c.getApplicationContext();
        accessibility = LensAccessibilityService.get();
        FloatSettings fs = new FloatSettings(context);
        float px = fs.sizeDp() * context.getResources().getDisplayMetrics().density;
        pointTransformer = new SelectionPointTransformer(context, px, px);
    }

    public boolean available() { return accessibility != null; }
    public boolean isActive() { return state == State.DIRECT; }
    public State state() { return state; }

    /** Capture the original in-icon touch offset while FloatIconView is still the small window. */
    public void dispatchTouchEvent(MotionEvent e) {
        if (e == null || accessibility == null) return;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            pointTransformer.begin(e);
            PointF p = pointTransformer.transform(e);
            selectionX = p.x;
            selectionY = p.y;
            DiagnosticLog.i(context, "FV_SELECT", "DOWN hotspot="
                    + Math.round(selectionX) + "," + Math.round(selectionY));
        } else if (action == MotionEvent.ACTION_MOVE && state == State.DIRECT) {
            updateDirect(e.getRawX(), e.getRawY());
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
        }
    }

    /** Called by the observed FV-style q Runnable while the finger is still down. */
    public boolean activateDirect(float rawX, float rawY) {
        if (accessibility == null) return false;
        if (state == State.DIRECT) {
            updateDirect(rawX, rawY);
            return true;
        }

        overlay = new ViewHoverOverlay(context);
        if (!overlay.available()) {
            overlay = null;
            return false;
        }
        overlay.begin();
        PointF p = pointTransformer.transformRaw(rawX, rawY);
        selectionX = p.x;
        selectionY = p.y;
        state = State.DIRECT;
        overlay.beginDirect(selectionX, selectionY);
        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_ENTER raw="
                + Math.round(rawX) + "," + Math.round(rawY)
                + " hotspot=" + Math.round(selectionX) + "," + Math.round(selectionY));
        return true;
    }

    public void updateDirect(float rawX, float rawY) {
        if (state != State.DIRECT || overlay == null) return;
        PointF p = pointTransformer.transformRaw(rawX, rawY);
        selectionX = p.x;
        selectionY = p.y;
        overlay.updateDirect(selectionX, selectionY);
    }

    /** Same-touch ACTION_UP: a dragged region wins; otherwise complete the current View candidate. */
    public boolean finishDirect(float rawX, float rawY) {
        if (state != State.DIRECT) {
            cancel();
            return false;
        }
        updateDirect(rawX, rawY);
        boolean region = overlay != null && overlay.isRegionMode();
        boolean hadTarget = overlay != null && overlay.hasCandidate();
        boolean result = overlay != null && overlay.finishDirect();
        DiagnosticLog.i(context, "FV_SELECT", "DIRECT_UP region=" + region
                + " target=" + hadTarget + " result=" + result + " hotspot="
                + Math.round(selectionX) + "," + Math.round(selectionY));
        overlay = null;
        state = State.IDLE;
        return result;
    }

    /** Compatibility entry for older callers. */
    public boolean finish(MotionEvent up) {
        if (state != State.DIRECT || up == null) {
            cancel();
            return false;
        }
        return finishDirect(up.getRawX(), up.getRawY());
    }

    public void cancel() {
        boolean active = state == State.DIRECT;
        if (overlay != null) overlay.cancel();
        overlay = null;
        state = State.IDLE;
        selectionX = selectionY = Float.NaN;
        if (active) DiagnosticLog.i(context, "FV_SELECT", "DIRECT_CANCEL");
    }
}
