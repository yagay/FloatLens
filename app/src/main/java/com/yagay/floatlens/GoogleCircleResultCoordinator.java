package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.List;

/**
 * Resolves selected CONTENT instead of treating every gesture as a screenshot crop.
 * Native screen text wins immediately; OCR is a fallback for visual text; image output is used only
 * when the selected target is visual content or no text can be resolved.
 */
final class GoogleCircleResultCoordinator {
    enum Kind { NATIVE_TEXT, OCR_TEXT, IMAGE }

    interface Callback {
        void onResolved(Resolution resolution);
    }

    static final class Resolution {
        final Kind kind;
        final Bitmap crop;
        final Rect screenAnchor;
        final String text;
        final List<String> blocks;
        final Throwable error;
        final String source;

        Resolution(Kind kind, Bitmap crop, Rect screenAnchor, String text,
                   List<String> blocks, Throwable error, String source) {
            this.kind = kind == null ? Kind.IMAGE : kind;
            this.crop = crop;
            this.screenAnchor = screenAnchor == null ? new Rect() : new Rect(screenAnchor);
            this.text = text == null ? "" : text.trim();
            this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
            this.error = error;
            this.source = source == null ? "unknown" : source;
        }

        boolean hasText() { return !text.isBlank(); }
    }

    static void resolve(Context c, GoogleCircleCapture.Frame frame,
                        GoogleCircleContentSnapshot content,
                        GoogleCircleSelection.Selection selection,
                        Callback callback) {
        if (c == null || frame == null || selection == null || callback == null) return;
        Context app = c.getApplicationContext();
        Bitmap source = frame.bitmap;
        if (source == null || source.isRecycled()) {
            callback.onResolved(new Resolution(Kind.IMAGE, null, new Rect(), "", List.of(),
                    new IllegalStateException("source bitmap unavailable"), "invalid_frame"));
            return;
        }

        GoogleCircleContentSnapshot.Target target = content == null
                ? GoogleCircleContentSnapshot.Target.none(selectionScreenBounds(frame, selection))
                : content.resolve(frame, selection);
        DiagnosticLog.i(app, "G_CIRCLE_TARGET", "gesture=" + selection.kind
                + " target=" + target.kind
                + " anchor=" + target.screenBounds.toShortString()
                + " textChars=" + target.text.length());

        if (target.hasText()) {
            callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, target.screenBounds,
                    target.text, List.of(target.text), null, "accessibility_text"));
            return;
        }

        // A real image target selected by tap/circle/scribble stays visual. Highlight remains
        // text-biased: it gets an OCR chance before falling back to the visual target.
        if (target.kind == GoogleCircleContentSnapshot.Kind.IMAGE
                && selection.kind != GoogleCircleSelection.Kind.HIGHLIGHT) {
            resolveImage(app, frame, target.screenBounds, callback, "accessibility_image");
            return;
        }

        Rect fallbackScreen = target.kind == GoogleCircleContentSnapshot.Kind.IMAGE
                ? target.screenBounds : selectionScreenBounds(frame, selection);
        recognizeVisualText(app, frame, selection, fallbackScreen, callback);
    }

    private static void recognizeVisualText(Context app, GoogleCircleCapture.Frame frame,
                                             GoogleCircleSelection.Selection selection,
                                             Rect fallbackScreen,
                                             Callback callback) {
        Bitmap source = frame.bitmap;
        Rect roi = GoogleCircleSelection.ensureMinAndClamp(selection.bounds,
                source.getWidth(), source.getHeight(), 96);
        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height());
        } catch (Throwable t) {
            callback.onResolved(new Resolution(Kind.IMAGE, null, frame.bitmapRectToScreen(roi),
                    "", List.of(), t, "crop_failed"));
            return;
        }
        Rect screenAnchor = frame.bitmapRectToScreen(roi);
        DiagnosticLog.i(app, "G_CIRCLE_ROI", "gesture=" + selection.kind
                + " purpose=content_ocr bitmap=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " screen=" + screenAnchor.toShortString());

        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                String text = resolveText(selection, roi, document);
                if (!text.isBlank()) {
                    List<String> blocks = selection.kind == GoogleCircleSelection.Kind.TAP
                            ? List.of(text) : document.blocks();
                    DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                            + " resolved=text chars=" + text.length()
                            + " engine=" + document.engine());
                    recycle(crop);
                    callback.onResolved(new Resolution(Kind.OCR_TEXT, null, screenAnchor,
                            text, blocks, null, "ocr_text"));
                    return;
                }

                DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                        + " resolved=image reason=no_text engine=" + document.engine());
                Rect imageAnchor = fallbackScreen == null || fallbackScreen.isEmpty()
                        ? screenAnchor : fallbackScreen;
                if (!imageAnchor.equals(screenAnchor)) {
                    recycle(crop);
                    resolveImage(app, frame, imageAnchor, callback, "visual_no_text");
                } else {
                    callback.onResolved(new Resolution(Kind.IMAGE, crop, screenAnchor,
                            "", List.of(), null, "visual_no_text"));
                }
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                        + " failed error=" + ScreenCaptureBackend.safeMessage(error));
                Rect imageAnchor = fallbackScreen == null || fallbackScreen.isEmpty()
                        ? screenAnchor : fallbackScreen;
                if (!imageAnchor.equals(screenAnchor)) {
                    recycle(crop);
                    resolveImage(app, frame, imageAnchor, callback, "ocr_failed_image");
                } else {
                    callback.onResolved(new Resolution(Kind.IMAGE, crop, screenAnchor,
                            "", List.of(), error, "ocr_failed_image"));
                }
            }
        });
    }

    private static void resolveImage(Context app, GoogleCircleCapture.Frame frame, Rect screenBounds,
                                     Callback callback, String sourceName) {
        Rect bitmapRect = screenToBitmapRect(frame, screenBounds);
        bitmapRect = GoogleCircleSelection.ensureMinAndClamp(new RectF(bitmapRect),
                frame.bitmap.getWidth(), frame.bitmap.getHeight(), 32);
        try {
            Bitmap crop = Bitmap.createBitmap(frame.bitmap, bitmapRect.left, bitmapRect.top,
                    bitmapRect.width(), bitmapRect.height());
            Rect anchor = frame.bitmapRectToScreen(bitmapRect);
            DiagnosticLog.i(app, "G_CIRCLE_IMAGE", "source=" + sourceName
                    + " bitmap=" + bitmapRect.toShortString()
                    + " screen=" + anchor.toShortString());
            callback.onResolved(new Resolution(Kind.IMAGE, crop, anchor,
                    "", List.of(), null, sourceName));
        } catch (Throwable t) {
            callback.onResolved(new Resolution(Kind.IMAGE, null, screenBounds,
                    "", List.of(), t, sourceName + "_crop_failed"));
        }
    }

    private static String resolveText(GoogleCircleSelection.Selection selection,
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
        float acceptable = Math.max(40f, Math.min(roi.width(), roi.height()) * 0.30f);
        return best != null && bestDistance <= acceptable ? best.text() : "";
    }

    private static Rect selectionScreenBounds(GoogleCircleCapture.Frame frame,
                                              GoogleCircleSelection.Selection selection) {
        Rect roi = rectFrom(selection.bounds, frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return frame.bitmapRectToScreen(roi);
    }

    private static Rect screenToBitmapRect(GoogleCircleCapture.Frame frame, Rect screen) {
        Rect clipped = screen == null ? new Rect(frame.screenBounds) : new Rect(screen);
        if (!clipped.intersect(frame.screenBounds)) clipped.set(frame.screenBounds);
        float sx = frame.bitmap.getWidth() / (float) Math.max(1, frame.screenBounds.width());
        float sy = frame.bitmap.getHeight() / (float) Math.max(1, frame.screenBounds.height());
        int left = Math.round((clipped.left - frame.screenBounds.left) * sx);
        int top = Math.round((clipped.top - frame.screenBounds.top) * sy);
        int right = Math.round((clipped.right - frame.screenBounds.left) * sx);
        int bottom = Math.round((clipped.bottom - frame.screenBounds.top) * sy);
        left = Math.max(0, Math.min(frame.bitmap.getWidth() - 1, left));
        top = Math.max(0, Math.min(frame.bitmap.getHeight() - 1, top));
        right = Math.max(left + 1, Math.min(frame.bitmap.getWidth(), right));
        bottom = Math.max(top + 1, Math.min(frame.bitmap.getHeight(), bottom));
        return new Rect(left, top, right, bottom);
    }

    private static Rect rectFrom(RectF r, int width, int height) {
        int left = Math.max(0, Math.min(width - 1, (int) Math.floor(r.left)));
        int top = Math.max(0, Math.min(height - 1, (int) Math.floor(r.top)));
        int right = Math.max(left + 1, Math.min(width, (int) Math.ceil(r.right)));
        int bottom = Math.max(top + 1, Math.min(height, (int) Math.ceil(r.bottom)));
        return new Rect(left, top, right, bottom);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleResultCoordinator() {}
}
