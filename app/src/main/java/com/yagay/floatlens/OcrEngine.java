package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-accuracy OCR pipeline for screenshots.
 *
 * Instead of trusting one recognition pass, FloatLens now runs multiple complementary passes over
 * the original and preprocessed image, scores the returned text, and presents the strongest result.
 */
public final class OcrEngine {
    public static void recognize(Context c, Bitmap b) {
        recognize(c, b, null);
    }

    /** Anchor is the selected View/region in screen coordinates; null keeps the centered fallback. */
    public static void recognize(Context c, Bitmap b, Rect anchor) {
        Context app = c.getApplicationContext();
        Rect resultAnchor = anchor == null ? null : new Rect(anchor);
        FloatService service = FloatService.get();
        if (service != null) service.onCircleRecognizeStarted();

        if (b == null || b.isRecycled() || b.getWidth() <= 0 || b.getHeight() <= 0) {
            if (service != null) service.onCircleFinished("ocr_invalid_bitmap");
            Toast.makeText(app, "OCR失败: 图片无效", Toast.LENGTH_SHORT).show();
            return;
        }

        FloatSettings fs = new FloatSettings(app);
        List<OcrImagePreprocessor.Variant> variants = OcrImagePreprocessor.build(b);
        OcrImagePreprocessor.Variant original = find(variants, "original");
        OcrImagePreprocessor.Variant enhanced = find(variants, "enhanced");
        OcrImagePreprocessor.Variant mono = find(variants, "mono");

        ArrayList<Pass> passes = new ArrayList<>();
        if (fs.ocrType() == 1) {
            add(passes, "latin-original", original, false);
            add(passes, "latin-enhanced", enhanced, false);
            add(passes, "latin-mono", mono, false);
        } else {
            // Chinese recognizer is the primary path for mixed Chinese/Latin UI text. Latin passes
            // remain valuable for English-only labels, serial numbers and dense ASCII text.
            add(passes, "zh-original", original, true);
            add(passes, "zh-enhanced", enhanced, true);
            add(passes, "zh-mono", mono, true);
            add(passes, "latin-original", original, false);
            add(passes, "latin-enhanced", enhanced, false);
        }

        if (passes.isEmpty()) {
            OcrImagePreprocessor.recycleOwned(variants);
            if (service != null) service.onCircleFinished("ocr_no_passes");
            Toast.makeText(app, "OCR失败: 无可用识别图像", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder variantLog = new StringBuilder();
        for (OcrImagePreprocessor.Variant v : variants) {
            if (variantLog.length() > 0) variantLog.append(' ');
            variantLog.append(v.name()).append('=').append(v.bitmap().getWidth())
                    .append('x').append(v.bitmap().getHeight());
        }
        DiagnosticLog.i(app, "OCR_PIPELINE", "start highAccuracy=true type=" + fs.ocrType()
                + " source=" + b.getWidth() + "x" + b.getHeight()
                + " variants=[" + variantLog + "] passes=" + passes.size()
                + " anchor=" + (resultAnchor == null ? "none" : resultAnchor.toShortString()));

        RunState state = new RunState(app, service, b, resultAnchor, variants, passes.size());
        for (Pass pass : passes) runPass(state, pass);
    }

    private static void runPass(RunState state, Pass pass) {
        TextRecognizer client = null;
        try {
            client = pass.chinese
                    ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                    : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            TextRecognizer finalClient = client;
            client.process(InputImage.fromBitmap(pass.variant.bitmap(), 0))
                    .addOnSuccessListener(t -> {
                        try {
                            Candidate candidate = candidate(pass.name, t);
                            state.add(candidate);
                            DiagnosticLog.i(state.app, "OCR_PASS", pass.name
                                    + " chars=" + candidate.full.length()
                                    + " blocks=" + candidate.blocks.size()
                                    + " score=" + Math.round(candidate.score));
                        } catch (Throwable error) {
                            DiagnosticLog.i(state.app, "OCR_PASS", pass.name + " parseFailure=" + safe(error));
                        } finally {
                            try { finalClient.close(); } catch (Throwable ignored) {}
                            state.done();
                        }
                    })
                    .addOnFailureListener(e -> {
                        DiagnosticLog.i(state.app, "OCR_PASS", pass.name + " failure=" + safe(e));
                        try { finalClient.close(); } catch (Throwable ignored) {}
                        state.done();
                    });
        } catch (Throwable t) {
            if (client != null) try { client.close(); } catch (Throwable ignored) {}
            DiagnosticLog.i(state.app, "OCR_PASS", pass.name + " launchFailure=" + safe(t));
            state.done();
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

    /** Heuristic quality score: reward readable language content and structure, punish OCR garbage. */
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
                cjk++;
                meaningful++;
            } else if (Character.isLetter(cp)) {
                letters++;
                meaningful++;
            } else if (Character.isDigit(cp)) {
                digits++;
                meaningful++;
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

        // Strongly penalize common failure patterns: many replacement/private characters or a result
        // dominated by symbols instead of letters/numbers/CJK glyphs.
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

    private static OcrImagePreprocessor.Variant find(List<OcrImagePreprocessor.Variant> list, String name) {
        if (list == null) return null;
        for (OcrImagePreprocessor.Variant v : list) if (v != null && name.equals(v.name())) return v;
        return null;
    }

    private static void add(List<Pass> passes, String name, OcrImagePreprocessor.Variant variant, boolean chinese) {
        if (variant != null && variant.bitmap() != null && !variant.bitmap().isRecycled()) {
            passes.add(new Pass(name, variant, chinese));
        }
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private record Pass(String name, OcrImagePreprocessor.Variant variant, boolean chinese) {}

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

    private static final class RunState {
        final Context app;
        final FloatService service;
        final Bitmap source;
        final Rect anchor;
        final List<OcrImagePreprocessor.Variant> variants;
        final AtomicInteger remaining;
        final List<Candidate> results = Collections.synchronizedList(new ArrayList<>());

        RunState(Context app, FloatService service, Bitmap source, Rect anchor,
                 List<OcrImagePreprocessor.Variant> variants, int passCount) {
            this.app = app;
            this.service = service;
            this.source = source;
            this.anchor = anchor;
            this.variants = variants;
            this.remaining = new AtomicInteger(passCount);
        }

        void add(Candidate c) {
            if (c != null && !c.full.isBlank()) results.add(c);
        }

        void done() {
            if (remaining.decrementAndGet() != 0) return;
            finish();
        }

        void finish() {
            try {
                Candidate best = null;
                synchronized (results) {
                    for (Candidate c : results) {
                        if (best == null || c.score > best.score) best = c;
                    }
                }
                if (best == null || best.full.isBlank()) {
                    if (service != null) service.onCircleFinished("ocr_empty");
                    Toast.makeText(app, "未识别到文字", Toast.LENGTH_SHORT).show();
                    return;
                }

                DiagnosticLog.i(app, "OCR_PIPELINE", "selected pass=" + best.passName
                        + " score=" + Math.round(best.score)
                        + " chars=" + best.full.length()
                        + " candidates=" + best.blocks.size()
                        + " totalSuccessfulPasses=" + results.size());
                if (service != null) service.onOcrResults(best.blocks.size());

                if (!ResultTextActivity.show(app, best.full, best.blocks, source, anchor)) {
                    DiagnosticLog.i(app, "RESULT_TEXT_ACTIVITY", "fallback to overlay");
                    ResultOverlay.show(app, best.full, best.blocks, source, anchor);
                }
            } finally {
                OcrImagePreprocessor.recycleOwned(variants);
            }
        }
    }

    private OcrEngine() {}
}
