package com.yagay.floatlens;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
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
        getBitmap(c, b -> { if (region) RegionOverlay.show(c, b, false); else save(c, b); });
    }
    public static void captureForOcr(Context c) { getBitmap(c, b -> RegionOverlay.show(c, b, true)); }

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
        if (hidden && service != null) new Handler(Looper.getMainLooper()).postDelayed(() -> service.setScreenshotHidden(false), 80L);
    }

    private static void captureNow(Context c, FloatSettings fs, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        if (fs.accessibilityScreenshot()) {
            captureAccessibility(c, ok, accessError -> {
                if (fs.rootScreenshot()) RootCapture.captureAsync(c, ok, rootError -> fail.accept(combined(accessError, rootError)));
                else fail.accept(accessError);
            });
            return;
        }
        if (fs.rootScreenshot()) { RootCapture.captureAsync(c, ok, fail); return; }
        captureAccessibility(c, ok, fail);
    }

    private static IllegalStateException combined(Throwable a, Throwable b) {
        return new IllegalStateException("Accessibility 与 Root 截图均失败；Accessibility=" + safeMessage(a) + "，Root=" + safeMessage(b));
    }

    private static void captureAccessibility(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        LensAccessibilityService s = LensAccessibilityService.get();
        if (s == null) { fail.accept(new IllegalStateException("需要开启 FloatLens 无障碍服务，或启用 Root 截图")); return; }
        s.capture(ok, fail);
    }

    private static Bitmap maybeCropSystemBars(Context c, Bitmap b, FloatSettings fs) {
        if (b == null || fs.keepStatusBarInScreenshot()) return b;
        try {
            WindowManager wm = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
            var metrics = wm.getCurrentWindowMetrics();
            var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars());
            int screenH = metrics.getBounds().height();
            int topPx = insets.top;
            if (topPx <= 0 || screenH <= 0) return b;
            float sy = b.getHeight() / (float)screenH;
            int cropTop = Math.max(0, Math.min(b.getHeight() - 1, Math.round(topPx * sy)));
            return Bitmap.createBitmap(b, 0, cropTop, b.getWidth(), b.getHeight() - cropTop);
        } catch (Throwable ignored) { return b; }
    }

    static void save(Context c, Bitmap b) {
        if (b == null || b.isRecycled()) { Toast.makeText(c, "截图无效", Toast.LENGTH_LONG).show(); return; }
        String name = "FloatLens_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".png";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FloatLens");
        var uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) { Toast.makeText(c, "保存失败", Toast.LENGTH_LONG).show(); return; }
        try (OutputStream o = c.getContentResolver().openOutputStream(uri)) {
            if (o == null || !b.compress(Bitmap.CompressFormat.PNG, 100, o)) throw new IllegalStateException("PNG 写入失败");
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
