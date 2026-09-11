package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FV-style cached hit-test model.
 *
 * FV does not treat every Accessibility rectangle as one flat depth-ranked list. Text/editable
 * semantics and ordinary/non-text Views are prepared as different candidate classes before pointer
 * hit testing. Keep that distinction here: a deeper empty wrapper must not hide usable text exposed
 * by a containing Accessibility candidate.
 *
 * The full Accessibility snapshot is still prepared only once. MOVE performs cached rectangle
 * contains() tests only; it never walks the live Accessibility tree.
 */
public final class ScreenSelectionModel {
    private final ArrayList<ScreenCandidate> accessibility = new ArrayList<>();

    public void setAccessibility(List<ScreenCandidate> items) {
        accessibility.clear();
        if (items == null) return;
        for (ScreenCandidate c : dedupe(items)) {
            if (isAcceptedType(c)) accessibility.add(c);
        }

        // FV-style prepared candidate groups:
        //   text -> editable -> explicit non-text -> ordinary view -> root/fallback.
        // Within a group, a specific/deeper rectangle wins, while whole-page candidates remain a
        // last resort. This fixes cases where a text-bearing parent contains a deeper empty View
        // wrapper (common in Compose/custom layouts and feeds).
        accessibility.sort((a, b) -> {
            boolean aBroad = isBroad(a);
            boolean bBroad = isBroad(b);
            if (aBroad != bBroad) return aBroad ? 1 : -1;

            int byGroup = Integer.compare(candidateGroup(a), candidateGroup(b));
            if (byGroup != 0) return byGroup;

            int byDepth = Integer.compare(b.depth(), a.depth());
            if (byDepth != 0) return byDepth;

            int byArea = Long.compare(area(a.bounds()), area(b.bounds()));
            if (byArea != 0) return byArea;
            return 0;
        });
    }

    /** Kept for source compatibility; visual candidates are intentionally ignored. */
    public void setVisual(List<ScreenCandidate> items) {}

    public List<ScreenCandidate> accessibilityCandidates() {
        return new ArrayList<>(accessibility);
    }

    public List<ScreenCandidate> visualCandidates() {
        return new ArrayList<>();
    }

    public boolean isEmpty() { return accessibility.isEmpty(); }
    public int size() { return accessibility.size(); }

    /** MOVE-time path: cached rectangle contains() only; no tree walk and no per-MOVE live ranking. */
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

    private int candidateGroup(ScreenCandidate c) {
        if (c == null) return 5;
        // hasText() is intentionally checked in addition to Type.TEXT so text exposed by a custom
        // class is never demoted merely because its Android class is not TextView.
        if (c.hasText() || c.type() == ScreenCandidate.Type.TEXT) return 0;
        if (c.editable()) return 1;
        if (c.type() == ScreenCandidate.Type.NON_TEXT || c.iconLike()) return 2;
        if (c.type() == ScreenCandidate.Type.VIEW) return 3;
        return 4;
    }

    private boolean isAcceptedType(ScreenCandidate c) {
        return c != null && (c.type() == ScreenCandidate.Type.TEXT
                || c.type() == ScreenCandidate.Type.NON_TEXT
                || c.type() == ScreenCandidate.Type.VIEW
                || c.type() == ScreenCandidate.Type.ROOT);
    }

    private boolean isBroad(ScreenCandidate c) {
        return c != null && (c.type() == ScreenCandidate.Type.ROOT || c.fullscreenLike());
    }

    private long area(Rect r) {
        return r == null ? 0L : (long) r.width() * r.height();
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
