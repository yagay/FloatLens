package com.yagay.floatlens;

import android.widget.EditText;
import android.widget.LinearLayout;

/** Gesture timing, distance and trail settings page. */
final class SettingsGesturePage {
    static LinearLayout build(SettingsActivity activity, FloatSettings fs) {
        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        LinearLayout root = AppUi.pageRoot(activity, "手势与轨迹",
                "把判定阈值和视觉反馈分开，调节时更容易找到。" );

        AppUi.Section timing = AppUi.section(activity, "点击与长按", null);
        ui.seek(timing.body, "长按时间", FloatSettings.K_LONG_PRESS,
                150, 1000, fs.longPressMs(), " ms");
        ui.seek(timing.body, "双击间隔", FloatSettings.K_DOUBLE_TAP,
                150, 600, fs.doubleTapMs(), " ms");
        ui.seek(timing.body, "短按最大持续时间", FloatSettings.K_TAP_MAX_MS,
                80, 400, fs.tapMaxMs(), " ms");
        ui.seek(timing.body, "手势启动距离", FloatSettings.K_GESTURE_START_DISTANCE,
                10, 80, fs.gestureStartDistance(), " dp");
        AppUi.addSection(root, timing);

        AppUi.Section distance = AppUi.section(activity, "滑动距离", null);
        ui.seek(distance.body, "下滑长短分界", FloatSettings.K_DOWN_SHORT_DISTANCE,
                50, 600, fs.downShortDistance(), " dp");
        ui.seek(distance.body, "侧滑长短分界", FloatSettings.K_SIDE_SHORT_DISTANCE,
                50, 700, fs.sideShortDistance(), " dp");
        AppUi.addSection(root, distance);

        AppUi.Section feedback = AppUi.section(activity, "反馈与轨迹", null);
        ui.check(feedback.body, "震动反馈", null,
                FloatSettings.K_VIBRATE, fs.vibrate());
        ui.check(feedback.body, "显示手势轨迹", null,
                FloatSettings.K_TRACK, fs.track());
        ui.seek(feedback.body, "轨迹透明度", FloatSettings.K_LINE_ALPHA,
                10, 100, fs.lineAlpha(), "%");
        ui.seek(feedback.body, "轨迹宽度", FloatSettings.K_LINE_WIDTH,
                1, 24, fs.lineWidthDp(), " dp");
        ui.check(feedback.body, "轨迹颜色渐变", null,
                FloatSettings.K_LINE_GRADIENT, fs.lineGradient());
        ui.lineStyleSpinner(feedback.body);

        LinearLayout colorsBlock = AppUi.settingBlock(activity);
        colorsBlock.addView(AppUi.text(activity, "轨迹颜色", 14, false));
        EditText lineColors = new EditText(activity);
        AppUi.styleInput(activity, lineColors);
        lineColors.setHint("例如 #FFFFFF,#42A5F5");
        lineColors.setText(fs.lineColors());
        lineColors.setSingleLine(true);
        lineColors.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) fs.prefs().edit()
                    .putString(FloatSettings.K_LINE_COLORS, lineColors.getText().toString()).apply();
        });
        LinearLayout.LayoutParams colorLp = new LinearLayout.LayoutParams(-1, -2);
        colorLp.topMargin = AppUi.dp(activity, 7);
        colorsBlock.addView(lineColors, colorLp);
        AppUi.addRow(feedback.body, colorsBlock);
        AppUi.addSection(root, feedback);
        return root;
    }

    private SettingsGesturePage() {}
}
