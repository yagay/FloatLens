package com.yagay.floatlens;

import android.graphics.Rect;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Borrows ML Kit rectangles for approximate Accessibility/View characters while preserving the
 * original View text. Exact Accessibility character rectangles (confidence ~= 1) are never moved.
 */
final class ViewTextGeometryRefiner {
    private static final float EXACT_VIEW_CONFIDENCE = 0.995f;
    private static final float MIN_MATCH_RATIO = 0.55f;
    private static final long MAX_ALIGNMENT_CELLS = 120_000L;

    static Result refine(OcrDocument view, OcrDocument mlKit) {
        if (view == null || mlKit == null || view.lines().isEmpty() || mlKit.lines().isEmpty()) {
            return new Result(view, 0, countApproximate(view));
        }
        if (!view.isScreenSpace() || !mlKit.isScreenSpace()) {
            throw new IllegalArgumentException("View geometry refinement requires SCREEN coordinates");
        }

        ArrayList<OcrDocument.Line> out = new ArrayList<>();
        int refinedChars = 0;
        int approximateChars = 0;

        for (OcrDocument.Line viewLine : view.lines()) {
            if (viewLine == null || viewLine.chars().isEmpty() || viewLine.bounds().isEmpty()) continue;
            int approximate = countApproximate(viewLine);
            approximateChars += approximate;
            if (approximate == 0) {
                out.add(viewLine);
                continue;
            }

            List<Glyph> viewGlyphs = viewGlyphs(viewLine.chars());
            List<Glyph> mlGlyphs = mlGlyphsNear(viewLine.bounds(), mlKit.lines());
            Alignment alignment = align(viewGlyphs, mlGlyphs);
            if (!alignment.accepted(viewGlyphs.size())) {
                out.add(viewLine);
                continue;
            }

            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            Rect lineBounds = null;
            int lineRefined = 0;
            for (int i = 0; i < viewLine.chars().size(); i++) {
                OcrDocument.CharUnit original = viewLine.chars().get(i);
                Rect bounds = original.bounds();
                float confidence = original.confidence();
                if (confidence < EXACT_VIEW_CONFIDENCE) {
                    Rect mapped = alignment.boundsForSource(i);
                    if (mapped != null && !mapped.isEmpty()) {
                        bounds = mapped;
                        confidence = Math.max(confidence, 0.97f);
                        lineRefined++;
                    }
                }
                chars.add(new OcrDocument.CharUnit(original.text(), bounds, confidence,
                        original.line(), original.group(), original.order()));
                if (lineBounds == null) lineBounds = new Rect(bounds); else lineBounds.union(bounds);
            }

            refinedChars += lineRefined;
            if (lineBounds == null || lineBounds.isEmpty()) lineBounds = viewLine.bounds();
            out.add(new OcrDocument.Line(viewLine.text(), lineBounds,
                    lineRefined > 0 ? Math.max(viewLine.confidence(), 0.97f) : viewLine.confidence(),
                    chars));
        }

        if (refinedChars == 0) return new Result(view, 0, approximateChars);
        OcrDocument refined = OcrDocument.screenSpace(
                view.fullText(), view.blocks(), out,
                "view-snapshot+mlkit-geometry", view.confidence(), view.score(),
                view.imageWidth(), view.imageHeight());
        return new Result(refined, refinedChars, approximateChars);
    }

    private static List<Glyph> viewGlyphs(List<OcrDocument.CharUnit> chars) {
        ArrayList<Glyph> out = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) {
            OcrDocument.CharUnit c = chars.get(i);
            if (c == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
            out.add(new Glyph(normalize(c.text()), c.bounds(), i));
        }
        return out;
    }

    private static List<Glyph> mlGlyphsNear(Rect viewBounds, List<OcrDocument.Line> lines) {
        Rect gate = new Rect(viewBounds);
        int marginX = Math.max(6, Math.min(36, Math.max(1, viewBounds.height()) / 2));
        int marginY = Math.max(4, Math.min(28, Math.max(1, viewBounds.height()) / 3));
        gate.inset(-marginX, -marginY);

        ArrayList<OcrDocument.CharUnit> candidates = new ArrayList<>();
        for (OcrDocument.Line line : lines) {
            if (line == null || line.bounds().isEmpty()) continue;
            Rect lineRect = line.bounds();
            if (!Rect.intersects(gate, lineRect) && !gate.contains(lineRect.centerX(), lineRect.centerY())) continue;
            for (OcrDocument.CharUnit c : line.chars()) {
                if (c == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
                Rect r = c.bounds();
                if (Rect.intersects(gate, r) || gate.contains(r.centerX(), r.centerY())) candidates.add(c);
            }
        }
        candidates.sort((a, b) -> {
            Rect ar = a.bounds(), br = b.bounds();
            int tolerance = Math.max(3, Math.min(Math.max(1, ar.height()), Math.max(1, br.height())) / 2);
            int dy = ar.centerY() - br.centerY();
            if (Math.abs(dy) > tolerance) return Integer.compare(ar.centerY(), br.centerY());
            return Integer.compare(ar.left, br.left);
        });

        ArrayList<Glyph> out = new ArrayList<>();
        for (OcrDocument.CharUnit c : candidates) appendSplitGlyphs(out, c);
        return out;
    }

    private static void appendSplitGlyphs(List<Glyph> out, OcrDocument.CharUnit c) {
        int[] cps = c.text().codePoints().filter(cp -> !Character.isWhitespace(cp)).toArray();
        if (cps.length == 0) return;
        Rect box = c.bounds();
        for (int i = 0; i < cps.length; i++) {
            int left = box.left + box.width() * i / cps.length;
            int right = box.left + box.width() * (i + 1) / cps.length;
            out.add(new Glyph(normalize(new String(Character.toChars(cps[i]))),
                    new Rect(left, box.top, Math.max(left + 1, right), box.bottom), -1));
        }
    }

    /** Character LCS: text ownership remains View; only equal-character ML rectangles can be used. */
    private static Alignment align(List<Glyph> view, List<Glyph> ml) {
        int n = view.size(), m = ml.size();
        if (n == 0 || m == 0 || (long) n * m > MAX_ALIGNMENT_CELLS) return Alignment.empty(n);

        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (same(view.get(i - 1).key, ml.get(j - 1).key)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        Rect[] mapped = new Rect[n];
        int i = n, j = m, matches = 0;
        while (i > 0 && j > 0) {
            Glyph vg = view.get(i - 1), mg = ml.get(j - 1);
            if (same(vg.key, mg.key)) {
                if (vg.sourceIndex >= 0 && vg.sourceIndex < mapped.length) {
                    mapped[vg.sourceIndex] = new Rect(mg.bounds);
                }
                matches++;
                i--; j--;
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--;
            } else {
                j--;
            }
        }
        return new Alignment(mapped, matches);
    }

    private static boolean same(String a, String b) {
        return a != null && !a.isEmpty() && a.equals(b);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int cp : normalized.codePoints().toArray()) {
            if (!Character.isWhitespace(cp)) out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static int countApproximate(OcrDocument document) {
        if (document == null) return 0;
        int count = 0;
        for (OcrDocument.Line line : document.lines()) count += countApproximate(line);
        return count;
    }

    private static int countApproximate(OcrDocument.Line line) {
        if (line == null) return 0;
        int count = 0;
        for (OcrDocument.CharUnit c : line.chars()) {
            if (c != null && c.confidence() < EXACT_VIEW_CONFIDENCE) count++;
        }
        return count;
    }

    static final class Result {
        private final OcrDocument document;
        private final int refinedChars;
        private final int approximateChars;

        Result(OcrDocument document, int refinedChars, int approximateChars) {
            this.document = document;
            this.refinedChars = refinedChars;
            this.approximateChars = approximateChars;
        }

        OcrDocument document() { return document; }
        int refinedChars() { return refinedChars; }
        int approximateChars() { return approximateChars; }
    }

    private static final class Glyph {
        final String key;
        final Rect bounds;
        final int sourceIndex;

        Glyph(String key, Rect bounds, int sourceIndex) {
            this.key = key == null ? "" : key;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.sourceIndex = sourceIndex;
        }
    }

    private static final class Alignment {
        final Rect[] mapped;
        final int matches;

        Alignment(Rect[] mapped, int matches) {
            this.mapped = mapped == null ? new Rect[0] : mapped;
            this.matches = Math.max(0, matches);
        }

        static Alignment empty(int size) { return new Alignment(new Rect[Math.max(0, size)], 0); }

        boolean accepted(int viewSize) {
            if (viewSize <= 0 || matches <= 0) return false;
            if (viewSize == 1) return matches == 1;
            return matches / (float) viewSize >= MIN_MATCH_RATIO;
        }

        Rect boundsForSource(int index) {
            if (index < 0 || index >= mapped.length || mapped[index] == null) return null;
            return new Rect(mapped[index]);
        }
    }

    private ViewTextGeometryRefiner() {}
}
