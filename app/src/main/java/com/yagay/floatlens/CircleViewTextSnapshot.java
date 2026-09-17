package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * View-first text snapshot used by Circle full-screen ML Kit recognition.
 *
 * <p>Only real Accessibility {@code getText()} candidates from clearly text-only View classes are
 * accepted for OCR exclusion. Mixed image+text Views, WebViews, containers, buttons and custom
 * Views stay untouched so ML Kit can still see any visual text inside them.</p>
 */
final class CircleViewTextSnapshot {
    private static final float VIEW_GEOMETRY_CONFIDENCE = 0.92f;
    private static final int MAX_BORDER_SAMPLES_PER_EDGE = 12;

    static final class Snapshot {
        private final OcrDocument viewDocument;
        private final List<Rect> maskRects;
        private final int viewCount;

        Snapshot(OcrDocument viewDocument, List<Rect> maskRects, int viewCount) {
            this.viewDocument = viewDocument;
            this.maskRects = maskRects == null ? List.of() : copyRects(maskRects);
            this.viewCount = Math.max(0, viewCount);
        }

        boolean isEmpty() {
            return viewDocument == null || viewDocument.chars().isEmpty() || maskRects.isEmpty();
        }

        int viewCount() { return viewCount; }
        int charCount() { return viewDocument == null ? 0 : viewDocument.chars().size(); }
        int maskCount() { return maskRects.size(); }

        /** Masks only accepted pure-text View bounds in-place using the surrounding background. */
        int mask(Bitmap bitmap) {
            if (bitmap == null || bitmap.isRecycled() || !bitmap.isMutable() || maskRects.isEmpty()) {
                return 0;
            }
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();
            paint.setStyle(Paint.Style.FILL);
            Rect bitmapBounds = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            int count = 0;
            for (Rect source : maskRects) {
                Rect r = new Rect(source);
                if (!r.intersect(bitmapBounds) || r.isEmpty()) continue;
                paint.setColor(sampleBorderColor(bitmap, r));
                canvas.drawRect(r, paint);
                count++;
            }
            return count;
        }

        /**
         * Merges direct View text with ML Kit output. Residual ML characters inside an actually
         * masked pure-text View are discarded to avoid duplicates. Mixed Views are never in masks.
         */
        OcrDocument merge(OcrDocument mlDocument, int width, int height) {
            if (viewDocument == null || viewDocument.chars().isEmpty()) return mlDocument;
            if (mlDocument == null || mlDocument.chars().isEmpty()) return viewDocument;

            ArrayList<OcrDocument.Line> lines = new ArrayList<>();
            lines.addAll(viewDocument.lines());
            for (OcrDocument.Line line : mlDocument.lines()) {
                if (line == null || line.chars().isEmpty()) continue;
                ArrayList<OcrDocument.CharUnit> kept = new ArrayList<>();
                Rect keptBounds = new Rect();
                StringBuilder text = new StringBuilder();
                for (OcrDocument.CharUnit c : line.chars()) {
                    if (c == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
                    Rect b = c.bounds();
                    if (insideAnyMask(b.centerX(), b.centerY())) continue;
                    kept.add(c);
                    if (keptBounds.isEmpty()) keptBounds.set(b); else keptBounds.union(b);
                    text.append(c.text());
                }
                if (!kept.isEmpty() && !keptBounds.isEmpty()) {
                    String lineText = text.toString().trim();
                    if (lineText.isEmpty()) lineText = line.text();
                    lines.add(new OcrDocument.Line(lineText, keptBounds, line.confidence(), kept));
                }
            }

            return rebuild(lines,
                    "view-accessibility+" + mlDocument.engine(),
                    mlDocument.confidence(), mlDocument.score(), width, height);
        }

        private boolean insideAnyMask(int x, int y) {
            for (Rect r : maskRects) {
                if (r != null && r.contains(x, y)) return true;
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
        ArrayList<Rect> masks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pureTextViews = 0;
        int mixedTextViewsSkipped = 0;
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
                mixedTextViewsSkipped++;
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
            masks.add(new Rect(bitmapBounds));
            appendTextLines(rawLines, value, bitmapBounds);
            pureTextViews++;
        }

        OcrDocument document = buildViewDocument(rawLines, bitmap.getWidth(), bitmap.getHeight());
        DiagnosticLog.i(app, "G_CIRCLE_VIEW_TEXT",
                "capturedPureTextViews=" + pureTextViews
                        + " skippedMixedTextViews=" + mixedTextViewsSkipped
                        + " chars=" + (document == null ? 0 : document.chars().size())
                        + " maskRects=" + masks.size()
                        + " policy=pure_text_class_only_mixed_views_to_mlkit");
        return new Snapshot(document, masks, pureTextViews);
    }

    /**
     * Deliberately conservative: only classes whose Accessibility surface represents text itself
     * are allowed to erase their whole bounds from the OCR bitmap. Anything that may contain an
     * image or custom rendering is left for ML Kit.
     */
    private static boolean isPureTextView(ScreenCandidate candidate) {
        if (candidate == null) return false;
        String cls = candidate.className() == null
                ? "" : candidate.className().trim().toLowerCase(Locale.ROOT);
        if (cls.isEmpty()) return false;

        if (cls.contains("webview") || cls.contains("image") || cls.contains("button")
                || cls.contains("layout") || cls.contains("container") || cls.contains("compose")
                || cls.contains("surface") || cls.contains("texture") || cls.endsWith(".view")
                || cls.equals("android.view.view")) {
            return false;
        }

        return cls.equals("android.widget.textview")
                || cls.endsWith(".textview")
                || cls.contains("appcompattextview")
                || cls.contains("materialtextview")
                || cls.equals("android.widget.edittext")
                || cls.endsWith(".edittext")
                || cls.contains("appcompatedittext")
                || cls.contains("textinputedittext")
                || cls.equals("android.widget.checkedtextview")
                || cls.endsWith(".checkedtextview");
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

    private static int sampleBorderColor(Bitmap bitmap, Rect rect) {
        long alpha = 0L, red = 0L, green = 0L, blue = 0L;
        int count = 0;
        int top = rect.top - 1;
        int bottom = rect.bottom;
        int left = rect.left - 1;
        int right = rect.right;

        int xStep = Math.max(1, rect.width() / MAX_BORDER_SAMPLES_PER_EDGE);
        for (int x = rect.left; x < rect.right; x += xStep) {
            if (top >= 0) {
                int color = bitmap.getPixel(clamp(x, 0, bitmap.getWidth() - 1), top);
                alpha += Color.alpha(color); red += Color.red(color);
                green += Color.green(color); blue += Color.blue(color); count++;
            }
            if (bottom < bitmap.getHeight()) {
                int color = bitmap.getPixel(clamp(x, 0, bitmap.getWidth() - 1), bottom);
                alpha += Color.alpha(color); red += Color.red(color);
                green += Color.green(color); blue += Color.blue(color); count++;
            }
        }

        int yStep = Math.max(1, rect.height() / MAX_BORDER_SAMPLES_PER_EDGE);
        for (int y = rect.top; y < rect.bottom; y += yStep) {
            if (left >= 0) {
                int color = bitmap.getPixel(left, clamp(y, 0, bitmap.getHeight() - 1));
                alpha += Color.alpha(color); red += Color.red(color);
                green += Color.green(color); blue += Color.blue(color); count++;
            }
            if (right < bitmap.getWidth()) {
                int color = bitmap.getPixel(right, clamp(y, 0, bitmap.getHeight() - 1));
                alpha += Color.alpha(color); red += Color.red(color);
                green += Color.green(color); blue += Color.blue(color); count++;
            }
        }

        if (count <= 0) {
            int x = clamp(rect.centerX(), 0, bitmap.getWidth() - 1);
            int y = clamp(rect.centerY(), 0, bitmap.getHeight() - 1);
            return bitmap.getPixel(x, y);
        }
        return Color.argb((int) (alpha / count), (int) (red / count),
                (int) (green / count), (int) (blue / count));
    }

    private static Snapshot empty(Bitmap bitmap) {
        int width = bitmap == null ? 1 : Math.max(1, bitmap.getWidth());
        int height = bitmap == null ? 1 : Math.max(1, bitmap.getHeight());
        return new Snapshot(new OcrDocument("", List.of(), List.of(),
                "view-accessibility", 0f, 0d, width, height), List.of(), 0);
    }

    private static List<Rect> copyRects(List<Rect> source) {
        ArrayList<Rect> out = new ArrayList<>();
        for (Rect r : source) if (r != null && !r.isEmpty()) out.add(new Rect(r));
        return List.copyOf(out);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
