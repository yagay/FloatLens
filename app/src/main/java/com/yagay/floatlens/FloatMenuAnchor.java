package com.yagay.floatlens;

import android.graphics.Rect;

/**
 * Temporary mutable anchor used only by the legacy FloatActionMenu implementation.
 *
 * <p>All View/TextView geometry belongs to {@link SelectionGeometry}. New callers must pass
 * SelectionSnapshot/Rect explicitly and must not add geometry or business logic here.</p>
 */
@Deprecated
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

    private FloatMenuAnchor() {}
}
