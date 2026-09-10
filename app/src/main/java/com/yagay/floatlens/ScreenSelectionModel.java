package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict text/image selection model.
 *
 * Only TEXT and NON_TEXT(image/icon) Accessibility candidates may be selected directly.
 * Screenshot VISUAL candidates are never standalone controls: they may only geometrically refine
 * an already-known TEXT cell (for example a launcher BubbleTextView containing icon + label).
 */
public final class ScreenSelectionModel {
    private final ArrayList<ScreenCandidate> accessibility = new ArrayList<>();
    private final ArrayList<ScreenCandidate> visual = new ArrayList<>();

    public void setAccessibility(List<ScreenCandidate> items) {
        accessibility.clear();
        if (items != null) {
            for (ScreenCandidate c : dedupe(items)) {
                if (isSelectableType(c)) accessibility.add(c);
            }
        }
    }

    public void setVisual(List<ScreenCandidate> items) {
        visual.clear();
        if (items != null) {
            for (ScreenCandidate c : dedupe(items)) {
                if (c != null && c.type() == ScreenCandidate.Type.NON_TEXT) visual.add(c);
            }
        }
    }

    public List<ScreenCandidate> accessibilityCandidates() { return new ArrayList<>(accessibility); }
    public List<ScreenCandidate> visualCandidates() { return new ArrayList<>(visual); }

    public ScreenCandidate selectAccessibilityAt(float x, float y) {
        final int px = Math.round(x), py = Math.round(y);
        ScreenCandidate best = null;

        for (ScreenCandidate c : accessibility) {
            if (!isSelectableType(c)) continue;
            Rect r = c.bounds();
            if (r.isEmpty() || !r.contains(px, py)) continue;

            // Position + real tree depth only. Type (text/image), clickable and other semantics never
            // add a score. A deeper child wins; containment/area only breaks equal-depth overlaps.
            if (best == null
                    || c.depth() > best.depth()
                    || (c.depth() == best.depth() && strictlyInside(r, best.bounds()))) {
                best = c;
            }
        }
        return best;
    }

    public ScreenCandidate selectAt(float x, float y) {
        ScreenCandidate access = selectAccessibilityAt(x, y);
        if (access == null) return null; // no generic visual/control recognition

        // An exact Accessibility image/icon already has authoritative screen bounds.
        if (access.type() == ScreenCandidate.Type.NON_TEXT) return access;

        // A VISUAL rectangle can only refine a known text cell and must be substantially smaller
        // and fully inside it. This is primarily for launcher icon + label exposed as one text node.
        ScreenCandidate visualHit = selectVisualAt(x, y);
        if (visualHit == null) return access;
        Rect ar = access.bounds();
        Rect vr = visualHit.bounds();
        if (!ar.contains(vr) || ar.equals(vr)) return access;

        long aa = area(ar), va = area(vr);
        if (aa > 0 && va * 100L <= aa * 62L) return visualHit;
        return access;
    }

    public boolean needsVisualRefinement(float x, float y) {
        ScreenCandidate c = selectAccessibilityAt(x, y);
        return c != null && c.type() == ScreenCandidate.Type.TEXT;
    }

    private ScreenCandidate selectVisualAt(float x, float y) {
        int px = Math.round(x), py = Math.round(y);
        ScreenCandidate best = null;
        for (ScreenCandidate c : visual) {
            if (c == null || c.type() != ScreenCandidate.Type.NON_TEXT) continue;
            Rect r = c.bounds();
            if (r.isEmpty() || !r.contains(px, py)) continue;
            if (best == null || strictlyInside(r, best.bounds())) best = c;
        }
        return best;
    }

    private boolean isSelectableType(ScreenCandidate c) {
        return c != null && (c.type() == ScreenCandidate.Type.TEXT
                || c.type() == ScreenCandidate.Type.NON_TEXT);
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
