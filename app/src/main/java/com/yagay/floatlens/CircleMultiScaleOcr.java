package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Local OCR recovery for tiny logos/stylized text missed by the frozen full-frame OCR index.
 *
 * <p>Every pass goes through {@link OcrEngine#recognizeDocument(Context, Bitmap,
 * OcrEngine.DocumentCallback)}, so the OCR engine always follows the user's main OCR setting.
 * Passes are sequential because OcrEngine's document lane intentionally keeps only one live request.
 * Repeated normalized text wins early; otherwise the best consensus/result is returned after all
 * variants have been tried.</p>
 */
final class CircleMultiScaleOcr {
    interface Callback {
        void onSuccess(OcrDocument document, float scaleX, float scaleY, String variant);
        void onFailure(Throwable error);
    }

    private static final int MAX_EDGE = 1024;
    private static final float[] SCALES = {1f, 2f, 3f, 4f};

    private static final class Candidate {
        final OcrDocument document;
        final float scaleX;
        final float scaleY;
        final String variant;
        final String key;

        Candidate(OcrDocument document, float scaleX, float scaleY,
                  String variant, String key) {
            this.document = document;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.variant = variant;
            this.key = key;
        }
    }

    private static final class Session {
        final Context app;
        final Bitmap source;
        final Callback callback;
        final ArrayList<Candidate> candidates = new ArrayList<>();
        final Map<String, Integer> votes = new HashMap<>();
        int passIndex;
        Throwable lastError;
        boolean finished;

        Session(Context app, Bitmap source, Callback callback) {
            this.app = app;
            this.source = source;
            this.callback = callback;
        }

        void start() {
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "start strategy=main_setting_multiscale"
                            + " input=" + source.getWidth() + "x" + source.getHeight()
                            + " passes=1x,2x,3x,4x,contrast3x"
                            + " earlyConsensus=2");
            runNext();
        }

        private void runNext() {
            if (finished) return;
            if (passIndex >= SCALES.length + 1) {
                finishBest();
                return;
            }

            final boolean contrast = passIndex == SCALES.length;
            final float requestedScale = contrast ? 3f : SCALES[passIndex];
            final String variant = contrast
                    ? "contrast-3x" : String.format(Locale.ROOT, "%.0fx", requestedScale);
            passIndex++;

            final Bitmap input;
            try {
                input = makeVariant(source, requestedScale, contrast);
            } catch (Throwable t) {
                lastError = t;
                DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                        "variant prepare failed variant=" + variant + " error=" + safe(t));
                runNext();
                return;
            }

            final float scaleX = input.getWidth() / (float) Math.max(1, source.getWidth());
            final float scaleY = input.getHeight() / (float) Math.max(1, source.getHeight());
            final long started = android.os.SystemClock.uptimeMillis();
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "pass start variant=" + variant
                            + " bitmap=" + input.getWidth() + "x" + input.getHeight()
                            + " scale=" + String.format(Locale.ROOT, "%.2fx%.2f", scaleX, scaleY)
                            + " engine=main_setting");

            OcrEngine.recognizeDocument(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    recycleVariant(input);
                    if (finished) return;
                    String key = normalize(document == null ? "" : document.fullText());
                    boolean usable = document != null && !key.isEmpty() && !document.chars().isEmpty();
                    int vote = 0;
                    if (usable) {
                        Candidate candidate = new Candidate(document, scaleX, scaleY, variant, key);
                        candidates.add(candidate);
                        vote = votes.getOrDefault(key, 0) + 1;
                        votes.put(key, vote);
                    }
                    DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                            "pass done variant=" + variant
                                    + " usable=" + usable
                                    + " vote=" + vote
                                    + " engine=" + (document == null ? "none" : document.engine())
                                    + " chars=" + (document == null ? 0 : document.chars().size())
                                    + " text=" + summarize(document == null ? "" : document.fullText())
                                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));

                    if (usable && vote >= 2) {
                        finishForKey(key, "consensus");
                    } else {
                        runNext();
                    }
                }

                @Override public void onFailure(Throwable error) {
                    recycleVariant(input);
                    if (finished) return;
                    lastError = error;
                    DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                            "pass failed variant=" + variant
                                    + " error=" + safe(error)
                                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                    runNext();
                }
            });
        }

        private void finishForKey(String key, String reason) {
            if (finished) return;
            Candidate best = null;
            for (Candidate candidate : candidates) {
                if (!candidate.key.equals(key)) continue;
                if (best == null || quality(candidate.document) > quality(best.document)) {
                    best = candidate;
                }
            }
            if (best == null) {
                finishBest();
                return;
            }
            finished = true;
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "finish reason=" + reason
                            + " variant=" + best.variant
                            + " votes=" + votes.getOrDefault(key, 0)
                            + " engine=" + best.document.engine()
                            + " text=" + summarize(best.document.fullText()));
            callback.onSuccess(best.document, best.scaleX, best.scaleY, best.variant);
        }

        private void finishBest() {
            if (finished) return;
            Candidate best = null;
            int bestVotes = -1;
            double bestQuality = Double.NEGATIVE_INFINITY;
            for (Candidate candidate : candidates) {
                int count = votes.getOrDefault(candidate.key, 0);
                double q = quality(candidate.document);
                if (count > bestVotes || (count == bestVotes && q > bestQuality)) {
                    best = candidate;
                    bestVotes = count;
                    bestQuality = q;
                }
            }
            finished = true;
            if (best != null) {
                DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                        "finish reason=best_available"
                                + " variant=" + best.variant
                                + " votes=" + bestVotes
                                + " engine=" + best.document.engine()
                                + " text=" + summarize(best.document.fullText()));
                callback.onSuccess(best.document, best.scaleX, best.scaleY, best.variant);
            } else {
                Throwable error = lastError == null
                        ? new IllegalStateException("multi-scale OCR empty") : lastError;
                DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                        "finish reason=empty error=" + safe(error));
                callback.onFailure(error);
            }
        }
    }

    static void recognize(Context context, Bitmap bitmap, Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        new Session(context.getApplicationContext(), bitmap, callback).start();
    }

    private static Bitmap makeVariant(Bitmap source, float requestedScale, boolean contrast) {
        int sw = Math.max(1, source.getWidth());
        int sh = Math.max(1, source.getHeight());
        float maxScale = MAX_EDGE / (float) Math.max(sw, sh);
        float scale = Math.max(1f, Math.min(requestedScale, maxScale));
        int width = Math.max(1, Math.round(sw * scale));
        int height = Math.max(1, Math.round(sh * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (!contrast) {
            if (scaled == source) {
                Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) throw new IllegalStateException("OCR variant copy failed");
                return copy;
            }
            return scaled;
        }

        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        float c = 1.8f;
        float offset = 128f * (1f - c);
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                c, 0, 0, 0, offset,
                0, c, 0, 0, offset,
                0, 0, c, 0, offset,
                0, 0, 0, 1, 0
        });
        matrix.postConcat(contrastMatrix);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(scaled, 0f, 0f, paint);
        if (scaled != source && !scaled.isRecycled()) scaled.recycle();
        return out;
    }

    private static void recycleVariant(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static double quality(OcrDocument document) {
        if (document == null) return Double.NEGATIVE_INFINITY;
        String key = normalize(document.fullText());
        return document.score() + document.confidence() * 100.0
                + Math.min(64, key.codePointCount(0, key.length()));
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || isCjk(cp)) out.appendCodePoint(cp);
        });
        return out.toString();
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private static String summarize(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 48 ? oneLine : oneLine.substring(0, 48) + "…";
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private CircleMultiScaleOcr() {}
}
