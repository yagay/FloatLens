package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.widget.Toast;

/** Single owner for executing an already-resolved FloatLens action ID. */
public final class ActionExecutor {
    public static void execute(Context c, String action) {
        if (c == null) return;
        String id = action == null ? ActionId.NONE : action;
        LensAccessibilityService service = LensAccessibilityService.get();
        DiagnosticLog.i(c, "ACTION", "execute=" + id + " accessibility=" + (service != null));
        switch (id) {
            case ActionId.BACK -> global(c, service, AccessibilityService.GLOBAL_ACTION_BACK);
            case ActionId.HOME -> global(c, service, AccessibilityService.GLOBAL_ACTION_HOME);
            case ActionId.RECENTS -> global(c, service, AccessibilityService.GLOBAL_ACTION_RECENTS);
            case ActionId.NOTIFICATIONS -> global(c, service, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
            case ActionId.SCREENSHOT -> {
                OcrEngine.invalidatePending(c, "action_screenshot");
                ScreenshotController.capture(c, false);
            }
            case ActionId.REGION_SCREENSHOT -> {
                OcrEngine.invalidatePending(c, "action_region_screenshot");
                ScreenshotController.capture(c, true);
            }
            case ActionId.OCR -> {
                OcrEngine.invalidatePending(c, "action_ocr_selection");
                ScreenshotController.captureForOcr(c);
            }
            case ActionId.AI_SCREEN -> {
                FloatSettings fs = new FloatSettings(c);
                if (fs.circleEngine() == 1) {
                    DiagnosticLog.i(c, "AI_SCREEN", "engine=google_cts source=floatlens");
                    boolean launched = GoogleCtsTrigger.trigger(c);
                    if (!launched && fs.privilegeFallback()) {
                        DiagnosticLog.i(c, "AI_SCREEN", "google trigger failed; fallback=fl_circle");
                        OcrEngine.invalidatePending(c, "action_fl_circle_fallback");
                        FLCircleController.show(c);
                    }
                } else {
                    OcrEngine.invalidatePending(c, "action_fl_circle");
                    DiagnosticLog.i(c, "AI_SCREEN", "engine=fl_circle");
                    FLCircleController.show(c);
                }
            }
            case ActionId.MOVE_ICON -> {
                FloatService f = FloatService.get();
                if (f != null) f.armPositionMove();
            }
            case ActionId.CLICK_UNDER -> {
                FloatService f = FloatService.get();
                if (f != null) f.clickScreenUnderIcon();
            }
            case ActionId.HIDE -> {
                FloatService f = FloatService.get();
                if (f != null) f.setManualHidden(true);
            }
            default -> { }
        }
    }

    private static void global(Context c, LensAccessibilityService service, int action) {
        if (service == null || !service.global(action)) {
            Toast.makeText(c, "请先开启 FloatLens 无障碍服务", Toast.LENGTH_SHORT).show();
        }
    }

    private ActionExecutor() {}
}
