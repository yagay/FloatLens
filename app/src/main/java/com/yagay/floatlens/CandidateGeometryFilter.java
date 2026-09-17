package com.yagay.floatlens;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** First-pass Accessibility de-duplication before FL semantic preparation. */
public final class CandidateGeometryFilter {
    private CandidateGeometryFilter() {}

    public static List<ScreenCandidate> filter(List<ScreenCandidate> input) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        if (input == null) return out;

        // Duplicate active-root/window traversals can expose the exact same candidate more than once.
        // Remove only true semantic duplicates here. Do not collapse near-identical rectangles across
        // TEXT/NON_TEXT/VIEW classes: FL performs geometry ordering after separating those buckets.
        Map<String, ScreenCandidate> exact = new LinkedHashMap<>();
        for (ScreenCandidate c : input) {
            if (c == null) continue;
            Rect r = c.bounds();
            if (r.isEmpty()) continue;
            exact.putIfAbsent(c.stableKey(), c);
        }
        out.addAll(exact.values());
        return out;
    }
}
