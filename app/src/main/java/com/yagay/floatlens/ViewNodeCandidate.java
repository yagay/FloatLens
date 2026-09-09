package com.yagay.floatlens;

import android.graphics.Rect;

/** Immutable snapshot of an AccessibilityNodeInfo suitable for overlay selection. */
public final class ViewNodeCandidate {
    private final Rect bounds;
    private final String text;
    private final String className;
    private final String viewId;
    private final boolean clickable;
    private final boolean editable;

    public ViewNodeCandidate(Rect bounds, String text, String className, String viewId, boolean clickable, boolean editable) {
        this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        this.text = text == null ? "" : text;
        this.className = className == null ? "" : className;
        this.viewId = viewId == null ? "" : viewId;
        this.clickable = clickable;
        this.editable = editable;
    }

    public Rect bounds() { return new Rect(bounds); }
    public String text() { return text; }
    public String className() { return className; }
    public String viewId() { return viewId; }
    public boolean clickable() { return clickable; }
    public boolean editable() { return editable; }
    public boolean hasText() { return !text.isBlank(); }

    public String label() {
        if (!text.isBlank()) return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        if (!className.isBlank()) {
            int i = className.lastIndexOf('.');
            return i >= 0 ? className.substring(i + 1) : className;
        }
        return "View";
    }
}
