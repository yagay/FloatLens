package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict position-only model for user-visible Accessibility candidates.
 *
 * Only TEXT and NON_TEXT(image/icon) nodes are selectable. No screenshot candidate is allowed to
 * split or replace an Accessibility node. Therefore an icon+label exposed as one node (for example
 * a launcher BubbleTextView) stays one highlighted rectangle exactly as Android reports it.
 */
public final class ScreenSelectionModel {
    private final ArrayList<ScreenCandidate> accessibility = new ArrayList<>();

    public void setAccessibility(List<ScreenCandidate> items) {
        accessibility.clear();
        if (items == null) return;
        for (ScreenCandidate c : dedupe(items)) {
            if (isSelectableType(c)) accessibility.add(c);
        }
    }

    /** Kept for source compatibility; visual candidates are intentionally ignored. */
    public void setVisual(List<ScreenCandidate> items) {}

    public List<ScreenCandidate> accessibilityCandidates() {
        return new ArrayList<>(accessibility);
    }

    public List<ScreenCandidate> visualCandidates() {
        return new ArrayList<>();
    }

    public ScreenCandidate selectAccessibilityAt(float x, float y) {
        final int px = Math.round(x), py = Math.round(y);
        ScreenCandidate best = null;

        for (ScreenCandidate c : accessibility) {
            if (!isSelectableType(c)) continue;
            Rect r = c.bounds();
            if (r.isEmpty() || !r.contains(px, py)) continue;

            // Pure geometry/tree position. A real deeper child wins. At equal depth, prefer a
            // strictly contained rectangle; if unrelated rectangles overlap, prefer the smaller one.
            if (best == null
                    || c.depth() > best.depth()
                    || (c.depth() == best.depth() && moreSpecific(r, best.bounds()))) {
                best = c;
            }
        }
        return best;
    }

    public ScreenCandidate selectAt(float x, float y) {
        return selectAccessibilityAt(x, y);
    }

    public boolean needsVisualRefinement(float x, float y) {
        return false;
    }

    private boolean isSelectableType(ScreenCandidate c) {
        return c != null && (c.type() == ScreenCandidate.Type.TEXT
                || c.type() == ScreenCandidate.Type.NON_TEXT);
    }

    private boolean moreSpecific(Rect candidate, Rect current) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (current == null || current.isEmpty()) return true;
        if (current.contains(candidate) && !candidate.equals(current)) return true;
        if (candidate.contains(current) && !candidate.equals(current)) return false;
        return area(candidate) < area(current);
    }

    private long area(Rect r) {
        return (long) r.width() * r.height();
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
