package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gesture-scoped text resolver for the Google-style workspace.
 *
 * Nothing is recognized when the workspace opens. For TAP/HIGHLIGHT/SCRIBBLE we first take a fresh
 * Accessibility/View snapshot at gesture time. Native per-character geometry is used directly when
 * Android really provides it. Synthetic View geometry is classification-only.
 *
 * When exact View character geometry is unavailable, OCR runs only after that user gesture, against
 * the current frozen screenshot. The whole frozen frame is intentionally recognized so the initial
 * selection may stay small while the two handles can later extend to additional words/lines without
 * hitting the boundary of the original gesture ROI. This is not the old pre-index flow: recognition
 * still starts at the click/scribble moment and all OCR geometry is translated into absolute screen
 * coordinates before the selection model sees it.
 */
final class GoogleCircleTextResolver {
    enum Source { VIEW, VIEW_OCR, IMAGE_OCR, NONE }

    interface Callback {
        void onResolved(Result result);
    }

    static final class Result {
        final Source source;
        final OcrDocument document;
        final Rect gestureScreenBounds;
        final Rect ocrBitmapRoi;
        final Throwable error;

        Result(Source source, OcrDocument document, Rect gestureScreenBounds,
               Rect ocrBitmapRoi, Throwable error) {
            this.source = source == null ? Source.NONE : source;
            this.document = document;
            this.gestureScreenBounds = gestureScreenBounds == null
                    ? new Rect() : new Rect(gestureScreenBounds);
            this.ocrBitmapRoi = ocrBitmapRoi == null ? new Rect() : new Rect(ocrBitmapRoi);
            this.error = error;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService VIEW_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-circle-hit-view");
        t.setDaemon(true);
        return t;
    });

    private static final float VIEW_TAP_TOLERANCE_DP = 8f;

    static void resolve(Context c, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (c == null || frame == null || gesture == null || callback == null) return;
        Context app = c.getApplicationContext();
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        long started = android.os.SystemClock.uptimeMillis();

        DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "start gesture=" + gesture.kind
                + " screen=" + gestureScreen.toShortString()
                + " strategy=exact_view_else_gesture_time_full_frame_ocr"
                + " preIndex=false staleSnapshot=false");

        VIEW_IO.execute(() -> {
            OcrDocument viewDocument;
            try {
                CircleViewTextSnapshot snapshot = CircleViewTextSnapshot.capture(app);
                viewDocument = snapshot.toScreenDocument();
            } catch (Throwable t) {
                viewDocument = CircleViewTextSnapshot.empty(frame.screenBounds).toScreenDocument();
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "view snapshot failed="
                        + ScreenCaptureBackend.safeMessage(t));
            }

            OcrDocument exactViewDocument = exactViewGeometry(viewDocument);
            boolean exactViewHit = documentHitsGesture(app, frame, gesture, exactViewDocument,
                    VIEW_TAP_TOLERANCE_DP);
            boolean coarseViewHit = exactViewHit || documentHitsGesture(app, frame, gesture,
                    viewDocument, VIEW_TAP_TOLERANCE_DP);
            long elapsed = android.os.SystemClock.uptimeMillis() - started;
            OcrDocument finalExactViewDocument = exactViewDocument;

            MAIN.post(() -> {
                if (exactViewHit) {
                    DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "resolved source=view_exact"
                            + " chars=" + finalExactViewDocument.chars().size()
                            + " elapsedMs=" + elapsed
                            + " coordinateSpace=SCREEN"
                            + " handleExpansion=whole_view_document");
                    callback.onResolved(new Result(Source.VIEW, finalExactViewDocument,
                            gestureScreen, new Rect(), null));
                    return;
                }

                Source ocrSource = coarseViewHit ? Source.VIEW_OCR : Source.IMAGE_OCR;
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE",
                        (coarseViewHit
                                ? "view hit has no exact character geometry -> gesture-time OCR geometry"
                                : "view miss -> gesture-time OCR")
                                + " elapsedMs=" + elapsed
                                + " source=" + ocrSource
                                + " scope=full_frozen_frame_for_handle_expansion");
                resolveGestureTimeOcr(app, frame, gestureScreen, ocrSource, callback);
            });
        });
    }

    private static void resolveGestureTimeOcr(Context app, GoogleCircleCapture.Frame frame,
                                              Rect gestureScreen, Source resolvedSource,
                                              Callback callback) {
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, new Rect(),
                    new IllegalStateException("frozen screenshot unavailable")));
            return;
        }

        Rect roi = new Rect(0, 0, source.getWidth(), source.getHeight());
        final Bitmap copy;
        try {
            Bitmap made = source.copy(Bitmap.Config.ARGB_8888, false);
            if (made == null) throw new IllegalStateException("OCR frame copy failed");
            copy = made;
        } catch (Throwable t) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, t));
            return;
        }

        DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "gesture-time OCR roi="
                + roi.toShortString()
                + " bitmap=" + copy.getWidth() + "x" + copy.getHeight()
                + " semanticSource=" + resolvedSource
                + " scope=full_frozen_frame"
                + " reason=selection_handle_expansion"
                + " preIndex=false");

        OcrEngine.recognizeDocument(app, copy, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                OcrDocument screen = translateToScreen(frame, roi, document);
                recycle(copy);
                int chars = screen == null ? 0 : screen.chars().size();
                boolean usable = screen != null && screen.isScreenSpace() && chars > 0;
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "gesture-time OCR done usable="
                        + usable
                        + " chars=" + chars
                        + " semanticSource=" + resolvedSource
                        + " coordinateSpace=" + (screen == null ? "none" : screen.coordinateSpace())
                        + " handleExpansionReady=" + usable);

                // The selection model owns the gesture hit. Keeping the complete document here is
                // deliberate: the initial gesture chooses only its target, while later handle drags
                // can extend into any additional recognized character in the same frozen frame.
                callback.onResolved(new Result(usable ? resolvedSource : Source.NONE,
                        usable ? screen : null, gestureScreen, roi, null));
            }

            @Override public void onFailure(Throwable error) {
                recycle(copy);
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "gesture-time OCR failed="
                        + ScreenCaptureBackend.safeMessage(error)
                        + " semanticSource=" + resolvedSource);
                callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, error));
            }
        });
    }

    /**
     * CircleViewTextSnapshot marks true Accessibility character boxes with confidence 1.0 and its
     * synthetic fallback with 0.92. Only the true boxes may be rendered directly.
     */
    private static OcrDocument exactViewGeometry(OcrDocument source) {
        if (source == null || !source.isScreenSpace() || source.lines().isEmpty()) {
            return emptyScreenDocument(source, "view-exact-empty");
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int order = 0;
        for (OcrDocument.Line line : source.lines()) {
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            Rect lineBounds = null;
            StringBuilder text = new StringBuilder();
            for (OcrDocument.CharUnit c : line.chars()) {
                if (c == null || c.text().isBlank() || c.bounds().isEmpty()
                        || c.confidence() < 0.999f) continue;
                Rect bounds = c.bounds();
                if (lineBounds == null) lineBounds = new Rect(bounds); else lineBounds.union(bounds);
                chars.add(new OcrDocument.CharUnit(c.text(), bounds, c.confidence(),
                        lineId, c.group(), order++));
                text.append(c.text());
            }
            if (chars.isEmpty() || lineBounds == null || lineBounds.isEmpty()) continue;
            String row = text.toString();
            lines.add(new OcrDocument.Line(row, lineBounds, 1f, chars));
            blocks.add(row);
            if (full.length() > 0) full.append('\n');
            full.append(row);
            lineId++;
        }

        return OcrDocument.screenSpace(full.toString(), blocks, lines,
                "view-exact", 1f, source.score(),
                source.imageWidth(), source.imageHeight());
    }

    private static OcrDocument emptyScreenDocument(OcrDocument source, String engine) {
        int width = source == null ? 1 : source.imageWidth();
        int height = source == null ? 1 : source.imageHeight();
        return OcrDocument.screenSpace("", List.of(), List.of(), engine, 1f, 0d,
                Math.max(1, width), Math.max(1, height));
    }

    private static boolean documentHitsGesture(Context app, GoogleCircleCapture.Frame frame,
                                               GoogleCircleSelection.Selection gesture,
                                               OcrDocument document, float tapToleranceDp) {
        if (document == null || !document.isScreenSpace() || document.chars().isEmpty()) return false;
        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            PointF screenPoint = bitmapPointToScreen(frame, gesture.focus);
            float tolerance = dp(app, tapToleranceDp);
            float best = Float.MAX_VALUE;
            for (OcrDocument.CharUnit c : document.chars()) {
                Rect r = c.bounds();
                if (r.isEmpty()) continue;
                if (r.contains(Math.round(screenPoint.x), Math.round(screenPoint.y))) return true;
                float dx = screenPoint.x < r.left ? r.left - screenPoint.x
                        : screenPoint.x > r.right ? screenPoint.x - r.right : 0f;
                float dy = screenPoint.y < r.top ? r.top - screenPoint.y
                        : screenPoint.y > r.bottom ? screenPoint.y - r.bottom : 0f;
                best = Math.min(best, (float) Math.hypot(dx, dy));
            }
            return best <= tolerance;
        }

        Rect exact = gestureScreenBounds(frame, gesture);
        if (exact.isEmpty()) return false;
        int tolerance = Math.max(1, Math.round(dp(app, 5f)));
        Rect tolerant = new Rect(exact);
        tolerant.inset(-tolerance, -tolerance);
        tolerant.intersect(frame.screenBounds);
        for (OcrDocument.CharUnit c : document.chars()) {
            Rect r = c.bounds();
            if (!r.isEmpty() && Rect.intersects(tolerant, r)) return true;
        }
        return false;
    }

    private static OcrDocument translateToScreen(GoogleCircleCapture.Frame frame, Rect roi,
                                                 OcrDocument local) {
        if (local == null || local.lines().isEmpty()) {
            return OcrDocument.screenSpace(local == null ? "" : local.fullText(),
                    local == null ? List.of() : local.blocks(), List.of(),
                    local == null ? "gesture-ocr" : "gesture-" + local.engine(),
                    local == null ? 0f : local.confidence(),
                    local == null ? 0d : local.score(),
                    Math.max(1, frame.screenBounds.width()),
                    Math.max(1, frame.screenBounds.height()));
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int order = 0;
        for (OcrDocument.Line line : local.lines()) {
            Rect lineBitmap = offsetAndClamp(line.bounds(), roi,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
            if (lineBitmap.isEmpty()) continue;
            Rect lineScreen = frame.bitmapRectToScreen(lineBitmap);
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit c : line.chars()) {
                Rect charBitmap = offsetAndClamp(c.bounds(), roi,
                        frame.bitmap.getWidth(), frame.bitmap.getHeight());
                if (charBitmap.isEmpty()) continue;
                Rect charScreen = frame.bitmapRectToScreen(charBitmap);
                if (charScreen.isEmpty()) continue;
                chars.add(new OcrDocument.CharUnit(c.text(), charScreen, c.confidence(),
                        lineId, c.group(), order++));
            }
            if (!chars.isEmpty()) {
                lines.add(new OcrDocument.Line(line.text(), lineScreen,
                        line.confidence(), chars));
                lineId++;
            }
        }

        return OcrDocument.screenSpace(local.fullText(), local.blocks(), lines,
                "gesture-" + local.engine(), local.confidence(), local.score(),
                Math.max(1, frame.screenBounds.width()),
                Math.max(1, frame.screenBounds.height()));
    }

    private static Rect offsetAndClamp(Rect local, Rect roi, int width, int height) {
        if (local == null || local.isEmpty()) return new Rect();
        Rect out = new Rect(local);
        out.offset(roi.left, roi.top);
        if (!out.intersect(0, 0, width, height)) return new Rect();
        return out;
    }

    private static Rect gestureScreenBounds(GoogleCircleCapture.Frame frame,
                                            GoogleCircleSelection.Selection gesture) {
        Rect bitmap = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return bitmap.isEmpty() ? new Rect() : frame.bitmapRectToScreen(bitmap);
    }

    private static PointF bitmapPointToScreen(GoogleCircleCapture.Frame frame, PointF bitmapPoint) {
        float sx = frame.screenBounds.width() / (float) Math.max(1, frame.bitmap.getWidth());
        float sy = frame.screenBounds.height() / (float) Math.max(1, frame.bitmap.getHeight());
        return new PointF(frame.screenBounds.left + bitmapPoint.x * sx,
                frame.screenBounds.top + bitmapPoint.y * sy);
    }

    private static float dp(Context app, float value) {
        return value * app.getResources().getDisplayMetrics().density;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleTextResolver() {}
}
