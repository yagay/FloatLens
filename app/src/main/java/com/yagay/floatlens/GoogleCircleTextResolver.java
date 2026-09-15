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
 * a fresh Accessibility/View-text snapshot and test only the user's gesture against that document.
 * If the gesture did not hit native View text, only a small bitmap ROI around the gesture is OCR'd.
 * OCR geometry is translated immediately back to absolute SCREEN coordinates so the same old
 * CircleTextSelectionModel can render/adjust both native and image text without coordinate mixing.
 */
final class GoogleCircleTextResolver {
    enum Source { VIEW, IMAGE_OCR, NONE }

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
    private static final float OCR_TAP_TOLERANCE_DP = 44f;
    private static final float OCR_TAP_HALF_WIDTH_DP = 180f;
    private static final float OCR_TAP_HALF_HEIGHT_DP = 72f;
    private static final float OCR_CONTEXT_PAD_DP = 22f;
    private static final float OCR_MIN_WIDTH_DP = 120f;
    private static final float OCR_MIN_HEIGHT_DP = 72f;

    static void resolve(Context c, GoogleCircleCapture.Frame frame,
                        GoogleCircleSelection.Selection gesture, Callback callback) {
        if (c == null || frame == null || gesture == null || callback == null) return;
        Context app = c.getApplicationContext();
        Rect gestureScreen = gestureScreenBounds(frame, gesture);
        long started = android.os.SystemClock.uptimeMillis();

        DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "start gesture=" + gesture.kind
                + " screen=" + gestureScreen.toShortString()
                + " strategy=view_then_local_ocr fullScreenOcr=false");

        VIEW_IO.execute(() -> {
            CircleViewTextSnapshot snapshot;
            OcrDocument viewDocument;
            try {
                snapshot = CircleViewTextSnapshot.capture(app);
                viewDocument = snapshot.toScreenDocument();
            } catch (Throwable t) {
                snapshot = CircleViewTextSnapshot.empty(frame.screenBounds);
                viewDocument = snapshot.toScreenDocument();
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "view snapshot failed="
                        + ScreenCaptureBackend.safeMessage(t));
            }

            boolean hit = documentHitsGesture(app, frame, gesture, viewDocument,
                    VIEW_TAP_TOLERANCE_DP);
            long elapsed = android.os.SystemClock.uptimeMillis() - started;
            OcrDocument finalViewDocument = viewDocument;
            MAIN.post(() -> {
                if (hit) {
                    DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "resolved source=view"
                            + " chars=" + finalViewDocument.chars().size()
                            + " elapsedMs=" + elapsed
                            + " coordinateSpace=SCREEN");
                    callback.onResolved(new Result(Source.VIEW, finalViewDocument,
                            gestureScreen, new Rect(), null));
                } else {
                    DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "view miss -> local OCR"
                            + " elapsedMs=" + elapsed);
                    resolveLocalOcr(app, frame, gesture, gestureScreen, callback);
                }
            });
        });
    }

    private static void resolveLocalOcr(Context app, GoogleCircleCapture.Frame frame,
                                        GoogleCircleSelection.Selection gesture,
                                        Rect gestureScreen, Callback callback) {
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
                + " recognitionPaddingOnly=true fullScreenOcr=false");

        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                OcrDocument screen = translateToScreen(frame, roi, document);
                recycle(crop);
                boolean hit = documentHitsGesture(app, frame, gesture, screen,
                        OCR_TAP_TOLERANCE_DP);
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "local OCR done hit=" + hit
                        + " chars=" + (screen == null ? 0 : screen.chars().size())
                        + " coordinateSpace=" + (screen == null ? "none" : screen.coordinateSpace()));
                callback.onResolved(new Result(hit ? Source.IMAGE_OCR : Source.NONE,
                        hit ? screen : null, gestureScreen, roi, null));
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                DiagnosticLog.i(app, "G_CIRCLE_TEXT_RESOLVE", "local OCR failed="
                        + ScreenCaptureBackend.safeMessage(error));
                callback.onResolved(new Result(Source.NONE, null, gestureScreen, roi, error));
            }
        });
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
            return clamp(roi, bw, bh);
        }

        Rect exact = GoogleCircleSelection.exactRectAndClamp(gesture.bounds, bw, bh);
        if (exact.isEmpty()) return exact;
        int padX = Math.max(1, Math.round(bitmapPxForDp(app, frame, OCR_CONTEXT_PAD_DP, true)));
        int padY = Math.max(1, Math.round(bitmapPxForDp(app, frame, OCR_CONTEXT_PAD_DP, false)));
        Rect roi = new Rect(exact.left - padX, exact.top - padY,
                exact.right + padX, exact.bottom + padY);

        int minW = Math.max(1, Math.round(bitmapPxForDp(app, frame, OCR_MIN_WIDTH_DP, true)));
        int minH = Math.max(1, Math.round(bitmapPxForDp(app, frame, OCR_MIN_HEIGHT_DP, false)));
        expandToMinimum(roi, minW, minH);
        return clamp(roi, bw, bh);
    }

    private static void expandToMinimum(Rect r, int minW, int minH) {
        if (r.width() < minW) {
            int extra = minW - r.width();
            r.left -= extra / 2;
            r.right += extra - extra / 2;
        }
        if (r.height() < minH) {
            int extra = minH - r.height();
            r.top -= extra / 2;
            r.bottom += extra - extra / 2;
        }
    }

    private static Rect clamp(Rect source, int width, int height) {
        Rect r = new Rect(source);
        if (r.left < 0) r.offset(-r.left, 0);
        if (r.top < 0) r.offset(0, -r.top);
        if (r.right > width) r.offset(width - r.right, 0);
        if (r.bottom > height) r.offset(0, height - r.bottom);
        r.left = Math.max(0, Math.min(width - 1, r.left));
        r.top = Math.max(0, Math.min(height - 1, r.top));
        r.right = Math.max(r.left + 1, Math.min(width, r.right));
        r.bottom = Math.max(r.top + 1, Math.min(height, r.bottom));
        return r;
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
