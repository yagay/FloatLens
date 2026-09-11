package com.yagay.floatlens;

import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

/** Tracks the screen-space text selection used to position FloatLens' floating action menu. */
public final class FloatMenuAnchor {
    private static final Object LOCK = new Object();
    private static Rect current;

    public static void set(Rect anchor) {
        synchronized (LOCK) {
            current = anchor == null || anchor.isEmpty() ? null : new Rect(anchor);
        }
    }

    public static Rect current() {
        synchronized (LOCK) {
            return current == null ? null : new Rect(current);
        }
    }

    public static void clear() {
        synchronized (LOCK) { current = null; }
    }

    public static Rect forView(View view) {
        if (view == null || !view.isShown()) return null;
        Rect visible = new Rect();
        if (view.getGlobalVisibleRect(visible) && !visible.isEmpty()) return visible;
        int[] loc = new int[2];
        try { view.getLocationOnScreen(loc); } catch (Throwable ignored) { return null; }
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        return new Rect(loc[0], loc[1], loc[0] + view.getWidth(), loc[1] + view.getHeight());
    }

    /** Resolve the current TextView selection path to screen coordinates, including multiline selections. */
    public static Rect forTextSelection(TextView tv) {
        if (tv == null || tv.getText() == null) return forView(tv);
        int a = tv.getSelectionStart();
        int b = tv.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return forView(tv);
        int lo = Math.max(0, Math.min(a, b));
        int hi = Math.min(tv.length(), Math.max(a, b));
        Layout layout = tv.getLayout();
        if (layout == null || lo >= hi) return forView(tv);

        try {
            Path path = new Path();
            layout.getSelectionPath(lo, hi, path);
            RectF local = new RectF();
            path.computeBounds(local, true);
            if (local.isEmpty()) return forView(tv);

            int[] loc = new int[2];
            tv.getLocationOnScreen(loc);
            float dx = loc[0] + tv.getTotalPaddingLeft() - tv.getScrollX();
            float dy = loc[1] + tv.getTotalPaddingTop() - tv.getScrollY();
            Rect out = new Rect(
                    Math.round(local.left + dx),
                    Math.round(local.top + dy),
                    Math.round(local.right + dx),
                    Math.round(local.bottom + dy));

            Rect visible = new Rect();
            if (tv.getGlobalVisibleRect(visible) && Rect.intersects(out, visible)) {
                out.intersect(visible);
            }
            return out.isEmpty() ? forView(tv) : out;
        } catch (Throwable ignored) {
            return forView(tv);
        }
    }

    private FloatMenuAnchor() {}
}
