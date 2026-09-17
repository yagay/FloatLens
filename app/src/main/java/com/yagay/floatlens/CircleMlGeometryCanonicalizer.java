package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Makes every non-ML Circle OCR result follow ML Kit's visual text structure.
 *
 * <p>The source recognizer keeps ownership of text. ML Kit owns line bands, character boxes and
 * word/group boundaries whenever the two documents can be matched safely. This keeps TAP,
 * HIGHLIGHT and SCRIBBLE semantics stable regardless of which full-screen text engine is selected.</p>
 */
final class CircleMlGeometryCanonicalizer {
    private static final float MIN_SPATIAL_MATCH = 0.12f;
    private static final float MIN_ACCEPT_SCORE = 0.34f;

    static OcrDocument canonicalize(Context context, OcrDocument textDocument, OcrDocument mlDocument) {
        if (!usable(textDocument) || !usable(mlDocument)) return textDocument;

        List<OcrDocument.Line> sourceLines = new ArrayList<>(textDocument.lines());
        sourceLines.sort(Comparator.comparingInt((OcrDocument.Line line) -> line.bounds().centerY())
                .thenComparingInt(line -> line.bounds().left));
        List<OcrDocument.Line> mlLines = new ArrayList<>(mlDocument.lines());
        boolean[] usedMl = new boolean[mlLines.size()];

        ArrayList<OcrDocument.Line> output = new ArrayList<>();
        int order = 0;
        int canonicalLines = 0;
        int fallbackLines = 0;

        for (OcrDocument.Line source : sourceLines) {
            int matched = bestMlLine(source, mlLines, usedMl);
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            Rect lineBounds;

            if (matched >= 0) {
                OcrDocument.Line ml = mlLines.get(matched);
                usedMl[matched] = true;
                canonicalLines++;
                boolean direct = compact(source.text()).equals(compact(ml.text()))
                        && source.chars().size() == ml.chars().size();
                if (direct) {
                    for (int i = 0; i < source.chars().size(); i++) {
                        OcrDocument.CharUnit s = source.chars().get(i);
                        OcrDocument.CharUnit m = ml.chars().get(i);
                        chars.add(new OcrDocument.CharUnit(s.text(), m.bounds(), s.confidence(),
                                output.size(), m.group(), order++));
                    }
                } else {
                    Rect sourceBand = tightBand(source);
                    Rect mlBand = tightBand(ml);
                    for (OcrDocument.CharUnit s : source.chars()) {
                        if (s == null || s.text().isBlank() || s.bounds().isEmpty()) continue;
                        Rect projected = projectRect(s.bounds(), sourceBand, mlBand);
                        int group = nearestMlGroup(ml.chars(), projected, s.group());
                        chars.add(new OcrDocument.CharUnit(s.text(), projected, s.confidence(),
                                output.size(), group, order++));
                    }
                }
                lineBounds = union(chars, ml.bounds());
            } else {
                fallbackLines++;
                for (OcrDocument.CharUnit s : source.chars()) {
                    if (s == null || s.text().isBlank() || s.bounds().isEmpty()) continue;
                    chars.add(new OcrDocument.CharUnit(s.text(), s.bounds(), s.confidence(),
                            output.size(), s.group(), order++));
                }
                lineBounds = union(chars, source.bounds());
            }

            if (!chars.isEmpty() && !lineBounds.isEmpty()) {
                output.add(new OcrDocument.Line(source.text(), lineBounds,
                        source.confidence(), chars));
            }
        }

        if (output.isEmpty()) return textDocument;
        StringBuilder full = new StringBuilder();
        ArrayList<String> blocks = new ArrayList<>();
        for (OcrDocument.Line line : output) {
            String value = line.text() == null ? "" : line.text().trim();
            if (value.isEmpty()) continue;
            if (full.length() > 0) full.append('\n');
            full.append(value);
            blocks.add(value);
        }

        String engine = textDocument.engine() + "+mlkit-canonical";
        OcrDocument result = new OcrDocument(full.toString(), blocks, output, engine,
                textDocument.confidence(), textDocument.score(),
                textDocument.imageWidth(), textDocument.imageHeight(),
                textDocument.coordinateSpace());
        DiagnosticLog.i(context, "G_CIRCLE_ML_CANONICAL",
                "textEngine=" + textDocument.engine()
                        + " mlEngine=" + mlDocument.engine()
                        + " sourceLines=" + sourceLines.size()
                        + " mlLines=" + mlLines.size()
                        + " canonicalLines=" + canonicalLines
                        + " fallbackLines=" + fallbackLines
                        + " chars=" + result.chars().size()
                        + " policy=ml_lines_boxes_groups_pp_text");
        return result;
    }

    private static int bestMlLine(OcrDocument.Line source,
                                  List<OcrDocument.Line> mlLines,
                                  boolean[] usedMl) {
        int best = -1;
        float bestScore = 0f;
        String sourceText = compact(source.text());
        for (int i = 0; i < mlLines.size(); i++) {
            if (usedMl[i]) continue;
            OcrDocument.Line candidate = mlLines.get(i);
            float spatial = spatialScore(source.bounds(), candidate.bounds());
            if (spatial < MIN_SPATIAL_MATCH) continue;
            String mlText = compact(candidate.text());
            float text = textSimilarity(sourceText, mlText);
            float score = spatial * 0.68f + text * 0.32f;
            if (!sourceText.isEmpty() && sourceText.equals(mlText)) score += 0.40f;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return bestScore >= MIN_ACCEPT_SCORE ? best : -1;
    }

    private static int nearestMlGroup(List<OcrDocument.CharUnit> mlChars, Rect projected, int fallback) {
        if (mlChars == null || mlChars.isEmpty() || projected == null || projected.isEmpty()) return fallback;
        int cx = projected.centerX();
        int cy = projected.centerY();
        int bestGroup = fallback;
        float bestDistance = Float.MAX_VALUE;
        for (OcrDocument.CharUnit unit : mlChars) {
            if (unit == null || unit.bounds().isEmpty()) continue;
            Rect r = unit.bounds();
            if (r.contains(cx, cy)) return unit.group();
            float dx = cx < r.left ? r.left - cx : cx > r.right ? cx - r.right : 0f;
            float dy = cy < r.top ? r.top - cy : cy > r.bottom ? cy - r.bottom : 0f;
            float distance = dx * dx + dy * dy * 2f;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestGroup = unit.group();
            }
        }
        return bestGroup;
    }

    private static Rect projectRect(Rect source, Rect sourceBand, Rect targetBand) {
        if (source == null || source.isEmpty() || sourceBand == null || sourceBand.isEmpty()
                || targetBand == null || targetBand.isEmpty()) return new Rect(source == null ? new Rect() : source);
        float leftRatio = (source.left - sourceBand.left) / (float) Math.max(1, sourceBand.width());
        float rightRatio = (source.right - sourceBand.left) / (float) Math.max(1, sourceBand.width());
        leftRatio = clamp01(leftRatio);
        rightRatio = clamp01(rightRatio);
        int left = targetBand.left + Math.round(targetBand.width() * leftRatio);
        int right = targetBand.left + Math.round(targetBand.width() * rightRatio);
        left = Math.max(targetBand.left, Math.min(targetBand.right - 1, left));
        right = Math.max(left + 1, Math.min(targetBand.right, right));
        return new Rect(left, targetBand.top, right, targetBand.bottom);
    }

    private static Rect tightBand(OcrDocument.Line line) {
        if (line == null) return new Rect();
        return union(line.chars(), line.bounds());
    }

    private static Rect union(List<OcrDocument.CharUnit> chars, Rect fallback) {
        Rect out = null;
        if (chars != null) {
            for (OcrDocument.CharUnit unit : chars) {
                if (unit == null || unit.bounds().isEmpty()) continue;
                Rect r = unit.bounds();
                if (out == null) out = new Rect(r); else out.union(r);
            }
        }
        return out == null || out.isEmpty() ? new Rect(fallback) : out;
    }

    private static float spatialScore(Rect a, Rect b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        int vertical = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        int minHeight = Math.max(1, Math.min(a.height(), b.height()));
        float verticalRatio = vertical / (float) minHeight;
        float centerDistance = Math.abs(a.centerY() - b.centerY());
        float centerScore = 1f - Math.min(1f, centerDistance / Math.max(1f, minHeight * 1.8f));
        int horizontal = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        int minWidth = Math.max(1, Math.min(a.width(), b.width()));
        float horizontalRatio = horizontal / (float) minWidth;
        return verticalRatio * 0.58f + centerScore * 0.27f + horizontalRatio * 0.15f;
    }

    private static float textSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        if (a.equals(b)) return 1f;
        if (a.contains(b) || b.contains(a)) {
            return Math.min(a.length(), b.length()) / (float) Math.max(a.length(), b.length());
        }
        int prefix = 0;
        int limit = Math.min(a.length(), b.length());
        while (prefix < limit && a.charAt(prefix) == b.charAt(prefix)) prefix++;
        return prefix / (float) Math.max(a.length(), b.length());
    }

    private static String compact(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            out.appendCodePoint(Character.toLowerCase(cp));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static boolean usable(OcrDocument document) {
        return document != null && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private CircleMlGeometryCanonicalizer() {}
}
