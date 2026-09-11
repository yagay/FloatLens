package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Position-only selection model.
 *
 * TEXT, NON_TEXT(image/icon) and generic VIEW candidates are selectable. Near-fullscreen VIEW/ROOT
 * candidates are retained as final fallback so a whole-page View never beats a more specific child
 * at the same point. No semantic score or clickable priority participates in selection.
 */
public final class ScreenSelectionModel {
    private final ArrayList<ScreenCandidate> accessibility = new ArrayList<>();

    public void setAccessibility(List<ScreenCandidate> items) {
        accessibility.clear();
        if (items == null) return;
        for (ScreenCandidate c : dedupe(items)) {
            if (isAcceptedType(c)) accessibility.add(c);
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
        ScreenCandidate rootFallback = null;

        for (ScreenCandidate c : accessibility) {
            if (!isAcceptedType(c)) continue;
            Rect r = c.bounds();
            if (r.isEmpty() || !r.contains(px, py)) continue;

            if (c.type() == ScreenCandidate.Type.ROOT || c.fullscreenLike()) {
                // Whole-page Views are deliberately last. If several windows expose one, keep the
                // geometrically/depth-wise most specific candidate only for fallback.
                if (rootFallback == null
                        || c.depth() > rootFallback.depth()
                        || (c.depth() == rootFallback.depth()
                        && moreSpecific(r, rootFallback.bounds()))) {
                    rootFallback = c;
                }
                continue;
            }

            // Normal TEXT/NON_TEXT/VIEW selection remains pure geometry/tree position.
            if (best == null
                    || c.depth() > best.depth()
                    || (c.depth() == best.depth() && moreSpecific(r, best.bounds()))) {
                best = c;
            }
        }
        return best != null ? best : rootFallback;
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
