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
import java.util.List;
import java.util.Set;

/**
 * Per-Circle-Select recognition session.
 *
 * View text is frozen before the overlay is attached and remains authoritative for the whole
 * session. ML Kit is a full-frame blind-spot supplement only; the configured OCR engine is used
 * only for tap-sized ROI refinement, and neither OCR layer is allowed to replace View text.
 */
final class CircleRecognitionSession {
    enum Stage { VIEW_SNAPSHOT, FAST_MLKIT, ROI_PRECISE }

    interface Callback {
        void onUpdate(OcrDocument document, Stage stage, boolean fastReady);
        void onFailure(Stage stage, Throwable error, boolean fastReady);
    }

    private final Context app;
    private final Bitmap screenshot;
    private final CircleViewTextSnapshot viewSnapshot;
    private final Callback callback;
    private long generation;
    private boolean closed;
    private OcrDocument current;
    private CircleTextIndex index;
    private TextRecognizer fastRecognizer;

    CircleRecognitionSession(Context context, Bitmap screenshot, Callback callback) {
        this(context, screenshot, CircleViewTextSnapshotHandoff.consume(), callback);
    }

    CircleRecognitionSession(Context context, Bitmap screenshot,
                             CircleViewTextSnapshot viewSnapshot, Callback callback) {
        this.app = context.getApplicationContext();
        this.screenshot = screenshot;
        this.viewSnapshot = viewSnapshot == null
                ? CircleViewTextSnapshot.empty(new Rect()) : viewSnapshot;
        this.callback = callback;
    }

    void start() {
        if (closed || screenshot == null || screenshot.isRecycled()) return;
        final long run = ++generation;
        long started = android.os.SystemClock.uptimeMillis();

        try {
            OcrDocument view = viewSnapshot.toDocument(screenshot.getWidth(), screenshot.getHeight());
            if (!isCurrent(run)) return;
            index = new CircleTextIndex(view, screenshot.getWidth(), screenshot.getHeight());
            current = index.current();
            DiagnosticLog.i(app, "CIRCLE_INDEX",
                    "view snapshot nodes=" + viewSnapshot.nodeCount()
                            + " exactGeometryNodes=" + viewSnapshot.exactGeometryNodeCount()
                            + " chars=" + view.chars().size()
                            + " lines=" + view.lines().size()
                            + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
            emit(current, Stage.VIEW_SNAPSHOT, false);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "view snapshot failed=" + safe(t));
            index = new CircleTextIndex(emptyDocument("view-snapshot"),
                    screenshot.getWidth(), screenshot.getHeight());
            current = index.current();
            emitFailure(Stage.VIEW_SNAPSHOT, t, false);
        }

        startFastMlKit(run);
    }

    /** Refine only a missed tap-sized ROI with the configured high-accuracy OCR engine. */
    void refine(Rect imageRegion) {
        if (closed || imageRegion == null || imageRegion.isEmpty()
                || screenshot == null || screenshot.isRecycled() || index == null) return;
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
                    if (!isCurrent(run) || index == null) return;
                    OcrDocument translated = document.translated(region.left, region.top,
                            screenshot.getWidth(), screenshot.getHeight());
                    index.replaceOcrRegion(translated, region);
                    current = index.current();
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi precise ready engine="
                            + document.engine()
                            + " roiChars=" + translated.chars().size()
                            + " viewChars=" + index.viewDocument().chars().size()
                            + " mergedChars=" + current.chars().size()
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
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast blind-spot start engine=" + engine
                    + " image=" + screenshot.getWidth() + "x" + screenshot.getHeight()
                    + " languages=" + languages);

            recognizer.process(InputImage.fromBitmap(screenshot, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            if (!isCurrent(run) || index == null) return;
                            OcrDocument fast = mlKitDocument(text, engine,
                                    screenshot.getWidth(), screenshot.getHeight());
                            index.setFastOcr(fast);
                            current = index.current();
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast blind-spot ready engine=" + engine
                                    + " viewChars=" + index.viewDocument().chars().size()
                                    + " ocrChars=" + fast.chars().size()
                                    + " mergedChars=" + current.chars().size()
                                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                            emit(current, Stage.FAST_MLKIT, true);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    })
                    .addOnFailureListener(error -> {
                        try {
                            if (!isCurrent(run)) return;
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast blind-spot failed=" + safe(error));
                            emitFailure(Stage.FAST_MLKIT, error, true);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    });
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast blind-spot init failed=" + safe(t));
            emitFailure(Stage.FAST_MLKIT, t, true);
        }
    }

    private void closeFastRecognizer(TextRecognizer recognizer) {
        if (fastRecognizer == recognizer) fastRecognizer = null;
        try { recognizer.close(); } catch (Throwable ignored) {}
    }

    /** Build symbol/element geometry for OCR-only blind spots. */
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

    private static String compact(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            out.appendCodePoint(Character.toLowerCase(cp));
        }
        return out.toString();
    }

    private static OcrDocument emptyDocument(String engine) {
        return new OcrDocument("", List.of(), List.of(), engine, 0f, 0d, 1, 1);
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
