package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/** Single catalog for configurable action labels, defaults and execution. */
final class ActionRegistry {
    private static final LinkedHashMap<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(ActionId.NONE, "无动作");
        LABELS.put(ActionId.BACK, "返回");
        LABELS.put(ActionId.HOME, "主页");
        LABELS.put(ActionId.RECENTS, "最近任务");
        LABELS.put(ActionId.SCREENSHOT, "截图");
        LABELS.put(ActionId.REGION_SCREENSHOT, "区域截图");
        LABELS.put(ActionId.OCR, "OCR/提取文字");
        LABELS.put(ActionId.AI_SCREEN, "圈画识别");
        LABELS.put(ActionId.NOTIFICATIONS, "通知栏");
        LABELS.put(ActionId.CLICK_UNDER, "点击悬浮图标下方屏幕");
        LABELS.put(ActionId.MOVE_ICON, "移动图标位置");
        LABELS.put(ActionId.HIDE, "隐藏悬浮图标");
    }

    static String[] availableIds() {
        return LABELS.keySet().toArray(new String[0]);
    }

    static String label(String id) {
        String label = LABELS.get(id);
        return label == null ? LABELS.get(ActionId.NONE) : label;
    }

    static String defaultForPreference(String key) {
        if (FloatSettings.K_ACTION_DOUBLE.equals(key)) return ActionId.SCREENSHOT;
        if (FloatSettings.K_ACTION_RECOGNIZE.equals(key)) return ActionId.OCR;
        if (FloatSettings.K_ACTION_UP.equals(key)) return ActionId.RECENTS;
        if (FloatSettings.K_ACTION_DOWN_SHORT.equals(key)) return ActionId.NOTIFICATIONS;
        if (FloatSettings.K_ACTION_SIDE_SHORT.equals(key)) return ActionId.BACK;
        return ActionId.NONE;
    }

    static void execute(Context c, String action) {
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
                ViewSelectionOverlay.show(c);
            }
            case ActionId.AI_SCREEN -> {
                OcrEngine.invalidatePending(c, "action_circle_select");
                DiagnosticLog.i(c, "AI_SCREEN", "enter Circle Select workspace");
                CircleSelectController.show(c);
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

    private ActionRegistry() {}
}
