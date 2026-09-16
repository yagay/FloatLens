package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Builds the frozen Circle OCR index using the AKS spatial strategy: one full-frame pass plus four
 * overlapping 60% quadrant crops.
 *
 * <p>Each ML Kit pass is mapped back into full-bitmap coordinates. When all available passes
 * finish, {@link CircleOcrIndex} builds one AKS-style global document at whole Element/group
 * granularity: exact-text heavy-overlap duplicates are removed, conflicting groups are preserved,
 * and characters are never fused between OCR sources.</p>
 */
final class CirclePreindexTiledOcr {
    interface Callback {
        void onSuccess(CircleOcrIndex index);
        void onFailure(Throwable error);
    }

    private static final float TILE_FRACTION = 0.60f;

    private static final class PassSpec {
        final String source;
        final boolean fullFrame;
        final Rect coverage;

        PassSpec(String source, boolean fullFrame, Rect coverage) {
            this.source = source;
            this.fullFrame = fullFrame;
            this.coverage = new Rect(coverage);
        }
    }

    private static final class Session {
        final Context app;
        final Bitmap source;
        final BooleanSupplier cancelled;
        final Callback callback;
        final List<PassSpec> specs;
        final CircleOcrIndex.Entry[] results;
        final long started = android.os.SystemClock.uptimeMillis();

        int remaining;
        Throwable lastError;
        boolean finished;

        Session(Context app, Bitmap source, BooleanSupplier cancelled, Callback callback) {
            this.app = app;
            this.source = source;
            this.cancelled = cancelled;
            this.callback = callback;
            this.specs = buildPasses(source.getWidth(), source.getHeight());
            this.results = new CircleOcrIndex.Entry[specs.size()];
            this.remaining = specs.size();
        }

        void start() {
            if (isCancelled(cancelled)) {
                failOnce(new CancellationException("Circle preindex cancelled before passes"),
                        "before_passes");
                return;
            }
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                    "start strategy=aks_full_plus_overlap_tiles"
                            + " bitmap=" + source.getWidth() + "x" + source.getHeight()
                            + " passes=" + specs.size()
                            + " tileFraction=" + TILE_FRACTION
                            + " parallel=true"
                            + " merge=aks_word_group exactText=true overlap=0.70"
                            + " characterFusion=false"
                            + " enginePolicy=mlkit_only_single_document"
                            + " workspaceCancellation=true");

            for (int i = 0; i < specs.size(); i++) launchPass(i, specs.get(i));
        }

        private void launchPass(int index, PassSpec spec) {
            if (finished) return;
            if (isCancelled(cancelled)) {
                failOnce(new CancellationException("Circle preindex cancelled before " + spec.source),
                        "before_" + spec.source);
                return;
            }

            final Bitmap input;
            try {
                input = spec.fullFrame ? source : Bitmap.createBitmap(source,
                        spec.coverage.left, spec.coverage.top,
                        spec.coverage.width(), spec.coverage.height());
            } catch (Throwable t) {
                completePass(index, spec, null, t, null);
                return;
            }

            final long passStarted = android.os.SystemClock.uptimeMillis();
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                    "pass start source=" + spec.source
                            + " coverage=" + spec.coverage.toShortString()
                            + " bitmap=" + input.getWidth() + "x" + input.getHeight()
                            + " engine=mlkit");

            CircleStableOcr.recognizeMlKit(app, input, new OcrEngine.DocumentCallback() {
                @Override public void onSuccess(OcrDocument document) {
                    OcrDocument mapped = null;
                    Throwable error = null;
                    try {
                        if (!isCancelled(cancelled)) {
                            mapped = mapPassToFull(document, spec.coverage,
                                    source.getWidth(), source.getHeight(), spec.source + "-");
                            if (!usable(mapped)) {
                                error = new IllegalStateException(spec.source + " OCR empty");
                            }
                        } else {
                            error = new CancellationException(
                                    "Circle preindex cancelled after " + spec.source);
                        }
                    } catch (Throwable t) {
                        error = t;
                    }
                    completePass(index, spec, mapped, error,
                            spec.fullFrame ? null : input);
                    DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                            "pass done source=" + spec.source
                                    + " usable=" + usable(mapped)
                                    + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                    + " lines=" + (mapped == null ? 0 : mapped.lines().size())
                                    + " engine=" + (mapped == null ? "none" : mapped.engine())
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - passStarted));
                }

                @Override public void onFailure(Throwable error) {
                    completePass(index, spec, null,
                            error == null ? new IllegalStateException(spec.source + " OCR failed") : error,
                            spec.fullFrame ? null : input);
                    DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                            "pass failed source=" + spec.source
                                    + " error=" + safe(error)
                                    + " elapsedMs="
                                    + (android.os.SystemClock.uptimeMillis() - passStarted));
                }
            });
        }

        private void completePass(int index, PassSpec spec, OcrDocument document,
                                  Throwable error, Bitmap ownedInput) {
            if (ownedInput != null && ownedInput != source && !ownedInput.isRecycled()) {
                ownedInput.recycle();
            }

            CircleOcrIndex complete = null;
            Throwable terminal = null;
            synchronized (this) {
                if (finished) return;
                if (usable(document)) {
                    results[index] = new CircleOcrIndex.Entry(
                            spec.source, spec.fullFrame, spec.coverage, document);
                }
                if (error != null) lastError = error;
                remaining--;
                if (remaining > 0) return;

                if (isCancelled(cancelled)) {
                    finished = true;
                    terminal = new CancellationException("Circle preindex cancelled at finish");
                } else {
                    ArrayList<CircleOcrIndex.Entry> entries = new ArrayList<>();
                    for (CircleOcrIndex.Entry entry : results) if (entry != null) entries.add(entry);
                    if (entries.isEmpty()) {
                        finished = true;
                        terminal = lastError == null
                                ? new IllegalStateException("all Circle OCR passes empty") : lastError;
                    } else {
                        finished = true;
                        complete = new CircleOcrIndex(entries);
                    }
                }
            }

            if (complete != null) {
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                        "finish usable=true passes=" + complete.passCount()
                                + " totalChars=" + complete.totalChars()
                                + " mergedChars=" + complete.mergedChars()
                                + " engine=mlkit"
                                + " merge=aks_word_group exactText=true overlap=0.70"
                                + " characterFusion=false"
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                callback.onSuccess(complete);
            } else if (terminal != null) {
                DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                        "finish usable=false error=" + safe(terminal)
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                callback.onFailure(terminal);
            }
        }

        private void failOnce(Throwable error, String stage) {
            synchronized (this) {
                if (finished) return;
                finished = true;
            }
            DiagnosticLog.i(app, "G_CIRCLE_PREINDEX",
                    "cancel stage=" + stage + " error=" + safe(error));
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

    private static List<PassSpec> buildPasses(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int tw = Math.max(1, Math.min(w, Math.round(w * TILE_FRACTION)));
        int th = Math.max(1, Math.min(h, Math.round(h * TILE_FRACTION)));

        ArrayList<PassSpec> out = new ArrayList<>(5);
        out.add(new PassSpec("full", true, new Rect(0, 0, w, h)));
        out.add(new PassSpec("tile-tl", false, new Rect(0, 0, tw, th)));
        out.add(new PassSpec("tile-tr", false, new Rect(w - tw, 0, w, th)));
        out.add(new PassSpec("tile-bl", false, new Rect(0, h - th, tw, h)));
        out.add(new PassSpec("tile-br", false, new Rect(w - tw, h - th, w, h)));
        return out;
    }

    private static OcrDocument mapPassToFull(OcrDocument document, Rect coverage,
                                             int fullWidth, int fullHeight, String prefix) {
        if (document == null || coverage == null || coverage.isEmpty()) return null;
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, Math.max(1, document.imageWidth()),
                        Math.max(1, document.imageHeight())),
                new RectF(coverage.left, coverage.top, coverage.right, coverage.bottom));
        return mapper.mapDocument(document, false,
                Math.max(1, fullWidth), Math.max(1, fullHeight), prefix);
    }

    private static boolean usable(OcrDocument document) {
        return document != null && document.isBitmapSpace()
                && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        if (cancelled == null) return false;
        try { return cancelled.getAsBoolean(); }
        catch (Throwable ignored) { return true; }
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private CirclePreindexTiledOcr() {}
}
