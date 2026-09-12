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
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "try accessibility primary");
            captureAccessibility(app, b -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility success bitmap=" + size(b));
                ok.accept(b);
            }, accessError -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility failed=" + safeMessage(accessError));
                if (settings.rootScreenshot()) {
                    DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "fallback root");
                    RootCapture.captureAsync(app, b -> {
                        DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "root fallback success bitmap=" + size(b));
                        ok.accept(b);
                    }, rootError -> {
                        DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "root fallback failed=" + safeMessage(rootError));
                        fail.accept(combined(accessError, rootError));
                    });
                } else {
                    fail.accept(accessError);
                }
            });
            return;
        }
        if (settings.rootScreenshot()) {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "try root primary");
            RootCapture.captureAsync(app, b -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "root success bitmap=" + size(b));
                ok.accept(b);
            }, error -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "root failed=" + safeMessage(error));
                fail.accept(error);
            });
            return;
        }
        DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "try accessibility implicit");
        captureAccessibility(app, b -> {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility implicit success bitmap=" + size(b));
            ok.accept(b);
        }, error -> {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility implicit failed=" + safeMessage(error));
            fail.accept(error);
        });
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

    private static String size(Bitmap b) {
        if (b == null) return "null";
        if (b.isRecycled()) return "recycled";
        return b.getWidth() + "x" + b.getHeight();
    }

    private ScreenCaptureBackend() {}
}
