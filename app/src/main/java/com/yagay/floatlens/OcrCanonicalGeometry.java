package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure local OCR geometry normalizer inspired by ML-style text semantics.
 *
 * <p>No ML recognizer is invoked here. Recognizers keep ownership of text while this class owns
 * stable reading order, tight row bands, character boxes and word/group boundaries.</p>
 */
final class OcrCanonicalGeometry {
    private static final float ROW_HEIGHT_SCALE = 0.78f;
    private static final float LATIN_WORD_GAP_FACTOR = 0.52f;
    private static final float CJK_WIDTH_FACTOR = 0.92f;

    static OcrDocument normalize(Context context, OcrDocument source) {
        if (source == null || source.lines().isEmpty() || source.chars().isEmpty()) return source;

        ArrayList<OcrDocument.Line> input = new ArrayList<>(source.lines());
        input.sort(Comparator.comparingInt((OcrDocument.Line l) -> l.bounds().centerY())
                .thenComparingInt(l -> l.bounds().left));

        ArrayList<OcrDocument.Line> output = new ArrayList<>();
        int order = 0;
        int nextGroup = 0;

        for (OcrDocument.Line rawLine : input) {
            if (rawLine == null || rawLine.chars().isEmpty()) continue;
            ArrayList<OcrDocument.CharUnit> rawChars = new ArrayList<>();
            for (OcrDocument.CharUnit c : rawLine.chars()) {
                if (c == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
                rawChars.add(c);
            }
            if (rawChars.isEmpty()) continue;
            rawChars.sort(Comparator.comparingInt((OcrDocument.CharUnit c) -> c.bounds().centerX())
                    .thenComparingInt(c -> c.bounds().left));

            Rect rowBand = canonicalRowBand(rawLine, rawChars);
            if (rowBand.isEmpty()) rowBand = rawLine.bounds();
            int rowHeight = Math.max(1, rowBand.height());

            ArrayList<Rect> boxes = canonicalCharBoxes(rawChars, rowBand);
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            int currentGroup = nextGroup++;
            OcrDocument.CharUnit previousRaw = null;
            Rect previousBox = null;

            for (int i = 0; i < rawChars.size(); i++) {
                OcrDocument.CharUnit raw = rawChars.get(i);
                Rect box = boxes.get(i);
                if (box.isEmpty()) continue;

                if (previousRaw != null && shouldBreakGroup(previousRaw, raw, previousBox, box, rowHeight)) {
                    currentGroup = nextGroup++;
                }
                chars.add(new OcrDocument.CharUnit(raw.text(), box, raw.confidence(),
                        output.size(), currentGroup, order++));
                previousRaw = raw;
                previousBox = box;
            }
            if (chars.isEmpty()) continue;

            Rect lineBounds = union(chars, rowBand);
            output.add(new OcrDocument.Line(rawLine.text(), lineBounds,
                    rawLine.confidence(), chars));
        }

        if (output.isEmpty()) return source;
        StringBuilder full = new StringBuilder();
        ArrayList<String> blocks = new ArrayList<>();
        for (OcrDocument.Line line : output) {
            String text = line.text() == null ? "" : line.text().trim();
            if (text.isEmpty()) continue;
            if (full.length() > 0) full.append('\n');
            full.append(text);
            blocks.add(text);
        }

        OcrDocument result = new OcrDocument(full.toString(), blocks, output,
                source.engine() + "+canonical-geometry", source.confidence(), source.score(),
                source.imageWidth(), source.imageHeight(), source.coordinateSpace());
        DiagnosticLog.i(context, "OCR_CANONICAL_GEOMETRY",
                "engine=" + source.engine()
                        + " lines=" + source.lines().size() + "->" + result.lines().size()
                        + " chars=" + source.chars().size() + "->" + result.chars().size()
                        + " policy=local_ml_style_no_ml_runtime");
        return result;
    }

    private static Rect canonicalRowBand(OcrDocument.Line line,
                                         List<OcrDocument.CharUnit> chars) {
        ArrayList<Integer> heights = new ArrayList<>();
        ArrayList<Integer> centers = new ArrayList<>();
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        for (OcrDocument.CharUnit c : chars) {
            Rect r = c.bounds();
            if (r.isEmpty()) continue;
            heights.add(r.height());
            centers.add(r.centerY());
            left = Math.min(left, r.left);
            right = Math.max(right, r.right);
        }
        if (heights.isEmpty() || centers.isEmpty() || left >= right) return line.bounds();
        heights.sort(Integer::compareTo);
        centers.sort(Integer::compareTo);
        int medianHeight = Math.max(1, heights.get(heights.size() / 2));
        int centerY = centers.get(centers.size() / 2);
        int targetHeight = Math.max(6, Math.round(medianHeight * ROW_HEIGHT_SCALE));
        Rect lineBox = line.bounds();
        if (!lineBox.isEmpty()) targetHeight = Math.min(Math.max(1, lineBox.height()), targetHeight);
        int top = centerY - targetHeight / 2;
        int bottom = top + targetHeight;
        if (!lineBox.isEmpty()) {
            if (top < lineBox.top) { bottom += lineBox.top - top; top = lineBox.top; }
            if (bottom > lineBox.bottom) { top -= bottom - lineBox.bottom; bottom = lineBox.bottom; }
        }
        top = Math.max(0, top);
        bottom = Math.max(top + 1, bottom);
        return new Rect(left, top, right, bottom);
    }

    private static ArrayList<Rect> canonicalCharBoxes(List<OcrDocument.CharUnit> chars,
                                                       Rect rowBand) {
        ArrayList<Rect> out = new ArrayList<>();
        int n = chars.size();
        int[] centers = new int[n];
        for (int i = 0; i < n; i++) centers[i] = chars.get(i).bounds().centerX();

        for (int i = 0; i < n; i++) {
            OcrDocument.CharUnit unit = chars.get(i);
            Rect raw = unit.bounds();
            int leftLimit = i == 0 ? rowBand.left : (centers[i - 1] + centers[i]) / 2;
            int rightLimit = i == n - 1 ? rowBand.right : (centers[i] + centers[i + 1]) / 2;
            int left = Math.max(leftLimit, raw.left);
            int right = Math.min(rightLimit, raw.right);
            if (right <= left) {
                left = Math.max(rowBand.left, Math.min(rowBand.right - 1, centers[i] - 1));
                right = Math.min(rowBand.right, Math.max(left + 1, centers[i] + 1));
            }

            if (isCjk(unit.text())) {
                int maxWidth = Math.max(2, Math.round(rowBand.height() * CJK_WIDTH_FACTOR));
                if (right - left > maxWidth) {
                    int cx = centers[i];
                    left = Math.max(leftLimit, cx - maxWidth / 2);
                    right = Math.min(rightLimit, left + maxWidth);
                    if (right <= left) right = Math.min(rowBand.right, left + 1);
                }
            }
            out.add(new Rect(left, rowBand.top, right, rowBand.bottom));
        }
        return out;
    }

    private static boolean shouldBreakGroup(OcrDocument.CharUnit previous,
                                            OcrDocument.CharUnit current,
                                            Rect previousBox,
                                            Rect currentBox,
                                            int rowHeight) {
        if (previous == null || current == null || previousBox == null || currentBox == null) return true;
        String a = previous.text();
        String b = current.text();
        if (isCjk(a) || isCjk(b)) return true;
        if (!isLatinWordToken(a) || !isLatinWordToken(b)) return true;
        int gap = currentBox.left - previousBox.right;
        return gap > Math.max(1, Math.round(rowHeight * LATIN_WORD_GAP_FACTOR));
    }

    private static boolean isLatinWordToken(String value) {
        if (value == null || value.isBlank()) return false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (isCjk(cp)) return false;
            if (!Character.isLetterOrDigit(cp) && cp != '\'' && cp != 0x2019 && cp != '-' && cp != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isCjk(String value) {
        if (value == null || value.isBlank()) return false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (isCjk(cp)) return true;
        }
        return false;
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    private static Rect union(List<OcrDocument.CharUnit> chars, Rect fallback) {
        Rect out = null;
        for (OcrDocument.CharUnit c : chars) {
            if (c == null || c.bounds().isEmpty()) continue;
            Rect r = c.bounds();
            if (out == null) out = new Rect(r); else out.union(r);
        }
        return out == null ? new Rect(fallback) : out;
    }

    private OcrCanonicalGeometry() {}
}
