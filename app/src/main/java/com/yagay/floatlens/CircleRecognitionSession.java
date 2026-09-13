package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-Circle-Select recognition session.
 *
 * Accessibility View text is authoritative whenever available. Image OCR is used only to fill
 * regions/content that do not expose usable View text. The configured OcrEngine is reserved for
 * small on-demand ROI refinement and must not replace existing View text.
 */
final class CircleRecognitionSession {
    enum Stage { ACCESSIBILITY, FAST_MLKIT, ROI_PRECISE }

    interface Callback {
        void onUpdate(OcrDocument document, Stage stage, boolean fastReady);
        void onFailure(Stage stage, Throwable error, boolean fastReady);
    }

    private final Context app;
    private final Bitmap screenshot;
    private final Callback callback;
    private long generation;
    private boolean closed;
    private OcrDocument current;
    private TextRecognizer fastRecognizer;

    CircleRecognitionSession(Context context, Bitmap screenshot, Callback callback) {
        this.app = context.getApplicationContext();
        this.screenshot = screenshot;
        this.callback = callback;
    }

    void start() {
        if (closed || screenshot == null || screenshot.isRecycled()) return;
        final long run = ++generation;
        long started = android.os.SystemClock.uptimeMillis();

        try {
            OcrDocument accessibility = accessibilityDocument();
            if (!isCurrent(run)) return;
            current = accessibility;
            DiagnosticLog.i(app, "CIRCLE_INDEX", "accessibility chars=" + accessibility.chars().size()
                    + " lines=" + accessibility.lines().size()
                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
            emit(accessibility, Stage.ACCESSIBILITY, false);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "accessibility failed=" + safe(t));
            emitFailure(Stage.ACCESSIBILITY, t, false);
        }

        startFastMlKit(run);
    }

    /** Refine only a missed tap/line ROI with the configured high-accuracy OCR engine. */
    void refine(Rect imageRegion) {
        if (closed || imageRegion == null || imageRegion.isEmpty()
                || screenshot == null || screenshot.isRecycled()) return;
        Rect region = new Rect(imageRegion);
        if (!region.intersect(0, 0, screenshot.getWidth(), screenshot.getHeight()) || region.isEmpty()) return;

        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(screenshot, region.left, region.top, region.width(), region.height());
        } catch (Throwable t) {
            emitFailure(Stage.ROI_PRECISE, t, true);
            return;
        }

        final long run = generation;
        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi precise start=" + region.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight());
        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                try {
                    if (!isCurrent(run)) return;
                    OcrDocument translated = document.translated(region.left, region.top,
                            screenshot.getWidth(), screenshot.getHeight());
                    current = replaceRegionPreservingView(current, translated, region);
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi precise ready engine="
                            + document.engine() + " chars=" + document.chars().size()
                            + " region=" + region.toShortString());
                    emit(current, Stage.ROI_PRECISE, true);
                } finally {
                    if (!crop.isRecycled()) crop.recycle();
                }
            }

            @Override public void onFailure(Throwable error) {
                if (!crop.isRecycled()) crop.recycle();
                if (!isCurrent(run)) return;
                DiagnosticLog.i(app, "CIRCLE_INDEX", "roi precise failed=" + safe(error));
                emitFailure(Stage.ROI_PRECISE, error, true);
            }
        });
    }

    void cancel() {
        if (closed) return;
        closed = true;
        generation++;
        TextRecognizer recognizer = fastRecognizer;
        fastRecognizer = null;
        if (recognizer != null) {
            try { recognizer.close(); } catch (Throwable ignored) {}
        }
        DiagnosticLog.i(app, "CIRCLE_INDEX", "session cancelled");
    }

    private void startFastMlKit(long run) {
        if (!isCurrent(run)) return;
        long started = android.os.SystemClock.uptimeMillis();
        try {
            Set<String> languages = OcrLanguages.get(app);
            boolean chinese = OcrLanguages.chineseEnabled(languages);
            boolean english = OcrLanguages.englishEnabled(languages);
            if (!chinese && !english) chinese = true;

            TextRecognizer recognizer = chinese
                    ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                    : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            fastRecognizer = recognizer;
            String engine = chinese ? "fast-mlkit-zh" : "fast-mlkit-latin";
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast start engine=" + engine
                    + " image=" + screenshot.getWidth() + "x" + screenshot.getHeight()
                    + " languages=" + languages);

            recognizer.process(InputImage.fromBitmap(screenshot, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            if (!isCurrent(run)) return;
                            OcrDocument fast = mlKitDocument(text, engine,
                                    screenshot.getWidth(), screenshot.getHeight());
                            OcrDocument viewFirst = current;
                            current = mergePreferViewText(viewFirst, fast);
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast ready engine=" + engine
                                    + " ocrChars=" + fast.chars().size()
                                    + " viewChars=" + (viewFirst == null ? 0 : viewFirst.chars().size())
                                    + " merged=" + (current == null ? 0 : current.chars().size())
                                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                            emit(current, Stage.FAST_MLKIT, true);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    })
                    .addOnFailureListener(error -> {
                        try {
                            if (!isCurrent(run)) return;
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast failed=" + safe(error));
                            emitFailure(Stage.FAST_MLKIT, error, true);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    });
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast init failed=" + safe(t));
            emitFailure(Stage.FAST_MLKIT, t, true);
        }
    }

    private void closeFastRecognizer(TextRecognizer recognizer) {
        if (fastRecognizer == recognizer) fastRecognizer = null;
        try { recognizer.close(); } catch (Throwable ignored) {}
    }

    /** Build authoritative selectable text directly from Accessibility Views. */
    private OcrDocument accessibilityDocument() {
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) return emptyDocument("accessibility");
        List<ScreenCandidate> candidates = AccessibilityCandidateCollector.collect(service);
        Rect screen = service.screenBounds();
        if (screen == null || screen.isEmpty()) {
            screen = new Rect(0, 0, screenshot.getWidth(), screenshot.getHeight());
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        int lineId = 0;
        int group = 0;
        int order = 0;
        for (ScreenCandidate candidate : candidates) {
            if (candidate == null || candidate.type() != ScreenCandidate.Type.TEXT || !candidate.hasText()) continue;
            Rect mapped = mapScreenRect(candidate.bounds(), screen,
                    screenshot.getWidth(), screenshot.getHeight());
            if (mapped.isEmpty()) continue;
            String text = candidate.text() == null ? "" : candidate.text().trim();
            if (text.isEmpty()) continue;
            String key = mapped.flattenToString() + "\u0000" + text;
            if (!seen.add(key)) continue;

            String[] rows = text.split("\\R", -1);
            int nonEmptyRows = 0;
            for (String row : rows) if (!row.trim().isEmpty()) nonEmptyRows++;
            if (nonEmptyRows == 0) continue;
            int rowIndex = 0;
            for (String rawRow : rows) {
                String row = rawRow.trim();
                if (row.isEmpty()) continue;
                int top = mapped.top + mapped.height() * rowIndex / nonEmptyRows;
                int bottom = mapped.top + mapped.height() * (rowIndex + 1) / nonEmptyRows;
                Rect lineRect = new Rect(mapped.left, top, mapped.right, Math.max(top + 1, bottom));
                ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
                int[] cps = row.codePoints().toArray();
                int visible = 0;
                for (int cp : cps) if (!Character.isWhitespace(cp)) visible++;
                int visibleIndex = 0;
                int localGroup = group++;
                for (int cp : cps) {
                    if (Character.isWhitespace(cp)) {
                        localGroup = group++;
                        continue;
                    }
                    int left = lineRect.left + lineRect.width() * visibleIndex / Math.max(1, visible);
                    int right = lineRect.left + lineRect.width() * (visibleIndex + 1) / Math.max(1, visible);
                    chars.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)),
                            new Rect(left, lineRect.top, Math.max(left + 1, right), lineRect.bottom),
                            1.0f, lineId, localGroup, order++, OcrDocument.Source.VIEW));
                    visibleIndex++;
                }
                if (!chars.isEmpty()) {
                    lines.add(new OcrDocument.Line(row, lineRect, 1.0f, chars, OcrDocument.Source.VIEW));
                    lineId++;
                }
                rowIndex++;
            }
        }
        return documentFromLines(lines, "accessibility-view", 1.0f,
                screenshot.getWidth(), screenshot.getHeight());
    }

    /** Build OCR-only symbol geometry from the screenshot. */
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
                        Rect elementBox = element.getBoundingBox() == null
                                ? new Rect() : new Rect(element.getBoundingBox());
                        if (value.isBlank() || elementBox.isEmpty()) continue;

                        int elementGroup = group++;
                        ArrayList<OcrDocument.CharUnit> symbolsOut = new ArrayList<>();
                        StringBuilder symbolsText = new StringBuilder();
                        try {
                            List<Text.Symbol> symbols = element.getSymbols();
                            if (symbols != null) {
                                for (Text.Symbol symbol : symbols) {
                                    if (symbol == null || symbol.getText() == null || symbol.getText().isBlank()
                                            || symbol.getBoundingBox() == null || symbol.getBoundingBox().isEmpty()) continue;
                                    symbolsText.append(symbol.getText());
                                    symbolsOut.add(new OcrDocument.CharUnit(symbol.getText(),
                                            symbol.getBoundingBox(), 0.72f, lineId, elementGroup, order++));
                                }
                            }
                        } catch (Throwable ignored) {}

                        if (!symbolsOut.isEmpty()
                                && compact(symbolsText.toString()).equals(compact(value))) {
                            chars.addAll(symbolsOut);
                        } else {
                            order -= symbolsOut.size();
                            appendSplit(chars, value, elementBox, lineId, elementGroup, order);
                            order += countVisible(value);
                        }
                    }

                    if (chars.isEmpty()) {
                        int lineGroup = group++;
                        appendSplit(chars, lineText, lineBox, lineId, lineGroup, order);
                        order += countVisible(lineText);
                    }
                    if (!chars.isEmpty()) {
                        chars.sort(Comparator.comparingInt((OcrDocument.CharUnit c) -> c.bounds().left)
                                .thenComparingInt(c -> c.bounds().top));
                        lines.add(new OcrDocument.Line(lineText, lineBox, 0.72f, chars));
                        lineId++;
                    }
                }
            }
        }
        return documentFromLines(lines, engine, 0.72f, width, height);
    }

    /**
     * Accessibility View text wins only for OCR lines that represent the same visible text.
     * Geometry alone never suppresses OCR, so a mixed image+text View can still expose independent
     * text that exists inside the image portion while its View-provided label avoids re-OCR.
     */
    private static OcrDocument mergePreferViewText(OcrDocument viewText, OcrDocument ocr) {
        if (viewText == null || viewText.lines().isEmpty()) return ocr;
        if (ocr == null || ocr.lines().isEmpty()) return viewText;

        ArrayList<OcrDocument.Line> lines = new ArrayList<>(viewText.lines());
        for (OcrDocument.Line ocrLine : ocr.lines()) {
            if (ocrLine == null || ocrLine.bounds().isEmpty()) continue;
            boolean duplicateOfViewText = false;
            for (OcrDocument.Line viewLine : viewText.lines()) {
                if (viewLine == null || viewLine.source() != OcrDocument.Source.VIEW
                        || viewLine.bounds().isEmpty()) continue;
                if (sameViewAndOcrText(viewLine, ocrLine)) {
                    duplicateOfViewText = true;
                    break;
                }
            }
            if (!duplicateOfViewText) lines.add(ocrLine);
        }

        return documentFromLines(lines,
                "accessibility-view+" + ocr.engine() + "-fallback",
                Math.max(viewText.confidence(), ocr.confidence()),
                ocr.imageWidth(), ocr.imageHeight());
    }

    private static boolean sameViewAndOcrText(OcrDocument.Line viewLine, OcrDocument.Line ocrLine) {
        Rect vb = viewLine.bounds();
        Rect ob = ocrLine.bounds();
        Rect overlap = new Rect();
        if (!overlap.setIntersect(vb, ob)) return false;

        long overlapArea = Math.max(0L, (long) overlap.width() * overlap.height());
        long ocrArea = Math.max(1L, (long) ob.width() * ob.height());
        float ocrCoverage = overlapArea / (float) ocrArea;
        if (ocrCoverage < 0.18f && !vb.contains(ob.centerX(), ob.centerY())) return false;

        String viewCompact = compact(viewLine.text());
        String ocrCompact = compact(ocrLine.text());
        String viewSemantic = semanticCompact(viewLine.text());
        String ocrSemantic = semanticCompact(ocrLine.text());

        boolean exactOrContained = !viewCompact.isEmpty() && !ocrCompact.isEmpty()
                && (viewCompact.equals(ocrCompact)
                || viewCompact.contains(ocrCompact)
                || ocrCompact.contains(viewCompact));
        if (exactOrContained) return true;

        // OCR often differs only by punctuation/whitespace. Ignore those for duplicate detection,
        // but require at least two semantic code points so unrelated one-character image text is
        // not accidentally suppressed inside a broad mixed View.
        int semanticMin = Math.min(codePointCount(viewSemantic), codePointCount(ocrSemantic));
        return semanticMin >= 2
                && !viewSemantic.isEmpty() && !ocrSemantic.isEmpty()
                && (viewSemantic.equals(ocrSemantic)
                || viewSemantic.contains(ocrSemantic)
                || ocrSemantic.contains(viewSemantic));
    }

    private static OcrDocument replaceRegionPreservingView(OcrDocument base,
                                                            OcrDocument patch,
                                                            Rect region) {
        if (patch == null || patch.lines().isEmpty()) return base == null ? patch : base;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<OcrDocument.Line> viewLines = new ArrayList<>();
        if (base != null) {
            for (OcrDocument.Line line : base.lines()) {
                if (line == null || line.bounds().isEmpty()) continue;
                if (line.source() == OcrDocument.Source.VIEW) {
                    lines.add(line);
                    viewLines.add(line);
                } else if (!Rect.intersects(line.bounds(), region)) {
                    lines.add(line);
                }
            }
        }

        for (OcrDocument.Line patchLine : patch.lines()) {
            if (patchLine == null || patchLine.bounds().isEmpty()) continue;
            boolean duplicateOfViewText = false;
            for (OcrDocument.Line viewLine : viewLines) {
                if (sameViewAndOcrText(viewLine, patchLine)) {
                    duplicateOfViewText = true;
                    break;
                }
            }
            if (!duplicateOfViewText) lines.add(patchLine);
        }

        return documentFromLines(lines, patch.engine() + "+view-preserved-index",
                patch.confidence(), patch.imageWidth(), patch.imageHeight());
    }

    private static OcrDocument documentFromLines(List<OcrDocument.Line> source, String engine,
                                                  float confidence, int width, int height) {
        if (source == null || source.isEmpty()) {
            return new OcrDocument("", List.of(), List.of(), engine, confidence, 0d, width, height);
        }
        ArrayList<OcrDocument.Line> sorted = new ArrayList<>();
        for (OcrDocument.Line line : source) {
            if (line != null && !line.text().isBlank() && !line.bounds().isEmpty()) sorted.add(line);
        }
        sorted.sort(Comparator.comparingInt((OcrDocument.Line l) -> l.bounds().centerY())
                .thenComparingInt(l -> l.bounds().left));

        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (OcrDocument.Line line : sorted) {
            if (full.length() > 0) full.append('\n');
            full.append(line.text().trim());
            blocks.add(line.text().trim());
        }
        double score = sorted.size() * 5d;
        for (OcrDocument.Line line : sorted) score += line.chars().size();
        return new OcrDocument(full.toString(), blocks, sorted, engine,
                confidence, score, width, height);
    }

    private static void appendSplit(List<OcrDocument.CharUnit> out, String value, Rect box,
                                    int line, int group, int startOrder) {
        if (value == null || value.isEmpty() || box == null || box.isEmpty()) return;
        int[] cps = value.codePoints().toArray();
        int visible = countVisible(value);
        if (visible <= 0) return;
        int index = 0;
        for (int cp : cps) {
            if (Character.isWhitespace(cp)) continue;
            int left = box.left + box.width() * index / visible;
            int right = box.left + box.width() * (index + 1) / visible;
            out.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)),
                    new Rect(left, box.top, Math.max(left + 1, right), box.bottom),
                    0.58f, line, group, startOrder + index));
            index++;
        }
    }

    private static int countVisible(String value) {
        if (value == null || value.isEmpty()) return 0;
        int count = 0;
        for (int cp : value.codePoints().toArray()) if (!Character.isWhitespace(cp)) count++;
        return count;
    }

    private static int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
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

    private static String semanticCompact(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (!Character.isLetterOrDigit(cp) && !isCjk(cp)) continue;
            out.appendCodePoint(Character.toLowerCase(cp));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static OcrDocument emptyDocument(String engine) {
        return new OcrDocument("", List.of(), List.of(), engine, 0f, 0d, 1, 1);
    }

    private static Rect mapScreenRect(Rect source, Rect screen, int imageWidth, int imageHeight) {
        if (source == null || source.isEmpty() || screen == null || screen.isEmpty()) return new Rect();
        Rect clipped = new Rect(source);
        if (!clipped.intersect(screen)) return new Rect();
        float sx = imageWidth / (float) Math.max(1, screen.width());
        float sy = imageHeight / (float) Math.max(1, screen.height());
        int left = Math.max(0, Math.min(imageWidth - 1, Math.round((clipped.left - screen.left) * sx)));
        int top = Math.max(0, Math.min(imageHeight - 1, Math.round((clipped.top - screen.top) * sy)));
        int right = Math.max(left + 1, Math.min(imageWidth, Math.round((clipped.right - screen.left) * sx)));
        int bottom = Math.max(top + 1, Math.min(imageHeight, Math.round((clipped.bottom - screen.top) * sy)));
        return new Rect(left, top, right, bottom);
    }

    private void emit(OcrDocument document, Stage stage, boolean fastReady) {
        if (closed || callback == null) return;
        try {
            callback.onUpdate(document == null ? emptyDocument("none") : document, stage, fastReady);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "callback failed=" + safe(t));
        }
    }

    private void emitFailure(Stage stage, Throwable error, boolean fastReady) {
        if (closed || callback == null) return;
        try { callback.onFailure(stage, error, fastReady); }
        catch (Throwable ignored) {}
    }

    private boolean isCurrent(long run) {
        return !closed && run == generation && screenshot != null && !screenshot.isRecycled();
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
