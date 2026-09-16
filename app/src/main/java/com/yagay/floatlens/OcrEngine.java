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

/**
 * Normal OCR execution strategy.
 *
 * PP-OCR escalation, ML Kit multi-script fusion and UI/document generations live here. The actual
 * ML Kit Text -> OcrDocument geometry conversion is shared with Circle through MlKitTextCore.
 */
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
    private static final int ML_TIER_ORIGINAL = 0;
    private static final int ML_TIER_ENHANCED = 1;
    private static final int ML_TIER_MONO = 2;

    private static final AtomicLong UI_GENERATION = new AtomicLong(0L);
    private static final AtomicLong DOCUMENT_GENERATION = new AtomicLong(0L);

    public static void invalidatePending(Context c, String reason) {
        long generation = UI_GENERATION.incrementAndGet();
        if (c != null) {
            DiagnosticLog.i(c.getApplicationContext(), "OCR_SESSION", "invalidate lane=ui generation="
                    + generation + " reason=" + (reason == null ? "unknown" : reason));
        }
    }

    public static void invalidateDocumentPending(Context c, String reason) {
        long generation = DOCUMENT_GENERATION.incrementAndGet();
        if (c != null) {
            DiagnosticLog.i(c.getApplicationContext(), "OCR_SESSION", "invalidate lane=document generation="
                    + generation + " reason=" + (reason == null ? "unknown" : reason));
        }
    }

    public static void recognize(Context c, Bitmap b) { recognize(c, b, null); }

    public static void recognize(Context c, Bitmap b, Rect anchor) {
        start(c, b, anchor == null ? null : new Rect(anchor), null, true);
    }

    public static void recognizeDocument(Context c, Bitmap b, DocumentCallback callback) {
        start(c, b, null, callback, false);
    }

    private static void start(Context c, Bitmap b, Rect anchor,
                              DocumentCallback callback, boolean deliverUi) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        FloatService service = deliverUi ? FloatService.get() : null;
        AtomicLong generation = deliverUi ? UI_GENERATION : DOCUMENT_GENERATION;
        long requestId = generation.incrementAndGet();
        DiagnosticLog.i(app, "OCR_REQUEST", "request=" + requestId
                + " lane=" + (deliverUi ? "ui" : "document")
                + " bitmap=" + bitmapSize(b)
                + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));

        if (deliverUi && service != null) service.onCircleRecognizeStarted();
        if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0) {
            fail(app, service, callback, deliverUi, generation, requestId, "ocr_invalid_bitmap",
                    "OCR失败: 图片无效", new IllegalArgumentException("invalid bitmap"));
            return;
        }
        startSelectedEngine(app, service, b, anchor, callback, deliverUi, generation, requestId);
    }

    private static void startSelectedEngine(Context app, FloatService service, Bitmap source, Rect anchor,
                                            DocumentCallback callback, boolean deliverUi,
                                            AtomicLong generation, long requestId) {
        if (stale(app, generation, requestId, "engine_start")) return;
        int mode = readOcrEngineModeSafely(app);
        boolean smallReady = OcrModelManager.isReady(app, OcrModelManager.SMALL);
        boolean mediumReady = OcrModelManager.isReady(app, OcrModelManager.MEDIUM);
        DiagnosticLog.i(app, "OCR_ENGINE", "request=" + requestId + " mode=" + mode
                + " lane=" + laneName(generation)
                + " small=" + smallReady + " medium=" + mediumReady);

        if (mode == 3) {
            startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                    "manual_mlkit", generation, requestId);
        } else if (mode == 1) {
            runPaddle(app, service, source, anchor, callback, deliverUi,
                    OcrModelManager.MEDIUM, false, null, generation, requestId);
        } else if (mode == 2) {
            runPaddle(app, service, source, anchor, callback, deliverUi,
                    OcrModelManager.SMALL, false, null, generation, requestId);
        } else if (smallReady) {
            runPaddle(app, service, source, anchor, callback, deliverUi,
                    OcrModelManager.SMALL, true, null, generation, requestId);
        } else if (mediumReady) {
            runPaddle(app, service, source, anchor, callback, deliverUi,
                    OcrModelManager.MEDIUM, true, null, generation, requestId);
        } else {
            if (deliverUi) {
                Toast.makeText(app, "未下载 PP-OCRv6 模型，暂用 ML Kit；可在设置中下载",
                        Toast.LENGTH_SHORT).show();
            }
            startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                    "no_local_model", generation, requestId);
        }
    }

    private static void runPaddle(Context app, FloatService service, Bitmap source, Rect anchor,
                                  DocumentCallback callback, boolean deliverUi,
                                  int model, boolean auto, OcrDocument previous,
                                  AtomicLong generation, long requestId) {
        if (stale(app, generation, requestId, "paddle_start")) return;
        if (!OcrModelManager.isReady(app, model)) {
            if (auto) {
                startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                        "local_model_missing", generation, requestId);
            } else {
                fail(app, service, callback, deliverUi, generation, requestId, "ppocr_model_missing",
                        "请先在设置中下载 " + OcrModelManager.displayName(model),
                        new IllegalStateException("PP-OCR model missing"));
            }
            return;
        }

        PaddleOcrBridge.recognize(app, source, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(OcrDocument raw, long totalMs, int lineCount) {
                if (stale(app, generation, requestId, "paddle_success")) return;
                double score = paddleScore(raw.fullText(), raw.confidence(), raw.blocks().size());
                OcrDocument now = new OcrDocument(raw.fullText(), raw.blocks(), raw.lines(),
                        raw.engine(), raw.confidence(), score, source.getWidth(), source.getHeight());
                DiagnosticLog.i(app, "PPOCRV6", "success request=" + requestId + " model=" + model
                        + " lane=" + laneName(generation)
                        + " chars=" + now.chars().size() + " lines=" + lineCount
                        + " avgConf=" + now.confidence() + " totalMs=" + totalMs);

                if (now.fullText().isBlank()) {
                    if (previous != null && !previous.fullText().isBlank()) {
                        deliver(app, service, source, anchor, callback, deliverUi,
                                previous, generation, requestId);
                    } else if (auto) {
                        startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                                "ppocr_empty", generation, requestId);
                    } else {
                        fail(app, service, callback, deliverUi, generation, requestId, "ppocr_empty",
                                "未识别到文字", new IllegalStateException("PP-OCR empty"));
                    }
                    return;
                }

                if (auto && model == OcrModelManager.SMALL
                        && OcrModelManager.isReady(app, OcrModelManager.MEDIUM)
                        && shouldEscalate(now)) {
                    runPaddle(app, service, source, anchor, callback, deliverUi,
                            OcrModelManager.MEDIUM, true, now, generation, requestId);
                    return;
                }
                deliver(app, service, source, anchor, callback, deliverUi,
                        chooseBetter(previous, now), generation, requestId);
            }

            @Override public void onFailure(String message) {
                if (stale(app, generation, requestId, "paddle_failure")) return;
                if (previous != null && !previous.fullText().isBlank()) {
                    deliver(app, service, source, anchor, callback, deliverUi,
                            previous, generation, requestId);
                } else if (auto) {
                    startMlKitPipeline(app, service, source, anchor, callback, deliverUi,
                            "ppocr_failure:" + message, generation, requestId);
                } else {
                    fail(app, service, callback, deliverUi, generation, requestId, "ppocr_failure",
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

    /**
     * Fast ML Kit mode: run the Chinese (Hans/Hant) and Latin recognizers on the original bitmap in
     * parallel, then fuse their character geometry. No enhanced/mono preprocessing passes are used.
     */
    private static void startMlKitPipeline(Context app, FloatService service, Bitmap source, Rect anchor,
                                           DocumentCallback callback, boolean deliverUi,
                                           String reason, AtomicLong generation, long requestId) {
        if (stale(app, generation, requestId, "mlkit_start")) return;
        try {
            Set<String> languages = OcrLanguages.get(app);
            boolean chinese = OcrLanguages.chineseEnabled(languages);
            boolean english = OcrLanguages.englishEnabled(languages);
            if (!chinese && !english) { chinese = true; english = true; }

            DiagnosticLog.i(app, "OCR_PIPELINE", "start request=" + requestId
                    + " lane=" + laneName(generation)
                    + " strategy=parallel_original_fusion"
                    + " chinese=" + chinese + " latin=" + english
                    + " passes=" + ((chinese ? 1 : 0) + (english ? 1 : 0))
                    + " reason=" + reason);
            new MlFusionState(app, service, source, anchor, callback, deliverUi,
                    chinese, english, generation, requestId).start();
        } catch (Throwable t) {
            fail(app, service, callback, deliverUi, generation, requestId, "mlkit_init_failure",
                    "OCR失败: " + safe(t), t);
        }
    }

    /** App-lifetime recognizers avoid model/client setup on every gesture. */
    private static final class MlChineseHolder {
        static final TextRecognizer INSTANCE = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
    }

    private static final class MlLatinHolder {
        static final TextRecognizer INSTANCE = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    private static TextRecognizer cachedMlRecognizer(boolean chinese) {
        return chinese ? MlChineseHolder.INSTANCE : MlLatinHolder.INSTANCE;
    }

    private static final class MlFusionState {
        final Context app;
        final FloatService service;
        final Bitmap source;
        final Rect anchor;
        final DocumentCallback callback;
        final boolean deliverUi;
        final boolean runChinese;
        final boolean runLatin;
        final AtomicLong generation;
        final long requestId;
        final int expected;
        final long startedMs = android.os.SystemClock.uptimeMillis();

        int completed;
        boolean finished;
        OcrDocument chineseDocument;
        OcrDocument latinDocument;
        Throwable lastError;

        MlFusionState(Context app, FloatService service, Bitmap source, Rect anchor,
                      DocumentCallback callback, boolean deliverUi,
                      boolean runChinese, boolean runLatin,
                      AtomicLong generation, long requestId) {
            this.app = app;
            this.service = service;
            this.source = source;
            this.anchor = anchor;
            this.callback = callback;
            this.deliverUi = deliverUi;
            this.runChinese = runChinese;
            this.runLatin = runLatin;
            this.generation = generation;
            this.requestId = requestId;
            this.expected = (runChinese ? 1 : 0) + (runLatin ? 1 : 0);
        }

        void start() {
            if (expected <= 0) {
                fail(app, service, callback, deliverUi, generation, requestId,
                        "mlkit_no_script", "未启用 OCR 语言",
                        new IllegalStateException("no ML Kit script enabled"));
                return;
            }
            if (runChinese) runPass(true);
            if (runLatin) runPass(false);
        }

        private void runPass(boolean chinese) {
            if (stale(app, generation, requestId, "mlkit_parallel_start")) return;
            final String name = chinese ? "mlkit-zh-hans-hant" : "mlkit-latin-en";
            final long passStarted = android.os.SystemClock.uptimeMillis();
            try {
                TextRecognizer recognizer = cachedMlRecognizer(chinese);
                recognizer.process(InputImage.fromBitmap(source, 0))
                        .addOnSuccessListener(text -> {
                            OcrDocument doc = null;
                            Throwable error = null;
                            try {
                                if (!stale(app, generation, requestId,
                                        "mlkit_parallel_success_" + name)) {
                                    doc = mlOriginalDocument(name, text,
                                            source.getWidth(), source.getHeight());
                                }
                            } catch (Throwable t) {
                                error = t;
                            }
                            complete(chinese, doc, error,
                                    android.os.SystemClock.uptimeMillis() - passStarted);
                        })
                        .addOnFailureListener(error -> complete(chinese, null, error,
                                android.os.SystemClock.uptimeMillis() - passStarted));
            } catch (Throwable t) {
                complete(chinese, null, t,
                        android.os.SystemClock.uptimeMillis() - passStarted);
            }
        }

        private synchronized void complete(boolean chinese, OcrDocument document,
                                           Throwable error, long elapsedMs) {
            if (finished || stale(app, generation, requestId, "mlkit_parallel_complete")) return;
            if (chinese) chineseDocument = document; else latinDocument = document;
            if (error != null) lastError = error;
            completed++;
            DiagnosticLog.i(app, "OCR_PASS", (chinese ? "zh-original" : "latin-original")
                    + " chars=" + (document == null ? 0 : document.chars().size())
                    + " elapsedMs=" + elapsedMs
                    + (error == null ? "" : " failure=" + safe(error)));
            if (completed < expected) return;
            finished = true;
            MAIN.post(this::finishOnMain);
        }

        private void finishOnMain() {
            if (stale(app, generation, requestId, "mlkit_parallel_finish")) return;
            OcrDocument result = fuseMlKitDocuments(chineseDocument, latinDocument,
                    source.getWidth(), source.getHeight());
            if (result == null || result.fullText().isBlank()) {
                fail(app, service, callback, deliverUi, generation, requestId, "ocr_empty",
                        "未识别到文字", lastError == null
                                ? new IllegalStateException("ML Kit empty") : lastError);
                return;
            }
            DiagnosticLog.i(app, "OCR_MLKIT_FUSION", "request=" + requestId
                    + " zhChars=" + (chineseDocument == null ? 0 : chineseDocument.chars().size())
                    + " latinChars=" + (latinDocument == null ? 0 : latinDocument.chars().size())
                    + " fusedChars=" + result.chars().size()
                    + " lines=" + result.lines().size()
                    + " totalMs=" + (android.os.SystemClock.uptimeMillis() - startedMs));
            deliver(app, service, source, anchor, callback, deliverUi,
                    result, generation, requestId);
        }
    }

    private static OcrDocument mlOriginalDocument(String engine, Text text,
                                                  int imageWidth, int imageHeight) {
        OcrDocument parsed = MlKitTextCore.toDocument(
                text, engine, imageWidth, imageHeight, 0f, null);
        double score = textScore(parsed.fullText(), parsed.blocks().size(),
                parsed.lines().size(), parsed.chars().size());
        return new OcrDocument(parsed.fullText(), parsed.blocks(), parsed.lines(),
                parsed.engine(), parsed.confidence(), score, imageWidth, imageHeight);
    }

    private static final class FusionChar {
        final OcrDocument.CharUnit unit;
        final boolean chineseSource;

        FusionChar(OcrDocument.CharUnit unit, boolean chineseSource) {
            this.unit = unit;
            this.chineseSource = chineseSource;
        }
    }

    private static final class FusionRow {
        final ArrayList<FusionChar> chars = new ArrayList<>();
        int centerY;
        int averageHeight;

        FusionRow(FusionChar first) {
            Rect r = first.unit.bounds();
            centerY = r.centerY();
            averageHeight = Math.max(1, r.height());
            chars.add(first);
        }

        boolean accepts(FusionChar candidate) {
            Rect r = candidate.unit.bounds();
            int gate = Math.max(4, Math.round(Math.min(averageHeight,
                    Math.max(1, r.height())) * 0.62f));
            return Math.abs(r.centerY() - centerY) <= gate;
        }

        void add(FusionChar candidate) {
            int n = chars.size();
            Rect r = candidate.unit.bounds();
            centerY = (centerY * n + r.centerY()) / (n + 1);
            averageHeight = Math.max(1, (averageHeight * n + Math.max(1, r.height())) / (n + 1));
            chars.add(candidate);
        }
    }

    /**
     * Fuse the two recognizers at character geometry level. Chinese-script glyphs prefer the
     * Chinese recognizer; Latin letters/digits prefer the Latin recognizer. Overlapping duplicates
     * are removed before rebuilding stable line/group metadata for Circle selection.
     */
    private static OcrDocument fuseMlKitDocuments(OcrDocument chinese, OcrDocument latin,
                                                  int imageWidth, int imageHeight) {
        boolean zhEmpty = chinese == null || chinese.chars().isEmpty();
        boolean latinEmpty = latin == null || latin.chars().isEmpty();
        if (zhEmpty && latinEmpty) return chooseBetter(chinese, latin);
        if (latinEmpty) return chinese;
        if (zhEmpty) return latin;

        ArrayList<FusionChar> merged = new ArrayList<>();
        for (OcrDocument.CharUnit c : chinese.chars()) addFusionChar(merged, new FusionChar(c, true));
        for (OcrDocument.CharUnit c : latin.chars()) addFusionChar(merged, new FusionChar(c, false));
        if (merged.isEmpty()) return chooseBetter(chinese, latin);

        merged.sort((a, b) -> {
            Rect ar = a.unit.bounds(), br = b.unit.bounds();
            int dy = Integer.compare(ar.centerY(), br.centerY());
            return dy != 0 ? dy : Integer.compare(ar.left, br.left);
        });

        ArrayList<FusionRow> rows = new ArrayList<>();
        for (FusionChar c : merged) {
            FusionRow best = null;
            int bestDy = Integer.MAX_VALUE;
            for (FusionRow row : rows) {
                if (!row.accepts(c)) continue;
                int dy = Math.abs(c.unit.bounds().centerY() - row.centerY);
                if (dy < bestDy) { bestDy = dy; best = row; }
            }
            if (best == null) rows.add(new FusionRow(c)); else best.add(c);
        }
        rows.sort((a, b) -> Integer.compare(a.centerY, b.centerY));

        ArrayList<OcrDocument.Line> outLines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int nextGroup = 0;
        int order = 0;

        for (FusionRow row : rows) {
            row.chars.sort((a, b) -> Integer.compare(a.unit.bounds().left, b.unit.bounds().left));
            ArrayList<OcrDocument.CharUnit> outChars = new ArrayList<>();
            Rect lineBounds = null;
            StringBuilder lineText = new StringBuilder();
            String previousGroupKey = null;
            String previousText = "";

            for (FusionChar fc : row.chars) {
                OcrDocument.CharUnit c = fc.unit;
                String value = c.text();
                if (value == null || value.isBlank() || c.bounds().isEmpty()) continue;
                String groupKey = (fc.chineseSource ? "z:" : "l:")
                        + c.line() + ':' + c.group();
                boolean sameGroup = groupKey.equals(previousGroupKey);
                if (!sameGroup) {
                    nextGroup++;
                    if (lineText.length() > 0 && !noSpaceBetweenFusion(previousText, value)) {
                        lineText.append(' ');
                    }
                }
                Rect bounds = c.bounds();
                if (lineBounds == null) lineBounds = new Rect(bounds); else lineBounds.union(bounds);
                outChars.add(new OcrDocument.CharUnit(value, bounds, c.confidence(),
                        lineId, nextGroup, order++));
                lineText.append(value);
                previousGroupKey = groupKey;
                previousText = value;
            }

            String rowText = lineText.toString().trim();
            if (outChars.isEmpty() || lineBounds == null || lineBounds.isEmpty() || rowText.isEmpty()) {
                continue;
            }
            OcrDocument.Line line = new OcrDocument.Line(rowText, lineBounds, 0f, outChars);
            outLines.add(line);
            blocks.add(rowText);
            if (full.length() > 0) full.append('\n');
            full.append(rowText);
            lineId++;
        }

        if (outLines.isEmpty()) return chooseBetter(chinese, latin);
        double score = textScore(full.toString(), blocks.size(), outLines.size(), order);
        return new OcrDocument(full.toString(), blocks, outLines,
                "mlkit-fused-zh-hans-hant+latin-en", 0f, score,
                imageWidth, imageHeight);
    }

    private static void addFusionChar(List<FusionChar> merged, FusionChar candidate) {
        Rect candidateBounds = candidate.unit.bounds();
        if (candidateBounds.isEmpty() || candidate.unit.text().isBlank()) return;
        for (int i = 0; i < merged.size(); i++) {
            FusionChar existing = merged.get(i);
            if (!sameCharRegion(existing.unit.bounds(), candidateBounds)) continue;
            if (preferFusionCandidate(existing, candidate) == candidate) merged.set(i, candidate);
            return;
        }
        merged.add(candidate);
    }

    private static boolean sameCharRegion(Rect a, Rect b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        Rect intersection = new Rect();
        if (!intersection.setIntersect(a, b)) return false;
        long overlap = Math.max(0L, (long) intersection.width() * intersection.height());
        long areaA = Math.max(1L, (long) a.width() * a.height());
        long areaB = Math.max(1L, (long) b.width() * b.height());
        return overlap >= Math.min(areaA, areaB) * 0.34f;
    }

    private static FusionChar preferFusionCandidate(FusionChar a, FusionChar b) {
        boolean aCjk = containsCjk(a.unit.text());
        boolean bCjk = containsCjk(b.unit.text());
        if (aCjk != bCjk) return bCjk ? b : a;
        if (aCjk) {
            if (a.chineseSource != b.chineseSource) return b.chineseSource ? b : a;
            return a;
        }

        boolean aLatin = containsLatinOrDigit(a.unit.text());
        boolean bLatin = containsLatinOrDigit(b.unit.text());
        if (aLatin && bLatin && a.chineseSource != b.chineseSource) {
            return b.chineseSource ? a : b;
        }
        if (aLatin != bLatin) return bLatin ? b : a;
        return a;
    }

    private static boolean containsCjk(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int cp : value.codePoints().toArray()) if (isCjk(cp)) return true;
        return false;
    }

    private static boolean containsLatinOrDigit(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int cp : value.codePoints().toArray()) {
            if (Character.isDigit(cp)) return true;
            if (Character.isLetter(cp) && !isCjk(cp)) return true;
        }
        return false;
    }

    private static boolean noSpaceBetweenFusion(String previous, String current) {
        if (previous == null || previous.isEmpty() || current == null || current.isEmpty()) return true;
        if (containsCjk(previous) || containsCjk(current)) return true;
        int first = current.codePointAt(0);
        int last = previous.codePointBefore(previous.length());
        if (isClosingPunctuation(first)) return true;
        return isOpeningPunctuation(last);
    }

    private static boolean isClosingPunctuation(int cp) {
        return cp == '.' || cp == ',' || cp == ':' || cp == ';' || cp == '!' || cp == '?'
                || cp == ')' || cp == ']' || cp == '}' || cp == '%' || cp == 0x3002
                || cp == 0xFF0C || cp == 0xFF01 || cp == 0xFF1F || cp == 0xFF1A
                || cp == 0xFF1B || cp == 0x3001 || cp == 0x3009 || cp == 0x300B
                || cp == 0x300D || cp == 0x300F || cp == 0x3011;
    }

    private static boolean isOpeningPunctuation(int cp) {
        return cp == '(' || cp == '[' || cp == '{' || cp == 0x3008 || cp == 0x300A
                || cp == 0x300C || cp == 0x300E || cp == 0x3010;
    }

    private static String tierName(int tier) {
        return switch (tier) {
            case ML_TIER_ORIGINAL -> "original";
            case ML_TIER_ENHANCED -> "enhanced";
            default -> "mono";
        };
    }

    private static OcrDocument mlDocument(String passName, Text text,
                                          OcrImagePreprocessor.Prepared prepared,
                                          int imageWidth, int imageHeight) {
        OcrDocument parsed = MlKitTextCore.toDocument(
                text, passName, imageWidth, imageHeight, 0f, prepared::toSourceRect);
        double score = textScore(parsed.fullText(), parsed.blocks().size(),
                parsed.lines().size(), parsed.chars().size());
        return new OcrDocument(parsed.fullText(), parsed.blocks(), parsed.lines(),
                parsed.engine(), parsed.confidence(), score, imageWidth, imageHeight);
    }

    private static void deliver(Context app, FloatService service, Bitmap source, Rect anchor,
                                DocumentCallback callback, boolean deliverUi,
                                OcrDocument document, AtomicLong generation, long requestId) {
        if (document == null || document.fullText().isBlank()
                || stale(app, generation, requestId, "deliver")) return;
        MAIN.post(() -> {
            if (stale(app, generation, requestId, "deliver_main")) return;
            if (callback != null) {
                try {
                    callback.onSuccess(document);
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "OCR_DISPATCH",
                            "success callback failed=" + safe(t));
                    try {
                        callback.onFailure(new IllegalStateException(
                                "OCR success callback failed", t));
                    } catch (Throwable failureError) {
                        DiagnosticLog.i(app, "OCR_DISPATCH",
                                "failure callback also failed=" + safe(failureError));
                    }
                }
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
                             boolean deliverUi, AtomicLong generation, long requestId, String reason,
                             String userMessage, Throwable error) {
        if (stale(app, generation, requestId, "fail_" + reason)) return;
        MAIN.post(() -> {
            if (stale(app, generation, requestId, "fail_main_" + reason)) return;
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

    private static boolean stale(Context app, AtomicLong generation, long requestId, String stage) {
        long current = generation.get();
        if (requestId == current) return false;
        DiagnosticLog.i(app, "OCR_SESSION", "drop stale lane=" + laneName(generation)
                + " request=" + requestId + " current=" + current + " stage=" + stage);
        return true;
    }

    private static String laneName(AtomicLong generation) {
        return generation == DOCUMENT_GENERATION ? "document" : "ui";
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
