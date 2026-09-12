package com.yagay.floatlens;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/** Business routing for screenshot, region, View and OCR capture operations. */
public final class ScreenshotController {
    public static void capture(Context c, boolean region) {
        Context app = c.getApplicationContext();
        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, region ? "screenshot_region" : "screenshot_full");
        getBitmap(app, b -> {
            try {
                if (region) RegionOverlay.show(app, b, false);
                else save(app, b);
                FlSystemPanelController.onResultReady(
                        app, shadeState, region ? "region_overlay_shown" : "screenshot_saved");
                DiagnosticLog.i(app, "SCREENSHOT_FLOW", "full result region=" + region
                        + " bitmap=" + bitmapSize(b));
            } catch (Throwable t) {
                DiagnosticLog.i(app, "SCREENSHOT_FLOW", "full result failed region=" + region
                        + " error=" + ScreenCaptureBackend.safeMessage(t));
                Toast.makeText(app, region ? "区域选择器启动失败" : "截图结果处理失败",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    public static void captureForOcr(Context c) {
        Context app = c.getApplicationContext();
        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "ocr_region_capture");
        getBitmap(app, b -> {
            try {
                RegionOverlay.show(app, b, true);
                FlSystemPanelController.onResultReady(app, shadeState, "ocr_region_overlay_shown");
            } catch (Throwable t) {
                DiagnosticLog.i(app, "SCREENSHOT_FLOW", "ocr region result failed error="
                        + ScreenCaptureBackend.safeMessage(t));
                Toast.makeText(app, "OCR 区域选择器启动失败", Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Raw Circle Select frame. CircleSelectController owns icon hiding/restoration itself. */
    public static void captureRawFrame(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        ScreenCaptureBackend.capture(app, new FloatSettings(app), ok, fail);
    }

    public static void captureForRegionEditor(Context c) {
        Context app = c.getApplicationContext();
        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "editable_region_capture");
        FloatSettings fs = new FloatSettings(app);
        ScreenshotCaptureSession.capture(app, fs, raw -> {
            if (raw == null || raw.isRecycled()) {
                DiagnosticLog.i(app, "SCREENSHOT_CAPTURE", "region editor invalid bitmap");
                Toast.makeText(app, "区域截图失败: 截图无效", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                DiagnosticLog.i(app, "REGION_EDIT", "open screenshot=" + bitmapSize(raw));
                EditableRegionOverlay.show(app, raw);
                FlSystemPanelController.onResultReady(app, shadeState, "editable_region_overlay_shown");
            } catch (Throwable t) {
                DiagnosticLog.i(app, "REGION_EDIT", "open failed=" + ScreenCaptureBackend.safeMessage(t));
                Toast.makeText(app, "区域编辑器启动失败", Toast.LENGTH_LONG).show();
            }
        }, t -> {
            DiagnosticLog.i(app, "SCREENSHOT_CAPTURE", "region editor backend failed="
                    + ScreenCaptureBackend.safeMessage(t));
            Toast.makeText(app,
                    "区域截图失败: " + ScreenCaptureBackend.safeMessage(t), Toast.LENGTH_LONG).show();
        });
    }

    public static void captureBoundsForOcr(Context c, Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) {
            captureForOcr(c);
            return;
        }
        Context app = c.getApplicationContext();
        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "view_ocr_capture");
        Rect anchor = new Rect(screenBounds);
        captureBounds(app, anchor, crop -> {
            OcrEngine.recognize(app, crop, anchor);
            FlSystemPanelController.onResultReady(app, shadeState, "view_ocr_capture_ready");
        }, "View OCR", true);
    }

    public static void captureBoundsForRegion(Context c, Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "fv_region_capture");
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            DiagnosticLog.i(app, "FL_REGION_CAPTURE", "crop=" + bitmapSize(crop)
                    + " bounds=" + bounds);
            boolean shown = ResultSurfaceRouter.showCapturedScreenshot(app, crop, bounds, shadeState);
            DiagnosticLog.i(app, "FL_REGION_CAPTURE", "result shown=" + shown);
            if (!shown) {
                DiagnosticLog.i(app, "FL_REGION_CAPTURE",
                        "all result surfaces failed; save image as final fallback");
                save(app, crop);
                FlSystemPanelController.onResultReady(app, shadeState, "fl_region_saved_fallback");
            }
        }, "区域截图", false);
    }

    public static void captureBoundsForViewCandidate(Context c, Rect screenBounds,
                                                     ViewNodeCandidate candidate, String directText) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "view_candidate_capture");
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            String text = directText == null ? "" : directText.trim();
            DiagnosticLog.i(app, "VIEW_CAPTURE", "crop=" + bitmapSize(crop)
                    + " bounds=" + bounds + " textLen=" + text.length()
                    + " kind=" + (candidate == null ? "view" : candidate.kind()));

            boolean shown;
            if (!text.isEmpty()) {
                DiagnosticLog.i(app, "VIEW_EXTRACT", "direct Accessibility text chars=" + text.length()
                        + " bounds=" + bounds);
                shown = ResultSurfaceRouter.showCapturedViewText(app, text, crop, bounds, shadeState);
            } else {
                DiagnosticLog.i(app, "VIEW_SCREENSHOT",
                        "no Accessibility text; show cropped View bounds=" + bounds);
                shown = ResultSurfaceRouter.showCapturedViewImage(app, crop, candidate, bounds, shadeState);
            }
            DiagnosticLog.i(app, "VIEW_CAPTURE", "result shown=" + shown);
            if (!shown) {
                DiagnosticLog.i(app, "VIEW_CAPTURE", "all result surfaces failed");
                FlSystemPanelController.onResultReady(app, shadeState, "view_result_failed");
            }
        }, "View 截图", false);
    }

    public static void captureBoundsForVisualCandidate(Context c, Rect screenBounds,
                                                       ViewNodeCandidate candidate) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        final FlSystemPanelController.CaptureState shadeState = FlSystemPanelController.beginCapture(
                app, "visual_candidate_capture");
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            DiagnosticLog.i(app, "VIEW_SCREENSHOT", "visual candidate crop="
                    + bitmapSize(crop) + " bounds=" + bounds);
            boolean shown = ResultSurfaceRouter.showCapturedViewImage(app, crop, candidate, bounds, shadeState);
            DiagnosticLog.i(app, "VIEW_CAPTURE", "visual result shown=" + shown);
            if (!shown) {
                DiagnosticLog.i(app, "VIEW_CAPTURE", "visual result surfaces failed");
                FlSystemPanelController.onResultReady(app, shadeState, "visual_result_failed");
            }
        }, "View 截图", false);
    }

    /**
     * Capture, crop and deliver are three separate failure domains. Never report a result-window
     * exception as "screenshot failed" again: the log must say exactly which stage failed.
     */
    private static void captureBounds(Context c, Rect screenBounds, Consumer<Bitmap> onCrop,
                                      String label, boolean fallbackToFreeOcr) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        ScreenshotCaptureSession.capture(app, fs, raw -> {
            DiagnosticLog.i(app, "SCREENSHOT_CAPTURE", "backend success label=" + label
                    + " raw=" + bitmapSize(raw) + " requested=" + screenBounds);

            final Bitmap crop;
            try {
                crop = ScreenshotGeometry.cropScreenBounds(app, raw, screenBounds);
                DiagnosticLog.i(app, "SCREENSHOT_CROP", "success label=" + label
                        + " raw=" + bitmapSize(raw) + " requested=" + screenBounds
                        + " crop=" + bitmapSize(crop));
            } catch (Throwable t) {
                DiagnosticLog.i(app, "SCREENSHOT_CROP", "failed label=" + label
                        + " raw=" + bitmapSize(raw) + " requested=" + screenBounds
                        + " error=" + ScreenCaptureBackend.safeMessage(t));
                Toast.makeText(app, label + "失败: 裁剪失败", Toast.LENGTH_SHORT).show();
                if (fallbackToFreeOcr) captureForOcr(app);
                return;
            }

            try {
                onCrop.accept(crop);
            } catch (Throwable t) {
                DiagnosticLog.i(app, "SCREENSHOT_RESULT", "failed label=" + label
                        + " crop=" + bitmapSize(crop)
                        + " error=" + ScreenCaptureBackend.safeMessage(t));
                Toast.makeText(app, label + "结果处理失败", Toast.LENGTH_LONG).show();
            }
        }, t -> {
            DiagnosticLog.i(app, "SCREENSHOT_CAPTURE", "backend failed label=" + label
                    + " error=" + ScreenCaptureBackend.safeMessage(t));
            Toast.makeText(app,
                    "截图失败: " + ScreenCaptureBackend.safeMessage(t), Toast.LENGTH_LONG).show();
        });
    }

    private static void getBitmap(Context c, Consumer<Bitmap> ok) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        ScreenshotCaptureSession.capture(app, fs, raw -> {
            DiagnosticLog.i(app, "SCREENSHOT_CAPTURE", "full backend success raw=" + bitmapSize(raw));
            try {
                Bitmap out = ScreenshotGeometry.maybeCropStatusBar(
                        app, raw, fs.keepStatusBarInScreenshot());
                DiagnosticLog.i(app, "SCREENSHOT_CROP", "full statusBar keep="
                        + fs.keepStatusBarInScreenshot() + " output=" + bitmapSize(out));
                ok.accept(out);
            } catch (Throwable t) {
                DiagnosticLog.i(app, "SCREENSHOT_CROP", "full failed error="
                        + ScreenCaptureBackend.safeMessage(t));
                Toast.makeText(app, "截图处理失败", Toast.LENGTH_LONG).show();
            }
        }, t -> {
            DiagnosticLog.i(app, "SCREENSHOT_CAPTURE", "full backend failed error="
                    + ScreenCaptureBackend.safeMessage(t));
            Toast.makeText(app,
                    "截图失败: " + ScreenCaptureBackend.safeMessage(t), Toast.LENGTH_LONG).show();
        });
    }

    private static String bitmapSize(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
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
            Toast.makeText(c,
                    "保存失败: " + ScreenCaptureBackend.safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private ScreenshotController() {}
}
