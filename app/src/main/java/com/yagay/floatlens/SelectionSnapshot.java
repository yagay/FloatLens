package com.yagay.floatlens;

import android.graphics.Rect;

/**
 * Immutable description of one stable text selection.
 *
 * localBounds belongs to the selection host view/window; screenBounds belongs to the physical
 * screen. Keeping both coordinate spaces explicit prevents result-dialog coordinates from being
 * confused with capture/source coordinates or overlay-window coordinates.
 */
public final class SelectionSnapshot {
    private final String text;
    private final int start;
    private final int end;
    private final Rect localBounds;
    private final Rect screenBounds;
    private final long generation;

    SelectionSnapshot(String text, int start, int end,
                      Rect localBounds, Rect screenBounds, long generation) {
        this.text = text == null ? "" : text;
        this.start = start;
        this.end = end;
        this.localBounds = localBounds == null ? null : new Rect(localBounds);
        this.screenBounds = screenBounds == null ? null : new Rect(screenBounds);
        this.generation = generation;
    }

    public String text() { return text; }
    public int start() { return start; }
    public int end() { return end; }
    public long generation() { return generation; }

    public Rect localBounds() {
        return localBounds == null ? null : new Rect(localBounds);
    }

    public Rect screenBounds() {
        return screenBounds == null ? null : new Rect(screenBounds);
    }

    public boolean hasGeometry() {
        return screenBounds != null && !screenBounds.isEmpty();
    }
}
