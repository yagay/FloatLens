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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** High-accuracy OCR with sequential low-memory preprocessing and stale-result suppression. */
public final class OcrEngine {
    private static final ExecutorService PREP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-OCR-Prep");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong REQUEST_GENERATION = new AtomicLong(0L);

    /**
     * Make every OCR request that started before this point stale. Native/MLKit work may finish in
     * the background, but its callbacks are not allowed to change UI, show toasts, or open a result
     * window after the user has started another interaction.
     */
    public static void invalidatePending(Context c, String reason) {
        long generation = REQUEST_GENERATION.incrementAndGet();
        if (c != null) {
            DiagnosticLog.i(c.getApplicationContext(), "OCR_SESSION", "invalidate generation="
                    + generation + " reason=" + (reason == null ? "unknown" : reason));
        }
    }

    private static boolean stale(Context app, long requestId, String stage) {
        long current = REQUEST_GENERATION.get();
        if (requestId == current) return false;
        DiagnosticLog.i(app, "OCR_SESSION", "drop stale request=" + requestId
                + " current=" + current + " stage=" + stage);
        return true;
    }

    public static void recognize(Context c, Bitmap b) {
        recognize(c, b, null);
    }

    public static void recognize(Context c, Bitmap b, Rect anchor) {
        Context app = c.getApplicationContext();
        Rect resultAnchor = anchor == null ? null : new Rect(anchor);
        FloatService service = FloatService.get();
        long requestId = REQUEST_GENERATION.incrementAndGet();

        DiagnosticLog.i(app, "OCR_REQUEST", "request=" + requestId + " bitmap="
                + (b == null ? "null" : b.getWidth() + "x" + b.getHeight())
                + " recycled=" + (b != null && b.isRecycled())
                + " anchor=" + (resultAnchor == null ? "none" : resultAnchor.toShortString()));

        if (service != null) service.onCircleRecognizeStarted();
        if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0) {
            if (service != null) service.onCircleFinished("ocr_invalid_bitmap");
            Toast.makeText(app, "OCR失败: 图片无效", Toast.LENGTH_SHORT).show();
            return;
        }

        startSelectedEngine(app, service, b, resultAnchor, requestId);
    }

    private static void startSelectedEngine(Context app, FloatService service,
                                            Bitmap source, Rect anchor, long requestId) {
        if (stale(app, requestId, "engine_start")) return;
        int mode = readOcrEngineModeSafely(app);
        boolean smallReady = OcrModelManager.isReady(app, OcrModelManager.SMALL);
        boolean mediumReady = OcrModelManager.isReady(app, OcrModelManager.MEDIUM);
        DiagnosticLog.i(app, "OCR_ENGINE", "request=" + requestId + " mode=" + mode
                + " small=" + smallReady + " medium=" + mediumReady);
        if (mode == 3) { startMlKitPipeline(app, service, source, anchor, "manual_mlkit", requestId); return; }
        if (mode == 1) { runPaddle(app, service, source, anchor, OcrModelManager.MEDIUM, false, null, requestId); return; }
        if (mode == 2) { runPaddle(app, service, source, anchor, OcrModelManager.SMALL, false, null, requestId); return; }

        if (smallReady) {
            runPaddle(app, service, source, anchor, OcrModelManager.SMALL, true, null, requestId);
        } else if (mediumReady) {
            runPaddle(app, service, source, anchor, OcrModelManager.MEDIUM, true, null, requestId);
        } else {
            DiagnosticLog.i(app, "OCR_ENGINE", "auto no local model -> ML Kit request=" + requestId);
            Toast.makeText(app, "未下载 PP-OCRv6 模型，暂用 ML Kit；可在设置中下载", Toast.LENGTH_SHORT).show();
            startMlKitPipeline(app, service, source, anchor, "no_local_model", requestId);
        }
    }

    private static final class PaddleResult {
        final String text; final List<String> blocks; final float confidence;
        PaddleResult(String text, List<String> blocks, float confidence) {
            this.text = text == null ? "" : text.trim();
            this.blocks = blocks == null ? List.of() : blocks;
            this.confidence = confidence;
        }
    }

    private static void runPaddle(Context app, FloatService service, Bitmap source, Rect anchor,
                                  int model, boolean auto, PaddleResult previous, long requestId) {
        if (stale(app, requestId, "paddle_start")) return;
        if (!OcrModelManager.isReady(app, model)) {
            if (auto) {
                startMlKitPipeline(app, service, source, anchor, "local_model_missing", requestId);
                return;
            }
            if (!stale(app, requestId, "paddle_model_missing")) {
                if (service != null) service.onCircleFinished("ppocr_model_missing");
                Toast.makeText(app, "请先在设置中下载 " + OcrModelManager.displayName(model), Toast.LENGTH_LONG).show();
            }
            return;
        }
        DiagnosticLog.i(app, "PPOCRV6", "launch request=" + requestId + " model=" + model
                + " languages=" + OcrLanguages.get(app)
                + " image=" + source.getWidth() + "x" + source.getHeight());
        PaddleOcrBridge.recognize(app, source, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(String text, List<String> blocks, long totalMs,
                                            int lineCount, float averageConfidence) {
                if (stale(app, requestId, "paddle_success")) return;
                PaddleResult now = new PaddleResult(text, blocks, averageConfidence);
                DiagnosticLog.i(app, "PPOCRV6", "success request=" + requestId + " model=" + model
                        + " chars=" + now.text.length() + " lines=" + lineCount
                        + " avgConf=" + String.format(java.util.Locale.US, "%.3f", averageConfidence)
                        + " totalMs=" + totalMs);
                if (now.text.isBlank()) {
                    if (previous != null && !previous.text.isBlank()) {
                        showPaddleResult(app, service, source, anchor, previous, requestId);
                        return;
                    }
                    if (auto) {
                        startMlKitPipeline(app, service, source, anchor, "ppocr_empty", requestId);
                        return;
                    }
                    if (!stale(app, requestId, "paddle_empty")) {
                        if (service != null) service.onCircleFinished("ppocr_empty");
                        Toast.makeText(app, "未识别到文字", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (auto && model == OcrModelManager.SMALL
                        && OcrModelManager.isReady(app, OcrModelManager.MEDIUM)
                        && shouldEscalate(now)) {
                    DiagnosticLog.i(app, "OCR_ENGINE", "Small low confidence -> Medium request=" + requestId);
                    runPaddle(app, service, source, anchor, OcrModelManager.MEDIUM, true, now, requestId);
                    return;
                }
                showPaddleResult(app, service, source, anchor, chooseBetter(previous, now), requestId);
            }

            @Override public void onFailure(String message) {
                if (stale(app, requestId, "paddle_failure")) return;
                DiagnosticLog.i(app, "PPOCRV6", "failure request=" + requestId
                        + " model=" + model + " " + message);
                if (previous != null && !previous.text.isBlank()) {
                    showPaddleResult(app, service, source, anchor, previous, requestId);
                    return;
                }
                if (auto) {
                    startMlKitPipeline(app, service, source, anchor,
                            "ppocr_failure:" + message, requestId);
                } else if (!stale(app, requestId, "paddle_failure_ui")) {
                    if (service != null) service.onCircleFinished("ppocr_failure");
                    Toast.makeText(app, "PP-OCRv6 失败: " + message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static boolean shouldEscalate(PaddleResult r) {
        if (r == null || r.text.isBlank()) return true;
        if (r.confidence < 0.82f) return true;
        int meaningful = 0;
        for (int i = 0; i < r.text.length(); i++) {
            if (Character.isLetterOrDigit(r.text.charAt(i)) || isCjk(r.text.charAt(i))) meaningful++;
        }
        return meaningful < 6 || meaningful * 2 < r.text.length();
    }

    private static PaddleResult chooseBetter(PaddleResult a, PaddleResult b) {
        if (a == null || a.text.isBlank()) return b;
        if (b == null || b.text.isBlank()) return a;
        double sa = a.confidence * 1000.0 + Math.min(300, a.text.length());
        double sb = b.confidence * 1000.0 + Math.min(300, b.text.length());
        return sb >= sa ? b : a;
    }

    private static void showPaddleResult(Context app, FloatService service, Bitmap source, Rect anchor,
                                         PaddleResult r, long requestId) {
        if (stale(app, requestId, "paddle_show_schedule")) return;
        if (r == null || r.text.isBlank()) {
            if (service != null) service.onCircleFinished("ppocr_empty");
            return;
        }
        MAIN.post(() -> {
            if (stale(app, requestId, "paddle_show_main")) return;
            if (service != null) service.onOcrResults(Math.max(1, r.blocks.size()));
            if (!ResultTextActivity.show(app, r.text, r.blocks, source, anchor)) {
                if (stale(app, requestId, "paddle_overlay_fallback")) return;
                ResultOverlay.show(app, r.text, r.blocks, source, anchor);
            }
        });
    }

    private static void startMlKitPipeline(Context app, FloatService service,
                                           Bitmap source, Rect anchor, String reason, long requestId) {
        if (stale(app, requestId, "mlkit_start")) return;
        try {
            DiagnosticLog.i(app, "OCR_INIT", "MLKit begin request=" + requestId + " reason=" + reason);
            Set<String> languages = OcrLanguages.get(app);
            boolean chinese = OcrLanguages.chineseEnabled(languages);
            boolean english = OcrLanguages.englishEnabled(languages);
            if (!chinese && !english) {
                languages = OcrLanguages.all();
                chinese = true;
                english = true;
            }
            DiagnosticLog.i(app, "OCR_INIT", "request=" + requestId + " languages=" + languages
                    + " chinese=" + chinese + " english=" + english);

            ArrayList<PassSpec> plan = new ArrayList<>();
            if (chinese) plan.add(new PassSpec("zh-original", OcrImagePreprocessor.MODE_ORIGINAL, true));
            if (english) plan.add(new PassSpec("latin-original", OcrImagePreprocessor.MODE_ORIGINAL, false));
            if (chinese) plan.add(new PassSpec("zh-enhanced", OcrImagePreprocessor.MODE_ENHANCED, true));
            if (english) plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.MODE_ENHANCED, false));
            if (chinese) plan.add(new PassSpec("zh-mono", OcrImagePreprocessor.MODE_MONO, true));
            if (english) plan.add(new PassSpec("latin-mono", OcrImagePreprocessor.MODE_MONO, false));

            DiagnosticLog.i(app, "OCR_PIPELINE", "start request=" + requestId
                    + " MLKit serial-safe languages=" + languages
                    + " source=" + source.getWidth() + "x" + source.getHeight()
                    + " passes=" + plan.size() + " reason=" + reason);
            new RunState(app, service, source, anchor, plan, requestId).next();
        } catch (Throwable t) {
            if (stale(app, requestId, "mlkit_init_failure")) return;
            DiagnosticLog.i(app, "OCR_INIT_FAIL", "request=" + requestId + " "
                    + t.getClass().getName() + ":" + safe(t));
            runFallback(app, service, source, anchor, "mlkit_init_failure", requestId);
        }
    }

    private static int readOcrEngineModeSafely(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
            Object raw = p.getAll().get(FloatSettings.K_OCR_ENGINE);
            if (raw instanceof Number) return Math.max(0, Math.min(3, ((Number) raw).intValue()));
            if (raw instanceof String) {
                try { return Math.max(0, Math.min(3, Integer.parseInt(((String) raw).trim()))); }
                catch (Throwable ignored) { return 0; }
            }
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OCR_ENGINE", "read fallback=" + safe(t));
        }
        return 0;
    }

    private static final class RunState {
        final Context app;
        final FloatService service;
        final Bitmap source;
        final Rect anchor;
        final List<PassSpec> plan;
        final long requestId;
        final List<Candidate> results = new ArrayList<>();
        int index;

        RunState(Context app, FloatService service, Bitmap source, Rect anchor,
                 List<PassSpec> plan, long requestId) {
            this.app = app;
            this.service = service;
            this.source = source;
            this.anchor = anchor;
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
                if (stale(app, requestId, "mlkit_executor_failure")) return;
                DiagnosticLog.i(app, "OCR_EXECUTOR_FAIL", "request=" + requestId + " "
                        + spec.name + " " + t.getClass().getSimpleName() + ":" + safe(t));
                runFallback(app, service, source, anchor, "executor_failure", requestId);
            }
        }

        private void prepareAndRun(PassSpec spec) {
            if (stale(app, requestId, "mlkit_prepare_" + spec.name)) return;
            if (source.isRecycled()) {
                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " "
                        + spec.name + " skipped=source_recycled");
                next();
                return;
            }

            OcrImagePreprocessor.Prepared prepared;
            try {
                DiagnosticLog.i(app, "OCR_PREP", "request=" + requestId + " " + spec.name
                        + " begin mode=" + OcrImagePreprocessor.modeName(spec.mode));
                prepared = OcrImagePreprocessor.prepare(source, spec.mode);
            } catch (Throwable t) {
                DiagnosticLog.i(app, "OCR_PREP", "request=" + requestId + " " + spec.name
                        + " exception=" + t.getClass().getSimpleName() + ":" + safe(t));
                next();
                return;
            }

            if (prepared == null || prepared.bitmap == null || prepared.bitmap.isRecycled()) {
                DiagnosticLog.i(app, "OCR_PREP", "request=" + requestId + " "
                        + spec.name + " failed; skip");
                next();
                return;
            }
            if (stale(app, requestId, "mlkit_prepared_" + spec.name)) {
                OcrImagePreprocessor.recycle(prepared);
                return;
            }
            DiagnosticLog.i(app, "OCR_PREP", "request=" + requestId + " " + spec.name
                    + " ready=" + prepared.bitmap.getWidth() + "x" + prepared.bitmap.getHeight()
                    + " owned=" + prepared.owned);

            TextRecognizer client = null;
            try {
                client = spec.chinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                TextRecognizer finalClient = client;
                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " " + spec.name + " launch");
                client.process(InputImage.fromBitmap(prepared.bitmap, 0))
                        .addOnSuccessListener(t -> {
                            try {
                                if (stale(app, requestId, "mlkit_success_" + spec.name)) return;
                                Candidate candidate = candidate(spec.name, t);
                                if (!candidate.full.isBlank()) results.add(candidate);
                                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " "
                                        + spec.name + " chars=" + candidate.full.length()
                                        + " blocks=" + candidate.blocks.size()
                                        + " score=" + Math.round(candidate.score));
                            } catch (Throwable error) {
                                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " "
                                        + spec.name + " parseFailure=" + safe(error));
                            } finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            next();
                        })
                        .addOnFailureListener(e -> {
                            try {
                                if (stale(app, requestId, "mlkit_failure_" + spec.name)) return;
                                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " "
                                        + spec.name + " failure=" + safe(e));
                            } finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            next();
                        });
            } catch (Throwable t) {
                if (client != null) try { client.close(); } catch (Throwable ignored) {}
                OcrImagePreprocessor.recycle(prepared);
                if (stale(app, requestId, "mlkit_launch_failure_" + spec.name)) return;
                DiagnosticLog.i(app, "OCR_PASS", "request=" + requestId + " " + spec.name
                        + " launchFailure=" + t.getClass().getSimpleName() + ":" + safe(t));
                next();
            }
        }

        private void finishOnMain() {
            if (stale(app, requestId, "mlkit_finish")) return;
            Candidate best = null;
            for (Candidate c : results) {
                if (best == null || c.score > best.score) best = c;
            }
            if (best == null || best.full.isBlank()) {
                DiagnosticLog.i(app, "OCR_PIPELINE", "finish request=" + requestId
                        + " empty successfulPasses=" + results.size());
                if (service != null) service.onCircleFinished("ocr_empty");
                Toast.makeText(app, "未识别到文字", Toast.LENGTH_SHORT).show();
                return;
            }

            DiagnosticLog.i(app, "OCR_PIPELINE", "selected request=" + requestId
                    + " pass=" + best.passName + " score=" + Math.round(best.score)
                    + " chars=" + best.full.length() + " candidates=" + best.blocks.size()
                    + " successfulPasses=" + results.size());
            if (service != null) service.onOcrResults(best.blocks.size());

            if (stale(app, requestId, "mlkit_before_show")) return;
            if (!ResultTextActivity.show(app, best.full, best.blocks, source, anchor)) {
                if (stale(app, requestId, "mlkit_overlay_fallback")) return;
                DiagnosticLog.i(app, "RESULT_TEXT_ACTIVITY", "fallback to overlay request=" + requestId);
                ResultOverlay.show(app, best.full, best.blocks, source, anchor);
            }
        }
    }

    private static void runFallback(Context app, FloatService service, Bitmap source, Rect anchor,
                                    String reason, long requestId) {
        if (stale(app, requestId, "fallback_schedule")) return;
        if (source == null || source.isRecycled()) {
            if (service != null) service.onCircleFinished("ocr_fallback_invalid");
            return;
        }
        MAIN.post(() -> {
            if (stale(app, requestId, "fallback_start")) return;
            TextRecognizer client = null;
            try {
                Set<String> languages = OcrLanguages.get(app);
                boolean useChinese = OcrLanguages.chineseEnabled(languages);
                DiagnosticLog.i(app, "OCR_FALLBACK", "start request=" + requestId
                        + " reason=" + reason + " languages=" + languages
                        + " image=" + source.getWidth() + "x" + source.getHeight());
                client = useChinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                TextRecognizer finalClient = client;
                String passName = useChinese ? "fallback-zh-original" : "fallback-latin-original";
                client.process(InputImage.fromBitmap(source, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                if (stale(app, requestId, "fallback_success")) return;
                                Candidate result = candidate(passName, text);
                                DiagnosticLog.i(app, "OCR_FALLBACK", "success request=" + requestId
                                        + " chars=" + result.full.length()
                                        + " blocks=" + result.blocks.size());
                                if (result.full.isBlank()) {
                                    if (service != null) service.onCircleFinished("ocr_fallback_empty");
                                    Toast.makeText(app, "未识别到文字", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                if (stale(app, requestId, "fallback_before_show")) return;
                                if (service != null) service.onOcrResults(result.blocks.size());
                                if (!ResultTextActivity.show(app, result.full, result.blocks, source, anchor)) {
                                    if (stale(app, requestId, "fallback_overlay_fallback")) return;
                                    ResultOverlay.show(app, result.full, result.blocks, source, anchor);
                                }
                            } finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                            }
                        })
                        .addOnFailureListener(e -> {
                            try {
                                if (stale(app, requestId, "fallback_failure")) return;
                                DiagnosticLog.i(app, "OCR_FALLBACK", "failure request=" + requestId
                                        + " " + safe(e));
                                if (service != null) service.onCircleFinished("ocr_fallback_failure");
                                Toast.makeText(app, "OCR失败", Toast.LENGTH_SHORT).show();
                            } finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable t) {
                if (client != null) try { client.close(); } catch (Throwable ignored) {}
                if (stale(app, requestId, "fallback_launch_failure")) return;
                DiagnosticLog.i(app, "OCR_FALLBACK", "launchFailure request=" + requestId + " "
                        + t.getClass().getSimpleName() + ":" + safe(t));
                if (service != null) service.onCircleFinished("ocr_fallback_launch_failure");
                Toast.makeText(app, "OCR失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static Candidate candidate(String passName, Text text) {
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
        return new Candidate(passName, full, blocks,
                score(full, blocks.size(), lineCount, elementCount));
    }

    private static double score(String value, int blocks, int lines, int elements) {
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
        double result = meaningful * 8.0
                + cjk * 2.2
                + letters * 0.7
                + digits * 0.5
                + Math.min(12, blocks) * 5.0
                + Math.min(30, lines) * 2.5
                + Math.min(60, elements) * 0.8
                + readableRatio * 55.0
                - garbage * 35.0
                - Math.max(0, punctuation - meaningful / 2) * 1.5;
        if (garbage > 0 && garbage * 8 > visible) result -= 120;
        if (readableRatio < 0.45) result -= (0.45 - readableRatio) * 240.0;
        return result;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
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

    private static final class Candidate {
        final String passName;
        final String full;
        final List<String> blocks;
        final double score;

        Candidate(String passName, String full, List<String> blocks, double score) {
            this.passName = passName;
            this.full = full == null ? "" : full;
            this.blocks = blocks == null ? List.of() : blocks;
            this.score = score;
        }
    }

    private OcrEngine() {}
}
