package com.yagay.floatlens;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

/**
 * Invisible FV-style edge handle that enlarges the touch target of a partially hidden float icon.
 *
 * FV keeps a separate FloatIconView$l window beside the 119x119 visible icon. Runtime capture shows
 * that helper at roughly 0.5x icon width and 1.16x icon height. This view mirrors that role without
 * changing the visible icon size: it forwards the touch stream to the real FloatIconView while
 * remapping local coordinates to the real owner's current window origin.
 */
final class FloatIconTouchHandle extends View {
    private final FloatIconView target;
    private final int[] targetLocation = new int[2];

    FloatIconTouchHandle(Context context, FloatIconView target) {
        super(context);
        this.target = target;
        setClickable(true);
        setFocusable(false);
        setWillNotDraw(true);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (target == null || event == null) return false;
        MotionEvent forwarded = MotionEvent.obtainNoHistory(event);
        try {
            target.getLocationOnScreen(targetLocation);
            // Keep rawX/rawY from the original screen touch, but make getX/getY match the real icon
            // window. SelectionPointTransformer depends on raw-local to recover the icon origin.
            forwarded.setLocation(
                    event.getRawX() - targetLocation[0],
                    event.getRawY() - targetLocation[1]);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                DiagnosticLog.i(getContext(), "FV_HANDLE", "DOWN raw="
                        + Math.round(event.getRawX()) + "," + Math.round(event.getRawY())
                        + " owner=" + targetLocation[0] + "," + targetLocation[1]
                        + " local=" + Math.round(forwarded.getX()) + "," + Math.round(forwarded.getY()));
            }
            return target.onTouchEvent(forwarded);
        } finally {
            forwarded.recycle();
        }
    }
}
