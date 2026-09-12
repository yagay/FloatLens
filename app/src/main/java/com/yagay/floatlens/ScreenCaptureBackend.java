package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.function.Consumer;

/** Chooses Accessibility/Root screenshot backends; owns no UI visibility or crop policy. */
final class ScreenCaptureBackend {
    static void capture(Context c, FloatSettings settings,
                        Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        if (settings.accessibilityScreenshot()) {
            captureAccessibility(app, ok, accessError -> {
                if (settings.rootScreenshot()) {
                    RootCapture.captureAsync(app, ok,
                            rootError -> fail.accept(combined(accessError, rootError)));
                } else {
                    fail.accept(accessError);
                }
            });
            return;
        }
        if (settings.rootScreenshot()) {
            RootCapture.captureAsync(app, ok, fail);
            return;
        }
        captureAccessibility(app, ok, fail);
    }

    private static void captureAccessibility(Context app, Consumer<Bitmap> ok,
                                             Consumer<Throwable> fail) {
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) {
            fail.accept(new IllegalStateException("需要开启 FloatLens 无障碍服务，或启用 Root 截图"));
            return;
        }
        service.capture(ok, fail);
    }

    private static IllegalStateException combined(Throwable a, Throwable b) {
        return new IllegalStateException("Accessibility 与 Root 截图均失败；Accessibility="
                + safeMessage(a) + "，Root=" + safeMessage(b));
    }

    static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private ScreenCaptureBackend() {}
}
