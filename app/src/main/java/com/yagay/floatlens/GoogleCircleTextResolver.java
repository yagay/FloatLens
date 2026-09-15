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
 * Nothing is OCR'd when the workspace opens. For each TAP/HIGHLIGHT/SCRIBBLE gesture we first take
 * a fresh Accessibility/View-text snapshot. Native per-character geometry is used only when Android
 * actually provides it. Synthetic View character boxes are classification-only and are never shown
 * as selectable geometry. When exact View geometry is unavailable, or there is no View text at the
 * gesture, a compact local OCR ROI supplies real character geometry for the old selectable-text UI.
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
    private static final float OCR_TAP_HALF_WIDTH_DP = 96f;
    private static final float OCR_TAP_HALF_HEIGHT_DP = 48f;
    private static final float OCR_CONTEXT_PAD_DP = 18f;

    static void resolve(Context c, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (c == null || frame == null || gesture == null || callback == null) return;
        Context app = c.getApplicationContext();
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        long started = android.os.SystemClock.uptimeMillis();

        DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "start gesture=" + gesture.kind
                + " screen=" + gestureScreen.toShortString()
                + " strategy=exact_view_else_local_ocr fullScreenOcr=false");

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
                            + " coordinateSpace=SCREEN");
                    callback.onResolved(new Result(Source.VIEW, finalExactViewDocument,
                            gestureScreen, new Rect(), null));
                    return;
                }

                Source ocrSource = coarseViewHit ? Source.VIEW_OCR : Source.IMAGE_OCR;
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE",
                        (coarseViewHit
                                ? "view hit has no exact character geometry -> local OCR geometry"
                                : "view miss -> local OCR")
                                + " elapsedMs=" + elapsed
                                + " source=" + ocrSource);
                resolveLocalOcr(app, frame, gesture, gestureScreen, ocrSource, callback);
            });
        });
    }

    private static void resolveLocalOcr(Context app, GoogleCircleCapture.Frame frame,
                                        GoogleCircleSelection.Selection gesture,
                                        Rect gestureScreen, Source resolvedSource,
                                        Callback callback) {
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, new Rect(),
                    new IllegalStateException("frozen screenshot unavailable")));
            return;
        }

        Rect roi = recognitionRoi(app, frame, gesture);
        if (roi.isEmpty()) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, null));
            return;
        }

        final Bitmap crop;
        try {
            Bitmap made = Bitmap.createBitmap(source, roi.left, roi.top,
                    roi.width(), roi.height());
            if (made == source) {
                Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) throw new IllegalStateException("OCR ROI copy failed");
                made = copy;
            }
            crop = made;
        } catch (Throwable t) {
            callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, t));
            return;
        }

        DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "local OCR roi=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " semanticSource=" + resolvedSource
                + " edgePolicy=clip_not_shift"
                + " recognitionPaddingOnly=true fullScreenOcr=false");

        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                OcrDocument screen = translateToScreen(frame, roi, document);
                recycle(crop);
                int chars = screen == null ? 0 : screen.chars().size();
                boolean usable = screen != null && screen.isScreenSpace() && chars > 0;
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "local OCR done usable=" + usable
                        + " chars=" + chars
                        + " semanticSource=" + resolvedSource
                        + " coordinateSpace=" + (screen == null ? "none" : screen.coordinateSpace()));

                // Do not run a second independent hit test here. The UI selection model owns the
                // final gesture-to-character decision. The previous duplicate hit test discarded
                // valid OCR documents even when OCR had successfully returned dozens of characters.
                callback.onResolved(new Result(usable ? resolvedSource : Source.NONE,
                        usable ? screen : null, gestureScreen, roi, null));
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "local OCR failed="
                        + ScreenCaptureBackend.safeMessage(error)
                        + " semanticSource=" + resolvedSource);
                callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, error));
            }
        });
    }

    /**
     * CircleViewTextSnapshot deliberately marks exact Accessibility character boxes with confidence
     * 1.0 and its legacy synthetic fallback with 0.92. Only the former may be displayed directly.
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

    private static Rect recognitionRoi(Context app, GoogleCircleCapture.Frame frame,
                                       GoogleCircleSelection.Selection gesture) {
        int bw = frame.bitmap.getWidth();
        int bh = frame.bitmap.getHeight();
        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            float halfW = bitmapPxForDp(app, frame, OCR_TAP_HALF_WIDTH_DP, true);
            float halfH = bitmapPxForDp(app, frame, OCR_TAP_HALF_HEIGHT_DP, false);
            Rect roi = new Rect(
                    (int) Math.floor(gesture.focus.x - halfW),
                    (int) Math.floor(gesture.focus.y - halfH),
                    (int) Math.ceil(gesture.focus.x + halfW),
                    (int) Math.ceil(gesture.focus.y + halfH));
            return clip(roi, bw, bh);
        }

        Rect exact = GoogleCircleSelection.exactRectAndClamp(gesture.bounds, bw, bh);
        if (exact.isEmpty()) return exact;
        int padX = Math.max(1, Math.round(bitmapPxForDp(app, frame, OCR_CONTEXT_PAD_DP, true)));
        int padY = Math.max(1, Math.round(bitmapPxForDp(app, frame, OCR_CONTEXT_PAD_DP, false)));
        Rect roi = new Rect(exact.left - padX, exact.top - padY,
                exact.right + padX, exact.bottom + padY);
        return clip(roi, bw, bh);
    }

    /** Clip at the screen edge. Never translate the ROI to preserve its old size. */
    private static Rect clip(Rect source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) return new Rect();
        int left = Math.max(0, Math.min(width, source.left));
        int top = Math.max(0, Math.min(height, source.top));
        int right = Math.max(0, Math.min(width, source.right));
        int bottom = Math.max(0, Math.min(height, source.bottom));
        if (right <= left || bottom <= top) return new Rect();
        return new Rect(left, top, right, bottom);
    }

    private static OcrDocument translateToScreen(GoogleCircleCapture.Frame frame, Rect roi,
                                                 OcrDocument local) {
        if (local == null || local.lines().isEmpty()) {
            return OcrDocument.screenSpace(local == null ? "" : local.fullText(),
                    local == null ? List.of() : local.blocks(), List.of(),
                    local == null ? "local-ocr" : "local-" + local.engine(),
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
                "local-" + local.engine(), local.confidence(), local.score(),
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

    private static float bitmapPxForDp(Context app, GoogleCircleCapture.Frame frame,
                                       float dp, boolean horizontal) {
        float screenPx = dp(app, dp);
        if (horizontal) {
            return screenPx * frame.bitmap.getWidth()
                    / (float) Math.max(1, frame.screenBounds.width());
        }
        return screenPx * frame.bitmap.getHeight()
                / (float) Math.max(1, frame.screenBounds.height());
    }

    private static float dp(Context app, float value) {
        return value * app.getResources().getDisplayMetrics().density;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleTextResolver() {}
}
