package com.yagay.floatlens;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

public final class ScreenshotController {
    public static void capture(Context c, boolean region) {
        Context app = c.getApplicationContext();
        final FvSystemPanelController.CaptureState shadeState = FvSystemPanelController.beginCapture(
                app, region ? "screenshot_region" : "screenshot_full");
        getBitmap(app, b -> {
            if (region) RegionOverlay.show(app, b, false);
            else save(app, b);
            FvSystemPanelController.onResultReady(
                    app, shadeState, region ? "region_overlay_shown" : "screenshot_saved");
        });
    }

    public static void captureForOcr(Context c) {
        Context app = c.getApplicationContext();
        final FvSystemPanelController.CaptureState shadeState = FvSystemPanelController.beginCapture(
                app, "ocr_region_capture");
        getBitmap(app, b -> {
            RegionOverlay.show(app, b, true);
            FvSystemPanelController.onResultReady(
                    app, shadeState, "ocr_region_overlay_shown");
        });
    }

    /** Raw full-screen capture for Circle Select. Caller owns visibility and Bitmap lifetime. */
    public static void captureRawFrame(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        captureNow(app, fs, ok, fail);
    }

    /** Capture an uncropped full-screen frame and open the adjustable rectangular region editor. */
    public static void captureForRegionEditor(Context c) {
        Context app = c.getApplicationContext();
        final FvSystemPanelController.CaptureState shadeState = FvSystemPanelController.beginCapture(
                app, "editable_region_capture");
        FloatSettings fs = new FloatSettings(app);
        FloatService service = FloatService.get();
        boolean hideIcon = !fs.keepInScreenshot() && service != null;
        if (hideIcon) service.setScreenshotHidden(true);
        new Handler(Looper.getMainLooper()).postDelayed(() -> captureNow(app, fs, raw -> {
            restoreIcon(service, hideIcon);
            if (raw == null || raw.isRecycled()) {
                Toast.makeText(app, "区域截图失败: 截图无效", Toast.LENGTH_LONG).show();
                return;
            }
            DiagnosticLog.i(app, "REGION_EDIT", "open screenshot=" + raw.getWidth() + "x" + raw.getHeight());
            EditableRegionOverlay.show(app, raw);
            FvSystemPanelController.onResultReady(
                    app, shadeState, "editable_region_overlay_shown");
        }, t -> {
            restoreIcon(service, hideIcon);
            Toast.makeText(app, "区域截图失败: " + safeMessage(t), Toast.LENGTH_LONG).show();
        }), hideIcon ? 100L : 0L);
    }

    /** Explicit OCR path only. */
    public static void captureBoundsForOcr(Context c, Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) {
            captureForOcr(c);
            return;
        }
        Context app = c.getApplicationContext();
        final FvSystemPanelController.CaptureState shadeState = FvSystemPanelController.beginCapture(
                app, "view_ocr_capture");
        Rect anchor = new Rect(screenBounds);
        captureBounds(app, anchor, crop -> {
                    OcrEngine.recognize(app, crop, anchor);
                    FvSystemPanelController.onResultReady(
                            app, shadeState, "view_ocr_capture_ready");
                },
                "View OCR 失败，改用自由圈选", true);
    }

    /** FV screenshot operation. This path never invokes OCR automatically. */
    public static void captureBoundsForRegion(Context c, Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        final FvSystemPanelController.CaptureState shadeState = FvSystemPanelController.beginCapture(
                app, "fv_region_capture");
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            DiagnosticLog.i(app, "FV_REGION_CAPTURE", "crop=" + crop.getWidth() + "x" + crop.getHeight()
                    + " bounds=" + bounds);

            // Primary path now matches Circle Select: the frozen result is attached immediately via
            // FvOverlayWindowHost (TYPE_ACCESSIBILITY_OVERLAY when accessibility is available), then
            // the real SystemUI shade is cleaned underneath without blocking the visible result.
            boolean overlayShown = ScreenshotResultOverlay.show(app, crop, bounds);
            DiagnosticLog.i(app, "FV_REGION_CAPTURE", "result overlay shown=" + overlayShown);
            if (overlayShown) {
                OverlayShadeCoordinator.cleanup(app, shadeState.expandedAtCapture(),
                        "screenshot_result", collapsed ->
                                DiagnosticLog.i(app, "SCREENSHOT_RESULT",
                                        "background shade cleanup collapsed=" + collapsed));
                return;
            }

            // Safe fallback: keep the previous Activity result path if both overlay hosts fail.
            ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                    app, shadeState, "fv_region_result_fallback");
            if (!ScreenshotResultActivity.show(app, crop, bounds)) {
                ResultReadyCoordinator.cancel(ticket, app, "result_activity_start_failed");
                DiagnosticLog.i(app, "FV_REGION_CAPTURE",
                        "all result surfaces failed; save image as final fallback");
                save(app, crop);
                FvSystemPanelController.onResultReady(
                        app, shadeState, "fv_region_saved_fallback");
            }
        }, "区域截图失败", false);
    }

    /**
     * FV text/View operation. Accessibility text is extracted directly and shown as View content.
     * The captured image stays available so the user may explicitly run OCR from the result popup.
     */
    public static void captureBoundsForViewCandidate(Context c, Rect screenBounds,
                                                     ViewNodeCandidate candidate, String directText) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        final FvSystemPanelController.CaptureState shadeState = FvSystemPanelController.beginCapture(
                app, "view_candidate_capture");
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            String text = directText == null ? "" : directText.trim();
            DiagnosticLog.i(app, "VIEW_CAPTURE", "crop=" + crop.getWidth() + "x" + crop.getHeight()
                    + " bounds=" + bounds + " textLen=" + text.length()
                    + " kind=" + (candidate == null ? "view" : candidate.kind()));

            ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                    app, shadeState, "view_result_shown");
            boolean activityShown;
            if (!text.isEmpty()) {
                DiagnosticLog.i(app, "VIEW_EXTRACT", "direct Accessibility text chars=" + text.length()
                        + " bounds=" + bounds);
                activityShown = ViewContentActivity.show(app, text, crop, bounds);
                if (!activityShown) {
                    ResultOverlay.show(app, text, java.util.List.of(text), crop, bounds);
                }
            } else {
                DiagnosticLog.i(app, "VIEW_SCREENSHOT", "no Accessibility text; show cropped View bounds=" + bounds);
                activityShown = ViewImageResultActivity.show(app, crop, candidate, bounds);
                if (!activityShown) {
                    ResultOverlay.showVisual(app, crop, candidate, bounds);
                }
            }

            if (!activityShown) {
                ResultReadyCoordinator.cancel(ticket, app, "result_activity_start_failed");
                FvSystemPanelController.onResultReady(
                        app, shadeState, "view_overlay_shown");
            }
        }, "View 截图失败", false);
    }

    /** FV image/View operation. OCR is available only as an explicit result-window button. */
    public static void captureBoundsForVisualCandidate(Context c, Rect screenBounds, ViewNodeCandidate candidate) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        final FvSystemPanelController.CaptureState shadeState = FvSystemPanelController.beginCapture(
                app, "visual_candidate_capture");
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            DiagnosticLog.i(app, "VIEW_SCREENSHOT", "visual candidate crop="
                    + crop.getWidth() + "x" + crop.getHeight() + " bounds=" + bounds);

            ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(
                    app, shadeState, "visual_result_shown");
            if (!ViewImageResultActivity.show(app, crop, candidate, bounds)) {
                ResultReadyCoordinator.cancel(ticket, app, "result_activity_start_failed");
                ResultOverlay.showVisual(app, crop, candidate, bounds);
                FvSystemPanelController.onResultReady(
                        app, shadeState, "visual_overlay_shown");
            }
        }, "View 截图失败", false);
    }

    private static void captureBounds(Context c, Rect screenBounds, Consumer<Bitmap> onCrop,
                                      String failText, boolean fallbackToFreeOcr) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        FloatService service = FloatService.get();
        boolean hideIcon = !fs.keepInScreenshot() && service != null;
        if (hideIcon) service.setScreenshotHidden(true);

        // Selection/probe/hint teardown is owned by ViewSelectionEngine, matching FV m2/g.
        // This helper has no region-specific settle delay of its own.
        new Handler(Looper.getMainLooper()).postDelayed(() -> captureNow(app, fs, raw -> {
            try {
                Bitmap crop = cropToScreenBounds(app, raw, screenBounds);
                restoreIcon(service, hideIcon);
                onCrop.accept(crop);
            } catch (Throwable t) {
                restoreIcon(service, hideIcon);
                Toast.makeText(app, failText, Toast.LENGTH_SHORT).show();
                if (fallbackToFreeOcr) captureForOcr(app);
            }
        }, t -> {
            restoreIcon(service, hideIcon);
            Toast.makeText(app, "截图失败: " + safeMessage(t), Toast.LENGTH_LONG).show();
        }), hideIcon ? 100L : 0L);
    }

    private static Bitmap cropToScreenBounds(Context app, Bitmap raw, Rect screenBounds) {
        if (raw == null || screenBounds == null || screenBounds.isEmpty()) {
            throw new IllegalArgumentException("invalid crop");
        }
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        Rect display = wm.getCurrentWindowMetrics().getBounds();
        float sx = raw.getWidth() / (float) Math.max(1, display.width());
        float sy = raw.getHeight() / (float) Math.max(1, display.height());
        int left = Math.max(0, Math.min(raw.getWidth() - 1,
                Math.round((screenBounds.left - display.left) * sx)));
        int top = Math.max(0, Math.min(raw.getHeight() - 1,
                Math.round((screenBounds.top - display.top) * sy)));
        int right = Math.max(left + 1, Math.min(raw.getWidth(),
                Math.round((screenBounds.right - display.left) * sx)));
        int bottom = Math.max(top + 1, Math.min(raw.getHeight(),
                Math.round((screenBounds.bottom - display.top) * sy)));
        return Bitmap.createBitmap(raw, left, top, right - left, bottom - top);
    }

    private static void getBitmap(Context c, Consumer<Bitmap> ok) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        FloatService service = FloatService.get();
        boolean hideIcon = !fs.keepInScreenshot() && service != null;
        if (hideIcon) service.setScreenshotHidden(true);

        new Handler(Looper.getMainLooper()).postDelayed(() -> captureNow(app, fs, raw -> {
            Bitmap b = maybeCropSystemBars(app, raw, fs);
            restoreIcon(service, hideIcon);
            ok.accept(b);
        }, t -> {
            restoreIcon(service, hideIcon);
            Toast.makeText(app, "截图失败: " + safeMessage(t), Toast.LENGTH_LONG).show();
        }), hideIcon ? 100L : 0L);
    }

    private static void restoreIcon(FloatService service, boolean hidden) {
        if (hidden && service != null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> service.setScreenshotHidden(false), 80L);
        }
    }

    private static void captureNow(Context c, FloatSettings fs, Consumer<Bitmap> ok,
                                   Consumer<Throwable> fail) {
        if (fs.accessibilityScreenshot()) {
            captureAccessibility(c, ok, accessError -> {
                if (fs.rootScreenshot()) {
                    RootCapture.captureAsync(c, ok, rootError -> fail.accept(combined(accessError, rootError)));
                } else {
                    fail.accept(accessError);
                }
            });
            return;
        }
        if (fs.rootScreenshot()) {
            RootCapture.captureAsync(c, ok, fail);
            return;
        }
        captureAccessibility(c, ok, fail);
    }

    private static IllegalStateException combined(Throwable a, Throwable b) {
        return new IllegalStateException("Accessibility 与 Root 截图均失败；Accessibility="
                + safeMessage(a) + "，Root=" + safeMessage(b));
    }

    private static void captureAccessibility(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        LensAccessibilityService s = LensAccessibilityService.get();
        if (s == null) {
            fail.accept(new IllegalStateException("需要开启 FloatLens 无障碍服务，或启用 Root 截图"));
            return;
        }
        s.capture(ok, fail);
    }

    private static Bitmap maybeCropSystemBars(Context c, Bitmap b, FloatSettings fs) {
        if (b == null || fs.keepStatusBarInScreenshot()) return b;
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            var metrics = wm.getCurrentWindowMetrics();
            var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars());
            int screenH = metrics.getBounds().height();
            int topPx = insets.top;
            if (topPx <= 0 || screenH <= 0) return b;
            float sy = b.getHeight() / (float) screenH;
            int cropTop = Math.max(0, Math.min(b.getHeight() - 1, Math.round(topPx * sy)));
            return Bitmap.createBitmap(b, 0, cropTop, b.getWidth(), b.getHeight() - cropTop);
        } catch (Throwable ignored) {
            return b;
        }
    }

    static void save(Context c, Bitmap b) {
        if (b == null || b.isRecycled()) {
            Toast.makeText(c, "截图无效", Toast.LENGTH_LONG).show();
            return;
        }
        String name = "FloatLens_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".png";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FloatLens");
        var uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            Toast.makeText(c, "保存失败", Toast.LENGTH_LONG).show();
            return;
        }
        try (OutputStream o = c.getContentResolver().openOutputStream(uri)) {
            if (o == null || !b.compress(Bitmap.CompressFormat.PNG, 100, o)) {
                throw new IllegalStateException("PNG 写入失败");
            }
            Toast.makeText(c, "已保存到 Pictures/FloatLens", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            c.getContentResolver().delete(uri, null, null);
            Toast.makeText(c, "保存失败: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private ScreenshotController() {}
}
