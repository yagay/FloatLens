package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FV-style geometry model.
 *
 * Accessibility candidates are the primary source. Selection inside that source follows geometry
 * and tree depth, not text/icon/clickable scores. Visual candidates are used only when Accessibility
 * has no concrete result or when a visual rectangle is a strict geometric refinement inside a
 * coarse Accessibility rectangle. Near-fullscreen/root rectangles are always final fallback.
 */
public final class ScreenSelectionModel {
    private final ArrayList<ScreenCandidate> accessibility = new ArrayList<>();
    private final ArrayList<ScreenCandidate> visual = new ArrayList<>();

    public void setAccessibility(List<ScreenCandidate> items) {
        accessibility.clear();
        if (items != null) accessibility.addAll(dedupe(items));
    }

    public void setVisual(List<ScreenCandidate> items) {
        visual.clear();
        if (items != null) visual.addAll(dedupe(items));
    }

    public List<ScreenCandidate> accessibilityCandidates() { return new ArrayList<>(accessibility); }
    public List<ScreenCandidate> visualCandidates() { return new ArrayList<>(visual); }

    public ScreenCandidate selectAccessibilityAt(float x, float y) {
        final int px = Math.round(x), py = Math.round(y);
        ScreenCandidate best = null;
        ScreenCandidate root = null;

        for (ScreenCandidate c : accessibility) {
            if (c == null) continue;
            Rect r = c.bounds();
            if (r.isEmpty() || !r.contains(px, py)) continue;
            if (c.fullscreenLike() || c.type() == ScreenCandidate.Type.ROOT) {
                if (root == null || c.depth() > root.depth()) root = c;
                continue;
            }

            // Real parent/child depth wins. When duplicate-window paths report the same depth,
            // geometry containment breaks the tie; no semantic attribute participates.
            if (best == null
                    || c.depth() > best.depth()
                    || (c.depth() == best.depth() && strictlyInside(r, best.bounds()))) {
                best = c;
            }
        }
        return best != null ? best : root;
    }

    public ScreenCandidate selectAt(float x, float y) {
        ScreenCandidate access = selectAccessibilityAt(x, y);
        ScreenCandidate visualHit = selectVisualAt(x, y);

        if (access == null) return visualHit;
        if (access.fullscreenLike() || access.type() == ScreenCandidate.Type.ROOT) {
            return visualHit != null ? visualHit : access;
        }
        if (visualHit == null) return access;

        Rect ar = access.bounds();
        Rect vr = visualHit.bounds();
        // Visual analysis may refine a coarse Accessibility cell (for example a launcher
        // BubbleTextView containing icon+label), but cannot replace an unrelated/accurate node.
        if (ar.contains(vr) && !ar.equals(vr)) {
            long aa = area(ar), va = area(vr);
            if (aa > 0 && va * 100L <= aa * 72L) return visualHit;
        }
        return access;
    }

    public boolean needsVisualFallback(float x, float y) {
        ScreenCandidate c = selectAccessibilityAt(x, y);
        if (c == null || c.fullscreenLike() || c.type() == ScreenCandidate.Type.ROOT) return true;
        Rect r = c.bounds();
        return r.width() > 0 && r.height() > 0 && area(r) > 90000L;
    }

    private ScreenCandidate selectVisualAt(float x, float y) {
        int px = Math.round(x), py = Math.round(y);
        ScreenCandidate best = null;
        for (ScreenCandidate c : visual) {
            if (c == null) continue;
            Rect r = c.bounds();
            if (r.isEmpty() || !r.contains(px, py)) continue;
            if (best == null || strictlyInside(r, best.bounds())) best = c;
        }
        return best;
    }

    private boolean strictlyInside(Rect candidate, Rect current) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (current == null || current.isEmpty()) return true;
        if (current.contains(candidate) && !candidate.equals(current)) return true;
        if (candidate.contains(current) && !candidate.equals(current)) return false;
        return area(candidate) < area(current);
    }

    private long area(Rect r) { return (long) r.width() * r.height(); }

    private List<ScreenCandidate> dedupe(List<ScreenCandidate> in) {
        Map<String, ScreenCandidate> map = new LinkedHashMap<>();
        for (ScreenCandidate c : in) {
            if (c == null || c.bounds().isEmpty()) continue;
            map.putIfAbsent(c.stableKey(), c);
        }
        return new ArrayList<>(map.values());
    }
}
