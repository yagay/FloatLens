package com.yagay.floatlens;

import java.util.LinkedHashMap;

/** Single catalog for configurable action IDs, labels and defaults. */
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

    private ActionRegistry() {}
}
