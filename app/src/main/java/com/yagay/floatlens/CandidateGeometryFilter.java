package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * First-pass Accessibility cleanup before FV-style semantic preparation.
 *
 * Important: FV keeps text and non-text candidates in separate lists. Therefore this stage must not
 * delete a text candidate merely because an empty View has almost the same rectangle. Semantic
 * containment/overlap ordering is handled later by ScreenSelectionModel inside each candidate class.
 */
public final class CandidateGeometryFilter {
    private CandidateGeometryFilter() {}

    public static List<ScreenCandidate> filter(List<ScreenCandidate> input, Rect screen) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        if (input == null) return out;

        // Duplicate active-root/window traversals can expose the exact same candidate more than once.
        // Remove only true semantic duplicates here. Do not collapse near-identical rectangles across
        // TEXT/NON_TEXT/VIEW classes: FV performs its geometry cleanup after separating those lists.
        Map<String, ScreenCandidate> exact = new LinkedHashMap<>();
        for (ScreenCandidate c : input) {
            if (c == null) continue;
            Rect r = c.bounds();
            if (r.isEmpty()) continue;
            String key = c.stableKey();
            exact.putIfAbsent(key, c);
        }
        out.addAll(exact.values());
        return out;
    }
}
