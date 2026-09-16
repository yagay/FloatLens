package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;

import java.util.List;

/**
 * Screenshot-only recognition session for Circle Select.
 *
 * One frozen screenshot is the source for both the fast full-frame text index and precise ROI
 * refinement. Accessibility/View text is intentionally not read or merged here.
 */
final class CircleRecognitionSession {
    // VIEW_SNAPSHOT is kept for callback/source compatibility; screenshot-only sessions do not emit it.
    enum Stage { VIEW_SNAPSHOT, FAST_MLKIT, ROI_PRECISE }

    interface Callback {
        void onUpdate(OcrDocument document, Stage stage, boolean fastReady);
        void onFailure(Stage stage, Throwable error, boolean fastReady);
    }

    private static final int MLKIT_MIN_IMAGE_EDGE = 32;
    private static final float MLKIT_CONFIDENCE = 0.72f;

    private final Context app;
    private final Bitmap screenshot;
    private final ScreenBitmapTransform transform;
    private final Callback callback;
    private long generation;
    private boolean closed;
    private OcrDocument current;
    private CircleTextIndex index;
    private TextRecognizer fastRecognizer;
    private TextRecognizer roiRecognizer;

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

        index = new CircleTextIndex(emptyScreenDocument("screenshot-base", frame),
                Math.max(1, frame.width()), Math.max(1, frame.height()));
        current = index.current();

        DiagnosticLog.i(app, "CIRCLE_INDEX",
                "start source=screenshot mlkit=true view=false ppocr=false"
                        + " image=" + screenshot.getWidth() + "x" + screenshot.getHeight()
                        + " frame=" + frame.toShortString());
        startFastMlKit(run);
    }

    /** Refine one screen-space region by cropping the same frozen screenshot and running ML Kit. */
    void refine(Rect screenRegion) {
        if (closed || screenRegion == null || screenRegion.isEmpty()
                || screenshot == null || screenshot.isRecycled() || index == null) return;
        Rect requestedRegion = new Rect(screenRegion);
        if (!requestedRegion.intersect(transform.screenFrame()) || requestedRegion.isEmpty()) return;
        Rect requestedBitmap = transform.screenToBitmap(requestedRegion);
        if (requestedBitmap.isEmpty()) return;

        Rect bitmapRegion = ensureMinBitmapRegion(requestedBitmap,
                screenshot.getWidth(), screenshot.getHeight(), MLKIT_MIN_IMAGE_EDGE);
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
        TextRecognizer recognizer = null;
        try {
            recognizer = MlKitTextCore.createPreferredRecognizer(app);
            roiRecognizer = recognizer;
            String engine = MlKitTextCore.preferredEngine("roi-mlkit", app);
            TextRecognizer finalRecognizer = recognizer;
            DiagnosticLog.i(app, "CIRCLE_INDEX", "roi screenshot mlkit start requested="
                    + requestedRegion.toShortString()
                    + " screen=" + region.toShortString()
                    + " bitmap=" + bitmapRegion.toShortString()
                    + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                    + " ppocr=false");

            recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            if (!isCurrent(run) || index == null) return;
                            OcrDocument localBitmap = MlKitTextCore.toDocument(
                                    text, engine, crop.getWidth(), crop.getHeight(),
                                    MLKIT_CONFIDENCE, null);
                            OcrDocument parentBitmap = localBitmap.translated(
                                    bitmapRegion.left, bitmapRegion.top,
                                    screenshot.getWidth(), screenshot.getHeight());
                            OcrDocument screenPatch = transform.documentBitmapToScreen(parentBitmap);
                            index.replaceOcrRegion(screenPatch, region);
                            current = index.current();
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "roi screenshot mlkit ready engine=" + engine
                                    + " roiChars=" + screenPatch.chars().size()
                                    + " mergedChars=" + current.chars().size()
                                    + " screen=" + region.toShortString()
                                    + " coordinateSpace=" + screenPatch.coordinateSpace()
                                    + " ppocr=false");
                            emit(current, Stage.ROI_PRECISE, true);
                        } finally {
                            closeRoiRecognizer(finalRecognizer);
                            if (!crop.isRecycled()) crop.recycle();
                        }
                    })
                    .addOnFailureListener(error -> {
                        closeRoiRecognizer(finalRecognizer);
                        if (!crop.isRecycled()) crop.recycle();
                        if (!isCurrent(run)) return;
                        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi screenshot mlkit failed=" + safe(error));
                        emitFailure(Stage.ROI_PRECISE, error, true);
                    });
        } catch (Throwable t) {
            if (recognizer != null) closeRoiRecognizer(recognizer);
            if (!crop.isRecycled()) crop.recycle();
            DiagnosticLog.i(app, "CIRCLE_INDEX", "roi screenshot mlkit init failed=" + safe(t));
            emitFailure(Stage.ROI_PRECISE, t, true);
        }
    }

    void cancel() {
        if (closed) return;
        closed = true;
        generation++;
        TextRecognizer fast = fastRecognizer;
        fastRecognizer = null;
        if (fast != null) try { fast.close(); } catch (Throwable ignored) {}
        TextRecognizer roi = roiRecognizer;
        roiRecognizer = null;
        if (roi != null) try { roi.close(); } catch (Throwable ignored) {}
        DiagnosticLog.i(app, "CIRCLE_INDEX", "session cancelled source=screenshot ppocr=false");
    }

    private void startFastMlKit(long run) {
        if (!isCurrent(run)) return;
        long started = android.os.SystemClock.uptimeMillis();
        try {
            TextRecognizer recognizer = MlKitTextCore.createPreferredRecognizer(app);
            fastRecognizer = recognizer;
            String engine = MlKitTextCore.preferredEngine("fast-mlkit", app);
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast screenshot mlkit start engine=" + engine
                    + " image=" + screenshot.getWidth() + "x" + screenshot.getHeight()
                    + " frame=" + transform.screenFrame().toShortString()
                    + " languages=" + OcrLanguages.get(app)
                    + " ppocr=false");

            recognizer.process(InputImage.fromBitmap(screenshot, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            if (!isCurrent(run) || index == null) return;
                            OcrDocument bitmapFast = MlKitTextCore.toDocument(
                                    text, engine, screenshot.getWidth(), screenshot.getHeight(),
                                    MLKIT_CONFIDENCE, null);
                            OcrDocument screenFast = transform.documentBitmapToScreen(bitmapFast);
                            index.setFastOcr(screenFast);
                            current = index.current();
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast screenshot mlkit ready engine=" + engine
                                    + " ocrChars=" + screenFast.chars().size()
                                    + " mergedChars=" + current.chars().size()
                                    + " coordinateSpace=" + screenFast.coordinateSpace()
                                    + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                                    + " ppocr=false");
                            emit(current, Stage.FAST_MLKIT, true);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    })
                    .addOnFailureListener(error -> {
                        try {
                            if (!isCurrent(run)) return;
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast screenshot mlkit failed=" + safe(error));
                            emitFailure(Stage.FAST_MLKIT, error, true);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    });
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast screenshot mlkit init failed=" + safe(t));
            emitFailure(Stage.FAST_MLKIT, t, true);
        }
    }

    private void closeFastRecognizer(TextRecognizer recognizer) {
        if (fastRecognizer == recognizer) fastRecognizer = null;
        try { recognizer.close(); } catch (Throwable ignored) {}
    }

    private void closeRoiRecognizer(TextRecognizer recognizer) {
        if (roiRecognizer == recognizer) roiRecognizer = null;
        try { recognizer.close(); } catch (Throwable ignored) {}
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
