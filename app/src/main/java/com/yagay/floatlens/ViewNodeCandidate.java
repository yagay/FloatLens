package com.yagay.floatlens;

import android.graphics.Rect;

/** Immutable snapshot of an AccessibilityNodeInfo suitable for FV-style overlay selection. */
public final class ViewNodeCandidate {
    private final Rect bounds;
    private final String text;
    private final String className;
    private final String viewId;
    private final boolean clickable;
    private final boolean editable;
    private final boolean focusable;
    private final boolean iconLike;

    public ViewNodeCandidate(Rect bounds, String text, String className, String viewId,
                             boolean clickable, boolean editable, boolean focusable, boolean iconLike) {
        this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        this.text = text == null ? "" : text;
        this.className = className == null ? "" : className;
        this.viewId = viewId == null ? "" : viewId;
        this.clickable = clickable;
        this.editable = editable;
        this.focusable = focusable;
        this.iconLike = iconLike;
    }

    public Rect bounds() { return new Rect(bounds); }
    public String text() { return text; }
    public String className() { return className; }
    public String viewId() { return viewId; }
    public boolean clickable() { return clickable; }
    public boolean editable() { return editable; }
    public boolean focusable() { return focusable; }
    public boolean iconLike() { return iconLike; }
    public boolean hasText() { return !text.isBlank(); }
    public boolean visualOnly() { return iconLike && text.isBlank(); }

    public String kind() {
        if (iconLike) return "icon";
        if (!text.isBlank()) return "text";
        if (editable) return "editable";
        if (clickable) return "action";
        return "view";
    }

    public String label() {
        if (!text.isBlank()) return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        if (iconLike) {
            String idName = shortId();
            if (!idName.isBlank()) return "图标 · " + idName;
            String cls = shortClass();
            return cls.isBlank() ? "图标" : "图标 · " + cls;
        }
        String cls = shortClass();
        if (!cls.isBlank()) return cls;
        return "View";
    }

    private String shortClass() {
        if (className.isBlank()) return "";
        int i = className.lastIndexOf('.');
        return i >= 0 ? className.substring(i + 1) : className;
    }

    private String shortId() {
        if (viewId.isBlank()) return "";
        int slash=viewId.lastIndexOf('/');
        return slash>=0&&slash<viewId.length()-1?viewId.substring(slash+1):viewId;
    }
}