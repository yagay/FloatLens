package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Entry point for the Google-like local Circle Select workspace. */
public final class CircleSelectController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long generation;

    public static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        long gen = ++generation;
        CircleSelectOverlay.dismissActive("restart");

        // Snapshot the live notification shade before capture. The bitmap keeps that exact frame,
        // but on modern OxygenOS we must collapse the live shade before attaching the focusable
        // Circle Select TYPE_APPLICATION_OVERLAY or the transient Activity fallback is blocked.
        final FvSystemPanelController.CaptureState shadeState = FvSystemPanelController.beginCapture(
                app, "circle_select");

        FloatService service = FloatService.get();
        if (service != null) {
            service.onCircleCaptureStarted();
            service.setScreenshotHidden(true);
        }

        DiagnosticLog.i(app, "CIRCLE_SELECT", "capture begin gen=" + gen
                + " shadeExpanded=" + shadeState.expandedAtCapture());
        MAIN.postDelayed(() -> CircleSelectFrame.capture(app, bitmap -> {
            synchronized (CircleSelectController.class) {
                if (gen != generation) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
            }
            if (bitmap == null || bitmap.isRecycled()) {
                restore(service, "invalid_capture");
                return;
            }

            CircleShadeCoordinator.prepare(app, shadeState.expandedAtCapture(), collapsed -> {
                synchronized (CircleSelectController.class) {
                    if (gen != generation) {
                        if (!bitmap.isRecycled()) bitmap.recycle();
                        return;
                    }
                }
                DiagnosticLog.i(app, "CIRCLE_SELECT", "pre-candidate shade collapsed=" + collapsed);
                showCandidate(app, bitmap, service, shadeState, collapsed);
            });
        }, error -> {
            restore(service, "capture_failed");
            Toast.makeText(app, "圈画识别截图失败: " + safe(error), Toast.LENGTH_LONG).show();
            DiagnosticLog.i(app, "CIRCLE_SELECT", "capture failed=" + safe(error));
        }), 90L);
    }

    private static void showCandidate(Context app, Bitmap bitmap, FloatService service,
                                      FvSystemPanelController.CaptureState shadeState,
                                      boolean shadeAlreadyCollapsed) {
        if (bitmap == null || bitmap.isRecycled()) {
            restore(service, "invalid_candidate_bitmap");
            return;
        }

        boolean shown = CircleSelectOverlay.show(app, bitmap, () -> restore(service, "closed"));
        if (shown) {
            // Normal modern-OxygenOS path: the live shade was collapsed before the focusable overlay
            // attached, while the frozen screenshot still contains the original notification shade.
            // If the pre-candidate compatibility step failed, retain the shared FV fallback path so
            // Root-capable devices still get one final chance rather than silently regressing.
            if (!shadeAlreadyCollapsed && FvSystemPanelController.notificationShadeExpanded()) {
                DiagnosticLog.i(app, "CIRCLE_SELECT",
                        "pre-candidate collapse failed; retry shared result-ready path");
                FvSystemPanelController.onResultReady(
                        app, shadeState, "circle_candidate_shown_after_preclose_failed");
            } else {
                DiagnosticLog.i(app, "CIRCLE_SELECT",
                        "candidate shown after live shade collapse; no second dismiss needed");
            }
        } else {
            if (!bitmap.isRecycled()) bitmap.recycle();
            restore(service, "overlay_failed");
            Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static void restore(FloatService service, String reason) {
        if (service != null) {
            service.setScreenshotHidden(false);
            service.onCircleFinished("circle_select_" + reason);
        }
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private CircleSelectController() {}
}
