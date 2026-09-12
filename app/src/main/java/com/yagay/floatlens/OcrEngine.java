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

/** Single recognition owner for normal OCR, Circle Select, View OCR and local refinements. */
public final class OcrEngine {
    public interface DocumentCallback {
        void onSuccess(OcrDocument document);
        void onFailure(Throwable error);
    }

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

    public static void recognize(Context c, Bitmap b) { recognize(c, b, null); }

    public static void recognize(Context c, Bitmap b, Rect anchor) {
        start(c, b, anchor == null ? null : new Rect(anchor), null, true);
    }

    /** Return the exact same final OCR result without opening a result surface. */
    public static void recognizeDocument(Context c, Bitmap b, DocumentCallback callback) {
        start(c, b, null, callback, false);
    }

    private static void start(Context c, Bitmap b, Rect anchor,
                              DocumentCallback callback, boolean deliverUi) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        FloatService service = deliverUi ? FloatService.get() : null;
        long requestId = REQUEST_GENERATION.incrementAndGet();
        DiagnosticLog.i(app, "OCR_REQUEST", "request=" + requestId
                + " mode=" + (deliverUi ? "result" : "document")
                + " bitmap=" + bitmapSize(b)
                + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));

        if (deliverUi && service != null) service.onCircleRecognizeStarted();
        if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0) {
            fail(app, service, callback, deliverUi, requestId, "ocr_invalid_bitmap",
                    "OCR失败: 图片无效", new IllegalArgumentException("invalid bitmap"));
            return;
        }
        startSelectedEngine(app, service, b, anchor, callback, deliverUi, requestId);
    }

    private static void startSelectedEngine(Context app, FloatService service, Bitmap source, Rect anchor,
                                            DocumentCallback callback, boolean deliverUi, long requestId) {
        if (stale(app, requestId, "engine_start")) return;
        int mode = readOcrEngineModeSafely(app);
        boolean smallReady = OcrModelManager.isReady(app, OcrModelManager.SMALL);
        boolean mediumReady = OcrModelManager.isReady(app, OcrModelManager.MEDIUM);
        DiagnosticLog.i(app, "OCR_ENGINE", "request=" + requestId + " mode=" + mode
                + " small=" + smallReady + " medium=" + mediumReady);

        if (mode == 3) {
            startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                    "manual_mlkit", requestId);
        } else if (mode == 1) {
            runPaddle(app, service, source, anchor, callback, deliverUi,
                    OcrModelManager.MEDIUM, false, null, requestId);
        } else if (mode == 2) {
            runPaddle(app, service, source, anchor, callback, deliverUi,
                    OcrModelManager.SMALL, false, null, requestId);
        } else if (smallReady) {
            runPaddle(app, service, source, anchor, callback, deliverUi,
                    OcrModelManager.SMALL, true, null, requestId);
        } else if (mediumReady) {
            runPaddle(app, service, source, anchor, callback, deliverUi,
                    OcrModelManager.MEDIUM, true, null, requestId);
        } else {
            if (deliverUi) {
                Toast.makeText(app, "未下载 PP-OCRv6 模型，暂用 ML Kit；可在设置中下载",
                        Toast.LENGTH_SHORT).show();
            }
            startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                    "no_local_model", requestId);
        }
    }

    private static void runPaddle(Context app, FloatService service, Bitmap source, Rect anchor,
                                  DocumentCallback callback, boolean deliverUi,
                                  int model, boolean auto, OcrDocument previous, long requestId) {
        if (stale(app, requestId, "paddle_start")) return;
        if (!OcrModelManager.isReady(app, model)) {
            if (auto) {
                startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                        "local_model_missing", requestId);
            } else {
                fail(app, service, callback, deliverUi, requestId, "ppocr_model_missing",
                        "请先在设置中下载 " + OcrModelManager.displayName(model),
                        new IllegalStateException("PP-OCR model missing"));
            }
            return;
        }

        PaddleOcrBridge.recognize(app, source, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(OcrDocument raw, long totalMs, int lineCount) {
                if (stale(app, requestId, "paddle_success")) return;
                double score = paddleScore(raw.fullText(), raw.confidence(), raw.blocks().size());
                OcrDocument now = new OcrDocument(raw.fullText(), raw.blocks(), raw.lines(),
                        raw.engine(), raw.confidence(), score, source.getWidth(), source.getHeight());
                DiagnosticLog.i(app, "PPOCRV6", "success request=" + requestId + " model=" + model
                        + " chars=" + now.chars().size() + " lines=" + lineCount
                        + " avgConf=" + now.confidence() + " totalMs=" + totalMs);

                if (now.fullText().isBlank()) {
                    if (previous != null && !previous.fullText().isBlank()) {
                        deliver(app, service, source, anchor, callback, deliverUi, previous, requestId);
                    } else if (auto) {
                        startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                                "ppocr_empty", requestId);
                    } else {
                        fail(app, service, callback, deliverUi, requestId, "ppocr_empty",
                                "未识别到文字", new IllegalStateException("PP-OCR empty"));
                    }
                    return;
                }

                if (auto && model == OcrModelManager.SMALL
                        && OcrModelManager.isReady(app, OcrModelManager.MEDIUM)
                        && shouldEscalate(now)) {
                    runPaddle(app, service, source, anchor, callback, deliverUi,
                            OcrModelManager.MEDIUM, true, now, requestId);
                    return;
                }
                deliver(app, service, source, anchor, callback, deliverUi,
                        chooseBetter(previous, now), requestId);
            }

            @Override public void onFailure(String message) {
                if (stale(app, requestId, "paddle_failure")) return;
                if (previous != null && !previous.fullText().isBlank()) {
                    deliver(app, service, source, anchor, callback, deliverUi, previous, requestId);
                } else if (auto) {
                    startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                            "ppocr_failure:" + message, requestId);
                } else {
                    fail(app, service, callback, deliverUi, requestId, "ppocr_failure",
                            "PP-OCRv6 失败: " + message, new IllegalStateException(message));
                }
            }
        });
    }

    private static boolean shouldEscalate(OcrDocument d) {
        if (d == null || d.fullText().isBlank()) return true;
        if (d.confidence() < 0.82f) return true;
        int meaningful = 0;
        for (int offset = 0; offset < d.fullText().length();) {
            int cp = d.fullText().codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || isCjk(cp)) meaningful++;
        }
        return meaningful < 6 || meaningful * 2 < d.fullText().codePointCount(0, d.fullText().length());
    }

    private static OcrDocument chooseBetter(OcrDocument a, OcrDocument b) {
        if (a == null || a.fullText().isBlank()) return b;
        if (b == null || b.fullText().isBlank()) return a;
        return b.score() >= a.score() ? b : a;
    }

    private static double paddleScore(String text, float confidence, int blocks) {
        int length = text == null ? 0 : text.codePointCount(0, text.length());
        return confidence * 1000.0 + Math.min(300, length) + Math.min(12, blocks) * 5.0;
    }

    private static void startMlKitPipeline(Context app, FloatService service, Bitmap source, Rect anchor,
                                           DocumentCallback callback, boolean deliverUi,
                                           String reason, long requestId) {
        if (stale(app, requestId, "mlkit_start")) return;
        try {
            Set<String> languages = OcrLanguages.get(app);
            boolean chinese = OcrLanguages.chineseEnabled(languages);
            boolean english = OcrLanguages.englishEnabled(languages);
            if (!chinese && !english) { chinese = true; english = true; }

            ArrayList<PassSpec> plan = new ArrayList<>();
            if (chinese) plan.add(new PassSpec("zh-original", OcrImagePreprocessor.MODE_ORIGINAL, true));
            if (english) plan.add(new PassSpec("latin-original", OcrImagePreprocessor.MODE_ORIGINAL, false));
            if (chinese) plan.add(new PassSpec("zh-enhanced", OcrImagePreprocessor.MODE_ENHANCED, true));
            if (english) plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.MODE_ENHANCED, false));
            if (chinese) plan.add(new PassSpec("zh-mono", OcrImagePreprocessor.MODE_MONO, true));
            if (english) plan.add(new PassSpec("latin-mono", OcrImagePreprocessor.MODE_MONO, false));

            DiagnosticLog.i(app, "OCR_PIPELINE", "start request=" + requestId
                    + " passes=" + plan.size() + " reason=" + reason);
            new MlRunState(app, service, source, anchor, callback, deliverUi, plan, requestId).next();
        } catch (Throwable t) {
            fail(app, service, callback, deliverUi, requestId, "mlkit_init_failure",
                    "OCR失败: " + safe(t), t);
        }
    }

    private static final class PassSpec {
        final String name; final int mode; final boolean chinese;
        PassSpec(String name, int mode, boolean chinese) {
            this.name = name; this.mode = mode; this.chinese = chinese;
        }
    }

    private static final class MlRunState {
        final Context app; final FloatService service; final Bitmap source; final Rect anchor;
        final DocumentCallback callback; final boolean deliverUi; final List<PassSpec> plan;
        final long requestId; final List<OcrDocument> results = new ArrayList<>();
        int index;

        MlRunState(Context app, FloatService service, Bitmap source, Rect anchor,
                   DocumentCallback callback, boolean deliverUi, List<PassSpec> plan, long requestId) {
            this.app = app; this.service = service; this.source = source; this.anchor = anchor;
            this.callback = callback; this.deliverUi = deliverUi; this.plan = plan; this.requestId = requestId;
        }

        void next() {
            if (stale(app, requestId, "mlkit_next")) return;
            if (index >= plan.size()) { MAIN.post(this::finishOnMain); return; }
            PassSpec spec = plan.get(index++);
            try { PREP_EXECUTOR.execute(() -> prepareAndRun(spec)); }
            catch (Throwable t) { DiagnosticLog.i(app, "OCR_EXECUTOR_FAIL", safe(t)); next(); }
        }

        private void prepareAndRun(PassSpec spec) {
            if (stale(app, requestId, "mlkit_prepare_" + spec.name) || source.isRecycled()) return;
            OcrImagePreprocessor.Prepared prepared;
            try { prepared = OcrImagePreprocessor.prepare(source, spec.mode); }
            catch (Throwable t) { next(); return; }
            if (prepared == null || prepared.bitmap == null || prepared.bitmap.isRecycled()) { next(); return; }
            if (stale(app, requestId, "mlkit_prepared_" + spec.name)) {
                OcrImagePreprocessor.recycle(prepared); return;
            }

            TextRecognizer client = null;
            try {
                client = spec.chinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                TextRecognizer finalClient = client;
                client.process(InputImage.fromBitmap(prepared.bitmap, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                if (!stale(app, requestId, "mlkit_success_" + spec.name)) {
                                    OcrDocument doc = mlDocument(spec.name, text, prepared, source.getWidth(), source.getHeight());
                                    if (!doc.fullText().isBlank()) results.add(doc);
                                    DiagnosticLog.i(app, "OCR_PASS", spec.name + " chars=" + doc.chars().size()
                                            + " score=" + Math.round(doc.score()));
                                }
                            } finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            next();
                        })
                        .addOnFailureListener(error -> {
                            try { DiagnosticLog.i(app, "OCR_PASS", spec.name + " failure=" + safe(error)); }
                            finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            next();
                        });
            } catch (Throwable t) {
                if (client != null) try { client.close(); } catch (Throwable ignored) {}
                OcrImagePreprocessor.recycle(prepared);
                next();
            }
        }

        private void finishOnMain() {
            if (stale(app, requestId, "mlkit_finish")) return;
            OcrDocument best = null;
            for (OcrDocument d : results) if (best == null || d.score() > best.score()) best = d;
            if (best == null || best.fullText().isBlank()) {
                fail(app, service, callback, deliverUi, requestId, "ocr_empty",
                        "未识别到文字", new IllegalStateException("ML Kit empty"));
                return;
            }
            deliver(app, service, source, anchor, callback, deliverUi, best, requestId);
        }
    }

    private static OcrDocument mlDocument(String passName, Text text,
                                          OcrImagePreprocessor.Prepared prepared,
                                          int imageWidth, int imageHeight) {
        String full = text == null || text.getText() == null ? "" : text.getText().trim();
        ArrayList<String> blocks = new ArrayList<>();
        ArrayList<DraftLine> drafts = new ArrayList<>();
        int elementCount = 0;
        if (text != null) {
            for (Text.TextBlock block : text.getTextBlocks()) {
                if (block.getText() != null && !block.getText().isBlank()) blocks.add(block.getText());
                for (Text.Line line : block.getLines()) {
                    Rect lineBox = prepared.toSourceRect(line.getBoundingBox());
                    String lineText = line.getText() == null ? "" : line.getText().trim();
                    if (lineText.isBlank() || lineBox.isEmpty()) continue;
                    ArrayList<DraftChar> chars = new ArrayList<>();
                    int group = 0;
                    for (Text.Element element : line.getElements()) {
                        elementCount++;
                        String value = element.getText() == null ? "" : element.getText();
                        Rect elementBox = prepared.toSourceRect(element.getBoundingBox());
                        if (value.isBlank() || elementBox.isEmpty()) continue;
                        List<Text.Symbol> symbols;
                        try { symbols = element.getSymbols(); } catch (Throwable ignored) { symbols = List.of(); }
                        StringBuilder combined = new StringBuilder();
                        ArrayList<DraftChar> symbolChars = new ArrayList<>();
                        if (symbols != null) {
                            for (Text.Symbol symbol : symbols) {
                                if (symbol == null || symbol.getText() == null) continue;
                                String sv = symbol.getText();
                                Rect sr = prepared.toSourceRect(symbol.getBoundingBox());
                                if (sv.isBlank() || sr.isEmpty()) continue;
                                combined.append(sv);
                                symbolChars.add(new DraftChar(sv, sr, group));
                            }
                        }
                        if (!symbolChars.isEmpty()
                                && combined.toString().replace(" ", "").equals(value.replace(" ", ""))) {
                            chars.addAll(symbolChars);
                        } else {
                            splitElement(chars, value, elementBox, group);
                        }
                        group++;
                    }
                    if (chars.isEmpty()) splitElement(chars, lineText, lineBox, 0);
                    drafts.add(new DraftLine(lineText, lineBox, chars));
                }
            }
        }

        drafts.sort(Comparator
                .comparingInt((DraftLine l) -> l.bounds.centerY())
                .thenComparingInt(l -> l.bounds.left));
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int order = 0;
        for (int li = 0; li < drafts.size(); li++) {
            DraftLine d = drafts.get(li);
            d.chars.sort(Comparator.comparingInt((DraftChar c) -> c.bounds.left)
                    .thenComparingInt(c -> c.bounds.top));
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (DraftChar c : d.chars) {
                if (c.text.isBlank()) continue;
                chars.add(new OcrDocument.CharUnit(c.text, c.bounds, 0f, li, c.group, order++));
            }
            lines.add(new OcrDocument.Line(d.text, d.bounds, 0f, chars));
        }
        double score = textScore(full, blocks.size(), lines.size(), elementCount);
        return new OcrDocument(full, blocks, lines, passName, 0f, score, imageWidth, imageHeight);
    }

    private static void splitElement(List<DraftChar> out, String text, Rect box, int group) {
        if (text == null || text.isEmpty() || box == null || box.isEmpty()) return;
        int[] cps = text.codePoints().toArray();
        int visible = 0;
        for (int cp : cps) if (!Character.isWhitespace(cp)) visible++;
        if (visible == 0) return;
        int index = 0;
        for (int cp : cps) {
            if (Character.isWhitespace(cp)) continue;
            int left = box.left + box.width() * index / visible;
            int right = box.left + box.width() * (index + 1) / visible;
            out.add(new DraftChar(new String(Character.toChars(cp)),
                    new Rect(left, box.top, Math.max(left + 1, right), box.bottom), group));
            index++;
        }
    }

    private static final class DraftLine {
        final String text; final Rect bounds; final ArrayList<DraftChar> chars;
        DraftLine(String text, Rect bounds, ArrayList<DraftChar> chars) {
            this.text = text; this.bounds = new Rect(bounds); this.chars = chars;
        }
    }
    private static final class DraftChar {
        final String text; final Rect bounds; final int group;
        DraftChar(String text, Rect bounds, int group) {
            this.text = text; this.bounds = new Rect(bounds); this.group = group;
        }
    }

    private static void deliver(Context app, FloatService service, Bitmap source, Rect anchor,
                                DocumentCallback callback, boolean deliverUi,
                                OcrDocument document, long requestId) {
        if (document == null || document.fullText().isBlank() || stale(app, requestId, "deliver")) return;
        MAIN.post(() -> {
            if (stale(app, requestId, "deliver_main")) return;
            if (callback != null) {
                try { callback.onSuccess(document); }
                catch (Throwable t) { DiagnosticLog.i(app, "OCR_DISPATCH", "callback failed=" + safe(t)); }
                return;
            }
            if (!deliverUi) return;
            if (service != null) service.onOcrResults(Math.max(1, document.chars().size()));
            OcrResultDispatcher.deliver(app, document.fullText(), document.blocks(), source, anchor);
            DiagnosticLog.i(app, "OCR_DISPATCH", "engine=" + document.engine()
                    + " chars=" + document.chars().size() + " lines=" + document.lines().size());
        });
    }

    private static void fail(Context app, FloatService service, DocumentCallback callback,
                             boolean deliverUi, long requestId, String reason,
                             String userMessage, Throwable error) {
        if (stale(app, requestId, "fail_" + reason)) return;
        MAIN.post(() -> {
            if (stale(app, requestId, "fail_main_" + reason)) return;
            if (callback != null) {
                try { callback.onFailure(error == null ? new IllegalStateException(reason) : error); }
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
        } catch (Throwable t) { DiagnosticLog.i(app, "OCR_ENGINE", "read fallback=" + safe(t)); }
        return 0;
    }

    private static double textScore(String value, int blocks, int lines, int elements) {
        if (value == null || value.isBlank()) return -1_000_000d;
        int meaningful = 0, cjk = 0, letters = 0, digits = 0, garbage = 0, punctuation = 0, visible = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            visible++;
            if (isCjk(cp)) { cjk++; meaningful++; }
            else if (Character.isLetter(cp)) { letters++; meaningful++; }
            else if (Character.isDigit(cp)) { digits++; meaningful++; }
            else if (cp == 0xFFFD || Character.isISOControl(cp) || (cp >= 0xE000 && cp <= 0xF8FF)) garbage++;
            else punctuation++;
        }
        if (meaningful == 0) return -50_000d + visible;
        double readableRatio = meaningful / (double) Math.max(1, visible);
        return meaningful * 8.0 + cjk * 2.2 + letters * 0.7 + digits * 0.5
                + Math.min(12, blocks) * 5.0 + Math.min(30, lines) * 2.5
                + Math.min(60, elements) * 0.8 + readableRatio * 25.0
                - garbage * 24.0 - Math.max(0, punctuation - meaningful / 2) * 2.0;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static String bitmapSize(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
    }
    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private OcrEngine() {}
}
