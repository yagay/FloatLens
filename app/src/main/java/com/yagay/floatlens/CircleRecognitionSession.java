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
import java.util.Set;

/**
 * Per-Circle-Select recognition session.
 *
 * This is deliberately not a second OCR engine. It is the interaction index for the frozen screen:
 * Accessibility text is published first, one original-frame ML Kit pass fills visual gaps, and the
 * existing OcrEngine is used only for small on-demand ROI refinement. There is never a background
 * full-screen PP-OCR pass in Circle Select.
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

    /**
     * Refine only a missed tap-sized ROI with the user's configured high-accuracy OCR engine.
     * The returned crop-space document is translated and merged into the current page index.
     */
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
                    current = replaceRegion(current, translated, region);
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
                            current = mergePreferExisting(current, fast);
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast ready engine=" + engine
                                    + " chars=" + fast.chars().size()
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
                            1f, lineId, localGroup, order++));
                    visibleIndex++;
                }
                if (!chars.isEmpty()) {
                    lines.add(new OcrDocument.Line(row, lineRect, 1f, chars));
                    lineId++;
                }
                rowIndex++;
            }
        }
        return documentFromLines(lines, "accessibility", 1f,
                screenshot.getWidth(), screenshot.getHeight());
    }

    private static OcrDocument mlKitDocument(Text text, String engine, int width, int height) {
        ArrayList<DraftLine> drafts = new ArrayList<>();
        if (text != null) {
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String lineText = line.getText() == null ? "" : line.getText().trim();
                    Rect lineBox = line.getBoundingBox() == null ? new Rect() : new Rect(line.getBoundingBox());
                    if (lineText.isEmpty() || lineBox.isEmpty()) continue;
                    ArrayList<DraftChar> chars = new ArrayList<>();
                    int group = 0;
                    for (Text.Element element : line.getElements()) {
                        String value = element.getText() == null ? "" : element.getText();
                        Rect elementBox = element.getBoundingBox() == null ? new Rect() : new Rect(element.getBoundingBox());
                        if (value.isBlank() || elementBox.isEmpty()) continue;
                        ArrayList<DraftChar> symbolsOut = new ArrayList<>();
                        StringBuilder symbolsText = new StringBuilder();
                        try {
                            List<Text.Symbol> symbols = element.getSymbols();
                            if (symbols != null) for (Text.Symbol symbol : symbols) {
                                if (symbol == null || symbol.getText() == null || symbol.getText().isBlank()
                                        || symbol.getBoundingBox() == null || symbol.getBoundingBox().isEmpty()) continue;
                                symbolsText.append(symbol.getText());
                                symbolsOut.add(new DraftChar(symbol.getText(), symbol.getBoundingBox(), group));
                            }
                        } catch (Throwable ignored) {}
                        if (!symbolsOut.isEmpty()
                                && symbolsText.toString().replace(" ", "").equals(value.replace(" ", ""))) {
                            chars.addAll(symbolsOut);
                        } else {
                            split(chars, value, elementBox, group);
                        }
                        group++;
                    }
                    if (chars.isEmpty()) split(chars, lineText, lineBox, 0);
                    drafts.add(new DraftLine(lineText, lineBox, chars));
                }
            }
        }

        drafts.sort(Comparator.comparingInt((DraftLine d) -> d.bounds.centerY())
                .thenComparingInt(d -> d.bounds.left));
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int order = 0;
        int globalGroup = 0;
        for (int li = 0; li < drafts.size(); li++) {
            DraftLine d = drafts.get(li);
            d.chars.sort(Comparator.comparingInt((DraftChar c) -> c.bounds.left)
                    .thenComparingInt(c -> c.bounds.top));
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            int lastLocalGroup = Integer.MIN_VALUE;
            int mappedGroup = globalGroup++;
            for (DraftChar c : d.chars) {
                if (c.text == null || c.text.isBlank() || c.bounds.isEmpty()) continue;
                if (c.group != lastLocalGroup) {
                    mappedGroup = globalGroup++;
                    lastLocalGroup = c.group;
                }
                chars.add(new OcrDocument.CharUnit(c.text, c.bounds, 0.55f,
                        li, mappedGroup, order++));
            }
            if (!chars.isEmpty()) lines.add(new OcrDocument.Line(d.text, d.bounds, 0.55f, chars));
        }
        return documentFromLines(lines, engine, 0.55f, width, height);
    }

    /** Accessibility wins where it already exposes text; ML Kit only fills uncovered visual text. */
    private static OcrDocument mergePreferExisting(OcrDocument primary, OcrDocument supplement) {
        if (primary == null || primary.lines().isEmpty()) return supplement;
        if (supplement == null || supplement.lines().isEmpty()) return primary;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>(primary.lines());
        for (OcrDocument.Line candidate : supplement.lines()) {
            Rect cb = candidate.bounds();
            boolean duplicate = false;
            for (OcrDocument.Line existing : primary.lines()) {
                Rect eb = existing.bounds();
                Rect overlap = new Rect();
                if (!overlap.setIntersect(cb, eb)) continue;
                long overlapArea = (long) overlap.width() * overlap.height();
                long candidateArea = Math.max(1L, (long) cb.width() * cb.height());
                long existingArea = Math.max(1L, (long) eb.width() * eb.height());
                float coverage = overlapArea / (float) Math.min(candidateArea, existingArea);
                if (coverage >= 0.48f) { duplicate = true; break; }
            }
            if (!duplicate) lines.add(candidate);
        }
        return documentFromLines(lines, primary.engine() + "+" + supplement.engine(),
                Math.max(primary.confidence(), supplement.confidence()),
                primary.imageWidth(), primary.imageHeight());
    }

    private static OcrDocument replaceRegion(OcrDocument base, OcrDocument patch, Rect region) {
        if (patch == null || patch.lines().isEmpty()) return base == null ? patch : base;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        if (base != null) {
            for (OcrDocument.Line line : base.lines()) {
                Rect r = line.bounds();
                if (!Rect.intersects(r, region)) lines.add(line);
            }
        }
        lines.addAll(patch.lines());
        return documentFromLines(lines, patch.engine() + "+index",
                patch.confidence(), patch.imageWidth(), patch.imageHeight());
    }

    private static OcrDocument documentFromLines(List<OcrDocument.Line> source, String engine,
                                                  float confidence, int width, int height) {
        if (source == null || source.isEmpty()) {
            return new OcrDocument("", List.of(), List.of(), engine, confidence, 0d, width, height);
        }
        ArrayList<OcrDocument.Line> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparingInt((OcrDocument.Line l) -> l.bounds().centerY())
                .thenComparingInt(l -> l.bounds().left));
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (OcrDocument.Line line : sorted) {
            if (line == null || line.text() == null || line.text().isBlank()) continue;
            if (full.length() > 0) full.append('\n');
            full.append(line.text().trim());
            blocks.add(line.text().trim());
        }
        double score = sorted.size() * 5d;
        for (OcrDocument.Line line : sorted) score += line.chars().size();
        return new OcrDocument(full.toString(), blocks, sorted, engine, confidence, score, width, height);
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

    private static void split(List<DraftChar> out, String value, Rect box, int group) {
        if (value == null || value.isEmpty() || box == null || box.isEmpty()) return;
        int[] cps = value.codePoints().toArray();
        int visible = 0;
        for (int cp : cps) if (!Character.isWhitespace(cp)) visible++;
        int index = 0;
        for (int cp : cps) {
            if (Character.isWhitespace(cp)) continue;
            int left = box.left + box.width() * index / Math.max(1, visible);
            int right = box.left + box.width() * (index + 1) / Math.max(1, visible);
            out.add(new DraftChar(new String(Character.toChars(cp)),
                    new Rect(left, box.top, Math.max(left + 1, right), box.bottom), group));
            index++;
        }
    }

    private void emit(OcrDocument document, Stage stage, boolean fastReady) {
        if (closed || callback == null) return;
        try { callback.onUpdate(document == null ? emptyDocument("none") : document, stage, fastReady); }
        catch (Throwable t) { DiagnosticLog.i(app, "CIRCLE_INDEX", "callback failed=" + safe(t)); }
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

    private static final class DraftLine {
        final String text;
        final Rect bounds;
        final ArrayList<DraftChar> chars;
        DraftLine(String text, Rect bounds, ArrayList<DraftChar> chars) {
            this.text = text;
            this.bounds = new Rect(bounds);
            this.chars = chars;
        }
    }

    private static final class DraftChar {
        final String text;
        final Rect bounds;
        final int group;
        DraftChar(String text, Rect bounds, int group) {
            this.text = text;
            this.bounds = new Rect(bounds);
            this.group = group;
        }
    }
}
