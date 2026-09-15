package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/**
 * Resolve Google-style selection gestures without enlarging user geometry.
 *
 * Gesture roles are intentionally different:
 * - CIRCLE / SCRIBBLE: exact visual screenshot selection. Accessibility text must never steal it.
 * - HIGHLIGHT: exact-region text selection, then OCR, then exact screenshot fallback.
 * - TAP: semantic point selection (native text/image) without synthesizing an OCR rectangle.
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
        DiagnosticLog.i(app, "G_CIRCLE_TARGET", "gesture=" + selection.kind
                + " exactSelection=" + exactSelection.toShortString()
                + " autoExpand=false");

        // Circle and scribble are visual selections. Never let an Accessibility TextView inside the
        // region turn the user's screenshot selection into a text result.
        if (selection.kind == GoogleCircleSelection.Kind.CIRCLE
                || selection.kind == GoogleCircleSelection.Kind.SCRIBBLE) {
            resolveImage(app, frame, exactSelection, callback,
                    selection.kind == GoogleCircleSelection.Kind.CIRCLE
                            ? "circle_screenshot_exact" : "scribble_screenshot_exact");
            return;
        }

        GoogleCircleContentSnapshot.Target target = content == null
                ? GoogleCircleContentSnapshot.Target.none(exactSelection)
                : content.resolve(frame, selection);
        DiagnosticLog.i(app, "G_CIRCLE_TARGET", "gesture=" + selection.kind
                + " semanticTarget=" + target.kind
                + " targetAnchor=" + target.screenBounds.toShortString()
                + " textChars=" + target.text.length());

        if (selection.kind == GoogleCircleSelection.Kind.TAP) {
            if (target.hasText()) {
                callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, target.screenBounds,
                        target.text, List.of(target.text), null, "tap_accessibility_text"));
                return;
            }
            if (target.kind == GoogleCircleContentSnapshot.Kind.IMAGE
                    && target.screenBounds != null && !target.screenBounds.isEmpty()) {
                resolveImage(app, frame, target.screenBounds, callback, "tap_accessibility_image");
                return;
            }
            callback.onResolved(new Resolution(Kind.IMAGE, null, exactSelection,
                    "", List.of(), null, "tap_no_semantic_target"));
            return;
        }

        // HIGHLIGHT is text-biased. Native text is accepted only when the whole text node fits
        // inside the exact highlight. Otherwise OCR runs strictly on the exact user region.
        if (target.hasText() && containsRect(exactSelection, target.screenBounds)) {
            callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, target.screenBounds,
                    target.text, List.of(target.text), null, "highlight_accessibility_text"));
            return;
        }

        recognizeHighlightText(app, frame, selection, exactSelection, callback);
    }

    private static void recognizeHighlightText(Context app, GoogleCircleCapture.Frame frame,
                                               GoogleCircleSelection.Selection selection,
                                               Rect exactSelection,
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
                    + " purpose=highlight_ocr skipped=too_small"
                    + " bitmap=" + roi.toShortString()
                    + " autoExpand=false");
            resolveImage(app, frame, exactSelection, callback, "highlight_small_screenshot_exact");
            return;
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height());
        } catch (Throwable t) {
            callback.onResolved(new Resolution(Kind.IMAGE, null, screenAnchor,
                    "", List.of(), t, "highlight_crop_failed_exact"));
            return;
        }

        DiagnosticLog.i(app, "G_CIRCLE_ROI", "gesture=" + selection.kind
                + " purpose=highlight_ocr bitmap=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " autoExpand=false");

        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                String text = document == null ? "" : document.fullText();
                if (!text.isBlank()) {
                    Rect textAnchor = ocrTextAnchor(frame, roi, document, screenAnchor);
                    List<String> blocks = document.blocks();
                    DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=HIGHLIGHT resolved=text"
                            + " chars=" + text.length()
                            + " anchor=" + textAnchor.toShortString()
                            + " autoExpand=false");
                    recycle(crop);
                    callback.onResolved(new Resolution(Kind.OCR_TEXT, null, textAnchor,
                            text, blocks, null, "highlight_ocr_text_exact"));
                    return;
                }

                DiagnosticLog.i(app, "G_CIRCLE_OCR",
                        "gesture=HIGHLIGHT resolved=screenshot reason=no_text autoExpand=false");
                callback.onResolved(new Resolution(Kind.IMAGE, crop, screenAnchor,
                        "", List.of(), null, "highlight_no_text_screenshot_exact"));
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=HIGHLIGHT failed="
                        + ScreenCaptureBackend.safeMessage(error)
                        + " fallback=screenshot autoExpand=false");
                callback.onResolved(new Resolution(Kind.IMAGE, crop, screenAnchor,
                        "", List.of(), error, "highlight_ocr_failed_screenshot_exact"));
            }
        });
    }

    private static Rect ocrTextAnchor(GoogleCircleCapture.Frame frame, Rect roi,
                                      OcrDocument document, Rect fallback) {
        if (document == null || document.lines().isEmpty()) return new Rect(fallback);
        Rect union = new Rect();
        boolean has = false;
        for (OcrDocument.Line line : document.lines()) {
            Rect b = line.bounds();
            if (b == null || b.isEmpty()) continue;
            Rect bitmapLine = new Rect(
                    roi.left + b.left,
                    roi.top + b.top,
                    roi.left + b.right,
                    roi.top + b.bottom);
            if (!has) {
                union.set(bitmapLine);
                has = true;
            } else {
                union.union(bitmapLine);
            }
        }
        if (!has) return new Rect(fallback);
        union.intersect(new Rect(0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight()));
        return union.isEmpty() ? new Rect(fallback) : frame.bitmapRectToScreen(union);
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
