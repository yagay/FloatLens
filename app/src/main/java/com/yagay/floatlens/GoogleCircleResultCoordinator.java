package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/**
 * Resolve Google-style selection gestures without enlarging user geometry.
 *
 * Gesture roles are fixed:
 * - CIRCLE: exact visual screenshot selection.
 * - TAP / SCRIBBLE / HIGHLIGHT: text selection. They never become screenshot results.
 *
 * Native Accessibility/View text is preferred. OCR may be used only as a text fallback for a
 * non-circle region that is already large enough; it never enlarges the user's selection.
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
            this.kind = kind == null ? Kind.NATIVE_TEXT : kind;
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
            callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, new Rect(), "", List.of(),
                    new IllegalStateException("source bitmap unavailable"), "invalid_frame"));
            return;
        }

        Rect exactSelection = selectionScreenBounds(frame, selection);
        DiagnosticLog.i(app, "G_CIRCLE_TARGET", "gesture=" + selection.kind
                + " exactSelection=" + exactSelection.toShortString()
                + " autoExpand=false");

        // Only a true closed CIRCLE gesture is allowed to create a screenshot result.
        if (selection.kind == GoogleCircleSelection.Kind.CIRCLE) {
            resolveImage(app, frame, exactSelection, callback, "circle_screenshot_exact");
            return;
        }

        GoogleCircleContentSnapshot.Target target = content == null
                ? GoogleCircleContentSnapshot.Target.none(exactSelection)
                : content.resolve(frame, selection);
        DiagnosticLog.i(app, "G_CIRCLE_TARGET", "gesture=" + selection.kind
                + " semanticTarget=" + target.kind
                + " targetAnchor=" + target.screenBounds.toShortString()
                + " textChars=" + target.text.length());

        if (target.hasText()) {
            boolean tap = selection.kind == GoogleCircleSelection.Kind.TAP;
            boolean accepted = tap || intersects(exactSelection, target.screenBounds);
            if (accepted) {
                Rect anchor = target.screenBounds == null || target.screenBounds.isEmpty()
                        ? exactSelection : target.screenBounds;
                callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, anchor,
                        target.text, List.of(target.text), null,
                        selection.kind.name().toLowerCase() + "_accessibility_text"));
                return;
            }
        }

        // TAP is a point selection. Never synthesize or enlarge an OCR region around it.
        if (selection.kind == GoogleCircleSelection.Kind.TAP) {
            callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, exactSelection,
                    "", List.of(), null, "tap_no_view_text"));
            return;
        }

        // SCRIBBLE/HIGHLIGHT may use OCR only as a text fallback, never as an image/screenshot path.
        recognizeTextFallback(app, frame, selection, exactSelection, callback);
    }

    private static void recognizeTextFallback(Context app, GoogleCircleCapture.Frame frame,
                                              GoogleCircleSelection.Selection selection,
                                              Rect exactSelection,
                                              Callback callback) {
        Bitmap source = frame.bitmap;
        Rect roi = GoogleCircleSelection.exactRectAndClamp(selection.bounds,
                source.getWidth(), source.getHeight());
        if (roi.isEmpty()) {
            callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, exactSelection, "", List.of(),
                    null, "empty_exact_text_selection"));
            return;
        }

        Rect screenAnchor = frame.bitmapRectToScreen(roi);
        if (roi.width() < OCR_MIN_SIDE_PX || roi.height() < OCR_MIN_SIDE_PX) {
            DiagnosticLog.i(app, "G_CIRCLE_ROI", "gesture=" + selection.kind
                    + " purpose=text_ocr skipped=too_small"
                    + " bitmap=" + roi.toShortString()
                    + " autoExpand=false");
            callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, screenAnchor,
                    "", List.of(), null, "text_ocr_skipped_small_exact"));
            return;
        }

        final Bitmap crop;
        try {
            crop = Bitmap.createBitmap(source, roi.left, roi.top, roi.width(), roi.height());
        } catch (Throwable t) {
            callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, screenAnchor,
                    "", List.of(), t, "text_crop_failed_exact"));
            return;
        }

        DiagnosticLog.i(app, "G_CIRCLE_ROI", "gesture=" + selection.kind
                + " purpose=text_ocr bitmap=" + roi.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                + " autoExpand=false");

        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                String text = document == null ? "" : document.fullText();
                recycle(crop);
                if (!text.isBlank()) {
                    Rect textAnchor = ocrTextAnchor(frame, roi, document, screenAnchor);
                    DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                            + " resolved=text chars=" + text.length()
                            + " anchor=" + textAnchor.toShortString()
                            + " autoExpand=false");
                    callback.onResolved(new Resolution(Kind.OCR_TEXT, null, textAnchor,
                            text, document.blocks(), null,
                            selection.kind.name().toLowerCase() + "_ocr_text_exact"));
                } else {
                    DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                            + " resolved=no_text screenshotFallback=false autoExpand=false");
                    callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, screenAnchor,
                            "", List.of(), null,
                            selection.kind.name().toLowerCase() + "_no_text"));
                }
            }

            @Override public void onFailure(Throwable error) {
                recycle(crop);
                DiagnosticLog.i(app, "G_CIRCLE_OCR", "gesture=" + selection.kind
                        + " failed=" + ScreenCaptureBackend.safeMessage(error)
                        + " screenshotFallback=false autoExpand=false");
                callback.onResolved(new Resolution(Kind.NATIVE_TEXT, null, screenAnchor,
                        "", List.of(), error,
                        selection.kind.name().toLowerCase() + "_ocr_failed"));
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

    private static boolean intersects(Rect a, Rect b) {
        return a != null && b != null && !a.isEmpty() && !b.isEmpty()
                && Rect.intersects(a, b);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private GoogleCircleResultCoordinator() {}
}
