package com.yagay.floatlens;

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

    /**
     * Resolve only the currently selected glyph/line range to screen coordinates.
     *
     * <p>Do not fall back to the whole TextView for a valid selection. In the result dialog the
     * TextView sits directly below the screenshot preview, so a whole-view fallback makes the
     * floating toolbar appear attached to the screenshot instead of the selected OCR text.</p>
     */
    public static Rect forTextSelection(TextView tv) {
        if (tv == null || tv.getText() == null || !tv.isShown()) return null;
        int a = tv.getSelectionStart();
        int b = tv.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return forView(tv);

        int lo = Math.max(0, Math.min(a, b));
        int hi = Math.min(tv.length(), Math.max(a, b));
        Layout layout = tv.getLayout();
        if (layout == null || lo >= hi) return null;

        try {
            int startLine = layout.getLineForOffset(lo);
            int endLine = layout.getLineForOffset(Math.max(lo, hi - 1));
            RectF local = new RectF();
            boolean hasGeometry = false;

            for (int line = startLine; line <= endLine; line++) {
                int lineStart = layout.getLineStart(line);
                int lineEnd = layout.getLineEnd(line);
                int segmentStart = Math.max(lo, lineStart);
                int segmentEnd = Math.min(hi, lineEnd);
                if (segmentStart >= segmentEnd) continue;

                float x1 = segmentStart <= lineStart
                        ? layout.getLineLeft(line)
                        : layout.getPrimaryHorizontal(segmentStart);
                float x2 = segmentEnd >= lineEnd
                        ? layout.getLineRight(line)
                        : layout.getPrimaryHorizontal(segmentEnd);
                float left = Math.min(x1, x2);
                float right = Math.max(x1, x2);
                if (right <= left) right = left + 1f;

                RectF lineRect = new RectF(left, layout.getLineTop(line),
                        right, layout.getLineBottom(line));
                if (!hasGeometry) {
                    local.set(lineRect);
                    hasGeometry = true;
                } else {
                    local.union(lineRect);
                }
            }

            if (!hasGeometry || local.isEmpty()) {
                return selectionEndpointAnchor(tv, layout, Math.max(lo, hi - 1));
            }

            int[] loc = new int[2];
            tv.getLocationOnScreen(loc);
            float dx = loc[0] + tv.getCompoundPaddingLeft() - tv.getScrollX();
            float dy = loc[1] + tv.getExtendedPaddingTop() - tv.getScrollY();
            Rect out = new Rect(
                    Math.round(local.left + dx),
                    Math.round(local.top + dy),
                    Math.round(local.right + dx),
                    Math.round(local.bottom + dy));

            Rect visible = new Rect();
            if (tv.getGlobalVisibleRect(visible) && !visible.isEmpty()) {
                Rect clipped = new Rect(out);
                if (clipped.intersect(visible) && !clipped.isEmpty()) return clipped;
                return selectionEndpointAnchor(tv, layout, Math.max(lo, hi - 1));
            }
            return out.isEmpty() ? selectionEndpointAnchor(tv, layout, Math.max(lo, hi - 1)) : out;
        } catch (Throwable ignored) {
            return selectionEndpointAnchor(tv, layout, Math.max(lo, hi - 1));
        }
    }

    /** A narrow fallback at the selected line/end handle, never the whole text surface. */
    private static Rect selectionEndpointAnchor(TextView tv, Layout layout, int offset) {
        if (tv == null || layout == null || tv.length() <= 0) return null;
        try {
            int safe = Math.max(0, Math.min(tv.length() - 1, offset));
            int line = layout.getLineForOffset(safe);
            float x = layout.getPrimaryHorizontal(safe);
            int[] loc = new int[2];
            tv.getLocationOnScreen(loc);
            float dx = loc[0] + tv.getCompoundPaddingLeft() - tv.getScrollX();
            float dy = loc[1] + tv.getExtendedPaddingTop() - tv.getScrollY();
            int screenX = Math.round(x + dx);
            Rect out = new Rect(screenX, Math.round(layout.getLineTop(line) + dy),
                    screenX + 2, Math.round(layout.getLineBottom(line) + dy));

            Rect visible = new Rect();
            if (tv.getGlobalVisibleRect(visible) && !visible.isEmpty()) {
                if (!out.intersect(visible)) return null;
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private FloatMenuAnchor() {}
}
