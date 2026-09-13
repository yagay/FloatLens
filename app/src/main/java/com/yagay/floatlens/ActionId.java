package com.yagay.floatlens;

/** Stable persisted action IDs. Metadata and behavior live in ActionRegistry. */
public final class ActionId {
    public static final String NONE="none", BACK="back", HOME="home", RECENTS="recents",
            SCREENSHOT="screenshot", REGION_SCREENSHOT="region_screenshot", OCR="ocr",
            HIDE="hide", NOTIFICATIONS="notifications", CLICK_UNDER="click_under",
            AI_SCREEN="ai_screen", MOVE_ICON="move_icon";

    public static String label(String id) { return ActionRegistry.label(id); }
    public static String[] availableIds() { return ActionRegistry.availableIds(); }

    private ActionId() {}
}
