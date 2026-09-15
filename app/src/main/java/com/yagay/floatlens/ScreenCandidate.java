package com.yagay.floatlens;

import android.graphics.Rect;

/**
 * Unified clean-room screen candidate used by the FL selection pipeline.
 *
 * <p>{@code text} is visible text only. Accessibility descriptions/hints/state descriptions are
 * retained separately as {@code semanticLabel}; they may label a non-text View for diagnostics/UI,
 * but they must never be promoted to selectable on-screen text.</p>
 */
public final class ScreenCandidate {
    public enum Type { TEXT, NON_TEXT, VIEW, ROOT }
    public enum Source { ACCESSIBILITY, VISUAL }

    private final Rect bounds;
    private final Type type;
    private final Source source;
    private final String text;
    private final String semanticLabel;
    private final String className;
    private final String viewId;
    private final String packageName;
    private final int depth;
    private final boolean fullscreenLike;
    private final boolean clickable;
    private final boolean editable;
    private final boolean focusable;
    private final boolean iconLike;

    /** Source-compatible constructor for existing callers that do not provide semantic metadata. */
    public ScreenCandidate(Rect bounds, Type type, Source source, String text,
                           String className, String viewId, String packageName,
                           int depth, boolean fullscreenLike,
                           boolean clickable, boolean editable, boolean focusable,
                           boolean iconLike) {
        this(bounds, type, source, text, "", className, viewId, packageName, depth,
                fullscreenLike, clickable, editable, focusable, iconLike);
    }

    public ScreenCandidate(Rect bounds, Type type, Source source, String text, String semanticLabel,
                           String className, String viewId, String packageName,
                           int depth, boolean fullscreenLike,
                           boolean clickable, boolean editable, boolean focusable,
                           boolean iconLike) {
        this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        this.type = type == null ? Type.VIEW : type;
        this.source = source == null ? Source.ACCESSIBILITY : source;
        this.text = safe(text);
        this.semanticLabel = safe(semanticLabel);
        this.className = safe(className);
        this.viewId = safe(viewId);
        this.packageName = safe(packageName);
        this.depth = depth;
        this.fullscreenLike = fullscreenLike;
        this.clickable = clickable;
        this.editable = editable;
        this.focusable = focusable;
        this.iconLike = iconLike;
    }

    public static ScreenCandidate visual(Rect bounds) {
        return new ScreenCandidate(bounds, Type.NON_TEXT, Source.VISUAL, "", "", "", "", "",
                Integer.MAX_VALUE, false, false, false, false, true);
    }

    public Rect bounds() { return new Rect(bounds); }
    public Type type() { return type; }
    public Source source() { return source; }
    public String text() { return text; }
    public String semanticLabel() { return semanticLabel; }
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
    public boolean hasSemanticLabel() { return !semanticLabel.isBlank(); }

    public String label() {
        if (hasText()) return ellipsize(text);
        if (hasSemanticLabel()) return ellipsize(semanticLabel);
        if (source == Source.VISUAL) return "NonText";
        if (!className.isBlank()) {
            int i = className.lastIndexOf('.');
            return i >= 0 ? className.substring(i + 1) : className;
        }
        return type == Type.ROOT ? "Root" : "View";
    }

    public ViewNodeCandidate toViewNodeCandidate() {
        return new ViewNodeCandidate(bounds, text, semanticLabel, className, viewId,
                clickable, editable, focusable, iconLike || type == Type.NON_TEXT);
    }

    public String stableKey() {
        return source + ":" + type + ":" + bounds.flattenToString() + ":" + viewId + ":"
                + className + ":" + text + ":" + semanticLabel;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String ellipsize(String value) {
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }
}
