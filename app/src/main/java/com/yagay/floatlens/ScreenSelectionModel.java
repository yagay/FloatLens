package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FV-style cached hit-test model.
 *
 * Reverse engineering of fooView 1.6.4 shows that its selection overlay does not flatten every
 * Accessibility rectangle into one global depth-ranked list. FooAccessibilityService prepares
 * semantic lists first, and o1/n1#getSelectedRect() walks those lists in order and returns the first
 * candidate whose rectangle contains the pointer. FooAccessibilityService#c1() prepares each list
 * by putting contained/smaller overlapping rectangles before broader ones.
 *
 * FloatLens keeps the same important invariants here:
 *  - text semantics are tested before empty/non-text Views;
 *  - near-fullscreen TEXT remains a valid text target (only ROOT is a last-resort bucket);
 *  - each semantic bucket is geometry-prepared before MOVE starts;
 *  - MOVE performs cached contains(x,y) checks only and never walks the live Accessibility tree.
 */
public final class ScreenSelectionModel {
    private final ArrayList<ScreenCandidate> accessibility = new ArrayList<>();
    private final ArrayList<ScreenCandidate> text = new ArrayList<>();
    private final ArrayList<ScreenCandidate> editable = new ArrayList<>();
    private final ArrayList<ScreenCandidate> nonText = new ArrayList<>();
    private final ArrayList<ScreenCandidate> view = new ArrayList<>();
    private final ArrayList<ScreenCandidate> root = new ArrayList<>();

    public void setAccessibility(List<ScreenCandidate> items) {
        accessibility.clear();
        text.clear();
        editable.clear();
        nonText.clear();
        view.clear();
        root.clear();
        if (items == null) return;

        for (ScreenCandidate c : dedupe(items)) {
            if (!isAcceptedType(c)) continue;
            if (c.type() == ScreenCandidate.Type.ROOT) {
                root.add(c);
            } else if (c.hasText() || c.type() == ScreenCandidate.Type.TEXT) {
                text.add(c);
            } else if (c.editable()) {
                editable.add(c);
            } else if (c.type() == ScreenCandidate.Type.NON_TEXT || c.iconLike()) {
                nonText.add(c);
            } else {
                view.add(c);
            }
        }

        replacePrepared(text);
        replacePrepared(editable);
        replacePrepared(nonText);
        replacePrepared(view);
        replacePrepared(root);

        // Matches the meaningful part of FV's getSelectedRect() ordering for the normal selection
        // path: usable text is resolved before ordinary non-text candidates. Empty editable nodes are
        // kept as a separate fallback class, and FloatLens' generic VIEW extension remains behind FV
        // semantic candidates instead of being allowed to hide them by depth.
        accessibility.addAll(text);
        accessibility.addAll(editable);
        accessibility.addAll(nonText);
        accessibility.addAll(view);
        accessibility.addAll(root);
    }

    /** Kept for source compatibility; visual screenshot candidates are intentionally ignored. */
    public void setVisual(List<ScreenCandidate> items) {}

    public List<ScreenCandidate> accessibilityCandidates() {
        return new ArrayList<>(accessibility);
    }

    public List<ScreenCandidate> visualCandidates() {
        return new ArrayList<>();
    }

    public boolean isEmpty() { return accessibility.isEmpty(); }
    public int size() { return accessibility.size(); }

    /** FV MOVE-time behavior: first prepared candidate containing the pointer wins. */
    public ScreenCandidate selectAccessibilityAt(float x, float y) {
        final int px = Math.round(x), py = Math.round(y);
        for (ScreenCandidate c : accessibility) {
            Rect r = c.bounds();
            if (!r.isEmpty() && r.contains(px, py)) return c;
        }
        return null;
    }

    public ScreenCandidate selectAt(float x, float y) {
        return selectAccessibilityAt(x, y);
    }

    public boolean needsVisualRefinement(float x, float y) {
        return false;
    }

    private void replacePrepared(ArrayList<ScreenCandidate> bucket) {
        if (bucket.size() < 2) return;
        ArrayList<ScreenCandidate> prepared = fvPrepare(bucket);
        bucket.clear();
        bucket.addAll(prepared);
    }

    /**
     * Clean-room equivalent of the ordering behavior observed in FV FooAccessibilityService#c1().
     * Exact duplicate rectangles are ignored. A candidate contained by an existing candidate is
     * inserted before it. For partially overlapping candidates, the smaller rectangle is inserted
     * before the larger one. Unrelated candidates preserve traversal order.
     */
    private ArrayList<ScreenCandidate> fvPrepare(List<ScreenCandidate> input) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        for (ScreenCandidate candidate : input) {
            if (candidate == null || candidate.bounds().isEmpty()) continue;
            Rect next = candidate.bounds();
            boolean add = true;
            int insertAt = -1;

            for (int i = 0; i < out.size(); i++) {
                Rect existing = out.get(i).bounds();
                if (existing.equals(next)) {
                    add = false;
                    break;
                }
                if (contains(existing, next)) {
                    insertAt = i;
                    break;
                }
                if (Rect.intersects(existing, next) && area(next) < area(existing)) {
                    insertAt = i;
                    break;
                }
            }

            if (!add) continue;
            if (insertAt < 0) out.add(candidate);
            else out.add(insertAt, candidate);
        }
        return out;
    }

    private boolean contains(Rect outer, Rect inner) {
        return outer != null && inner != null
                && outer.left <= inner.left && outer.top <= inner.top
                && outer.right >= inner.right && outer.bottom >= inner.bottom;
    }

    private boolean isAcceptedType(ScreenCandidate c) {
        return c != null && (c.type() == ScreenCandidate.Type.TEXT
                || c.type() == ScreenCandidate.Type.NON_TEXT
                || c.type() == ScreenCandidate.Type.VIEW
                || c.type() == ScreenCandidate.Type.ROOT);
    }

    private long area(Rect r) {
        return r == null ? 0L : (long) Math.max(0, r.width()) * Math.max(0, r.height());
    }

    private List<ScreenCandidate> dedupe(List<ScreenCandidate> in) {
        Map<String, ScreenCandidate> map = new LinkedHashMap<>();
        for (ScreenCandidate c : in) {
            if (c == null || c.bounds().isEmpty()) continue;
            map.putIfAbsent(c.stableKey(), c);
        }
        return new ArrayList<>(map.values());
    }
}
