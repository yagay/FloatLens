package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/**
 * Screenshot-only recognition session for Circle Select.
 *
 * One frozen screenshot is the only source of truth. Full-frame indexing and precise ROI refinement
 * both reuse the normal screenshot {@link OcrEngine}, so engine/model/language selection stays
 * identical to ordinary screenshot OCR. Accessibility/View text is not read or merged here.
 */
final class CircleRecognitionSession {
    // Legacy stages are retained for source compatibility. New sessions emit FAST_SCREENSHOT_OCR.
    enum Stage { VIEW_SNAPSHOT, FAST_MLKIT, FAST_SCREENSHOT_OCR, ROI_PRECISE }

    interface Callback {
        void onUpdate(OcrDocument document, Stage stage, boolean fastReady);
        void onFailure(Stage stage, Throwable error, boolean fastReady);
    }

    private static final int OCR_MIN_IMAGE_EDGE = 32;

    private final Context app;
    private final Bitmap screenshot;
    private final ScreenBitmapTransform transform;
    private final Callback callback;
    private long generation;
    private boolean closed;
    private boolean fastFinished;
    private Rect pendingRefineRegion;
    private OcrDocument current;
    private CircleTextIndex index;

    CircleRecognitionSession(Context context, Bitmap screenshot,
                             CircleViewTextSnapshot ignoredViewSnapshot,
                             ScreenBitmapTransform transform,
                             Callback callback) {
        this.app = context.getApplicationContext();
        this.screenshot = screenshot;
        Rect frame = transform == null ? CircleSelectFrame.contentBounds(app) : transform.screenFrame();
        this.transform = transform == null
                ? new ScreenBitmapTransform(frame,
                        screenshot == null ? 1 : screenshot.getWidth(),
                        screenshot == null ? 1 : screenshot.getHeight())
                : transform;
        this.callback = callback;
    }

    int mode() { return CircleOcrPolicy.MODE_MLKIT_ONLY; }
    boolean visualOcrEnabled() { return true; }

    void start() {
        if (closed || screenshot == null || screenshot.isRecycled()) return;
        final long run = ++generation;
        Rect frame = transform.screenFrame();
        fastFinished = false;
        pendingRefineRegion = null;

        index = new CircleTextIndex(emptyScreenDocument("screenshot-base", frame),
                Math.max(1, frame.width()), Math.max(1, frame.height()));
        current = index.current();

        DiagnosticLog.i(app, "CIRCLE_INDEX",
                "start source=screenshot engine=shared-screenshot-ocr view=false"
                        + " image=" + screenshot.getWidth() + "x" + screenshot.getHeight()
                        + " frame=" + frame.toShortString());

        OcrEngine.recognizeDocument(app, screenshot, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                if (!isCurrent(run) || index == null) return;
                fastFinished = true;
                try {
                    OcrDocument screenDocument = toScreenDocument(document);
                    index.setFastOcr(screenDocument);
                    current = index.current();
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "full screenshot ocr ready engine="
                            + document.engine()
                            + " chars=" + screenDocument.chars().size()
                            + " lines=" + screenDocument.lines().size()
                            + " coordinateSpace=" + screenDocument.coordinateSpace());
                    emit(current, Stage.FAST_SCREENSHOT_OCR, true);
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "full screenshot ocr map failed=" + safe(t));
                    emitFailure(Stage.FAST_SCREENSHOT_OCR, t, true);
                }
                drainPendingRefine();
            }

            @Override public void onFailure(Throwable error) {
                if (!isCurrent(run)) return;
                fastFinished = true;
                DiagnosticLog.i(app, "CIRCLE_INDEX", "full screenshot ocr failed=" + safe(error));
                emitFailure(Stage.FAST_SCREENSHOT_OCR, error, true);
                drainPendingRefine();
            }
        });
    }

    /** Refine one screen-space region using the same OCR engine/model/language path as screenshots. */
    void refine(Rect screenRegion) {
        if (closed || screenRegion == null || screenRegion.isEmpty()
                || screenshot == null || screenshot.isRecycled() || index == null) return;

        Rect requestedRegion = new Rect(screenRegion);
        if (!requestedRegion.intersect(transform.screenFrame()) || requestedRegion.isEmpty()) return;

        // Do not let a later full-frame result overwrite a precise ROI patch. Queue the tap until
        // the initial screenshot OCR has completed; only one tap refinement can be active in UI.
        if (!fastFinished) {
            pendingRefineRegion = requestedRegion;
            DiagnosticLog.i(app, "CIRCLE_INDEX", "roi queued until full screenshot ocr ready region="
                    + requestedRegion.toShortString());
            return;
        }

        Rect requestedBitmap = transform.screenToBitmap(requestedRegion);
        if (requestedBitmap.isEmpty()) return;
        Rect bitmapRegion = ensureMinBitmapRegion(requestedBitmap,
                screenshot.getWidth(), screenshot.getHeight(), OCR_MIN_IMAGE_EDGE);
        if (bitmapRegion.isEmpty()) return;
        Rect region = transform.bitmapToScreen(bitmapRegion);
        if (region.isEmpty()) return;

        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(screenshot,
                    bitmapRegion.left, bitmapRegion.top,
                    bitmapRegion.width(), bitmapRegion.height());
        } catch (Throwable t) {
            emitFailure(Stage.ROI_PRECISE, t, true);
            return;
        }

        final long run = generation;
        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi screenshot ocr start requested="
                + requestedRegion.toShortString()
                + " screen=" + region.toShortString()
                + " bitmap=" + bitmapRegion.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight());

        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument localBitmap) {
                try {
                    if (!isCurrent(run) || index == null) return;
                    OcrDocument parentBitmap = localBitmap.translated(
                            bitmapRegion.left, bitmapRegion.top,
                            screenshot.getWidth(), screenshot.getHeight());
                    OcrDocument screenPatch = transform.documentBitmapToScreen(parentBitmap);
                    index.replaceOcrRegion(screenPatch, region);
                    current = index.current();
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi screenshot ocr ready engine="
                            + localBitmap.engine()
                            + " roiChars=" + screenPatch.chars().size()
                            + " mergedChars=" + current.chars().size()
                            + " screen=" + region.toShortString()
                            + " coordinateSpace=" + screenPatch.coordinateSpace());
                    emit(current, Stage.ROI_PRECISE, true);
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi screenshot ocr map failed=" + safe(t));
                    emitFailure(Stage.ROI_PRECISE, t, true);
                } finally {
                    if (!crop.isRecycled()) crop.recycle();
                }
            }

            @Override public void onFailure(Throwable error) {
                if (!crop.isRecycled()) crop.recycle();
                if (!isCurrent(run)) return;
                DiagnosticLog.i(app, "CIRCLE_INDEX", "roi screenshot ocr failed=" + safe(error));
                emitFailure(Stage.ROI_PRECISE, error, true);
            }
        });
    }

    void cancel() {
        if (closed) return;
        closed = true;
        generation++;
        pendingRefineRegion = null;
        DiagnosticLog.i(app, "CIRCLE_INDEX", "session cancelled source=screenshot engine=shared-screenshot-ocr");
    }

    private void drainPendingRefine() {
        if (closed || !fastFinished) return;
        Rect pending = pendingRefineRegion;
        pendingRefineRegion = null;
        if (pending != null && !pending.isEmpty()) refine(pending);
    }

    private OcrDocument toScreenDocument(OcrDocument document) {
        if (document == null) return emptyScreenDocument("screenshot-ocr-empty", transform.screenFrame());
        if (document.isScreenSpace()) return document;
        return transform.documentBitmapToScreen(document);
    }

    private static Rect ensureMinBitmapRegion(Rect source, int bitmapWidth, int bitmapHeight,
                                              int minEdge) {
        if (source == null || source.isEmpty() || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return new Rect();
        }
        Rect bounds = new Rect(0, 0, bitmapWidth, bitmapHeight);
        Rect clipped = new Rect(source);
        if (!clipped.intersect(bounds) || clipped.isEmpty()) return new Rect();
        int targetWidth = Math.min(bitmapWidth, Math.max(Math.max(1, minEdge), clipped.width()));
        int targetHeight = Math.min(bitmapHeight, Math.max(Math.max(1, minEdge), clipped.height()));
        int left = clipped.centerX() - targetWidth / 2;
        int top = clipped.centerY() - targetHeight / 2;
        left = Math.max(0, Math.min(left, bitmapWidth - targetWidth));
        top = Math.max(0, Math.min(top, bitmapHeight - targetHeight));
        return new Rect(left, top, left + targetWidth, top + targetHeight);
    }

    private static OcrDocument emptyScreenDocument(String engine, Rect frame) {
        return OcrDocument.screenSpace("", List.of(), List.of(), engine, 0f, 0d,
                Math.max(1, frame == null ? 1 : frame.width()),
                Math.max(1, frame == null ? 1 : frame.height()));
    }

    private void emit(OcrDocument document, Stage stage, boolean fastReady) {
        if (closed || callback == null) return;
        try {
            callback.onUpdate(document == null
                    ? emptyScreenDocument("none", transform.screenFrame()) : document,
                    stage, fastReady);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "callback failed=" + safe(t));
        }
    }

    private void emitFailure(Stage stage, Throwable error, boolean fastReady) {
        if (closed || callback == null) return;
        try { callback.onFailure(stage, error, fastReady); }
        catch (Throwable ignored) {}
    }

    private boolean isCurrent(long run) {
        return !closed && run == generation && screenshot != null && !screenshot.isRecycled();
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
