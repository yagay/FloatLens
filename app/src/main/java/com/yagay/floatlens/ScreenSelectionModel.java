package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges Accessibility and visual candidates without semantic scoring.
 * Selection is purely geometric: pointer containment + spatial specificity.
 * Near-fullscreen/root candidates are considered only when no concrete candidate contains the point.
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

    public List<ScreenCandidate> all() {
        ArrayList<ScreenCandidate> out = new ArrayList<>(accessibility.size() + visual.size());
        out.addAll(accessibility);
        out.addAll(visual);
        return dedupe(out);
    }

    public ScreenCandidate selectAt(float x, float y) {
        final int px = Math.round(x), py = Math.round(y);
        ScreenCandidate best = null;
        ScreenCandidate fullscreenFallback = null;

        for (ScreenCandidate c : all()) {
            if (c == null) continue;
            Rect r = c.bounds();
            if (r.isEmpty() || !r.contains(px, py)) continue;
            if (c.fullscreenLike() || c.type() == ScreenCandidate.Type.ROOT) {
                if (fullscreenFallback == null || isMoreSpecific(r, fullscreenFallback.bounds())) {
                    fullscreenFallback = c;
                }
                continue;
            }
            if (best == null || isMoreSpecific(r, best.bounds())) best = c;
        }
        return best != null ? best : fullscreenFallback;
    }

    /**
     * Geometry only: a contained rectangle is more specific than its container. For overlapping
     * unrelated rectangles, the smaller area is more local to the pointer. No text/icon/clickable
     * attribute participates.
     */
    private boolean isMoreSpecific(Rect candidate, Rect current) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (current == null || current.isEmpty()) return true;
        if (current.contains(candidate) && !candidate.equals(current)) return true;
        if (candidate.contains(current) && !candidate.equals(current)) return false;
        long a = (long) candidate.width() * candidate.height();
        long b = (long) current.width() * current.height();
        return a < b;
    }

    private List<ScreenCandidate> dedupe(List<ScreenCandidate> in) {
        Map<String, ScreenCandidate> map = new LinkedHashMap<>();
        for (ScreenCandidate c : in) if (c != null && !c.bounds().isEmpty()) map.putIfAbsent(c.stableKey(), c);
        return new ArrayList<>(map.values());
    }
}
