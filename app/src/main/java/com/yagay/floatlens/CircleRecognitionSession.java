package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

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
    enum Stage { ACCESSIBILITY, FAST_MLKIT, ML_RESCUE, FULL_DETECTOR, ROI_PRECISE }

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
    private boolean fullDetectorStarted;

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
     * Refine a missed tap/line ROI with ML Kit first. Original geometry wins; enhanced/inverted/
     * binary passes are attempted only when earlier ML passes return no text. The configured OCR
     * engine (which may be Paddle) is a final fallback, not the primary geometry source.
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
        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi ml-first start=" + region.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight());
        runRegionMlPass(run, crop, region,
                new int[] { OcrImagePreprocessor.MODE_ORIGINAL,
                        OcrImagePreprocessor.MODE_ENHANCED,
                        OcrImagePreprocessor.MODE_INVERTED,
                        OcrImagePreprocessor.MODE_BINARY }, 0);
    }

    private void runRegionMlPass(long run, Bitmap crop, Rect region, int[] modes, int index) {
        if (!isCurrent(run)) {
            if (crop != null && !crop.isRecycled()) crop.recycle();
            return;
        }
        if (index >= modes.length) {
            runRegionConfiguredFallback(run, crop, region);
            return;
        }

        final int mode = modes[index];
        final boolean chinese = useChineseRecognizer();
        Thread prep = new Thread(() -> {
            OcrImagePreprocessor.Prepared prepared = OcrImagePreprocessor.prepare(crop, mode);
            if (prepared == null || prepared.bitmap == null || prepared.bitmap.isRecycled()) {
                runRegionMlPass(run, crop, region, modes, index + 1);
                return;
            }
            TextRecognizer recognizer = null;
            try {
                recognizer = chinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                final TextRecognizer client = recognizer;
                String engine = "roi-ml-" + (chinese ? "zh-" : "latin-")
                        + OcrImagePreprocessor.modeName(mode).toLowerCase(Locale.ROOT);
                client.process(InputImage.fromBitmap(prepared.bitmap, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                if (!isCurrent(run)) return;
                                OcrDocument doc = mlKitPreparedDocument(text, engine, prepared,
                                        crop.getWidth(), crop.getHeight());
                                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "roi pass=" + engine
                                        + " chars=" + doc.chars().size()
                                        + " lines=" + doc.lines().size()
                                        + " prepared=" + prepared.bitmap.getWidth() + "x"
                                        + prepared.bitmap.getHeight());
                                if (!doc.chars().isEmpty()) {
                                    OcrDocument translated = doc.translated(region.left, region.top,
                                            screenshot.getWidth(), screenshot.getHeight());
                                    current = replaceRegionPreservingView(current, translated, region);
                                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi ml-first ready engine="
                                            + engine + " chars=" + doc.chars().size()
                                            + " region=" + region.toShortString());
                                    emit(current, Stage.ROI_PRECISE, true);
                                    if (!crop.isRecycled()) crop.recycle();
                                } else {
                                    runRegionMlPass(run, crop, region, modes, index + 1);
                                }
                            } finally {
                                try { client.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                        })
                        .addOnFailureListener(error -> {
                            try {
                                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "roi pass=" + engine
                                        + " failed=" + safe(error));
                            } finally {
                                try { client.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            runRegionMlPass(run, crop, region, modes, index + 1);
                        });
            } catch (Throwable t) {
                if (recognizer != null) try { recognizer.close(); } catch (Throwable ignored) {}
                OcrImagePreprocessor.recycle(prepared);
                runRegionMlPass(run, crop, region, modes, index + 1);
            }
        }, "FloatLens-Circle-ROI-ML");
        prep.setPriority(Thread.NORM_PRIORITY - 1);
        prep.start();
    }

    private void runRegionConfiguredFallback(long run, Bitmap crop, Rect region) {
        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi ML empty -> configured OCR fallback region="
                + region.toShortString());
        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                try {
                    if (!isCurrent(run)) return;
                    OcrDocument translated = document.translated(region.left, region.top,
                            screenshot.getWidth(), screenshot.getHeight());
                    current = replaceRegionPreservingView(current, translated, region);
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi fallback ready engine="
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
                DiagnosticLog.i(app, "CIRCLE_INDEX", "roi fallback failed=" + safe(error));
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
                            startMlRescue(run);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    })
                    .addOnFailureListener(error -> {
                        try {
                            if (!isCurrent(run)) return;
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast failed=" + safe(error));
                            emitFailure(Stage.FAST_MLKIT, error, true);
                            startMlRescue(run);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    });
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast init failed=" + safe(t));
            emitFailure(Stage.FAST_MLKIT, t, true);
            startMlRescue(run);
        }
    }

    private boolean useChineseRecognizer() {
        Set<String> languages = OcrLanguages.get(app);
        boolean chinese = OcrLanguages.chineseEnabled(languages);
        boolean english = OcrLanguages.englishEnabled(languages);
        if (!chinese && !english) chinese = true;
        return chinese;
    }

    /**
     * Build the persistent image-text cache entirely with ML Kit first. Later passes only add text
     * missed by earlier passes, so original ML geometry remains authoritative when results overlap.
     */
    private void startMlRescue(long run) {
        if (!isCurrent(run) || screenshot == null || screenshot.isRecycled()) return;
        int[] modes = new int[] {
                OcrImagePreprocessor.MODE_ENHANCED,
                OcrImagePreprocessor.MODE_INVERTED,
                OcrImagePreprocessor.MODE_BINARY
        };
        runFullScreenMlPass(run, modes, 0, useChineseRecognizer());
    }

    private void runFullScreenMlPass(long run, int[] modes, int index, boolean chinese) {
        if (!isCurrent(run)) return;
        if (index >= modes.length) {
            int mlChars = countOcrChars(current);
            if (mlChars == 0) {
                DiagnosticLog.i(app, "CIRCLE_INDEX", "all ML image passes empty -> Paddle fallback");
                startFullScreenDetector(run);
            } else {
                DiagnosticLog.i(app, "CIRCLE_INDEX", "ML-first cache complete ocrChars=" + mlChars
                        + " Paddle=skipped");
            }
            return;
        }

        final int mode = modes[index];
        Thread prep = new Thread(() -> {
            OcrImagePreprocessor.Prepared prepared = OcrImagePreprocessor.prepare(screenshot, mode);
            if (prepared == null || prepared.bitmap == null || prepared.bitmap.isRecycled()) {
                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "full pass="
                        + OcrImagePreprocessor.modeName(mode) + " skipped=prepare_failed");
                runFullScreenMlPass(run, modes, index + 1, chinese);
                return;
            }
            TextRecognizer recognizer = null;
            try {
                recognizer = chinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                final TextRecognizer client = recognizer;
                String engine = "ml-rescue-" + (chinese ? "zh-" : "latin-")
                        + OcrImagePreprocessor.modeName(mode).toLowerCase(Locale.ROOT);
                long started = android.os.SystemClock.uptimeMillis();
                client.process(InputImage.fromBitmap(prepared.bitmap, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                if (!isCurrent(run)) return;
                                OcrDocument doc = mlKitPreparedDocument(text, engine, prepared,
                                        screenshot.getWidth(), screenshot.getHeight());
                                int before = current == null ? 0 : current.chars().size();
                                current = mergeSupplementalOcr(current, doc);
                                int after = current == null ? 0 : current.chars().size();
                                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "full pass=" + engine
                                        + " chars=" + doc.chars().size()
                                        + " lines=" + doc.lines().size()
                                        + " added=" + Math.max(0, after - before)
                                        + " prepared=" + prepared.bitmap.getWidth() + "x"
                                        + prepared.bitmap.getHeight()
                                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                                emit(current, Stage.ML_RESCUE, true);
                            } finally {
                                try { client.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            runFullScreenMlPass(run, modes, index + 1, chinese);
                        })
                        .addOnFailureListener(error -> {
                            try {
                                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "full pass=" + engine
                                        + " failed=" + safe(error));
                            } finally {
                                try { client.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            runFullScreenMlPass(run, modes, index + 1, chinese);
                        });
            } catch (Throwable t) {
                if (recognizer != null) try { recognizer.close(); } catch (Throwable ignored) {}
                OcrImagePreprocessor.recycle(prepared);
                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "full pass="
                        + OcrImagePreprocessor.modeName(mode) + " init_failed=" + safe(t));
                runFullScreenMlPass(run, modes, index + 1, chinese);
            }
        }, "FloatLens-Circle-Full-ML");
        prep.setPriority(Thread.NORM_PRIORITY - 1);
        prep.start();
    }

    private static int countOcrChars(OcrDocument document) {
        if (document == null || document.lines().isEmpty()) return 0;
        int count = 0;
        for (OcrDocument.Line line : document.lines()) {
            if (line != null && line.source() == OcrDocument.Source.OCR) count += line.chars().size();
        }
        return count;
    }

    /**
     * One deep full-screen detector pass supplements the fast ML Kit cache. This runs only when a
     * local PP-OCR model is already installed; it never replaces View text and never waits for a
     * user tap/line before looking for image text.
     */
    private void startFullScreenDetector(long run) {
        if (!isCurrent(run) || fullDetectorStarted || screenshot == null || screenshot.isRecycled()) return;
        int model = OcrModelManager.isReady(app, OcrModelManager.MEDIUM)
                ? OcrModelManager.MEDIUM
                : OcrModelManager.isReady(app, OcrModelManager.SMALL)
                ? OcrModelManager.SMALL : -1;
        if (model < 0) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "full detector skip=no_local_model");
            return;
        }
        fullDetectorStarted = true;
        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "CIRCLE_INDEX", "full detector start model=" + model
                + " image=" + screenshot.getWidth() + "x" + screenshot.getHeight());
        PaddleOcrBridge.recognize(app, screenshot, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(OcrDocument document, long totalMs, int lineCount) {
                if (!isCurrent(run)) return;
                int before = current == null ? 0 : current.chars().size();
                current = mergeSupplementalOcr(current, document);
                DiagnosticLog.i(app, "CIRCLE_INDEX", "full detector ready model=" + model
                        + " detectorChars=" + (document == null ? 0 : document.chars().size())
                        + " mergedBefore=" + before
                        + " mergedAfter=" + (current == null ? 0 : current.chars().size())
                        + " lines=" + lineCount + " totalMs=" + totalMs
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                emit(current, Stage.FULL_DETECTOR, true);
            }

            @Override public void onFailure(String message) {
                if (!isCurrent(run)) return;
                DiagnosticLog.i(app, "CIRCLE_INDEX", "full detector failed model=" + model
                        + " message=" + message);
            }
        });
    }

    private void closeFastRecognizer(TextRecognizer recognizer) {
        if (fastRecognizer == recognizer) fastRecognizer = null;
        try { recognizer.close(); } catch (Throwable ignored) {}
    }

    /**
     * Build View text from Accessibility. Character geometry comes from the platform's
     * EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY whenever the provider supports it. We never invent
     * equal-width character boxes across a whole View: that made short selections span huge areas.
     *
     * If a View exposes text but not character locations, keep an empty-geometry VIEW line. The
     * later screenshot OCR pass may donate geometry to that line while the copied characters still
     * come from the authoritative View string.
     */
    private OcrDocument accessibilityDocument() {
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) return emptyDocument("accessibility");
        Rect screen = service.screenBounds();
        if (screen == null || screen.isEmpty()) {
            screen = new Rect(0, 0, screenshot.getWidth(), screenshot.getHeight());
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        int[] state = new int[] { 0, 0, 0, 0, 0, 0 };
        // 0=lineId, 1=group, 2=order, 3=textNodes, 4=geometryNodes, 5=geometryChars

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root != null) {
                        collectAccessibilityTextNode(root, 0, screen, lines, seen, state);
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (lines.isEmpty()) {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) collectAccessibilityTextNode(root, 0, screen, lines, seen, state);
            } catch (Throwable ignored) {}
        }

        // Some providers expose text through the candidate snapshot but do not return a traversable
        // node tree. Preserve those strings as VIEW lines with no fabricated per-character geometry;
        // OCR can later donate geometry if it sees the same text.
        if (lines.isEmpty()) {
            for (ScreenCandidate candidate : AccessibilityCandidateCollector.collect(service)) {
                if (candidate == null || candidate.type() != ScreenCandidate.Type.TEXT
                        || !candidate.hasText()) continue;
                String text = candidate.text() == null ? "" : candidate.text().trim();
                if (text.isEmpty()) continue;
                Rect mapped = mapScreenRect(candidate.bounds(), screen,
                        screenshot.getWidth(), screenshot.getHeight());
                if (mapped.isEmpty()) continue;
                String key = mapped.flattenToString() + "\u0000" + text;
                if (!seen.add(key)) continue;
                lines.add(new OcrDocument.Line(text, mapped, 1.0f, List.of(),
                        OcrDocument.Source.VIEW));
                state[0]++;
            }
        }

        DiagnosticLog.i(app, "CIRCLE_INDEX", "view geometry textNodes=" + state[3]
                + " geometryNodes=" + state[4] + " geometryChars=" + state[5]
                + " lines=" + lines.size());
        return documentFromLines(lines, "accessibility-view", 1.0f,
                screenshot.getWidth(), screenshot.getHeight());
    }

    private void collectAccessibilityTextNode(AccessibilityNodeInfo node,
                                              int depth,
                                              Rect screen,
                                              List<OcrDocument.Line> lines,
                                              Set<String> seen,
                                              int[] state) {
        if (node == null || depth > 80) return;
        try {
            if (node.isVisibleToUser()) {
                CharSequence raw = node.getText();
                if (raw != null && !raw.toString().trim().isEmpty()) {
                    addAccessibilityTextLine(node, raw.toString(), screen, lines, seen, state);
                }
            }
        } catch (Throwable ignored) {}

        int count = 0;
        try { count = node.getChildCount(); } catch (Throwable ignored) {}
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectAccessibilityTextNode(child, depth + 1, screen, lines, seen, state);
        }
    }

    private void addAccessibilityTextLine(AccessibilityNodeInfo node,
                                          String rawText,
                                          Rect screen,
                                          List<OcrDocument.Line> lines,
                                          Set<String> seen,
                                          int[] state) {
        Rect nodeBounds = new Rect();
        try { node.getBoundsInScreen(nodeBounds); } catch (Throwable ignored) {}
        Rect mappedNode = mapScreenRect(nodeBounds, screen,
                screenshot.getWidth(), screenshot.getHeight());
        if (mappedNode.isEmpty()) return;

        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) return;
        String key = mappedNode.flattenToString() + "\u0000" + text;
        if (!seen.add(key)) return;
        state[3]++;

        ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
        Parcelable[] locations = requestCharacterLocations(node, rawText);
        int localGroup = state[1]++;
        int located = 0;
        Rect union = null;

        for (int offset = 0; offset < rawText.length();) {
            int cp = rawText.codePointAt(offset);
            int charCount = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                localGroup = state[1]++;
                offset += charCount;
                continue;
            }

            Rect mappedChar = new Rect();
            if (locations != null && offset < locations.length && locations[offset] instanceof RectF) {
                RectF rf = (RectF) locations[offset];
                if (rf != null && !rf.isEmpty()) {
                    Rect sr = new Rect((int) Math.floor(rf.left), (int) Math.floor(rf.top),
                            (int) Math.ceil(rf.right), (int) Math.ceil(rf.bottom));
                    mappedChar = mapScreenRect(sr, screen,
                            screenshot.getWidth(), screenshot.getHeight());
                }
            }

            if (!mappedChar.isEmpty()) {
                chars.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)), mappedChar,
                        1.0f, state[0], localGroup, state[2]++, OcrDocument.Source.VIEW));
                if (union == null) union = new Rect(mappedChar); else union.union(mappedChar);
                located++;
            }
            offset += charCount;
        }

        int visibleChars = countVisible(rawText);
        if (located > 0) {
            state[4]++;
            state[5] += located;
        }

        // Require useful real geometry. A provider returning one location for a long label is not
        // enough to make reliable handle selection; keep the View text but let OCR donate geometry.
        boolean usefulGeometry = located > 0
                && (visibleChars <= 3 || located >= Math.max(2, Math.round(visibleChars * 0.55f)));
        if (!usefulGeometry) chars.clear();

        Rect lineBounds = !chars.isEmpty() && union != null && !union.isEmpty()
                ? union : mappedNode;
        lines.add(new OcrDocument.Line(text, lineBounds, 1.0f, chars, OcrDocument.Source.VIEW));
        state[0]++;
    }

    private static Parcelable[] requestCharacterLocations(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null || text.isEmpty() || Build.VERSION.SDK_INT < 26) return null;
        try {
            int length = Math.min(text.length(), 20000);
            if (length <= 0) return null;
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length);
            boolean ok = node.refreshWithExtraData(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args);
            if (!ok) return null;
            return node.getExtras().getParcelableArray(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
        } catch (Throwable ignored) {
            return null;
        }
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

    /** Build ML Kit symbol geometry and map every box back to the unprocessed source bitmap. */
    private static OcrDocument mlKitPreparedDocument(Text text, String engine,
                                                      OcrImagePreprocessor.Prepared prepared,
                                                      int width, int height) {
        if (prepared == null) return emptyDocument(engine);
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int group = 0;
        int order = 0;
        if (text != null) {
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String lineText = line.getText() == null ? "" : line.getText().trim();
                    Rect lineBox = prepared.toSourceRect(line.getBoundingBox());
                    if (lineText.isEmpty() || lineBox.isEmpty()) continue;

                    ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
                    for (Text.Element element : line.getElements()) {
                        String value = element.getText() == null ? "" : element.getText();
                        Rect elementBox = prepared.toSourceRect(element.getBoundingBox());
                        if (value.isBlank() || elementBox.isEmpty()) continue;

                        int elementGroup = group++;
                        ArrayList<OcrDocument.CharUnit> symbolsOut = new ArrayList<>();
                        StringBuilder symbolsText = new StringBuilder();
                        try {
                            List<Text.Symbol> symbols = element.getSymbols();
                            if (symbols != null) {
                                for (Text.Symbol symbol : symbols) {
                                    if (symbol == null || symbol.getText() == null || symbol.getText().isBlank()) continue;
                                    Rect symbolBox = prepared.toSourceRect(symbol.getBoundingBox());
                                    if (symbolBox.isEmpty()) continue;
                                    symbolsText.append(symbol.getText());
                                    symbolsOut.add(new OcrDocument.CharUnit(symbol.getText(), symbolBox,
                                            0.78f, lineId, elementGroup, order++));
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
                        lines.add(new OcrDocument.Line(lineText, lineBox, 0.78f, chars));
                        lineId++;
                    }
                }
            }
        }
        return documentFromLines(lines, engine, 0.78f, width, height);
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
            int matchingView = -1;
            for (int i = 0; i < lines.size(); i++) {
                OcrDocument.Line viewLine = lines.get(i);
                if (viewLine == null || viewLine.source() != OcrDocument.Source.VIEW
                        || viewLine.bounds().isEmpty()) continue;
                if (sameViewAndOcrText(viewLine, ocrLine)) {
                    matchingView = i;
                    break;
                }
            }

            if (matchingView >= 0) {
                OcrDocument.Line viewLine = lines.get(matchingView);
                if (viewLine.chars().isEmpty()) {
                    OcrDocument.Line hydrated = hydrateViewGeometry(viewLine, ocrLine);
                    if (hydrated != null) {
                        lines.set(matchingView, hydrated);
                    } else {
                        // We cannot safely map OCR glyphs to the View string. Keep OCR selectable
                        // instead of fabricating View character boxes; the View line remains as
                        // authoritative text metadata for later matching/refinement.
                        lines.add(ocrLine);
                    }
                }
            } else {
                lines.add(ocrLine);
            }
        }

        return documentFromLines(lines,
                "accessibility-view+" + ocr.engine() + "-geometry",
                Math.max(viewText.confidence(), ocr.confidence()),
                ocr.imageWidth(), ocr.imageHeight());
    }

    /** Merge an additional full-screen OCR detector into the persistent cache. */
    private static OcrDocument mergeSupplementalOcr(OcrDocument base, OcrDocument extra) {
        if (extra == null || extra.lines().isEmpty()) return base;
        if (base == null || base.lines().isEmpty()) return extra;

        ArrayList<OcrDocument.Line> lines = new ArrayList<>(base.lines());
        int added = 0;
        int hydrated = 0;
        int duplicate = 0;
        for (OcrDocument.Line extraLine : extra.lines()) {
            if (extraLine == null || extraLine.bounds().isEmpty() || extraLine.text().isBlank()) continue;

            int matchingView = -1;
            for (int i = 0; i < lines.size(); i++) {
                OcrDocument.Line line = lines.get(i);
                if (line != null && line.source() == OcrDocument.Source.VIEW
                        && sameViewAndOcrText(line, extraLine)) {
                    matchingView = i;
                    break;
                }
            }
            if (matchingView >= 0) {
                OcrDocument.Line viewLine = lines.get(matchingView);
                if (viewLine.chars().isEmpty()) {
                    OcrDocument.Line mapped = hydrateViewGeometry(viewLine, extraLine);
                    if (mapped != null) {
                        lines.set(matchingView, mapped);
                        hydrated++;
                    }
                }
                duplicate++;
                continue;
            }

            boolean alreadyKnown = false;
            for (OcrDocument.Line known : lines) {
                if (known != null && known.source() == OcrDocument.Source.OCR
                        && sameOcrVisualLine(known, extraLine)) {
                    alreadyKnown = true;
                    break;
                }
            }
            if (alreadyKnown) {
                duplicate++;
            } else {
                lines.add(extraLine);
                added++;
            }
        }

        return documentFromLines(lines,
                base.engine() + "+" + extra.engine() + "-full-cache",
                Math.max(base.confidence(), extra.confidence()),
                Math.max(base.imageWidth(), extra.imageWidth()),
                Math.max(base.imageHeight(), extra.imageHeight()));
    }

    private static boolean sameOcrVisualLine(OcrDocument.Line a, OcrDocument.Line b) {
        if (a == null || b == null || a.bounds().isEmpty() || b.bounds().isEmpty()) return false;
        Rect overlap = new Rect();
        Rect ar = a.bounds();
        Rect br = b.bounds();
        if (!overlap.setIntersect(ar, br)) return false;
        long overlapArea = Math.max(0L, (long) overlap.width() * overlap.height());
        long minArea = Math.max(1L, Math.min((long) ar.width() * ar.height(),
                (long) br.width() * br.height()));
        if (overlapArea / (float) minArea < 0.45f) return false;
        String ac = compact(a.text());
        String bc = compact(b.text());
        if (!ac.isEmpty() && ac.equals(bc)) return true;
        String as = semanticCompact(a.text());
        String bs = semanticCompact(b.text());
        return !as.isEmpty() && as.equals(bs);
    }

    /**
     * Use OCR only as a ruler for a View string that lacks platform character locations. The text,
     * whitespace groups and source remain VIEW. Mapping is accepted only when the compact strings
     * and visible character counts agree exactly.
     */
    private static OcrDocument.Line hydrateViewGeometry(OcrDocument.Line viewLine,
                                                        OcrDocument.Line ocrLine) {
        if (viewLine == null || ocrLine == null || !viewLine.chars().isEmpty()
                || ocrLine.chars().isEmpty()) return null;
        String viewText = viewLine.text() == null ? "" : viewLine.text();
        String ocrText = ocrLine.text() == null ? "" : ocrLine.text();
        if (!compact(viewText).equals(compact(ocrText))) return null;

        ArrayList<OcrDocument.CharUnit> geometry = new ArrayList<>();
        for (OcrDocument.CharUnit unit : ocrLine.chars()) {
            if (unit == null || unit.text() == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
            int[] cps = unit.text().codePoints().filter(cp -> !Character.isWhitespace(cp)).toArray();
            if (cps.length <= 1) {
                geometry.add(unit);
            } else {
                Rect box = unit.bounds();
                for (int i = 0; i < cps.length; i++) {
                    int left = box.left + box.width() * i / cps.length;
                    int right = box.left + box.width() * (i + 1) / cps.length;
                    geometry.add(new OcrDocument.CharUnit(new String(Character.toChars(cps[i])),
                            new Rect(left, box.top, Math.max(left + 1, right), box.bottom),
                            unit.confidence(), unit.line(), unit.group(), unit.order()));
                }
            }
        }
        if (geometry.size() != countVisible(viewText)) return null;

        ArrayList<OcrDocument.CharUnit> mapped = new ArrayList<>();
        int geo = 0;
        int group = 0;
        int order = 0;
        Rect union = null;
        for (int offset = 0; offset < viewText.length();) {
            int cp = viewText.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                group++;
                continue;
            }
            if (geo >= geometry.size()) return null;
            OcrDocument.CharUnit g = geometry.get(geo++);
            Rect bounds = g.bounds();
            mapped.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)), bounds,
                    Math.max(0.72f, g.confidence()), 0, group, order++, OcrDocument.Source.VIEW));
            if (union == null) union = new Rect(bounds); else union.union(bounds);
        }
        if (mapped.isEmpty() || union == null || union.isEmpty()) return null;
        return new OcrDocument.Line(viewText, union, 0.95f, mapped, OcrDocument.Source.VIEW);
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
        if (base != null) {
            for (OcrDocument.Line line : base.lines()) {
                if (line == null || line.bounds().isEmpty()) continue;
                if (line.source() == OcrDocument.Source.VIEW || !Rect.intersects(line.bounds(), region)) {
                    lines.add(line);
                }
            }
        }

        for (OcrDocument.Line patchLine : patch.lines()) {
            if (patchLine == null || patchLine.bounds().isEmpty()) continue;
            int matchingView = -1;
            for (int i = 0; i < lines.size(); i++) {
                OcrDocument.Line line = lines.get(i);
                if (line != null && line.source() == OcrDocument.Source.VIEW
                        && sameViewAndOcrText(line, patchLine)) {
                    matchingView = i;
                    break;
                }
            }
            if (matchingView >= 0) {
                OcrDocument.Line viewLine = lines.get(matchingView);
                if (viewLine.chars().isEmpty()) {
                    OcrDocument.Line hydrated = hydrateViewGeometry(viewLine, patchLine);
                    if (hydrated != null) lines.set(matchingView, hydrated);
                    else lines.add(patchLine);
                }
            } else {
                lines.add(patchLine);
            }
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
