package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Refines OCR character geometry without changing the recognizer's text.
 *
 * PP/Rapid style CTC recognizers are good at reading tiny text but their character boxes are
 * reconstructed from the recognition time axis. ML Kit exposes symbol geometry directly. For a
 * small ROI we therefore upscale the image, run one ML Kit pass, map symbol boxes back to source
 * coordinates, then transfer only the geometry onto the PP text. If ML Kit still cannot see the
 * text, the original PP document is returned unchanged.
 */
final class OcrGeometryRefiner {
    private static final int MAX_SOURCE_AREA = 650_000;
    private static final int MAX_SCALED_SIDE = 1800;
    private static final int MAX_SCALED_PIXELS = 2_200_000;

    private OcrGeometryRefiner() {}

    static OcrDocument refinePpWithUpscaledMlKit(Context context, Bitmap source, OcrDocument pp) {
        if (context == null || source == null || source.isRecycled() || pp == null || pp.isEmpty()) return pp;
        if (!pp.engine().startsWith("ppocr-")) return pp;
        long area = (long) source.getWidth() * source.getHeight();
        if (area <= 0 || area > MAX_SOURCE_AREA) {
            DiagnosticLog.i(context, "PPOCR_GEOMETRY", "skip large source="
                    + source.getWidth() + "x" + source.getHeight());
            return pp;
        }

        float scale = rescueScale(pp, source.getWidth(), source.getHeight());
        if (scale < 1.15f) return pp;
        int scaledWidth = Math.max(1, Math.round(source.getWidth() * scale));
        int scaledHeight = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap scaled = null;
        TextRecognizer recognizer = null;
        long started = android.os.SystemClock.uptimeMillis();
        try {
            scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
            Set<String> languages = OcrLanguages.get(context);
            boolean chinese = OcrLanguages.chineseEnabled(languages);
            boolean english = OcrLanguages.englishEnabled(languages);
            if (!chinese && !english) chinese = true;
            recognizer = chinese
                    ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                    : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            String engine = chinese ? "roi-upscale-mlkit-zh" : "roi-upscale-mlkit-latin";
            DiagnosticLog.i(context, "PPOCR_GEOMETRY", "start engine=" + engine
                    + " source=" + source.getWidth() + "x" + source.getHeight()
                    + " scaled=" + scaledWidth + "x" + scaledHeight
                    + " scale=" + String.format(Locale.ROOT, "%.2f", scale)
                    + " ppChars=" + pp.chars().size());

            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(scaled, 0)));
            OcrDocument mlScaled = mlKitDocument(result, engine, scaledWidth, scaledHeight);
            OcrDocument ml = scaledToSource(mlScaled, source.getWidth(), source.getHeight());
            RefineResult refined = transferGeometry(pp, ml);
            DiagnosticLog.i(context, "PPOCR_GEOMETRY", "ready mlChars=" + ml.chars().size()
                    + " refinedChars=" + refined.refinedChars
                    + " ppChars=" + pp.chars().size()
                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
            return refined.refinedChars > 0 ? refined.document : pp;
        } catch (Throwable t) {
            DiagnosticLog.i(context, "PPOCR_GEOMETRY", "fallback=" + safe(t));
            return pp;
        } finally {
            if (recognizer != null) {
                try { recognizer.close(); } catch (Throwable ignored) {}
            }
            if (scaled != null && scaled != source && !scaled.isRecycled()) scaled.recycle();
        }
    }

    private static float rescueScale(OcrDocument pp, int width, int height) {
        ArrayList<Integer> heights = new ArrayList<>();
        for (OcrDocument.Line line : pp.lines()) {
            Rect r = line.bounds();
            if (!r.isEmpty()) heights.add(r.height());
        }
        heights.sort(Integer::compareTo);
        int median = heights.isEmpty() ? 36 : heights.get(heights.size() / 2);
        float wanted = median <= 26 ? 3.0f : median <= 42 ? 2.6f : median <= 64 ? 2.1f : 1.6f;
        float bySide = MAX_SCALED_SIDE / (float) Math.max(1, Math.max(width, height));
        float byPixels = (float) Math.sqrt(MAX_SCALED_PIXELS / (double) Math.max(1L, (long) width * height));
        return Math.max(1f, Math.min(wanted, Math.min(bySide, byPixels)));
    }

    /** Build symbol-level ML Kit geometry in the upscaled bitmap coordinate space. */
    private static OcrDocument mlKitDocument(Text text, String engine, int width, int height) {
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int group = 0;
        int order = 0;
        if (text != null) {
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String lineText = line.getText() == null ? "" : line.getText().trim();
                    Rect lineBox = line.getBoundingBox() == null ? new Rect() : new Rect(line.getBoundingBox());
                    if (lineText.isEmpty() || lineBox.isEmpty()) continue;
                    ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
                    for (Text.Element element : line.getElements()) {
                        String value = element.getText() == null ? "" : element.getText();
                        Rect elementBox = element.getBoundingBox() == null ? new Rect() : new Rect(element.getBoundingBox());
                        if (value.isBlank() || elementBox.isEmpty()) continue;
                        int elementGroup = group++;
                        ArrayList<OcrDocument.CharUnit> symbols = new ArrayList<>();
                        StringBuilder symbolText = new StringBuilder();
                        try {
                            List<Text.Symbol> raw = element.getSymbols();
                            if (raw != null) {
                                for (Text.Symbol symbol : raw) {
                                    if (symbol == null || symbol.getText() == null || symbol.getText().isBlank()
                                            || symbol.getBoundingBox() == null || symbol.getBoundingBox().isEmpty()) continue;
                                    symbolText.append(symbol.getText());
                                    symbols.add(new OcrDocument.CharUnit(symbol.getText(), symbol.getBoundingBox(),
                                            0.82f, lineId, elementGroup, order++));
                                }
                            }
                        } catch (Throwable ignored) {}
                        if (!symbols.isEmpty() && compact(symbolText.toString()).equals(compact(value))) {
                            chars.addAll(symbols);
                        } else {
                            order -= symbols.size();
                            order += appendSplit(chars, value, elementBox, lineId, elementGroup, order);
                        }
                    }
                    if (chars.isEmpty()) {
                        int lineGroup = group++;
                        order += appendSplit(chars, lineText, lineBox, lineId, lineGroup, order);
                    }
                    if (!chars.isEmpty()) {
                        chars.sort(Comparator.comparingInt((OcrDocument.CharUnit c) -> c.bounds().left)
                                .thenComparingInt(c -> c.bounds().top));
                        lines.add(new OcrDocument.Line(lineText, union(chars, lineBox), 0.82f, chars));
                        lineId++;
                    }
                }
            }
        }
        return documentFromLines(lines, engine, 0.82f, width, height);
    }

    private static OcrDocument scaledToSource(OcrDocument scaled, int width, int height) {
        if (scaled == null || scaled.lines().isEmpty()) return emptyDocument("roi-upscale-mlkit", width, height);
        float sx = width / (float) Math.max(1, scaled.imageWidth());
        float sy = height / (float) Math.max(1, scaled.imageHeight());
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int order = 0;
        for (int li = 0; li < scaled.lines().size(); li++) {
            OcrDocument.Line line = scaled.lines().get(li);
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit c : line.chars()) {
                chars.add(new OcrDocument.CharUnit(c.text(), scaleRect(c.bounds(), sx, sy, width, height),
                        c.confidence(), li, c.group(), order++));
            }
            Rect lb = scaleRect(line.bounds(), sx, sy, width, height);
            lines.add(new OcrDocument.Line(line.text(), union(chars, lb), line.confidence(), chars));
        }
        return documentFromLines(lines, scaled.engine() + "-mapped", scaled.confidence(), width, height);
    }

    private static RefineResult transferGeometry(OcrDocument pp, OcrDocument ml) {
        if (ml == null || ml.lines().isEmpty()) return new RefineResult(pp, 0);
        ArrayList<OcrDocument.Line> output = new ArrayList<>();
        int refinedCount = 0;
        int order = 0;
        boolean[] usedMl = new boolean[ml.lines().size()];

        for (int pi = 0; pi < pp.lines().size(); pi++) {
            OcrDocument.Line pLine = pp.lines().get(pi);
            int best = bestMlLine(pLine, ml.lines(), usedMl);
            List<OcrDocument.CharUnit> geometry = null;
            if (best >= 0) {
                geometry = alignGeometry(pLine.chars(), ml.lines().get(best).chars(),
                        lineSpatialScore(pLine.bounds(), ml.lines().get(best).bounds()));
                if (geometry != null) usedMl[best] = true;
            }

            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (int ci = 0; ci < pLine.chars().size(); ci++) {
                OcrDocument.CharUnit p = pLine.chars().get(ci);
                Rect bounds = p.bounds();
                if (geometry != null && ci < geometry.size() && geometry.get(ci) != null) {
                    bounds = geometry.get(ci).bounds();
                    refinedCount++;
                }
                chars.add(new OcrDocument.CharUnit(p.text(), bounds, p.confidence(), pi, p.group(), order++));
            }
            output.add(new OcrDocument.Line(pLine.text(), union(chars, pLine.bounds()),
                    pLine.confidence(), chars));
        }
        OcrDocument out = documentFromLines(output, pp.engine() + "+mlkit-geometry",
                pp.confidence(), pp.imageWidth(), pp.imageHeight());
        return new RefineResult(out, refinedCount);
    }

    private static int bestMlLine(OcrDocument.Line pp, List<OcrDocument.Line> ml, boolean[] used) {
        int best = -1;
        float bestScore = 0f;
        String pt = compact(pp.text());
        for (int i = 0; i < ml.size(); i++) {
            if (used[i]) continue;
            OcrDocument.Line candidate = ml.get(i);
            float spatial = lineSpatialScore(pp.bounds(), candidate.bounds());
            if (spatial < 0.12f) continue;
            String mt = compact(candidate.text());
            float text = textSimilarity(pt, mt);
            float score = spatial * 0.62f + text * 0.38f;
            if (pt.equals(mt) && !pt.isEmpty()) score += 0.35f;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return bestScore >= 0.34f ? best : -1;
    }

    /**
     * Return one geometry slot per PP character. Exact/equal-length lines use ML boxes by order;
     * otherwise an LCS transfers geometry only for characters that can be matched safely.
     */
    private static List<OcrDocument.CharUnit> alignGeometry(List<OcrDocument.CharUnit> pp,
                                                             List<OcrDocument.CharUnit> ml,
                                                             float spatialScore) {
        if (pp == null || ml == null || pp.isEmpty() || ml.isEmpty()) return null;
        ArrayList<OcrDocument.CharUnit> mapped = new ArrayList<>();
        for (int i = 0; i < pp.size(); i++) mapped.add(null);
        String pText = compactChars(pp);
        String mText = compactChars(ml);
        if (pp.size() == ml.size() && (pText.equals(mText) || spatialScore >= 0.55f)) {
            for (int i = 0; i < pp.size(); i++) mapped.set(i, ml.get(i));
            return mapped;
        }

        int n = pp.size(), m = ml.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (compact(pp.get(i).text()).equals(compact(ml.get(j).text()))) dp[i][j] = 1 + dp[i + 1][j + 1];
                else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int matches = 0, i = 0, j = 0;
        while (i < n && j < m) {
            if (compact(pp.get(i).text()).equals(compact(ml.get(j).text()))) {
                mapped.set(i, ml.get(j));
                matches++;
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) i++;
            else j++;
        }
        return matches >= Math.max(1, Math.min(n, m) / 2) ? mapped : null;
    }

    private static float lineSpatialScore(Rect a, Rect b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        Rect intersection = new Rect();
        if (!intersection.setIntersect(a, b)) return 0f;
        long overlap = (long) intersection.width() * intersection.height();
        long aArea = Math.max(1L, (long) a.width() * a.height());
        long bArea = Math.max(1L, (long) b.width() * b.height());
        return overlap / (float) Math.min(aArea, bArea);
    }

    private static float textSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        if (a.equals(b)) return 1f;
        if (a.contains(b) || b.contains(a)) return Math.min(a.length(), b.length()) / (float) Math.max(a.length(), b.length());
        int prefix = 0;
        int limit = Math.min(a.length(), b.length());
        while (prefix < limit && a.charAt(prefix) == b.charAt(prefix)) prefix++;
        return prefix / (float) Math.max(a.length(), b.length());
    }

    private static Rect scaleRect(Rect source, float sx, float sy, int width, int height) {
        if (source == null || source.isEmpty()) return new Rect();
        int left = Math.max(0, Math.min(width - 1, Math.round(source.left * sx)));
        int top = Math.max(0, Math.min(height - 1, Math.round(source.top * sy)));
        int right = Math.max(left + 1, Math.min(width, Math.round(source.right * sx)));
        int bottom = Math.max(top + 1, Math.min(height, Math.round(source.bottom * sy)));
        return new Rect(left, top, right, bottom);
    }

    private static Rect union(List<OcrDocument.CharUnit> chars, Rect fallback) {
        Rect out = null;
        if (chars != null) {
            for (OcrDocument.CharUnit c : chars) {
                if (c == null || c.bounds().isEmpty()) continue;
                if (out == null) out = c.bounds(); else out.union(c.bounds());
            }
        }
        return out == null || out.isEmpty() ? new Rect(fallback) : out;
    }

    private static int appendSplit(List<OcrDocument.CharUnit> out, String value, Rect box,
                                   int line, int group, int startOrder) {
        if (value == null || value.isEmpty() || box == null || box.isEmpty()) return 0;
        int[] cps = value.codePoints().toArray();
        int visible = 0;
        for (int cp : cps) if (!Character.isWhitespace(cp)) visible++;
        if (visible <= 0) return 0;
        int index = 0;
        for (int cp : cps) {
            if (Character.isWhitespace(cp)) continue;
            int left = box.left + box.width() * index / visible;
            int right = box.left + box.width() * (index + 1) / visible;
            out.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)),
                    new Rect(left, box.top, Math.max(left + 1, right), box.bottom),
                    0.62f, line, group, startOrder + index));
            index++;
        }
        return index;
    }

    private static String compactChars(List<OcrDocument.CharUnit> chars) {
        StringBuilder out = new StringBuilder();
        for (OcrDocument.CharUnit c : chars) out.append(compact(c.text()));
        return out.toString();
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

    private static OcrDocument documentFromLines(List<OcrDocument.Line> source, String engine,
                                                  float confidence, int width, int height) {
        if (source == null || source.isEmpty()) return emptyDocument(engine, width, height);
        ArrayList<OcrDocument.Line> lines = new ArrayList<>(source);
        lines.sort(Comparator.comparingInt((OcrDocument.Line l) -> l.bounds().centerY())
                .thenComparingInt(l -> l.bounds().left));
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (OcrDocument.Line line : lines) {
            if (line == null || line.text().isBlank()) continue;
            if (full.length() > 0) full.append('\n');
            full.append(line.text().trim());
            blocks.add(line.text().trim());
        }
        double score = lines.size() * 5d;
        for (OcrDocument.Line line : lines) score += line.chars().size();
        return new OcrDocument(full.toString(), blocks, lines, engine, confidence, score, width, height);
    }

    private static OcrDocument emptyDocument(String engine, int width, int height) {
        return new OcrDocument("", List.of(), List.of(), engine, 0f, 0d, width, height);
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private static final class RefineResult {
        final OcrDocument document;
        final int refinedChars;
        RefineResult(OcrDocument document, int refinedChars) {
            this.document = document;
            this.refinedChars = refinedChars;
        }
    }
}
