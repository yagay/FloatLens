package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.function.Consumer;

/** Selects screenshot backends while respecting optional Root / LSPosed enhancement gates. */
final class ScreenCaptureBackend {
    private static final long SECURE_LEASE_PROPAGATION_MS = 40L;

    static void capture(Context c, FloatSettings settings,
                        Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        boolean rootAllowed = settings.effectiveRootScreenshot();
        boolean lsposedSecureAllowed = PrivilegeManager.canUseLsposedSecureScreenshot(settings);
        boolean fallbackNormal = settings.privilegeFallback();

        DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "mode="
                + PrivilegeManager.modeLabel(settings)
                + " accessibilityPreferred=" + settings.accessibilityScreenshot()
                + " rootFeature=" + settings.rootScreenshot()
                + " rootAllowed=" + rootAllowed
                + " lsposedSecureFeature=" + settings.lsposedSecureScreenshot()
                + " lsposedSecureAllowed=" + lsposedSecureAllowed
                + " fallbackNormal=" + fallbackNormal);

        // Secure-window enhancement is implemented in system_server's Accessibility screenshot path,
        // so it takes precedence over a Root-primary preference whenever its full gate is satisfied.
        if (lsposedSecureAllowed) {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "try LSPosed secure accessibility primary");
            captureSecureAccessibility(app, b -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND",
                        "LSPosed secure accessibility success bitmap=" + size(b));
                ok.accept(b);
            }, secureError -> {
                DiagnosticLog.i(app, "SCREENSHOT_BACKEND",
                        "LSPosed secure accessibility failed=" + safeMessage(secureError));
                if (rootAllowed) {
                    DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "fallback enhanced root");
                    RootCapture.captureAsync(app, b -> {
                        DiagnosticLog.i(app, "SCREENSHOT_BACKEND",
                                "root fallback success bitmap=" + size(b));
                        ok.accept(b);
                    }, rootError -> {
                        DiagnosticLog.i(app, "SCREENSHOT_BACKEND",
                                "root fallback failed=" + safeMessage(rootError));
                        fail.accept(combined(secureError, rootError));
                    });
                } else if (fallbackNormal && isLeaseFailure(secureError)) {
                    DiagnosticLog.i(app, "SCREENSHOT_BACKEND",
                            "secure lease unavailable; fallback normal accessibility");
                    captureAccessibility(app, ok, fail);
                } else {
                    fail.accept(secureError);
                }
            });
            return;
        }

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

        DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "try accessibility normal path");
        captureAccessibility(app, b -> {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility normal success bitmap=" + size(b));
            ok.accept(b);
        }, error -> {
            DiagnosticLog.i(app, "SCREENSHOT_BACKEND", "accessibility normal failed=" + safeMessage(error));
            fail.accept(error);
        });
    }

    private static void captureSecureAccessibility(Context app,
                                                   Consumer<Bitmap> ok,
                                                   Consumer<Throwable> fail) {
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) {
            fail.accept(new IllegalStateException("需要开启 FloatLens 无障碍服务才能使用安全窗口截图增强"));
            return;
        }

        LsposedStatusManager.armSecureCaptureAsync(armed -> {
            if (!armed) {
                fail.accept(new SecureLeaseException("LSPosed 安全截图短时授权失败"));
                return;
            }

            // Remote Preferences are framework-backed but target-process delivery is asynchronous.
            // A tiny settle delay keeps the 3s lease narrow while avoiding a race with system_server.
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    service.capture(bitmap -> {
                        LsposedStatusManager.disarmSecureCaptureAsync();
                        ok.accept(bitmap);
                    }, error -> {
                        LsposedStatusManager.disarmSecureCaptureAsync();
                        fail.accept(error);
                    }), SECURE_LEASE_PROPAGATION_MS);
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

    private static boolean isLeaseFailure(Throwable t) {
        return t instanceof SecureLeaseException;
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

    private static final class SecureLeaseException extends IllegalStateException {
        SecureLeaseException(String message) {
            super(message);
        }
    }

    private ScreenCaptureBackend() {}
}
