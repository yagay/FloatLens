package com.yagay.floatlens;

import android.content.Context;

import java.util.List;

/**
 * Selects cached full-frame OCR text at the real gesture location.
 *
 * <p>The Circle preindex now contains one full-frame document only, so there is deliberately no
 * spatial clustering, fuzzy text voting or cross-pass scoring here. Small image text/logos are
 * verified separately from a tight local crop.</p>
 */
final class CircleOcrConsensus {
    static final class Result {
        final OcrDocument document;
        final int passHits;
        final int clusters;
        final int maxSupport;
        final int maxTextSupport;
        final boolean textConflict;
        final String candidateSummary;

        Result(OcrDocument document, int passHits, int clusters, int maxSupport,
               int maxTextSupport, boolean textConflict, String candidateSummary) {
            this.document = document;
            this.passHits = passHits;
            this.clusters = clusters;
            this.maxSupport = maxSupport;
            this.maxTextSupport = maxTextSupport;
            this.textConflict = textConflict;
            this.candidateSummary = candidateSummary == null ? "" : candidateSummary;
        }
    }

    static Result resolve(Context context,
                          GoogleCircleCapture.Frame frame,
                          GoogleCircleSelection.Selection gesture,
                          CircleOcrIndex index,
                          float tapToleranceDp,
                          float corridorDp) {
        if (context == null || frame == null || gesture == null || index == null || index.isEmpty()) {
            return empty();
        }

        OcrDocument full = index.fullFrameDocument();
        if (full == null || full.lines().isEmpty() || full.chars().isEmpty()) return empty();

        List<CircleGestureTextSelector.GroupHit> hits = CircleGestureTextSelector.hitGroups(
                context, frame, gesture, full, tapToleranceDp, corridorDp);
        if (hits == null || hits.isEmpty()) return empty();

        OcrDocument selected = CircleGestureTextSelector.documentFromGroups(
                hits, full, "gesture-full-index");
        if (selected == null || selected.lines().isEmpty() || selected.chars().isEmpty()) return empty();

        String text = selected.fullText() == null ? "" : selected.fullText()
                .replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() > 48) text = text.substring(0, 48) + "…";
        return new Result(selected, 1, 1, 1, 1, false, "full=" + text);
    }

    private static Result empty() {
        return new Result(null, 0, 0, 0, 0, false, "");
    }

    private CircleOcrConsensus() {}
}
