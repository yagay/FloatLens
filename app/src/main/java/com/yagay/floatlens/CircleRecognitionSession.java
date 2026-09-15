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
 * View text and every OCR supplement are normalized into absolute screen coordinates before they
 * enter the text index. Screenshot/crop geometry therefore cannot move already indexed text.
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
    private final ScreenBitmapTransform transform;
    private final Callback callback;
    private long generation;
    private boolean closed;
    private OcrDocument current;
    private CircleTextIndex index;
    private TextRecognizer fastRecognizer;

    CircleRecognitionSession(Context context, Bitmap screenshot,
                             CircleViewTextSnapshot viewSnapshot,
                             ScreenBitmapTransform transform,
                             Callback callback) {
        this.app = context.getApplicationContext();
        this.screenshot = screenshot;
        Rect fallbackDisplay = ScreenGeometry.displayBounds(app);
        this.viewSnapshot = viewSnapshot == null
                ? CircleViewTextSnapshot.empty(fallbackDisplay) : viewSnapshot;
        Rect frame = transform == null ? CircleSelectFrame.contentBounds(app) : transform.screenFrame();
        this.transform = transform == null
                ? new ScreenBitmapTransform(frame,
                        screenshot == null ? 1 : screenshot.getWidth(),
                        screenshot == null ? 1 : screenshot.getHeight())
                : transform;
        this.callback = callback;
    }

    void start() {
        if (closed || screenshot == null || screenshot.isRecycled()) return;
        final long run = ++generation;
        long started = android.os.SystemClock.uptimeMillis();

        try {
            OcrDocument view = viewSnapshot.toScreenDocument();
            if (!isCurrent(run)) return;
            Rect frame = transform.screenFrame();
            index = new CircleTextIndex(view,
                    Math.max(1, frame.width()), Math.max(1, frame.height()));
            current = index.current();
            DiagnosticLog.i(app, "CIRCLE_INDEX",
                    "view snapshot nodes=" + viewSnapshot.nodeCount()
                            + " exactGeometryNodes=" + viewSnapshot.exactGeometryNodeCount()
                            + " chars=" + view.chars().size()
                            + " lines=" + view.lines().size()
                            + " frame=" + frame.toShortString()
                            + " coordinateSpace=" + view.coordinateSpace()
                            + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
            emit(current, Stage.VIEW_SNAPSHOT, false);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "view snapshot failed=" + safe(t));
            Rect frame = transform.screenFrame();
            index = new CircleTextIndex(emptyScreenDocument("view-snapshot", frame),
                    Math.max(1, frame.width()), Math.max(1, frame.height()));
            current = index.current();
            emitFailure(Stage.VIEW_SNAPSHOT, t, false);
        }

        startFastMlKit(run);
    }

    /** Refine one missed screen-space region with the configured high-accuracy OCR engine. */
    void refine(Rect screenRegion) {
        if (closed || screenRegion == null || screenRegion.isEmpty()
                || screenshot == null || screenshot.isRecycled() || index == null) return;
        Rect region = new Rect(screenRegion);
        if (!region.intersect(transform.screenFrame()) || region.isEmpty()) return;
        Rect bitmapRegion = transform.screenToBitmap(region);
        if (bitmapRegion.isEmpty()) return;

        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(screenshot,
                    bitmapRegion.left, bitmapRegion.top,
                    bitmapRegion.width(), bitmapRegion.height());
        } catch (Throwable t) {
            emitFailure(Stage.ROI_PRECISE, t, true);
            return;
        }

        final long run = generation;
        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi precise start screen=" + region.toShortString()
                + " bitmap=" + bitmapRegion.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight());
        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                try {
                    if (!isCurrent(run) || index == null) return;
                    OcrDocument parentBitmap = document.translated(
                            bitmapRegion.left, bitmapRegion.top,
                            screenshot.getWidth(), screenshot.getHeight());
                    OcrDocument screenPatch = transform.documentBitmapToScreen(parentBitmap);
                    index.replaceOcrRegion(screenPatch, region);
                    current = index.current();
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi precise ready engine="
                            + document.engine()
                            + " roiChars=" + screenPatch.chars().size()
                            + " viewChars=" + index.viewDocument().chars().size()
                            + " mergedChars=" + current.chars().size()
                            + " screen=" + region.toShortString()
                            + " coordinateSpace=" + screenPatch.coordinateSpace());
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
                    + " frame=" + transform.screenFrame().toShortString()
                    + " languages=" + languages);

            recognizer.process(InputImage.fromBitmap(screenshot, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            if (!isCurrent(run) || index == null) return;
                            OcrDocument bitmapFast = mlKitDocument(text, engine,
                                    screenshot.getWidth(), screenshot.getHeight());
                            OcrDocument screenFast = transform.documentBitmapToScreen(bitmapFast);
                            index.setFastOcr(screenFast);
                            current = index.current();
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast blind-spot ready engine=" + engine
                                    + " viewChars=" + index.viewDocument().chars().size()
                                    + " ocrChars=" + screenFast.chars().size()
                                    + " mergedChars=" + current.chars().size()
                                    + " coordinateSpace=" + screenFast.coordinateSpace()
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

    /** Build symbol/element geometry in bitmap space before one-time screen normalization. */
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

    private static OcrDocument emptyScreenDocument(String engine, Rect frame) {
        return OcrDocument.screenSpace("", List.of(), List.of(), engine, 0f, 0d,
                Math.max(1, frame == null ? 1 : frame.width()),
                Math.max(1, frame == null ? 1 : frame.height()));
    }

    private void emit(OcrDocument document, Stage stage, boolean fastReady) {
        if (closed || callback == null) return;
        try {
            callback.onUpdate(document == null
                    ? emptyScreenDocument("none", transform.screenFrame()) : document,
                    stage, fastReady);
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
