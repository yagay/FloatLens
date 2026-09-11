package com.yagay.floatlens;

import android.content.Context;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** High-accuracy OCR with sequential low-memory preprocessing and recognition passes. */
public final class OcrEngine {
    private static final ExecutorService PREP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-OCR-Prep");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void recognize(Context c, Bitmap b) {
        recognize(c, b, null);
    }

    public static void recognize(Context c, Bitmap b, Rect anchor) {
        Context app = c.getApplicationContext();
        Rect resultAnchor = anchor == null ? null : new Rect(anchor);
        FloatService service = FloatService.get();

        DiagnosticLog.i(app, "OCR_REQUEST", "bitmap="
                + (b == null ? "null" : b.getWidth() + "x" + b.getHeight())
                + " recycled=" + (b != null && b.isRecycled())
                + " anchor=" + (resultAnchor == null ? "none" : resultAnchor.toShortString()));

        if (service != null) service.onCircleRecognizeStarted();
        if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0) {
            if (service != null) service.onCircleFinished("ocr_invalid_bitmap");
            Toast.makeText(app, "OCR失败: 图片无效", Toast.LENGTH_SHORT).show();
            return;
        }

        FloatSettings fs = new FloatSettings(app);
        ArrayList<PassSpec> plan = new ArrayList<>();
        if (fs.ocrType() == 1) {
            plan.add(new PassSpec("latin-original", OcrImagePreprocessor.Mode.ORIGINAL, false));
            plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.Mode.ENHANCED, false));
            plan.add(new PassSpec("latin-mono", OcrImagePreprocessor.Mode.MONO, false));
        } else {
            plan.add(new PassSpec("zh-original", OcrImagePreprocessor.Mode.ORIGINAL, true));
            plan.add(new PassSpec("latin-original", OcrImagePreprocessor.Mode.ORIGINAL, false));
            plan.add(new PassSpec("zh-enhanced", OcrImagePreprocessor.Mode.ENHANCED, true));
            plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.Mode.ENHANCED, false));
            plan.add(new PassSpec("zh-mono", OcrImagePreprocessor.Mode.MONO, true));
        }

        DiagnosticLog.i(app, "OCR_PIPELINE", "start highAccuracy=serial type=" + fs.ocrType()
                + " source=" + b.getWidth() + "x" + b.getHeight()
                + " passes=" + plan.size());
        new RunState(app, service, b, resultAnchor, plan).next();
    }

    private static final class RunState {
        final Context app;
        final FloatService service;
        final Bitmap source;
        final Rect anchor;
        final List<PassSpec> plan;
        final List<Candidate> results = new ArrayList<>();
        int index;

        RunState(Context app, FloatService service, Bitmap source, Rect anchor, List<PassSpec> plan) {
            this.app = app;
            this.service = service;
            this.source = source;
            this.anchor = anchor;
            this.plan = plan;
        }

        void next() {
            if (index >= plan.size()) {
                MAIN.post(this::finishOnMain);
                return;
            }
            PassSpec spec = plan.get(index++);
            PREP_EXECUTOR.execute(() -> prepareAndRun(spec));
        }

        private void prepareAndRun(PassSpec spec) {
            if (source.isRecycled()) {
                DiagnosticLog.i(app, "OCR_PASS", spec.name + " skipped=source_recycled");
                next();
                return;
            }

            DiagnosticLog.i(app, "OCR_PREP", spec.name + " begin mode=" + spec.mode);
            OcrImagePreprocessor.Prepared prepared = OcrImagePreprocessor.prepare(source, spec.mode);
            if (prepared == null || prepared.bitmap() == null || prepared.bitmap().isRecycled()) {
                DiagnosticLog.i(app, "OCR_PREP", spec.name + " failed; skip");
                next();
                return;
            }
            DiagnosticLog.i(app, "OCR_PREP", spec.name + " ready="
                    + prepared.bitmap().getWidth() + "x" + prepared.bitmap().getHeight()
                    + " owned=" + prepared.owned());

            TextRecognizer client = null;
            try {
                client = spec.chinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                TextRecognizer finalClient = client;
                DiagnosticLog.i(app, "OCR_PASS", spec.name + " launch");
                client.process(InputImage.fromBitmap(prepared.bitmap(), 0))
                        .addOnSuccessListener(t -> {
                            try {
                                Candidate candidate = candidate(spec.name, t);
                                if (!candidate.full.isBlank()) results.add(candidate);
                                DiagnosticLog.i(app, "OCR_PASS", spec.name
                                        + " chars=" + candidate.full.length()
                                        + " blocks=" + candidate.blocks.size()
                                        + " score=" + Math.round(candidate.score));
                            } catch (Throwable error) {
                                DiagnosticLog.i(app, "OCR_PASS", spec.name + " parseFailure=" + safe(error));
                            } finally {
                                try { finalClient.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                                next();
                            }
                        })
                        .addOnFailureListener(e -> {
                            DiagnosticLog.i(app, "OCR_PASS", spec.name + " failure=" + safe(e));
                            try { finalClient.close(); } catch (Throwable ignored) {}
                            OcrImagePreprocessor.recycle(prepared);
                            next();
                        });
            } catch (Throwable t) {
                if (client != null) try { client.close(); } catch (Throwable ignored) {}
                OcrImagePreprocessor.recycle(prepared);
                DiagnosticLog.i(app, "OCR_PASS", spec.name + " launchFailure=" + safe(t));
                next();
            }
        }

        private void finishOnMain() {
            Candidate best = null;
            for (Candidate c : results) {
                if (best == null || c.score > best.score) best = c;
            }
            if (best == null || best.full.isBlank()) {
                DiagnosticLog.i(app, "OCR_PIPELINE", "finish empty successfulPasses=" + results.size());
                if (service != null) service.onCircleFinished("ocr_empty");
                Toast.makeText(app, "未识别到文字", Toast.LENGTH_SHORT).show();
                return;
            }

            DiagnosticLog.i(app, "OCR_PIPELINE", "selected pass=" + best.passName
                    + " score=" + Math.round(best.score)
                    + " chars=" + best.full.length()
                    + " candidates=" + best.blocks.size()
                    + " successfulPasses=" + results.size());
            if (service != null) service.onOcrResults(best.blocks.size());

            if (!ResultTextActivity.show(app, best.full, best.blocks, source, anchor)) {
                DiagnosticLog.i(app, "RESULT_TEXT_ACTIVITY", "fallback to overlay");
                ResultOverlay.show(app, best.full, best.blocks, source, anchor);
            }
        }
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

    private record PassSpec(String name, OcrImagePreprocessor.Mode mode, boolean chinese) {}

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
