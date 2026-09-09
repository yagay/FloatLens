package com.yagay.floatlens;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

/** Transparent edge strip used to restore a hidden main icon with an inward swipe. */
final class EdgeWakeView extends View {
    interface Callback { void onWake(); }
    private final boolean left;
    private final Callback cb;
    private float downX;
    EdgeWakeView(Context c, boolean left, Callback cb) { super(c); this.left=left; this.cb=cb; setBackgroundColor(0x00000000); }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked()==MotionEvent.ACTION_DOWN) { downX=e.getRawX(); return true; }
        if (e.getActionMasked()==MotionEvent.ACTION_UP) {
            float dx=e.getRawX()-downX;
            float need=40f*getResources().getDisplayMetrics().density;
            if ((left && dx>need) || (!left && dx<-need)) cb.onWake();
            return true;
        }
        return true;
    }
}
