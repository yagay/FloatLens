package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Geometry-only cleanup inspired by FV's rectangle de-duplication/containment stage.
 * Candidate semantics never affect which rectangle survives.
 */
public final class CandidateGeometryFilter {
    private CandidateGeometryFilter() {}

    public static List<ScreenCandidate> filter(List<ScreenCandidate> input, Rect screen) {
        ArrayList<ScreenCandidate> clean = new ArrayList<>();
        if (input == null) return clean;

        // First remove exact duplicate geometry from duplicate window/active-root traversals.
        Map<String, ScreenCandidate> exact = new LinkedHashMap<>();
        for (ScreenCandidate c : input) {
            if (c == null) continue;
            Rect r = c.bounds();
            if (r.isEmpty()) continue;
            String key = r.flattenToString() + ":" + c.type() + ":" + c.text() + ":" + c.viewId();
            exact.putIfAbsent(key, c);
        }
        clean.addAll(exact.values());

        // Then collapse only nearly-identical nested rectangles. This is not area ranking of normal
        // candidates: it removes duplicate wrappers that differ by a few pixels around the same UI
        // element. Genuine parent/child rectangles remain together for point-based selection.
        boolean[] remove = new boolean[clean.size()];
        for (int i = 0; i < clean.size(); i++) {
            if (remove[i]) continue;
            Rect a = clean.get(i).bounds();
            if (isFullscreenLike(a, screen)) continue;
            for (int j = i + 1; j < clean.size(); j++) {
                if (remove[j]) continue;
                Rect b = clean.get(j).bounds();
                if (isFullscreenLike(b, screen)) continue;
                if (!nearSameGeometry(a, b)) continue;
                // Preserve the geometrically tighter wrapper; no text/clickability/icon property is
                // consulted. This mirrors the intent of FV's rectangle cleanup stage.
                long aa = area(a), ba = area(b);
                if (aa <= ba) remove[j] = true;
                else { remove[i] = true; break; }
            }
        }

        ArrayList<ScreenCandidate> out = new ArrayList<>();
        for (int i = 0; i < clean.size(); i++) if (!remove[i]) out.add(clean.get(i));
        return out;
    }

    private static boolean nearSameGeometry(Rect a, Rect b) {
        Rect inter = new Rect();
        if (!inter.setIntersect(a, b)) return false;
        long ia = area(inter), aa = area(a), ba = area(b);
        if (aa <= 0 || ba <= 0) return false;
        long min = Math.min(aa, ba);
        boolean heavyOverlap = ia * 100L >= min * 94L;
        boolean closeEdges = Math.abs(a.left - b.left) <= 6 && Math.abs(a.top - b.top) <= 6
                && Math.abs(a.right - b.right) <= 6 && Math.abs(a.bottom - b.bottom) <= 6;
        return heavyOverlap && closeEdges;
    }

    private static boolean isFullscreenLike(Rect r, Rect screen) {
        if (r == null || r.isEmpty() || screen == null || screen.isEmpty()) return false;
        return area(r) >= area(screen) * 88L / 100L;
    }

    private static long area(Rect r) { return (long) r.width() * r.height(); }
}
