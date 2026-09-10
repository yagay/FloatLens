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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class ScreenshotController {
    public static void capture(Context c, boolean region) {
        getBitmap(c, b -> { if (region) RegionOverlay.show(c, b, false); else save(c, b); });
    }

    public static void captureForOcr(Context c) {
        getBitmap(c, b -> RegionOverlay.show(c, b, true));
    }

    /** Capture an uncropped full-screen frame and open the adjustable rectangular region editor. */
    public static void captureForRegionEditor(Context c) {
        Context app = c.getApplicationContext();
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
        }, t -> {
            restoreIcon(service, hideIcon);
            Toast.makeText(app, "区域截图失败: " + safeMessage(t), Toast.LENGTH_LONG).show();
        }), hideIcon ? 100L : 0L);
    }

    /** Explicit OCR path: OCR only the selected Accessibility View rectangle. */
    public static void captureBoundsForOcr(Context c, Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) {
            captureForOcr(c);
            return;
        }
        Rect anchor = new Rect(screenBounds);
        captureBounds(c, anchor, crop -> {
            FloatService f = FloatService.get();
            if (f != null) f.onCircleRecognizeStarted();
            OcrEngine.recognize(c.getApplicationContext(), crop, anchor);
        }, "View OCR 失败，改用自由圈选", true);
    }

    /**
     * FV Direct region selection is a screenshot path, not an implicit OCR path.
     * OCR remains available through the explicit OCR action.
     */
    public static void captureBoundsForRegion(Context c, Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            DiagnosticLog.i(app, "FV_REGION_CAPTURE", "crop=" + crop.getWidth() + "x" + crop.getHeight()
                    + " bounds=" + bounds);
            ResultOverlay.showVisual(app, crop, null, bounds);
        }, "区域截图失败", false);
    }

    /**
     * FV Direct View extraction keeps Accessibility text as View content. If the selected View has
     * no extractable text, show its exact cropped bitmap as a screenshot instead of silently OCRing
     * it. This keeps View extraction, screenshot and OCR as three separate capabilities.
     */
    public static void captureBoundsForViewCandidate(Context c, Rect screenBounds,
                                                     ViewNodeCandidate candidate, String directText) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            String text = directText == null ? "" : directText.trim();
            DiagnosticLog.i(app, "VIEW_CAPTURE", "crop=" + crop.getWidth() + "x" + crop.getHeight()
                    + " bounds=" + bounds + " textLen=" + text.length()
                    + " kind=" + (candidate == null ? "view" : candidate.kind()));
            if (!text.isEmpty()) {
                DiagnosticLog.i(app, "VIEW_EXTRACT", "direct Accessibility text chars=" + text.length()
                        + " bounds=" + bounds);
                ResultOverlay.show(app, text, List.of(text), crop, bounds);
            } else {
                DiagnosticLog.i(app, "VIEW_SCREENSHOT", "no Accessibility text; show cropped View bounds=" + bounds);
                ResultOverlay.showVisual(app, crop, candidate, bounds);
            }
        }, "View 截图失败", false);
    }

    /** Visual View candidates are shown as screenshots; they no longer fall through to OCR. */
    public static void captureBoundsForVisualCandidate(Context c, Rect screenBounds, ViewNodeCandidate candidate) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        Context app = c.getApplicationContext();
        Rect bounds = new Rect(screenBounds);
        captureBounds(app, bounds, crop -> {
            DiagnosticLog.i(app, "VIEW_SCREENSHOT", "visual candidate crop="
                    + crop.getWidth() + "x" + crop.getHeight() + " bounds=" + bounds);
            ResultOverlay.showVisual(app, crop, candidate, bounds);
        }, "View 截图失败", false);
    }

    private static void captureBounds(Context c, Rect screenBounds, Consumer<Bitmap> onCrop,
                                      String failText, boolean fallbackToFreeOcr) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        FloatService service = FloatService.get();
        boolean hideIcon = !fs.keepInScreenshot() && service != null;
        if (hideIcon) service.setScreenshotHidden(true);
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
