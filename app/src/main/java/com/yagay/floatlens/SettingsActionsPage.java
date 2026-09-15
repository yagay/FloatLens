package com.yagay.floatlens;

import android.widget.LinearLayout;

/** Gesture -> action binding settings page. */
final class SettingsActionsPage {
    static LinearLayout build(SettingsActivity activity, FloatSettings fs) {
        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        LinearLayout root = AppUi.pageRoot(activity, "手势动作映射",
                "每个手势只显示一行，动作选择集中在这里。" );

        AppUi.Section tap = AppUi.section(activity, "点击与识别", null);
        ui.actionSpinner(tap.body, "单击", FloatSettings.K_ACTION_CLICK,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_CLICK));
        ui.actionSpinner(tap.body, "双击", FloatSettings.K_ACTION_DOUBLE,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_DOUBLE));
        ui.actionSpinner(tap.body, "长按", FloatSettings.K_ACTION_LONG,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_LONG));
        ui.actionSpinner(tap.body, "圈选识别", FloatSettings.K_ACTION_RECOGNIZE,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_RECOGNIZE));
        AppUi.addSection(root, tap);

        AppUi.Section swipe = AppUi.section(activity, "滑动", null);
        ui.actionSpinner(swipe.body, "上滑", FloatSettings.K_ACTION_UP,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_UP));
        ui.actionSpinner(swipe.body, "下滑（短）", FloatSettings.K_ACTION_DOWN_SHORT,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_DOWN_SHORT));
        ui.actionSpinner(swipe.body, "下滑（长）", FloatSettings.K_ACTION_DOWN_LONG,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_DOWN_LONG));
        ui.actionSpinner(swipe.body, "侧滑（短）", FloatSettings.K_ACTION_SIDE_SHORT,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_SIDE_SHORT));
        ui.actionSpinner(swipe.body, "侧滑（长）", FloatSettings.K_ACTION_SIDE_LONG,
                ActionRegistry.defaultForPreference(FloatSettings.K_ACTION_SIDE_LONG));
        AppUi.addSection(root, swipe);
        return root;
    }

    private SettingsActionsPage() {}
}
