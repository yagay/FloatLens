package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Shared content resolver for explicit region workflows; ordinary Circle OCR does not use it. */
final class RegionContentResolver {
    private static final long MIN_OVERLAP_PERCENT = 45L;

    static List<String> visibleViewText(Rect screenRect) {
        if (screenRect == null || screenRect.isEmpty()) return List.of();
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) return List.of();

        List<ScreenCandidate> all = AccessibilityCandidateCollector.collect(service);
        ArrayList<ScreenCandidate> hits = new ArrayList<>();
        for (ScreenCandidate candidate : all) {
            if (candidate == null || candidate.type() != ScreenCandidate.Type.TEXT
                    || !candidate.hasText()) continue;
            Rect bounds = candidate.bounds();
            if (bounds.isEmpty() || !Rect.intersects(screenRect, bounds)) continue;
            Rect intersection = new Rect();
            if (!intersection.setIntersect(screenRect, bounds)) continue;
            long intersectionArea = (long) intersection.width() * intersection.height();
            long candidateArea = Math.max(1L, (long) bounds.width() * bounds.height());
            boolean centerInside = screenRect.contains(bounds.centerX(), bounds.centerY());
            if (centerInside || intersectionArea * 100L >= candidateArea * MIN_OVERLAP_PERCENT) {
                hits.add(candidate);
            }
        }

        hits.sort(Comparator.comparingInt((ScreenCandidate candidate) -> candidate.bounds().top)
                .thenComparingInt(candidate -> candidate.bounds().left)
                .thenComparingInt(ScreenCandidate::depth));
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (ScreenCandidate candidate : hits) {
            String value = candidate.text() == null ? "" : candidate.text().trim();
            if (!value.isEmpty()) unique.add(value);
        }
        return new ArrayList<>(unique);
    }

    private RegionContentResolver() {}
}
