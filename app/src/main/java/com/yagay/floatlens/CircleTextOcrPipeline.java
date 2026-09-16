package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Single-source Circle OCR pipeline.
 *
 * <p>PP-OCR is used only to detect text regions. ML Kit performs exactly one document recognition
 * pass over a full-size bitmap in which only detected regions are visible. Because the masked image
 * keeps the original screenshot dimensions, ML Kit geometry already belongs to the frozen bitmap
 * coordinate space and no cross-pass merge is necessary.</p>
 *
 * <p>If no PP model is installed, detection fails, or the detector returns no regions, the pipeline
 * falls back to one full-frame ML Kit pass. There are no tiles, no AKS merge, no character fusion,
 * and no gesture-time OCR.</p>
 */
final class CircleTextOcrPipeline {
    interface Callback {
        void onSuccess(OcrDocument document);
        void onFailure(Throwable error);
    }

    private static final float DETECTION_PADDING_DP = 4f;

    static void recognize(Context context, Bitmap bitmap,
                          BooleanSupplier cancelled, Callback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();
        if (isCancelled(cancelled)) {
            callback.onFailure(new CancellationException("Circle OCR cancelled before detection"));
            return;
        }

        int detectorModel = chooseDetectorModel(app);
        DiagnosticLog.i(app, "G_CIRCLE_OCR_PIPELINE",
                "start bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " detector=" + detectorName(detectorModel)
                        + " recognizer=mlkit_single_document"
                        + " tiles=false aksMerge=false characterFusion=false localOcr=false");

        if (detectorModel == 0) {
            runMlKit(app, bitmap, cancelled, callback,
                    "ppdet_unavailable_full_mlkit", null, false);
            return;
        }

        PaddleOcrBridge.detect(app, bitmap, detectorModel,
                new PaddleOcrBridge.DetectionCallback() {
            @Override public void onSuccess(List<Rect> regions, long totalMs) {
                if (isCancelled(cancelled)) {
                    callback.onFailure(new CancellationException(
                            "Circle OCR cancelled after detection"));
                    return;
                }
                List<Rect> normalized = normalizeRegions(app, regions,
                        bitmap.getWidth(), bitmap.getHeight());
                DiagnosticLog.i(app, "G_CIRCLE_OCR_PIPELINE",
                        "detect ready model=" + detectorModel
                                + " regionsRaw=" + (regions == null ? 0 : regions.size())
                                + " regions=" + normalized.size()
                                + " elapsedMs=" + totalMs);
                if (normalized.isEmpty()) {
                    runMlKit(app, bitmap, cancelled, callback,
                            "ppdet_empty_full_mlkit", null, false);
                    return;
                }

                Bitmap masked;
                try {
                    masked = buildMaskedBitmap(bitmap, normalized);
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "G_CIRCLE_OCR_PIPELINE",
                            "mask failed fallback=full_mlkit error="
                                    + ScreenCaptureBackend.safeMessage(t));
                    runMlKit(app, bitmap, cancelled, callback,
                            "ppdet_mask_failed_full_mlkit", null, false);
                    return;
                }
                runMlKit(app, masked, cancelled, new Callback() {
                    @Override public void onSuccess(OcrDocument document) {
                        recycle(masked);
                        callback.onSuccess(retag(document, "ppdet+"));
                    }

                    @Override public void onFailure(Throwable error) {
                        recycle(masked);
                        if (isCancelled(cancelled)) {
                            callback.onFailure(error);
                            return;
                        }
                        DiagnosticLog.i(app, "G_CIRCLE_OCR_PIPELINE",
                                "masked mlkit failed fallback=full_mlkit error="
                                        + ScreenCaptureBackend.safeMessage(error));
                        runMlKit(app, bitmap, cancelled, callback,
                                "masked_mlkit_failed_full_mlkit", null, false);
                    }
                }, "ppdet_masked_mlkit", normalized.size(), true);
            }

            @Override public void onFailure(String message) {
                if (isCancelled(cancelled)) {
                    callback.onFailure(new CancellationException(
                            "Circle OCR cancelled after detector failure"));
                    return;
                }
                DiagnosticLog.i(app, "G_CIRCLE_OCR_PIPELINE",
                        "detect failed fallback=full_mlkit error=" + message);
                runMlKit(app, bitmap, cancelled, callback,
                        "ppdet_failed_full_mlkit", null, false);
            }
        });
    }

    private static void runMlKit(Context app, Bitmap input,
                                 BooleanSupplier cancelled, Callback callback,
                                 String reason, Integer regionCount, boolean masked) {
        if (isCancelled(cancelled)) {
            callback.onFailure(new CancellationException("Circle OCR cancelled before ML Kit"));
            return;
        }
        long started = android.os.SystemClock.uptimeMillis();
        CircleStableOcr.recognizeMlKit(app, input, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                if (isCancelled(cancelled)) {
                    callback.onFailure(new CancellationException("Circle OCR cancelled after ML Kit"));
                    return;
                }
                OcrDocument tagged = masked ? document : retag(document, "full+");
                DiagnosticLog.i(app, "G_CIRCLE_OCR_PIPELINE",
                        "mlkit success reason=" + reason
                                + " masked=" + masked
                                + (regionCount == null ? "" : " regions=" + regionCount)
                                + " chars=" + (tagged == null ? 0 : tagged.chars().size())
                                + " lines=" + (tagged == null ? 0 : tagged.lines().size())
                                + " engine=" + (tagged == null ? "none" : tagged.engine())
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                callback.onSuccess(tagged);
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_OCR_PIPELINE",
                        "mlkit failure reason=" + reason
                                + " masked=" + masked
                                + " error=" + ScreenCaptureBackend.safeMessage(error)
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                callback.onFailure(error);
            }
        });
    }

    private static List<Rect> normalizeRegions(Context app, List<Rect> source,
                                               int width, int height) {
        ArrayList<Rect> out = new ArrayList<>();
        if (source == null || source.isEmpty() || width <= 0 || height <= 0) return out;
        float density = Math.max(1f, app.getResources().getDisplayMetrics().density);
        int pad = Math.max(2, Math.round(DETECTION_PADDING_DP * density));
        Rect frame = new Rect(0, 0, width, height);
        for (Rect raw : source) {
            if (raw == null || raw.isEmpty()) continue;
            Rect r = new Rect(raw);
            r.inset(-pad, -pad);
            if (!r.intersect(frame) || r.isEmpty()) continue;
            out.add(r);
        }
        return out;
    }

    private static Bitmap buildMaskedBitmap(Bitmap source, List<Rect> regions) {
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (Rect region : regions) {
            int save = canvas.save();
            canvas.clipRect(region);
            canvas.drawBitmap(source, 0f, 0f, paint);
            canvas.restoreToCount(save);
        }
        return out;
    }

    private static int chooseDetectorModel(Context app) {
        if (OcrModelManager.isReady(app, OcrModelManager.SMALL)) return OcrModelManager.SMALL;
        if (OcrModelManager.isReady(app, OcrModelManager.MEDIUM)) return OcrModelManager.MEDIUM;
        return 0;
    }

    private static String detectorName(int model) {
        if (model == OcrModelManager.SMALL) return "ppocrv6-small";
        if (model == OcrModelManager.MEDIUM) return "ppocrv6-medium";
        return "none";
    }

    private static OcrDocument retag(OcrDocument document, String prefix) {
        if (document == null) return null;
        return new OcrDocument(document.fullText(), document.blocks(), document.lines(),
                (prefix == null ? "" : prefix) + document.engine(),
                document.confidence(), document.score(),
                document.imageWidth(), document.imageHeight(), document.coordinateSpace());
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        if (cancelled == null) return false;
        try { return cancelled.getAsBoolean(); }
        catch (Throwable ignored) { return true; }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private CircleTextOcrPipeline() {}
}
