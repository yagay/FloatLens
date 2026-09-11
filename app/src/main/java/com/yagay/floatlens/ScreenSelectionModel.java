package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FV-style cached hit-test model.
 *
 * Candidate ordering is prepared once when a complete Accessibility tree snapshot is installed.
 * MOVE then only walks that cached list and returns the first rectangle containing the pointer.
 * Whole-page/ROOT candidates stay at the end so a more specific child wins at the same point.
 */
public final class ScreenSelectionModel {
    private final ArrayList<ScreenCandidate> accessibility = new ArrayList<>();

    public void setAccessibility(List<ScreenCandidate> items) {
        accessibility.clear();
        if (items == null) return;
        for (ScreenCandidate c : dedupe(items)) {
            if (isAcceptedType(c)) accessibility.add(c);
        }

        // FV prepares/cleans its candidate collections before pointer hit testing. Do the same here:
        // specific rectangles first, broad/fullscreen rectangles last. List.sort is stable, so equal
        // geometry/depth candidates preserve collector/window order.
        accessibility.sort((a, b) -> {
            boolean aBroad = isBroad(a);
            boolean bBroad = isBroad(b);
            if (aBroad != bBroad) return aBroad ? 1 : -1;

            int byDepth = Integer.compare(b.depth(), a.depth());
            if (byDepth != 0) return byDepth;

            long aa = area(a.bounds());
            long ba = area(b.bounds());
            int byArea = Long.compare(aa, ba);
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

    /** MOVE-time path: cached rectangle contains() only; no tree walk and no per-MOVE ranking. */
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
