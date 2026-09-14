package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.function.Consumer;

/**
 * Selects the screenshot backend while respecting the optional privilege layer.
 *
 * <p>Normal mode never calls RootCapture. Root is considered only when all three conditions are
 * true: enhanced mode, Root provider, and the per-feature Root screenshot switch. Enhanced
 * failures can fall back to Accessibility when the user keeps fallback enabled.</p>
 */
final class ScreenCaptureBackend {
    static void capture(Context c, FloatSettings settings,
                        Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        boolean rootAllowed = settings.effectiveRootScreenshot();
        boolean fallbackNormal = settings.privilegeFallback();

        DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "mode="
                + PrivilegeManager.modeLabel(settings)
                + " accessibilityPreferred=" + settings.accessibilityScreenshot()
                + " rootFeature=" + settings.rootScreenshot()
                + " rootAllowed=" + rootAllowed
                + " fallbackNormal=" + fallbackNormal);

        if (settings.accessibilityScreenshot()) {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "try accessibility primary");
            captureAccessibility(app, b -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility success bitmap=" + size(b));
                ok.accept(b);
            }, accessError -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility failed=" + safeMessage(accessError));
                if (rootAllowed) {
                    DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "fallback enhanced root");
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

        if (rootAllowed) {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "try enhanced root primary");
            RootCapture.captureAsync(app, b -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "root success bitmap=" + size(b));
                ok.accept(b);
            }, rootError -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "root failed=" + safeMessage(rootError));
                if (!fallbackNormal) {
                    fail.accept(rootError);
                    return;
                }
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "fallback normal accessibility");
                captureAccessibility(app, b -> {
                    DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "normal fallback success bitmap=" + size(b));
                    ok.accept(b);
                }, accessError -> {
                    DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "normal fallback failed=" + safeMessage(accessError));
                    fail.accept(combined(rootError, accessError));
                });
            });
            return;
        }

        // Enhanced mode is off, Root is off, or Root screenshot is off: ordinary path only.
        DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "try accessibility normal path");
        captureAccessibility(app, b -> {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility normal success bitmap=" + size(b));
            ok.accept(b);
        }, error -> {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility normal failed=" + safeMessage(error));
            fail.accept(error);
        });
    }

    private static void captureAccessibility(Context app, Consumer<Bitmap> ok,
                                             Consumer<Throwable> fail) {
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) {
            fail.accept(new IllegalStateException(
                    "需要开启 FloatLens 无障碍服务；Root 截图需同时开启增强模式、Root 功能和 Root 截图增强"));
            return;
        }
        service.capture(ok, fail);
    }

    private static IllegalStateException combined(Throwable a, Throwable b) {
        return new IllegalStateException("两个截图后端均失败；first="
                + safeMessage(a) + "，second=" + safeMessage(b));
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
