package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/**
 * Resolves selected content without ever enlarging the user's selection.
 * Native screen text is used when it fits the exact selection; OCR is a fallback only when the
 * exact region is already large enough for the OCR backend. Image output also keeps exact bounds.
 */
final class GoogleCircleResultCoordinator {
    private static final int OCR_MIN_SIDE_PX = 32;

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

        boolean hasText() {
            return !text.isBlank();
        }
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

        Rect exactSelection = selectionScreenBounds(frame, selection);
        GoogleCircleContentSnapshot.Target target = content == null
                ? GoogleCircleContentSnapshot.Target.none(exactSelection)
                : content.resolve(frame, selection);

        DiagnosticLog.i(app, "G_CIRCLE_TARGET", "gesture=" + selection.kind
                + " target=" + target.kind
                + " targetAnchor=" + target.screenBounds.toShortString()
                + " exactSelection=" + exactSelection.toShortString()
                + " textChars=" + target.text.length()
                + " autoExpand=false");

        if (target.hasText()) {
            boolean tap = selection.kind == GoogleCircleSelection.Kind.TAP;
            boolean targetInsideExactSelection = containsRect(exactSelection, target.screenBounds);
            if (tap || targetInsideExactSelection) {
                Rect anchor = tap ? target.screenBounds : exactSelection;
                callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, anchor,
                        target.text, List.of(target.text), null, "accessibility_text_exact"));
                return;
            }
            // Do not promote a partially intersecting large text node to the whole node.
            DiagnosticLog.i(app, "G_CIRCLE_TARGET", "native text rejected because target exceeds exact selection");
        }

        if (target.kind == GoogleCircleContentSnapshot.Kind.IMAGE
                && selection.kind != GoogleCircleSelection.Kind.HIGHLIGHT) {
            Rect imageBounds = selection.kind == GoogleCircleSelection.Kind.TAP
                    ? target.screenBounds : exactSelection;
            resolveImage(app, frame, imageBounds, callback, "accessibility_image_exact");
            return;
        }

        Rect fallbackScreen = selection.kind == GoogleCircleSelection.Kind.TAP
                && target.kind == GoogleCircleContentSnapshot.Kind.IMAGE
                ? target.screenBounds : exactSelection;
        recognizeVisualText(app, frame, selection, fallbackScreen, callback);
    }

    private static void recognizeVisualText(Context app, GoogleCircleCapture.Frame frame,
                                             GoogleCircleSelection.Selection selection,
                                             Rect fallbackScreen,
                                             Callback callback) {
        Bitmap source = frame.bitmap;
        Rect roi = GoogleCircleSelection.exactRectAndClamp(selection.bounds,
                source.getWidth(), source.getHeight());
        if (roi.isEmpty()) {
            callback.onResolved(new Resolution(Kind.IMAGE, null, new Rect(), "", List.of(),
                    null, "empty_exact_selection"));
            return;
        }

        Rect screenAnchor = frame.bitmapRectToScreen(roi);
        if (roi.width() < OCR_MIN_SIDE_PX || roi.height() < OCR_MIN_SIDE_PX) {
            DiagnosticLog.i(app, "G_CIRCLE_ROI", "gesture=" + selection.kind
                    + " purpose=content_ocr skipped=too_small"
                    + " bitmap=" + roi.toShortString()
                    + " screen=" + screenAnchor.toShortString()
                    + " autoExpand=false");
            Rect anchor = fallbackScreen == null || fallbackScreen.isEmpty()
                    ? screenAnchor : fallbackScreen;
            callback.onResolved(new Resolution(Kind.IMAGE, null, anchor,
                    "", List.of(), null, "ocr_skipped_small_exact"));
            return;
        }

        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height());
        } catch (Throwable t) {
            callback.onResolved(new Resolution(Kind.IMAGE, null, screenAnchor,
                    "", List.of(), t, "crop_failed_exact"));
            return;
        }

        DiagnosticLog.i(app, "G_CIRCLE_ROI", "gesture=" + selection.kind
                + " purpose=content_ocr bitmap=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " screen=" + screenAnchor.toShortString()
                + " autoExpand=false");

        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                String text = resolveText(selection, roi, document);
                if (!text.isBlank()) {
                    List<String> blocks = selection.kind == GoogleCircleSelection.Kind.TAP
                            ? List.of(text) : document.blocks();
                    DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                            + " resolved=text chars=" + text.length()
                            + " engine=" + document.engine()
                            + " autoExpand=false");
                    recycle(crop);
                    callback.onResolved(new Resolution(Kind.OCR_TEXT, null, screenAnchor,
                            text, blocks, null, "ocr_text_exact"));
                    return;
                }

                DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                        + " resolved=image reason=no_text engine=" + document.engine()
                        + " autoExpand=false");
                Rect imageAnchor = fallbackScreen == null || fallbackScreen.isEmpty()
                        ? screenAnchor : fallbackScreen;
                if (!imageAnchor.equals(screenAnchor)) {
                    recycle(crop);
                    resolveImage(app, frame, imageAnchor, callback, "visual_no_text_exact");
                } else {
                    callback.onResolved(new Resolution(Kind.IMAGE, crop, screenAnchor,
                            "", List.of(), null, "visual_no_text_exact"));
                }
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                        + " failed error=" + ScreenCaptureBackend.safeMessage(error)
                        + " autoExpand=false");
                Rect imageAnchor = fallbackScreen == null || fallbackScreen.isEmpty()
                        ? screenAnchor : fallbackScreen;
                if (!imageAnchor.equals(screenAnchor)) {
                    recycle(crop);
                    resolveImage(app, frame, imageAnchor, callback, "ocr_failed_image_exact");
                } else {
                    callback.onResolved(new Resolution(Kind.IMAGE, crop, screenAnchor,
                            "", List.of(), error, "ocr_failed_image_exact"));
                }
            }
        });
    }

    private static void resolveImage(Context app, GoogleCircleCapture.Frame frame, Rect screenBounds,
                                     Callback callback, String sourceName) {
        Rect bitmapRect = screenToBitmapRect(frame, screenBounds);
        if (bitmapRect.isEmpty()) {
            callback.onResolved(new Resolution(Kind.IMAGE, null, new Rect(),
                    "", List.of(), null, sourceName + "_empty"));
            return;
        }

        try {
            Bitmap crop = Bitmap.createBitmap(frame.bitmap, bitmapRect.left, bitmapRect.top,
                    bitmapRect.width(), bitmapRect.height());
            Rect anchor = frame.bitmapRectToScreen(bitmapRect);
            DiagnosticLog.i(app, "G_CIRCLE_IMAGE", "source=" + sourceName
                    + " bitmap=" + bitmapRect.toShortString()
                    + " screen=" + anchor.toShortString()
                    + " autoExpand=false");
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
            Rect bounds = line.bounds();
            float dx = localX < bounds.left ? bounds.left - localX
                    : (localX > bounds.right ? localX - bounds.right : 0f);
            float dy = localY < bounds.top ? bounds.top - localY
                    : (localY > bounds.bottom ? localY - bounds.bottom : 0f);
            float distance = (float) Math.hypot(dx, dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = line;
            }
        }
        float acceptable = Math.max(1f, Math.min(roi.width(), roi.height()) * 0.30f);
        return best != null && bestDistance <= acceptable ? best.text() : "";
    }

    private static Rect selectionScreenBounds(GoogleCircleCapture.Frame frame,
                                              GoogleCircleSelection.Selection selection) {
        Rect roi = GoogleCircleSelection.exactRectAndClamp(selection.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight());
        return roi.isEmpty() ? new Rect() : frame.bitmapRectToScreen(roi);
    }

    private static Rect screenToBitmapRect(GoogleCircleCapture.Frame frame, Rect screen) {
        if (frame == null || frame.bitmap == null || frame.bitmap.isRecycled()
                || screen == null || screen.isEmpty()) {
            return new Rect();
        }

        Rect clipped = new Rect(screen);
        if (!clipped.intersect(frame.screenBounds)) return new Rect();

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

    private static boolean containsRect(Rect outer, Rect inner) {
        return outer != null && inner != null && !outer.isEmpty() && !inner.isEmpty()
                && outer.left <= inner.left && outer.top <= inner.top
                && outer.right >= inner.right && outer.bottom >= inner.bottom;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleResultCoordinator() {}
}
