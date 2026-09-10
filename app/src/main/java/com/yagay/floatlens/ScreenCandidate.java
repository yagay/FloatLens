package com.yagay.floatlens;

import android.graphics.Rect;

/**
 * Unified clean-room screen candidate inspired by FV's Rect/Text + NonText candidate pipeline.
 * Type/source describe the candidate only; they are never semantic ranking weights.
 */
public final class ScreenCandidate {
    public enum Type { TEXT, NON_TEXT, VIEW, ROOT }
    public enum Source { ACCESSIBILITY, VISUAL }

    private final Rect bounds;
    private final Type type;
    private final Source source;
    private final String text;
    private final String className;
    private final String viewId;
    private final String packageName;
    private final int depth;
    private final boolean fullscreenLike;
    private final boolean clickable;
    private final boolean editable;
    private final boolean focusable;
    private final boolean iconLike;

    public ScreenCandidate(Rect bounds, Type type, Source source, String text,
                           String className, String viewId, String packageName,
                           int depth, boolean fullscreenLike,
                           boolean clickable, boolean editable, boolean focusable,
                           boolean iconLike) {
        this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        this.type = type == null ? Type.VIEW : type;
        this.source = source == null ? Source.ACCESSIBILITY : source;
        this.text = text == null ? "" : text;
        this.className = className == null ? "" : className;
        this.viewId = viewId == null ? "" : viewId;
        this.packageName = packageName == null ? "" : packageName;
        this.depth = depth;
        this.fullscreenLike = fullscreenLike;
        this.clickable = clickable;
        this.editable = editable;
        this.focusable = focusable;
        this.iconLike = iconLike;
    }

    public static ScreenCandidate visual(Rect bounds) {
        return new ScreenCandidate(bounds, Type.NON_TEXT, Source.VISUAL, "", "", "", "",
                Integer.MAX_VALUE, false, false, false, false, true);
    }

    public Rect bounds() { return new Rect(bounds); }
    public Type type() { return type; }
    public Source source() { return source; }
    public String text() { return text; }
    public String className() { return className; }
    public String viewId() { return viewId; }
    public String packageName() { return packageName; }
    public int depth() { return depth; }
    public boolean fullscreenLike() { return fullscreenLike; }
    public boolean clickable() { return clickable; }
    public boolean editable() { return editable; }
    public boolean focusable() { return focusable; }
    public boolean iconLike() { return iconLike; }
    public boolean hasText() { return !text.isBlank(); }

    public String label() {
        if (hasText()) return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        if (source == Source.VISUAL) return "NonText";
        if (!className.isBlank()) {
            int i = className.lastIndexOf('.');
            return i >= 0 ? className.substring(i + 1) : className;
        }
        return type == Type.ROOT ? "Root" : "View";
    }

    public ViewNodeCandidate toViewNodeCandidate() {
        return new ViewNodeCandidate(bounds, text, className, viewId,
                clickable, editable, focusable, iconLike || type == Type.NON_TEXT);
    }

    public String stableKey() {
        return source + ":" + type + ":" + bounds.flattenToString() + ":" + viewId + ":" + className + ":" + text;
    }
}
