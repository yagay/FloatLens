package com.yagay.floatlens;

import android.graphics.Rect;

/** One-shot handoff from the pre-overlay controller stage into the Circle Select workspace. */
final class CircleViewTextSnapshotHandoff {
    private static CircleViewTextSnapshot pending;

    static synchronized void publish(CircleViewTextSnapshot snapshot) {
        pending = snapshot;
    }

    static synchronized CircleViewTextSnapshot consume() {
        CircleViewTextSnapshot snapshot = pending;
        pending = null;
        return snapshot == null ? CircleViewTextSnapshot.empty(new Rect()) : snapshot;
    }

    static synchronized void clear() {
        pending = null;
    }

    private CircleViewTextSnapshotHandoff() {}
}
