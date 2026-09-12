package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One OCR pipeline for every FloatLens surface.
 *
 * Normal OCR consumes text/blocks from the final result. Circle Select consumes the same result's
 * source-space word/symbol rectangles. Engine selection, languages, PP-OCR fallback, ML Kit passes,
 * preprocessing and candidate scoring therefore live in this class only.
 */
public final class OcrEngine {
    private static final ExecutorService PREP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-OCR-Prep");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong REQUEST_GENERATION = new AtomicLong(0L);

    public static void invalidatePending(Context c, String reason) {
        long generation = REQUEST_GENERATION.incrementAndGet();
        if (c != null) {
            DiagnosticLog.i(c.getApplicationContext(), "OCR_SESSION", "invalidate generation="
                    + generation + " reason=" + (reason == null ? "unknown" : reason));
        }
    }

    public static void recognize(Context c, Bitmap b) {
        recognize(c, b, null);
    }

    public static void recognize(Context c, Bitmap b, Rect anchor) {
        start(c, b, anchor == null ? null : new Rect(anchor), null, true);
    }

    /** Same recognition pipeline as normal OCR, but returns source-image coordinates for selection. */
    static void recognizeSpatial(Context c, Bitmap b, SpatialOcrEngine.Callback callback) {
        start(c, b, null, callback, false);
    }

    private static void start(Context c, Bitmap b, Rect anchor,
                              SpatialOcrEngine.Callback spatialCallback, boolean deliverUi) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        FloatService service = deliverUi ? FloatService.get() : null;
        long requestId = REQUEST_GENERATION.incrementAndGet();
        DiagnosticLog.i(app, "OCR_REQUEST", "request=" + requestId
                + " mode=" + (deliverUi ? "result" : "spatial")
                + " bitmap=" + bitmapSize(b)
                + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));

        if (deliverUi && service != null) service.onCircleRecognizeStarted();
        if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0) {
            fail(app, service, spatialCallback, deliverUi, requestId,
                    "ocr_invalid_bitmap", "OCR失败: 图片无效", new IllegalArgumentException("invalid bitmap"));
            return;
        }
        startSelectedEngine(app, service, b, anchor, spatialCallback, deliverUi, requestId);
    }

    private static void startSelectedEngine(Context app, FloatService service, Bitmap source, Rect anchor,
                                            SpatialOcrEngine.Callback spatialCallback, boolean deliverUi,
                                            long requestId) {
        if (stale(app, requestId, "engine_start")) return;
        int mode = readOcrEngineModeSafely(app);
        boolean smallReady = OcrModelManager.isReady(app, OcrModelManager.SMALL);
        boolean mediumReady = OcrModelManager.isReady(app, OcrModelManager.MEDIUM);
        DiagnosticLog.i(app, "OCR_ENGINE", "request=" + requestId + " mode=" + mode
                + " small=" + smallReady + " medium=" + mediumReady
                + " output=" + (deliverUi ? "result" : "spatial"));

        if (mode == 3) {
            startMlKitPipeline(app, service, source, anchor, spatialCallback, deliverUi,
                    "manual_mlkit", requestId);
            return;
        }
        if (mode == 1) {
            runPaddle(app, service, source, anchor, spatialCallback, deliverUi,
                    OcrModelManager.MEDIUM, false, null, requestId);
            return;
        }
        if (mode == 2) {
            runPaddle(app, service, source, anchor, spatialCallback, deliverUi,
                    OcrModelManager.SMALL, false, null, requestId);
            return;
        }

        if (smallReady) {
            runPaddle(app, service, source, anchor, spatialCallback, deliverUi,
                    OcrModelManager.SMALL, true, null, requestId);
        } else if (mediumReady) {
            runPaddle(app, service, source, anchor, spatialCallback, deliverUi,
                    OcrModelManager.MEDIUM, true, null, requestId);
        } else {
            DiagnosticLog.i(app, "OCR_ENGINE", "auto no local model -> ML Kit request=" + requestId);
            if (deliverUi) {
                Toast.makeText(app, "未下载 PP-OCRv6 模型，暂用 ML Kit；可在设置中下载",
                        Toast.LENGTH_SHORT).show();
            }
            startMlKitPipeline(app, service, source, anchor, spatialCallback, deliverUi,
                    "no_local_model", requestId);
        }
    }

    private static final class UnifiedResult {
        final String text;
        final List<String> blocks;
        final List<SpatialOcrEngine.Word> words;
        final float confidence;
        final double score;
        final String sourceName;

        UnifiedResult(String text, List<String> blocks, List<SpatialOcrEngine.Word> words,
                      float confidence, double score, String sourceName) {
            this.text = text == null ? "" : text.trim();
            this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
            this.words = words == null ? List.of() : List.copyOf(words);
            this.confidence = confidence;
            this.score = score;
            this.sourceName = sourceName == null ? "unknown" : sourceName;
        }
    }

    private static void runPaddle(Context app, FloatService service, Bitmap source, Rect anchor,
                                  SpatialOcrEngine.Callback spatialCallback, boolean deliverUi,
                                  int model, boolean auto, UnifiedResult previous, long requestId) {
        if (stale(app, requestId, "paddle_start")) return;
        if (!OcrModelManager.isReady(app, model)) {
            if (auto) {
                startMlKitPipeline(app, service, source, anchor, spatialCallback, deliverUi,
                        "local_model_missing", requestId);
            } else {
                fail(app, service, spatialCallback, deliverUi, requestId,
                        "ppocr_model_missing", "请先在设置中下载 " + OcrModelManager.displayName(model),
                        new IllegalStateException("PP-OCR model missing"));
            }
            return;
        }

        DiagnosticLog.i(app, "PPOCRV6", "launch request=" + requestId + " model=" + model
                + " languages=" + OcrLanguages.get(app)
                + " image=" + source.getWidth() + "x" + source.getHeight());
        PaddleOcrBridge.recognize(app, source, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(String text, List<String> blocks,
                                            List<SpatialOcrEngine.Word> words, long totalMs,
                                            int lineCount, float averageConfidence) {
                if (stale(app, requestId, "paddle_success")) return;
                UnifiedResult now = new UnifiedResult(text, blocks, words, averageConfidence,
                        paddleScore(text, averageConfidence, blocks == null ? 0 : blocks.size()),
                        "ppocr-" + model);
                DiagnosticLog.i(app, "PPOCRV6", "success request=" + requestId + " model=" + model
                        + " chars=" + now.text.length() + " lines=" + lineCount
                        + " words=" + now.words.size()
                        + " avgConf=" + String.format(java.util.Locale.US, "%.3f", averageConfidence)
                        + " totalMs=" + totalMs);

                if (now.text.isBlank()) {
                    if (previous != null && !previous.text.isBlank()) {
                        deliver(app, service, source, anchor, spatialCallback, deliverUi, previous, requestId);
                    } else if (auto) {
                        startMlKitPipeline(app, service, source, anchor, spatialCallback, deliverUi,
                                "ppocr_empty", requestId);
                    } else {
                        fail(app, service, spatialCallback, deliverUi, requestId,
                                "ppocr_empty", "未识别到文字", new IllegalStateException("PP-OCR empty"));
                    }
                    return;
                }

                if (auto && model == OcrModelManager.SMALL
                        && OcrModelManager.isReady(app, OcrModelManager.MEDIUM)
                        && shouldEscalate(now)) {
                    DiagnosticLog.i(app, "OCR_ENGINE", "Small low confidence -> Medium request=" + requestId);
                    runPaddle(app, service, source, anchor, spatialCallback, deliverUi,
                            OcrModelManager.MEDIUM, true, now, requestId);
                    return;
                }
                deliver(app, service, source, anchor, spatialCallback, deliverUi,
                        chooseBetter(previous, now), requestId);
            }

            @Override public void onFailure(String message) {
                if (stale(app, requestId, "paddle_failure")) return;
                DiagnosticLog.i(app, "PPOCRV6", "failure request=" + requestId
                        + " model=" + model + " " + message);
                if (previous != null && !previous.text.isBlank()) {
                    deliver(app, service, source, anchor, spatialCallback, deliverUi, previous, requestId);
                } else if (auto) {
                    startMlKitPipeline(app, service, source, anchor, spatialCallback, deliverUi,
                            "ppocr_failure:" + message, requestId);
                } else {
                    fail(app, service, spatialCallback, deliverUi, requestId,
                            "ppocr_failure", "PP-OCRv6 失败: " + message,
                            new IllegalStateException(message));
                }
            }
        });
    }

    private static boolean shouldEscalate(UnifiedResult r) {
        if (r == null || r.text.isBlank()) return true;
        if (r.confidence < 0.82f) return true;
        int meaningful = 0;
        for (int offset = 0; offset < r.text.length();) {
            int cp = r.text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || isCjk(cp)) meaningful++;
        }
        return meaningful < 6 || meaningful * 2 < r.text.codePointCount(0, r.text.length());
    }

    private static UnifiedResult chooseBetter(UnifiedResult a, UnifiedResult b) {
        if (a == null || a.text.isBlank()) return b;
        if (b == null || b.text.isBlank()) return a;
        return b.score >= a.score ? b : a;
    }

    private static double paddleScore(String text, float confidence, int blocks) {
        int length = text == null ? 0 : text.codePointCount(0, text.length());
        return confidence * 1000.0 + Math.min(300, length) + Math.min(12, blocks) * 5.0;
    }

    private static void startMlKitPipeline(Context app, FloatService service, Bitmap source, Rect anchor,
                                           SpatialOcrEngine.Callback spatialCallback, boolean deliverUi,
                                           String reason, long requestId) {
        if (stale(app, requestId, "mlkit_start")) return;
        try {
            Set<String> languages = OcrLanguages.get(app);
            boolean chinese = OcrLanguages.chineseEnabled(languages);
            boolean english = OcrLanguages.englishEnabled(languages);
            if (!chinese && !english) {
                languages = OcrLanguages.all();
                chinese = true;
                english = true;
            }

            ArrayList<PassSpec> plan = new ArrayList<>();
            if (chinese) plan.add(new PassSpec("zh-original", OcrImagePreprocessor.MODE_ORIGINAL, true));
            if (english) plan.add(new PassSpec("latin-original", OcrImagePreprocessor.MODE_ORIGINAL, false));
            if (chinese) plan.add(new PassSpec("zh-enhanced", OcrImagePreprocessor.MODE_ENHANCED, true));
            if (english) plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.MODE_ENHANCED, false));
            if (chinese) plan.add(new PassSpec("zh-mono", OcrImagePreprocessor.MODE_MONO, true));
            if (english) plan.add(new PassSpec("latin-mono", OcrImagePreprocessor.MODE_MONO, false));

            DiagnosticLog.i(app, "OCR_PIPELINE", "start request=" + requestId
                    + " languages=" + languages + " source=" + source.getWidth() + "x" + source.getHeight()
                    + " passes=" + plan.size() + " reason=" + reason
                    + " output=" + (deliverUi ? "result" : "spatial"));
            new MlRunState(app, service, source, anchor, spatialCallback, deliverUi, plan, requestId).next();
        } catch (Throwable t) {
            fail(app, service, spatialCallback, deliverUi, requestId,
                    "mlkit_init_failure", "OCR失败: " + safe(t), t);
        }
    }

    private static final class PassSpec {
        final String name;
        final int mode;
        final boolean chinese;

        PassSpec(String name, int mode, boolean chinese) {
            this.name = name;
            this.mode = mode;
            this.chinese = chinese;
        }
    }

    private static final class MlRunState {
        final Context app;
        final FloatService service;
        final Bitmap source;
        final Rect anchor;
        final SpatialOcrEngine.Callback spatialCallback;
        final boolean deliverUi;
        final List<PassSpec> plan;
        final long requestId;
        final List<UnifiedResult> results = new ArrayList<>();
        int index;

        MlRunState(Context app, FloatService service, Bitmap source, Rect anchor,
                   SpatialOcrEngine.Callback spatialCallback, boolean deliverUi,
                   List<PassSpec> plan, long requestId) {
            this.app = app;
            this.service = service;
            this.source = source;
            this.anchor = anchor;
            this.spatialCallback = spatialCallback;
            this.deliverUi = deliverUi;
            this.plan = plan;
            this.requestId = requestId;
        }

        void next() {
            if (stale(app, requestId, "mlkit_next")) return;
            if (index >= plan.size()) {
                MAIN.post(this::finishOnMain);
                return;
            }
            PassSpec spec = plan.get(index++);
            try {
                PREP_EXECUTOR.execute(() -> prepareAndRun(spec));
            } catch (Throwable t) {
                DiagnosticLog.i(app, "OCR_EXECUTOR_FAIL", "request=" + requestId + " " + safe(t));
                next();
            }
        }

        private void prepareAndRun(PassSpec spec) {
            if (stale(app, requestId, "mlkit_prepare_" + spec.name)) return;
            if (source.isRecycled()) {
                next();
                return;
            }

            OcrImagePreprocessor.Prepared prepared;
            try {
                prepared = OcrImagePreprocessor.prepare(source, spec.mode);
            } catch (Throwable t) {
                DiagnosticLog.i(app, "OCR_PREP", "request=" + requestId + " " + spec.name
                        + " exception=" + safe(t));
                next();
                return;
            }
            if (prepared == null || prepared.bitmap == null || prepared.bitmap.isRecycled()) {
                DiagnosticLog.i(app, "OCR_PREP", "request=" + requestId + " " + spec.name + " failed");
                next();
                return;
            }
            if (stale(app, requestId, "mlkit_prepared_" + spec.name)) {
                OcrImagePreprocessor.recycle(prepared);
                return;
            }

            DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " " + spec.name
                    + " image=" + prepared.bitmap.getWidth() + "x" + prepared.bitmap.getHeight()
                    + " scale=" + prepared.scale + " pad=" + prepared.padX + "," + prepared.padY);
            TextRecognizer client = null;
            try {
                client = spec.chinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                TextRecognizer finalClient = client;
                client.process(InputImage.fromBitmap(prepared.bitmap, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                if (stale(app, requestId, "mlkit_success_" + spec.name)) return;
                                UnifiedResult result = mlCandidate(spec.name, text, prepared);
                                if (!result.text.isBlank()) results.add(result);
                                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " "
                                        + spec.name + " chars=" + result.text.length()
                                        + " blocks=" + result.blocks.size()
                                        + " words=" + result.words.size()
                                        + " score=" + Math.round(result.score));
                            } catch (Throwable error) {
                                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " "
                                        + spec.name + " parseFailure=" + safe(error));
                            } finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            next();
                        })
                        .addOnFailureListener(error -> {
                            try {
                                if (!stale(app, requestId, "mlkit_failure_" + spec.name)) {
                                    DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " "
                                            + spec.name + " failure=" + safe(error));
                                }
                            } finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            next();
                        });
            } catch (Throwable t) {
                if (client != null) try { client.close(); } catch (Throwable ignored) {}
                OcrImagePreprocessor.recycle(prepared);
                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " "
                        + spec.name + " launchFailure=" + safe(t));
                next();
            }
        }

        private void finishOnMain() {
            if (stale(app, requestId, "mlkit_finish")) return;
            UnifiedResult best = null;
            for (UnifiedResult result : results) {
                if (best == null || result.score > best.score) best = result;
            }
            if (best == null || best.text.isBlank()) {
                fail(app, service, spatialCallback, deliverUi, requestId,
                        "ocr_empty", "未识别到文字", new IllegalStateException("ML Kit empty"));
                return;
            }
            DiagnosticLog.i(app, "OCR_PIPELINE", "selected request=" + requestId
                    + " pass=" + best.sourceName + " score=" + Math.round(best.score)
                    + " chars=" + best.text.length() + " words=" + best.words.size()
                    + " candidates=" + results.size());
            deliver(app, service, source, anchor, spatialCallback, deliverUi, best, requestId);
        }
    }

    private static UnifiedResult mlCandidate(String passName, Text text,
                                             OcrImagePreprocessor.Prepared prepared) {
        String full = text == null || text.getText() == null ? "" : text.getText().trim();
        ArrayList<String> blocks = new ArrayList<>();
        int lineCount = 0;
        int elementCount = 0;
        if (text != null) {
            for (Text.TextBlock block : text.getTextBlocks()) {
                if (block.getText() != null && !block.getText().isBlank()) blocks.add(block.getText());
                lineCount += block.getLines().size();
                for (Text.Line line : block.getLines()) elementCount += line.getElements().size();
            }
        }
        List<SpatialOcrEngine.Word> words = spatialWords(text, prepared);
        double score = textScore(full, blocks.size(), lineCount, elementCount);
        return new UnifiedResult(full, blocks, words, 0f, score, passName);
    }

    private static List<SpatialOcrEngine.Word> spatialWords(Text text,
                                                            OcrImagePreprocessor.Prepared prepared) {
        ArrayList<LineCandidate> lines = new ArrayList<>();
        if (text == null) return List.of();
        int nextGroup = 0;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect lineBox = prepared.toSourceRect(line.getBoundingBox());
                ArrayList<UnitCandidate> units = new ArrayList<>();
                Rect union = null;

                for (Text.Element element : line.getElements()) {
                    String elementText = element.getText() == null ? "" : element.getText().trim();
                    Rect elementBox = prepared.toSourceRect(element.getBoundingBox());
                    if (elementText.isEmpty() || elementBox.isEmpty()) continue;

                    int group = nextGroup++;
                    ArrayList<UnitCandidate> symbols = validSymbols(element, group, prepared);
                    if (!symbols.isEmpty()) {
                        for (UnitCandidate symbol : symbols) {
                            units.add(symbol);
                            if (union == null) union = new Rect(symbol.bounds);
                            else union.union(symbol.bounds);
                        }
                    } else {
                        units.add(new UnitCandidate(elementText, elementBox, group));
                        if (union == null) union = new Rect(elementBox);
                        else union.union(elementBox);
                    }
                }

                if (units.isEmpty()) {
                    String value = line.getText() == null ? "" : line.getText().trim();
                    if (!value.isEmpty() && !lineBox.isEmpty()) {
                        units.add(new UnitCandidate(value, lineBox, nextGroup++));
                        union = new Rect(lineBox);
                    }
                }
                if (units.isEmpty()) continue;

                units.sort(Comparator
                        .comparingInt((UnitCandidate e) -> e.bounds.left)
                        .thenComparingInt(e -> e.bounds.top)
                        .thenComparingInt(e -> e.bounds.right));
                Rect stableLineBox = !lineBox.isEmpty() ? new Rect(lineBox)
                        : union == null ? new Rect() : new Rect(union);
                if (stableLineBox.isEmpty() && union != null) stableLineBox.set(union);
                lines.add(new LineCandidate(stableLineBox, units));
            }
        }

        lines.sort((a, b) -> {
            int ah = Math.max(1, a.bounds.height());
            int bh = Math.max(1, b.bounds.height());
            int tolerance = Math.max(3, Math.min(ah, bh) / 2);
            int dy = a.bounds.centerY() - b.bounds.centerY();
            if (Math.abs(dy) > tolerance) return Integer.compare(a.bounds.centerY(), b.bounds.centerY());
            if (Math.abs(a.bounds.top - b.bounds.top) > tolerance) {
                return Integer.compare(a.bounds.top, b.bounds.top);
            }
            return Integer.compare(a.bounds.left, b.bounds.left);
        });

        ArrayList<SpatialOcrEngine.Word> out = new ArrayList<>();
        int order = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            LineCandidate row = lines.get(lineIndex);
            for (UnitCandidate unit : row.units) {
                out.add(new SpatialOcrEngine.Word(unit.text, unit.bounds, lineIndex, unit.group, order++));
            }
        }
        return out;
    }

    private static ArrayList<UnitCandidate> validSymbols(Text.Element element, int group,
                                                          OcrImagePreprocessor.Prepared prepared) {
        ArrayList<UnitCandidate> out = new ArrayList<>();
        List<Text.Symbol> symbols;
        try { symbols = element.getSymbols(); }
        catch (Throwable ignored) { return out; }
        if (symbols == null || symbols.isEmpty()) return out;

        StringBuilder combined = new StringBuilder();
        for (Text.Symbol symbol : symbols) {
            if (symbol == null) return new ArrayList<>();
            String value = symbol.getText() == null ? "" : symbol.getText().trim();
            Rect box = prepared.toSourceRect(symbol.getBoundingBox());
            if (value.isEmpty() || box.isEmpty()) return new ArrayList<>();
            combined.append(value);
            out.add(new UnitCandidate(value, box, group));
        }
        String elementText = element.getText() == null ? "" : element.getText().replace(" ", "").trim();
        String symbolText = combined.toString().replace(" ", "").trim();
        if (out.isEmpty() || !elementText.equals(symbolText)) return new ArrayList<>();
        return out;
    }

    private static final class LineCandidate {
        final Rect bounds;
        final List<UnitCandidate> units;
        LineCandidate(Rect bounds, List<UnitCandidate> units) {
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.units = units;
        }
    }

    private static final class UnitCandidate {
        final String text;
        final Rect bounds;
        final int group;
        UnitCandidate(String text, Rect bounds, int group) {
            this.text = text == null ? "" : text.trim();
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.group = group;
        }
    }

    private static void deliver(Context app, FloatService service, Bitmap source, Rect anchor,
                                SpatialOcrEngine.Callback spatialCallback, boolean deliverUi,
                                UnifiedResult result, long requestId) {
        if (result == null || result.text.isBlank() || stale(app, requestId, "deliver")) return;
        MAIN.post(() -> {
            if (stale(app, requestId, "deliver_main")) return;
            if (spatialCallback != null) {
                try {
                    spatialCallback.onSuccess(result.words);
                    DiagnosticLog.i(app, "OCR_DISPATCH", "spatial source=" + result.sourceName
                            + " chars=" + result.text.length() + " words=" + result.words.size());
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "OCR_DISPATCH", "spatial callback failed=" + safe(t));
                }
                return;
            }
            if (!deliverUi) return;
            if (service != null) service.onOcrResults(Math.max(1, result.words.size()));
            OcrResultDispatcher.deliver(app, result.text, result.blocks, source, anchor);
            DiagnosticLog.i(app, "OCR_DISPATCH", "result source=" + result.sourceName
                    + " chars=" + result.text.length() + " blocks=" + result.blocks.size()
                    + " words=" + result.words.size());
        });
    }

    private static void fail(Context app, FloatService service,
                             SpatialOcrEngine.Callback spatialCallback, boolean deliverUi,
                             long requestId, String reason, String userMessage, Throwable error) {
        if (stale(app, requestId, "fail_" + reason)) return;
        DiagnosticLog.i(app, "OCR_FAIL", "request=" + requestId + " reason=" + reason
                + " error=" + safe(error));
        MAIN.post(() -> {
            if (stale(app, requestId, "fail_main_" + reason)) return;
            if (spatialCallback != null) {
                try { spatialCallback.onFailure(error == null ? new IllegalStateException(reason) : error); }
                catch (Throwable ignored) { }
                return;
            }
            if (deliverUi) {
                if (service != null) service.onCircleFinished(reason);
                Toast.makeText(app, userMessage == null ? "OCR失败" : userMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static boolean stale(Context app, long requestId, String stage) {
        long current = REQUEST_GENERATION.get();
        if (requestId == current) return false;
        DiagnosticLog.i(app, "OCR_SESSION", "drop stale request=" + requestId
                + " current=" + current + " stage=" + stage);
        return true;
    }

    private static int readOcrEngineModeSafely(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
            Object raw = p.getAll().get(FloatSettings.K_OCR_ENGINE);
            if (raw instanceof Number n) return Math.max(0, Math.min(3, n.intValue()));
            if (raw instanceof String s) {
                try { return Math.max(0, Math.min(3, Integer.parseInt(s.trim()))); }
                catch (Throwable ignored) { return 0; }
            }
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OCR_ENGINE", "read fallback=" + safe(t));
        }
        return 0;
    }

    private static double textScore(String value, int blocks, int lines, int elements) {
        if (value == null || value.isBlank()) return -1_000_000d;
        int meaningful = 0, cjk = 0, letters = 0, digits = 0, garbage = 0, punctuation = 0;
        int visible = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            visible++;
            if (isCjk(cp)) {
                cjk++; meaningful++;
            } else if (Character.isLetter(cp)) {
                letters++; meaningful++;
            } else if (Character.isDigit(cp)) {
                digits++; meaningful++;
            } else if (cp == 0xFFFD || Character.isISOControl(cp)
                    || (cp >= 0xE000 && cp <= 0xF8FF)) {
                garbage++;
            } else {
                punctuation++;
            }
        }
        if (meaningful == 0) return -50_000d + visible;
        double readableRatio = meaningful / (double) Math.max(1, visible);
        return meaningful * 8.0
                + cjk * 2.2
                + letters * 0.7
                + digits * 0.5
                + Math.min(12, blocks) * 5.0
                + Math.min(30, lines) * 2.5
                + Math.min(60, elements) * 0.8
                + readableRatio * 25.0
                - garbage * 24.0
                - Math.max(0, punctuation - meaningful / 2) * 2.0;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static String bitmapSize(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private OcrEngine() {}
}
