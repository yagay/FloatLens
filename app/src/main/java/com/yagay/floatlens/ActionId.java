package com.yagay.floatlens;

public final class ActionId {
    public static final String NONE="none", BACK="back", HOME="home", RECENTS="recents", SCREENSHOT="screenshot", REGION_SCREENSHOT="region_screenshot", OCR="ocr", HIDE="hide", NOTIFICATIONS="notifications", CLICK_UNDER="click_under";
    public static String label(String id) {
        return switch (id) {
            case BACK -> "返回"; case HOME -> "主页"; case RECENTS -> "最近任务"; case SCREENSHOT -> "截图";
            case REGION_SCREENSHOT -> "区域截图"; case OCR -> "OCR/提取文字"; case HIDE -> "隐藏悬浮图标";
            case NOTIFICATIONS -> "通知栏"; case CLICK_UNDER -> "点击悬浮图标下方屏幕"; default -> "无动作";
        };
    }
    private ActionId() {}
}
