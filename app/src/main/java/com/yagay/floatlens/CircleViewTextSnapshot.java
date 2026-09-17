package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * View-first text snapshot used by Circle full-screen ML Kit recognition.
 *
 * <p>Only conservative pure-text View candidates are allowed into the Accessibility text layer.
 * Mixed or ambiguous Views are left entirely to ML Kit. Accessibility owns the preferred text
 * content for accepted pure-text Views while ML Kit owns selectable geometry. The screenshot is
 * never masked by whole View bounds.</p>
 */
final class CircleViewTextSnapshot {
    private static final float VIEW_GEOMETRY_CONFIDENCE = 0.92f;
    private static final float SELECTABLE_GEOMETRY_CONFIDENCE = 0.965f;

    static final class Snapshot {
        private final OcrDocument viewDocument;
        private final int viewCount;

        Snapshot(OcrDocument viewDocument, int viewCount) {
            this.viewDocument = viewDocument;
            this.viewCount = Math.max(0, viewCount);
        }

        boolean isEmpty() {
            return viewDocument == null || viewDocument.chars().isEmpty();
        }

        int viewCount() { return viewCount; }
        int charCount() { return viewDocument == null ? 0 : viewDocument.chars().size(); }
        int maskCount() { return 0; }

        /** Whole-View masking stays disabled so image+text Views remain fully visible to ML Kit. */
        int mask(Bitmap bitmap) { return 0; }

        /**
         * Refines accepted pure-View text with ML Kit geometry, then merges both sources. View lines
         * that still contain approximate whole-View geometry are deliberately excluded from the
         * selectable document; their ML counterpart remains instead.
         */
        OcrDocument merge(OcrDocument mlDocument, int width, int height) {
            if (viewDocument == null || viewDocument.chars().isEmpty()) return mlDocument;

            if (mlDocument == null || mlDocument.chars().isEmpty()) {
                return selectableViewDocument(viewDocument, width, height);
            }

            ViewTextGeometryRefiner.Result refinedResult =
                    ViewTextGeometryRefiner.refine(viewDocument, mlDocument);
            OcrDocument refinedView = refinedResult.document();
            OcrDocument selectableView = selectableViewDocument(refinedView, width, height);

            ArrayList<OcrDocument.Line> lines = new ArrayList<>();
            if (selectableView != null) lines.addAll(selectableView.lines());
            for (OcrDocument.Line mlLine : mlDocument.lines()) {
                if (mlLine == null || mlLine.chars().isEmpty() || mlLine.bounds().isEmpty()) continue;
                if (duplicatesViewText(mlLine, selectableView)) continue;
                lines.add(mlLine);
            }

            if (lines.isEmpty()) return null;
            String prefix = selectableView == null || selectableView.chars().isEmpty()
                    ? "mlkit-geometry-fallback+"
                    : "view-accessibility-mlkit-geometry+";
            return rebuild(lines,
                    prefix + mlDocument.engine(),
                    mlDocument.confidence(), mlDocument.score(), width, height);
        }

        private boolean duplicatesViewText(OcrDocument.Line mlLine, OcrDocument selectableView) {
            if (selectableView == null || selectableView.lines().isEmpty()) return false;
            String ml = normalizeText(mlLine.text());
            if (ml.isEmpty()) return false;
            Rect mr = mlLine.bounds();
            for (OcrDocument.Line viewLine : selectableView.lines()) {
                if (viewLine == null || viewLine.bounds().isEmpty()) continue;
                String view = normalizeText(viewLine.text());
                if (view.isEmpty() || !view.equals(ml)) continue;
                Rect vr = viewLine.bounds();
                Rect intersection = new Rect();
                if (!intersection.setIntersect(mr, vr)) continue;
                long overlap = (long) intersection.width() * intersection.height();
                long smaller = Math.max(1L, Math.min(area(mr), area(vr)));
                if (overlap >= smaller * 35L / 100L
                        || vr.contains(mr.centerX(), mr.centerY())
                        || mr.contains(vr.centerX(), vr.centerY())) {
                    return true;
                }
            }
            return false;
        }
    }

    static Snapshot capture(Context context, Bitmap bitmap) {
        if (context == null || bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return empty(bitmap);
        }
        Context app = context.getApplicationContext();
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) return empty(bitmap);

        FloatSettings settings = new FloatSettings(app);
        Rect workspace = CaptureSystemBarsPolicy.captureBounds(
                app, settings.keepStatusBarInScreenshot(), settings.keepNavigationBarInScreenshot());
        ScreenBitmapTransform transform = new ScreenBitmapTransform(
                workspace, bitmap.getWidth(), bitmap.getHeight());

        List<ScreenCandidate> candidates = AccessibilityCandidateCollector.collect(service);
        if (candidates == null || candidates.isEmpty()) return empty(bitmap);

        ArrayList<RawLine> rawLines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pureTextViews = 0;
        int skippedNonPureTextViews = 0;
        for (ScreenCandidate candidate : candidates) {
            if (candidate == null
                    || candidate.source() != ScreenCandidate.Source.ACCESSIBILITY
                    || candidate.type() != ScreenCandidate.Type.TEXT
                    || !candidate.hasText()) {
                continue;
            }
            String value = candidate.text() == null ? "" : candidate.text().trim();
            if (value.isEmpty()) continue;

            if (!isPureTextView(candidate)) {
                skippedNonPureTextViews++;
                continue;
            }

            Rect screenBounds = candidate.bounds();
            if (screenBounds.isEmpty()) continue;
            Rect bitmapBounds = transform.screenToBitmap(screenBounds);
            if (bitmapBounds.isEmpty()) continue;
            Rect bitmapFrame = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            if (!bitmapBounds.intersect(bitmapFrame) || bitmapBounds.isEmpty()) continue;

            String key = value + "@" + bitmapBounds.flattenToString();
            if (!seen.add(key)) continue;
            appendTextLines(rawLines, value, bitmapBounds);
            pureTextViews++;
        }

        OcrDocument document = buildViewDocument(rawLines, bitmap.getWidth(), bitmap.getHeight());
        DiagnosticLog.i(app, "G_CIRCLE_VIEW_TEXT",
                "capturedPureTextViews=" + pureTextViews
                        + " skippedNonPureTextViews=" + skippedNonPureTextViews
                        + " chars=" + (document == null ? 0 : document.chars().size())
                        + " maskRects=0"
                        + " policy=pure_text_view_only_other_views_mlkit");
        return new Snapshot(document, pureTextViews);
    }

    /**
     * Conservative pure-text classification for Circle. Only TextView-style classes are accepted.
     * Anything that is commonly mixed with icons/images/content, editable, checked, web/custom,
     * or explicitly image-like is left to ML Kit even if Accessibility exposes getText().
     */
    private static boolean isPureTextView(ScreenCandidate candidate) {
        if (candidate == null || !candidate.hasText()) return false;
        if (candidate.iconLike() || candidate.fullscreenLike() || candidate.editable()) return false;

        String cls = candidate.className() == null
                ? "" : candidate.className().trim().toLowerCase(Locale.ROOT);
        if (cls.isEmpty()) return false;

        if (cls.contains("button")
                || cls.contains("checkedtextview")
                || cls.contains("edittext")
                || cls.contains("webview")
                || cls.contains("image")
                || cls.contains("layout")
                || cls.contains("viewgroup")
                || cls.contains("container")
                || cls.contains("compose")
                || cls.contains("surface")
                || cls.contains("texture")
                || cls.equals("android.view.view")) {
            return false;
        }

        boolean textClass = cls.equals("android.widget.textview")
                || cls.endsWith(".textview")
                || cls.endsWith("textview")
                || cls.contains("appcompattextview")
                || cls.contains("materialtextview");
        if (!textClass) return false;

        String id = candidate.viewId() == null
                ? "" : candidate.viewId().toLowerCase(Locale.ROOT);
        if (containsVisualToken(id)) return false;

        return true;
    }

    private static boolean containsVisualToken(String value) {
        if (value == null || value.isEmpty()) return false;
        return value.contains("icon")
                || value.contains("image")
                || value.contains("avatar")
                || value.contains("thumbnail")
                || value.contains("thumb")
                || value.contains("photo")
                || value.contains("picture")
                || value.contains("cover")
                || value.contains("media")
                || value.contains("drawable");
    }

    private static OcrDocument selectableViewDocument(OcrDocument document, int width, int height) {
        if (document == null || document.lines().isEmpty()) return null;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        for (OcrDocument.Line line : document.lines()) {
            if (line == null || line.chars().isEmpty() || line.bounds().isEmpty()) continue;
            boolean precise = true;
            for (OcrDocument.CharUnit c : line.chars()) {
                if (c == null || c.text().isBlank()) continue;
                if (c.bounds().isEmpty() || c.confidence() < SELECTABLE_GEOMETRY_CONFIDENCE) {
                    precise = false;
                    break;
                }
            }
            if (precise) lines.add(line);
        }
        if (lines.isEmpty()) return null;
        return rebuild(lines, "view-accessibility-refined", document.confidence(), document.score(),
                width, height);
    }

    private static void appendTextLines(List<RawLine> out, String value, Rect bounds) {
        String normalized = value == null ? "" : value.replace('\r', '\n').trim();
        if (normalized.isEmpty() || bounds == null || bounds.isEmpty()) return;
        String[] split = normalized.split("\\n+");
        ArrayList<String> rows = new ArrayList<>();
        for (String row : split) {
            String text = row == null ? "" : row.trim();
            if (!text.isEmpty()) rows.add(text);
        }
        if (rows.isEmpty()) return;

        for (int row = 0; row < rows.size(); row++) {
            int top = bounds.top + bounds.height() * row / rows.size();
            int bottom = bounds.top + bounds.height() * (row + 1) / rows.size();
            Rect rowBounds = new Rect(bounds.left, top, bounds.right, Math.max(top + 1, bottom));
            out.add(new RawLine(rows.get(row), rowBounds));
        }
    }

    private static OcrDocument buildViewDocument(List<RawLine> raw, int width, int height) {
        if (raw == null || raw.isEmpty()) return null;
        raw.sort(Comparator
                .comparingInt((RawLine line) -> line.bounds.top)
                .thenComparingInt(line -> line.bounds.left));

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int order = 0;
        for (int lineId = 0; lineId < raw.size(); lineId++) {
            RawLine line = raw.get(lineId);
            int[] cps = line.text.codePoints().toArray();
            if (cps.length == 0) continue;
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (int i = 0; i < cps.length; i++) {
                if (Character.isWhitespace(cps[i])) continue;
                int left = line.bounds.left + line.bounds.width() * i / cps.length;
                int right = line.bounds.left + line.bounds.width() * (i + 1) / cps.length;
                Rect charBounds = new Rect(left, line.bounds.top,
                        Math.max(left + 1, right), line.bounds.bottom);
                chars.add(new OcrDocument.CharUnit(
                        new String(Character.toChars(cps[i])), charBounds,
                        VIEW_GEOMETRY_CONFIDENCE, lineId, 0, order++));
            }
            if (!chars.isEmpty()) {
                lines.add(new OcrDocument.Line(line.text, line.bounds,
                        VIEW_GEOMETRY_CONFIDENCE, chars));
            }
        }
        if (lines.isEmpty()) return null;
        return rebuild(lines, "view-accessibility", 1f, 1d, width, height);
    }

    private static OcrDocument rebuild(List<OcrDocument.Line> source, String engine,
                                       float confidence, double score, int width, int height) {
        if (source == null || source.isEmpty()) return null;
        ArrayList<OcrDocument.Line> sorted = new ArrayList<>(source);
        sorted.sort(Comparator
                .comparingInt((OcrDocument.Line line) -> line.bounds().top)
                .thenComparingInt(line -> line.bounds().left));

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        int order = 0;
        for (OcrDocument.Line sourceLine : sorted) {
            if (sourceLine == null || sourceLine.chars().isEmpty()) continue;
            int lineId = lines.size();
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            Rect bounds = new Rect();
            StringBuilder lineText = new StringBuilder();
            for (OcrDocument.CharUnit c : sourceLine.chars()) {
                if (c == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
                Rect b = c.bounds();
                chars.add(new OcrDocument.CharUnit(c.text(), b, c.confidence(),
                        lineId, c.group(), order++));
                if (bounds.isEmpty()) bounds.set(b); else bounds.union(b);
                lineText.append(c.text());
            }
            if (chars.isEmpty()) continue;
            String text = sourceLine.text() == null ? "" : sourceLine.text().trim();
            if (text.isEmpty()) text = lineText.toString();
            if (fullText.length() > 0) fullText.append('\n');
            fullText.append(text);
            blocks.add(text);
            if (bounds.isEmpty()) bounds = sourceLine.bounds();
            lines.add(new OcrDocument.Line(text, bounds, sourceLine.confidence(), chars));
        }
        if (lines.isEmpty()) return null;
        return new OcrDocument(fullText.toString(), blocks, lines, engine,
                confidence, score, Math.max(1, width), Math.max(1, height));
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        normalized.codePoints().forEach(cp -> {
            if (!Character.isWhitespace(cp)) out.appendCodePoint(cp);
        });
        return out.toString();
    }

    private static long area(Rect r) {
        return r == null || r.isEmpty() ? 0L : (long) r.width() * r.height();
    }

    private static Snapshot empty(Bitmap bitmap) {
        int width = bitmap == null ? 1 : Math.max(1, bitmap.getWidth());
        int height = bitmap == null ? 1 : Math.max(1, bitmap.getHeight());
        return new Snapshot(new OcrDocument("", List.of(), List.of(),
                "view-accessibility", 0f, 0d, width, height), 0);
    }

    private static final class RawLine {
        final String text;
        final Rect bounds;

        RawLine(String text, Rect bounds) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        }
    }

    private CircleViewTextSnapshot() {}
}
