package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Small, deterministic OCR verifier for gesture-local image text.
 *
 * <p>Exactly three representations of the same tight ROI are recognized: a clean adaptive upscale,
 * grayscale/high-contrast, and automatic-polarity binary. There are no local tiles, scale ladders,
 * script-specific bonuses or character/document merges. Every pass returns one complete recognizer
 * document mapped back to the original ROI; exact text votes win first, then a generic readability
 * score chooses between ties.</p>
 */
final class CircleMultiScaleOcr {
    interface Callback {
        void onSuccess(OcrDocument document, float scaleX, float scaleY, String variant);
        void onFailure(Throwable error);
    }

    private enum Variant {
        UPSCALE("upscale", 3),
        CONTRAST("contrast", 2),
        ADAPTIVE_BINARY("adaptive-binary", 1);

        final String label;
        final int tiePriority;

        Variant(String label, int tiePriority) {
            this.label = label;
            this.tiePriority = tiePriority;
        }
    }

    private static final int MAX_EDGE = 1024;
    private static final int TARGET_MIN_EDGE = 560;
    private static final float MIN_SCALE = 1.5f;
    private static final float MAX_SCALE = 4f;
    private static final Variant[] VARIANTS = {
            Variant.UPSCALE, Variant.CONTRAST, Variant.ADAPTIVE_BINARY
    };

    private static final class Candidate {
        final OcrDocument document;
        final Variant variant;
        final String key;
        final double quality;

        Candidate(OcrDocument document, Variant variant, String key) {
            this.document = document;
            this.variant = variant;
            this.key = key;
            this.quality = quality(document, key, variant);
        }
    }

    private static final class Session {
        final Context app;
        final Bitmap source;
        final BooleanSupplier cancelled;
        final Callback callback;
        final ArrayList<Candidate> candidates = new ArrayList<>();
        final Map<String, Integer> votes = new HashMap<>();

        int variantIndex;
        Throwable lastError;
        boolean finished;

        Session(Context app, Bitmap source, BooleanSupplier cancelled, Callback callback) {
            this.app = app;
            this.source = source;
            this.cancelled = cancelled;
            this.callback = callback;
        }

        void start() {
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "start strategy=three_variant_image_text"
                            + " input=" + source.getWidth() + "x" + source.getHeight()
                            + " variants=upscale,contrast,adaptive-binary"
                            + " tiles=false merge=false scriptBias=false"
                            + " enginePolicy=follow_main_setting"
                            + " workspaceCancellation=true");
            runNext();
        }

        void runNext() {
            if (finished) return;
            if (isCancelled()) {
                cancel("before_variant");
                return;
            }
            if (variantIndex >= VARIANTS.length) {
                finishBest();
                return;
            }

            final Variant variant = VARIANTS[variantIndex++];
            final float requestedScale = adaptiveScale(source);
            final Bitmap input;
            try {
                input = makeVariant(source, requestedScale, variant);
            } catch (Throwable t) {
                lastError = t;
                DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                        "prepare failed variant=" + variant.label + " error=" + safe(t));
                runNext();
                return;
            }

            if (isCancelled()) {
                recycle(input);
                cancel("prepared_" + variant.label);
                return;
            }

            final float scaleX = input.getWidth() / (float) Math.max(1, source.getWidth());
            final float scaleY = input.getHeight() / (float) Math.max(1, source.getHeight());
            final long started = android.os.SystemClock.uptimeMillis();
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "pass start variant=" + variant.label
                            + " bitmap=" + input.getWidth() + "x" + input.getHeight()
                            + " scale=" + String.format(Locale.ROOT, "%.2fx%.2f", scaleX, scaleY));

            // Stable document mode follows the user's OCR engine selection but, for ML Kit,
            // chooses one complete recognizer result instead of fusing Chinese/Latin characters.
            OcrEngine.recognizeDocumentStable(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    try {
                        if (finished) return;
                        if (isCancelled()) {
                            cancel("after_" + variant.label);
                            return;
                        }
                        OcrDocument mapped = mapToSource(document, source.getWidth(), source.getHeight(),
                                variant.label + "-");
                        String key = normalize(mapped == null ? "" : mapped.fullText());
                        int vote = 0;
                        if (usable(mapped) && !key.isEmpty()) {
                            candidates.add(new Candidate(mapped, variant, key));
                            vote = votes.getOrDefault(key, 0) + 1;
                            votes.put(key, vote);
                        }
                        DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                                "pass done variant=" + variant.label
                                        + " usable=" + (usable(mapped) && !key.isEmpty())
                                        + " vote=" + vote
                                        + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                        + " text=" + summarize(mapped == null ? "" : mapped.fullText())
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started));
                    } catch (Throwable t) {
                        lastError = t;
                        DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                                "map failed variant=" + variant.label + " error=" + safe(t));
                    } finally {
                        recycle(input);
                    }
                    if (!finished) runNext();
                }

                @Override public void onFailure(Throwable error) {
                    lastError = error;
                    recycle(input);
                    DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                            "pass failed variant=" + variant.label
                                    + " error=" + safe(error)
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - started));
                    if (finished) return;
                    if (isCancelled()) cancel("failure_" + variant.label);
                    else runNext();
                }
            });
        }

        private void finishBest() {
            if (finished) return;
            if (isCancelled()) {
                cancel("finish");
                return;
            }
            Candidate best = null;
            int bestVotes = -1;
            double bestQuality = Double.NEGATIVE_INFINITY;
            for (Candidate candidate : candidates) {
                int count = votes.getOrDefault(candidate.key, 0);
                if (count > bestVotes || (count == bestVotes && candidate.quality > bestQuality)) {
                    best = candidate;
                    bestVotes = count;
                    bestQuality = candidate.quality;
                }
            }
            if (best == null) {
                finished = true;
                Throwable error = lastError == null
                        ? new IllegalStateException("three-variant local OCR empty") : lastError;
                DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                        "finish usable=false error=" + safe(error));
                callback.onFailure(error);
                return;
            }

            finished = true;
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "finish usable=true variant=" + best.variant.label
                            + " votes=" + Math.max(1, bestVotes)
                            + " candidates=" + candidates.size()
                            + " quality=" + String.format(Locale.ROOT, "%.1f", best.quality)
                            + " text=" + summarize(best.document.fullText())
                            + " merge=false tiles=false scriptBias=false");
            callback.onSuccess(best.document, 1f, 1f, best.variant.label);
        }

        private boolean isCancelled() {
            if (cancelled == null) return false;
            try { return cancelled.getAsBoolean(); }
            catch (Throwable ignored) { return true; }
        }

        private void cancel(String stage) {
            if (finished) return;
            finished = true;
            CancellationException error = new CancellationException(
                    "Circle local OCR cancelled at " + stage);
            DiagnosticLog.i(app, "G_CIRCLE_MULTI_OCR",
                    "cancel stage=" + stage + " completedVariants=" + candidates.size());
            callback.onFailure(error);
        }
    }

    static void recognize(Context context, Bitmap bitmap, Callback callback) {
        recognize(context, bitmap, () -> false, callback);
    }

    static void recognize(Context context, Bitmap bitmap,
                          BooleanSupplier cancelled, Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        new Session(context.getApplicationContext(), bitmap, cancelled, callback).start();
    }

    private static float adaptiveScale(Bitmap source) {
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        int minEdge = Math.max(1, Math.min(width, height));
        int maxEdge = Math.max(width, height);
        float wanted = TARGET_MIN_EDGE / (float) minEdge;
        wanted = Math.max(MIN_SCALE, Math.min(MAX_SCALE, wanted));
        wanted = Math.min(wanted, MAX_EDGE / (float) maxEdge);
        return Math.max(1f, wanted);
    }

    private static Bitmap makeVariant(Bitmap source, float scale, Variant variant) {
        int sw = Math.max(1, source.getWidth());
        int sh = Math.max(1, source.getHeight());
        int width = Math.max(1, Math.round(sw * scale));
        int height = Math.max(1, Math.round(sh * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (variant == Variant.UPSCALE) {
            if (scaled != source) return scaled;
            Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("OCR upscale copy failed");
            return copy;
        }
        if (variant == Variant.CONTRAST) return makeContrast(scaled, source);
        return makeAdaptiveBinary(scaled, source);
    }

    private static Bitmap makeContrast(Bitmap scaled, Bitmap source) {
        int width = scaled.getWidth();
        int height = scaled.getHeight();
        Bitmap out = null;
        try {
            out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            float contrast = 1.65f;
            float offset = 128f * (1f - contrast);
            matrix.postConcat(new ColorMatrix(new float[]{
                    contrast, 0, 0, 0, offset,
                    0, contrast, 0, 0, offset,
                    0, 0, contrast, 0, offset,
                    0, 0, 0, 1, 0
            }));
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            canvas.drawBitmap(scaled, 0f, 0f, paint);
            return out;
        } catch (Throwable t) {
            if (out != null && !out.isRecycled()) out.recycle();
            throw t;
        } finally {
            if (scaled != source && scaled != out && !scaled.isRecycled()) scaled.recycle();
        }
    }

    /**
     * Generic binary preprocessing. Otsu finds the luminance split and the dominant image luminance
     * chooses polarity: dark ink on a light background stays dark; light ink on a dark background
     * is inverted to dark ink on white. This is image-driven and has no language/content rules.
     */
    private static Bitmap makeAdaptiveBinary(Bitmap scaled, Bitmap source) {
        Bitmap working = scaled;
        try {
            if (!working.isMutable()) {
                Bitmap mutable = working.copy(Bitmap.Config.ARGB_8888, true);
                if (mutable == null) throw new IllegalStateException("binary OCR copy failed");
                if (working != source) working.recycle();
                working = mutable;
            }
            int width = working.getWidth();
            int height = working.getHeight();
            int[] pixels = new int[width * height];
            int[] histogram = new int[256];
            working.getPixels(pixels, 0, width, 0, 0, width, height);
            long luminanceSum = 0L;
            for (int color : pixels) {
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int y = (77 * r + 150 * g + 29 * b) >> 8;
                histogram[y]++;
                luminanceSum += y;
            }
            int threshold = Math.max(48, Math.min(208, otsuThreshold(histogram, pixels.length)));
            float mean = luminanceSum / (float) Math.max(1, pixels.length);
            boolean lightBackground = mean >= 128f;
            for (int i = 0; i < pixels.length; i++) {
                int color = pixels[i];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int y = (77 * r + 150 * g + 29 * b) >> 8;
                boolean ink = lightBackground ? y <= threshold : y >= threshold;
                pixels[i] = ink ? 0xff000000 : 0xffffffff;
            }
            working.setPixels(pixels, 0, width, 0, 0, width, height);
            DiagnosticLog.i(null, "G_CIRCLE_MULTI_OCR",
                    "adaptive-binary threshold=" + threshold
                            + " mean=" + Math.round(mean)
                            + " polarity=" + (lightBackground ? "dark_on_light" : "light_on_dark"));
            return working;
        } catch (Throwable t) {
            if (working != null && working != source && !working.isRecycled()) working.recycle();
            throw t;
        }
    }

    private static int otsuThreshold(int[] histogram, int total) {
        if (histogram == null || histogram.length < 256 || total <= 0) return 128;
        long sum = 0L;
        for (int i = 0; i < 256; i++) sum += (long) i * histogram[i];
        long backgroundWeight = 0L;
        long backgroundSum = 0L;
        double bestVariance = -1d;
        int best = 128;
        for (int t = 0; t < 256; t++) {
            backgroundWeight += histogram[t];
            if (backgroundWeight == 0) continue;
            long foregroundWeight = total - backgroundWeight;
            if (foregroundWeight == 0) break;
            backgroundSum += (long) t * histogram[t];
            double backgroundMean = backgroundSum / (double) backgroundWeight;
            double foregroundMean = (sum - backgroundSum) / (double) foregroundWeight;
            double diff = backgroundMean - foregroundMean;
            double variance = backgroundWeight * (double) foregroundWeight * diff * diff;
            if (variance > bestVariance) {
                bestVariance = variance;
                best = t;
            }
        }
        return best;
    }

    private static OcrDocument mapToSource(OcrDocument document, int width, int height,
                                           String prefix) {
        if (document == null) return null;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, Math.max(1, document.imageWidth()),
                        Math.max(1, document.imageHeight())),
                new RectF(0f, 0f, Math.max(1, width), Math.max(1, height)));
        return mapper.mapDocument(document, false,
                Math.max(1, width), Math.max(1, height), prefix);
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isBitmapSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    /** Generic candidate score: no script, brand or language-specific preference. */
    private static double quality(OcrDocument document, String key, Variant variant) {
        if (document == null || key == null || key.isEmpty()) return Double.NEGATIVE_INFINITY;
        String text = document.fullText();
        int meaningful = 0;
        int visible = 0;
        int garbage = 0;
        if (text != null) {
            for (int cp : text.codePoints().toArray()) {
                if (Character.isWhitespace(cp)) continue;
                visible++;
                if (Character.isLetterOrDigit(cp)) meaningful++;
                else if (cp == 0xfffd || Character.isISOControl(cp)
                        || (cp >= 0xe000 && cp <= 0xf8ff)) garbage++;
            }
        }
        double readableRatio = meaningful / (double) Math.max(1, visible);
        return document.score()
                + document.confidence() * 100d
                + meaningful * 4d
                + Math.min(64, document.chars().size()) * 0.25d
                + Math.min(16, document.lines().size()) * 0.5d
                + readableRatio * 40d
                - garbage * 30d
                + variant.tiePriority * 3d;
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp);
        });
        return out.toString();
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

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private CircleMultiScaleOcr() {}
}
