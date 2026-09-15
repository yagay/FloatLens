package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;

import java.util.List;

/**
 * Per-Circle-Select recognition strategy.
 *
 * Circle owns scheduling (View snapshot, fast full-frame ML Kit, precise ROI) while recognizer
 * language selection and ML Kit geometry parsing are shared by {@link MlKitTextCore}. PP-OCR remains
 * a normal-OCR policy and is not duplicated here.
 */
final class CircleRecognitionSession {
    enum Stage { VIEW_SNAPSHOT, FAST_MLKIT, ROI_PRECISE }

    interface Callback {
        void onUpdate(OcrDocument document, Stage stage, boolean fastReady);
        void onFailure(Stage stage, Throwable error, boolean fastReady);
    }

    private static final int MLKIT_MIN_IMAGE_EDGE = 32;
    private static final float MLKIT_CONFIDENCE = 0.72f;

    private final Context app;
    private final Bitmap screenshot;
    private final CircleViewTextSnapshot viewSnapshot;
    private final ScreenBitmapTransform transform;
    private final Callback callback;
    private final int ocrMode;
    private long generation;
    private boolean closed;
    private OcrDocument current;
    private CircleTextIndex index;
    private TextRecognizer fastRecognizer;
    private TextRecognizer roiRecognizer;

    CircleRecognitionSession(Context context, Bitmap screenshot,
                             CircleViewTextSnapshot viewSnapshot,
                             ScreenBitmapTransform transform,
                             Callback callback) {
        this.app = context.getApplicationContext();
        this.screenshot = screenshot;
        this.ocrMode = CircleOcrPolicy.mode(new FloatSettings(app));
        Rect fallbackDisplay = ScreenGeometry.displayBounds(app);
        this.viewSnapshot = viewSnapshot == null
                ? CircleViewTextSnapshot.empty(fallbackDisplay) : viewSnapshot;
        Rect frame = transform == null ? CircleSelectFrame.contentBounds(app) : transform.screenFrame();
        this.transform = transform == null
                ? new ScreenBitmapTransform(frame,
                        screenshot == null ? 1 : screenshot.getWidth(),
                        screenshot == null ? 1 : screenshot.getHeight())
                : transform;
        this.callback = callback;
    }

    int mode() { return ocrMode; }
    boolean visualOcrEnabled() { return ocrMode != CircleOcrPolicy.MODE_VIEW_ONLY; }

    void start() {
        if (closed || screenshot == null || screenshot.isRecycled()) return;
        final long run = ++generation;
        long started = android.os.SystemClock.uptimeMillis();
        Rect frame = transform.screenFrame();
        boolean useView = ocrMode != CircleOcrPolicy.MODE_MLKIT_ONLY;
        boolean viewOnly = ocrMode == CircleOcrPolicy.MODE_VIEW_ONLY;

        try {
            OcrDocument view = useView
                    ? viewSnapshot.toScreenDocument()
                    : emptyScreenDocument("view-disabled", frame);
            if (!isCurrent(run)) return;
            index = new CircleTextIndex(view,
                    Math.max(1, frame.width()), Math.max(1, frame.height()));
            current = index.current();
            DiagnosticLog.i(app, "CIRCLE_INDEX",
                    "start mode=" + ocrMode
                            + " view=" + useView
                            + " mlkit=" + !viewOnly
                            + " ppocr=false"
                            + " viewNodes=" + (useView ? viewSnapshot.nodeCount() : 0)
                            + " exactGeometryNodes=" + (useView ? viewSnapshot.exactGeometryNodeCount() : 0)
                            + " chars=" + view.chars().size()
                            + " lines=" + view.lines().size()
                            + " frame=" + frame.toShortString()
                            + " coordinateSpace=" + view.coordinateSpace()
                            + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
            emit(current, Stage.VIEW_SNAPSHOT, viewOnly);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "view snapshot failed=" + safe(t));
            index = new CircleTextIndex(emptyScreenDocument("view-snapshot", frame),
                    Math.max(1, frame.width()), Math.max(1, frame.height()));
            current = index.current();
            emitFailure(Stage.VIEW_SNAPSHOT, t, viewOnly);
        }

        if (viewOnly) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "view-only ready ppocr=false");
            return;
        }
        startFastMlKit(run);
    }

    /** Refine one missed screen-space region with the same shared ML Kit core. */
    void refine(Rect screenRegion) {
        if (ocrMode == CircleOcrPolicy.MODE_VIEW_ONLY) {
            emitFailure(Stage.ROI_PRECISE,
                    new IllegalStateException("Circle visual OCR disabled in View-only mode"), true);
            return;
        }
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
            DiagnosticLog.i(app, "CIRCLE_INDEX", "roi mlkit start requested="
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
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "roi mlkit ready engine=" + engine
                                    + " roiChars=" + screenPatch.chars().size()
                                    + " viewChars=" + index.viewDocument().chars().size()
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
                        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi mlkit failed=" + safe(error));
                        emitFailure(Stage.ROI_PRECISE, error, true);
                    });
        } catch (Throwable t) {
            if (recognizer != null) closeRoiRecognizer(recognizer);
            if (!crop.isRecycled()) crop.recycle();
            DiagnosticLog.i(app, "CIRCLE_INDEX", "roi mlkit init failed=" + safe(t));
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
        DiagnosticLog.i(app, "CIRCLE_INDEX", "session cancelled mode=" + ocrMode + " ppocr=false");
    }

    private void startFastMlKit(long run) {
        if (!isCurrent(run) || ocrMode == CircleOcrPolicy.MODE_VIEW_ONLY) return;
        long started = android.os.SystemClock.uptimeMillis();
        try {
            TextRecognizer recognizer = MlKitTextCore.createPreferredRecognizer(app);
            fastRecognizer = recognizer;
            String engine = MlKitTextCore.preferredEngine("fast-mlkit", app);
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast start engine=" + engine
                    + " mode=" + ocrMode
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
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast ready engine=" + engine
                                    + " mode=" + ocrMode
                                    + " viewChars=" + index.viewDocument().chars().size()
                                    + " ocrChars=" + screenFast.chars().size()
                                    + " mergedChars=" + current.chars().size()
                                    + " geometryRefined=" + index.lastGeometryRefinedChars()
                                    + " approximateView=" + index.lastApproximateViewChars()
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
                            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast failed=" + safe(error));
                            emitFailure(Stage.FAST_MLKIT, error, true);
                        } finally {
                            closeFastRecognizer(recognizer);
                        }
                    });
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "fast init failed=" + safe(t));
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
