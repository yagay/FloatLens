package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Runs OCR only on the final selected ROI; never on an independently transformed screen rect. */
final class GoogleCircleResultCoordinator {
    interface Callback {
        void onResolved(Resolution resolution);
    }

    static final class Resolution {
        final Bitmap crop;
        final Rect screenAnchor;
        final String text;
        final List<String> blocks;
        final Throwable error;

        Resolution(Bitmap crop, Rect screenAnchor, String text,
                   List<String> blocks, Throwable error) {
            this.crop = crop;
            this.screenAnchor = new Rect(screenAnchor);
            this.text = text == null ? "" : text.trim();
            this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
            this.error = error;
        }

        boolean hasText() { return !text.isBlank(); }
    }

    static void recognize(Context c, GoogleCircleCapture.Frame frame,
                          GoogleCircleSelection.Selection selection,
                          Callback callback) {
        if (c == null || frame == null || selection == null || callback == null) return;
        Context app = c.getApplicationContext();
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            callback.onResolved(new Resolution(null, new Rect(), "", List.of(),
                    new IllegalStateException("source bitmap unavailable")));
            return;
        }

        // ML Kit requires dimensions >= 32. Use 96 px minimum so taps near edges can never collapse
        // into the legacy 1 px-high ROI failure mode.
        Rect roi = GoogleCircleSelection.ensureMinAndClamp(selection.bounds,
                source.getWidth(), source.getHeight(), 96);
        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height());
        } catch (Throwable t) {
            callback.onResolved(new Resolution(null, frame.bitmapRectToScreen(roi),
                    "", List.of(), t));
            return;
        }
        Rect screenAnchor = frame.bitmapRectToScreen(roi);
        DiagnosticLog.i(app, "G_CIRCLE_ROI", "kind=" + selection.kind
                + " bitmap=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " screen=" + screenAnchor.toShortString());

        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                String text = resolveTextForTap(selection, roi, document);
                List<String> blocks = selection.kind == GoogleCircleSelection.Kind.TAP && !text.isBlank()
                        ? List.of(text) : document.blocks();
                DiagnosticLog.i(app, "G_CIRCLE_OCR", "kind=" + selection.kind
                        + " chars=" + text.length()
                        + " engine=" + document.engine()
                        + " crop=" + crop.getWidth() + "x" + crop.getHeight());
                callback.onResolved(new Resolution(crop, screenAnchor, text, blocks, null));
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_OCR", "failed kind=" + selection.kind
                        + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                        + " error=" + ScreenCaptureBackend.safeMessage(error));
                // Image selection is still useful even when OCR fails.
                callback.onResolved(new Resolution(crop, screenAnchor, "", List.of(), error));
            }
        });
    }

    private static String resolveTextForTap(GoogleCircleSelection.Selection selection,
                                            Rect roi, OcrDocument document) {
        if (document == null) return "";
        if (selection.kind != GoogleCircleSelection.Kind.TAP || document.lines().isEmpty()) {
            return document.fullText();
        }
        float localX = selection.focus.x - roi.left;
        float localY = selection.focus.y - roi.top;
        OcrDocument.Line best = null;
        float bestDistance = Float.MAX_VALUE;
        for (OcrDocument.Line line : document.lines()) {
            Rect b = line.bounds();
            float dx = localX < b.left ? b.left - localX : (localX > b.right ? localX - b.right : 0f);
            float dy = localY < b.top ? b.top - localY : (localY > b.bottom ? localY - b.bottom : 0f);
            float distance = (float) Math.hypot(dx, dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = line;
            }
        }
        float acceptable = Math.max(56f, Math.min(roi.width(), roi.height()) * 0.42f);
        return best != null && bestDistance <= acceptable ? best.text() : document.fullText();
    }

    private GoogleCircleResultCoordinator() {}
}
