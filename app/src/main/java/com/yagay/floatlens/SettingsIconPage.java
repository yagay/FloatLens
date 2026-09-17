package com.yagay.floatlens;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

/** Floating icon appearance/position settings page. */
final class SettingsIconPage {
    static LinearLayout build(SettingsActivity activity, FloatSettings fs) {
        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        LinearLayout root = AppUi.pageRoot(activity, "悬浮图标",
                "只保留与图标外观、位置和显示有关的设置。" );

        AppUi.Section appearance = AppUi.section(activity, "外观", null);
        ui.styleSpinner(appearance.body);
        ui.seek(appearance.body, "透明度", FloatSettings.K_ALPHA,
                10, 100, Math.round(fs.alpha() * 100), "%");
        ui.seek(appearance.body, "图标大小", FloatSettings.K_SIZE,
                24, 96, fs.sizeDp(), " dp");

        LinearLayout iconButtons = AppUi.buttonRow(activity);
        MaterialButton customIcon = AppUi.secondaryButton(activity, "自定义 / GIF");
        customIcon.setOnClickListener(v -> {
            Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            in.setType("image/*");
            in.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(in, SettingsActivity.REQUEST_CUSTOM_ICON);
        });
        MaterialButton slideIcon = AppUi.secondaryButton(activity, "多图轮播");
        slideIcon.setOnClickListener(v -> {
            Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            in.setType("image/*");
            in.addCategory(Intent.CATEGORY_OPENABLE);
            in.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            activity.startActivityForResult(in, SettingsActivity.REQUEST_SLIDE_ICONS);
        });
        ui.addWeightedButton(iconButtons, customIcon, true);
        ui.addWeightedButton(iconButtons, slideIcon, false);
        AppUi.addRow(appearance.body, iconButtons);
        ui.seek(appearance.body, "轮播间隔", FloatSettings.K_SLIDE_INTERVAL,
                500, 10000, fs.slideIntervalMs(), " ms");
        AppUi.addSection(root, appearance);

        AppUi.Section position = AppUi.section(activity, "位置与显示", null);
        ui.seek(position.body, "贴边后可见比例", FloatSettings.K_SHOW_PERCENT,
                10, 100, fs.showPercentage(), "%");
        ui.check(position.body, "左右两侧同时显示", "屏幕两侧都保留悬浮图标",
                FloatSettings.K_BOTH_SIDE, fs.bothSide());
        ui.check(position.body, "自动吸边", "拖动结束后自动贴近屏幕边缘",
                FloatSettings.K_SNAP, fs.snap());
        ui.fullscreenModeCheck(position.body);
        ui.check(position.body, "边缘滑入唤回", "隐藏后可从屏幕边缘滑入恢复",
                FloatSettings.K_HIDE_MAIN_SWIPE, fs.edgeSwipeRecallEnabled());
        ui.check(position.body, "锁屏仍显示", null,
                FloatSettings.K_SHOW_ON_LOCK, fs.showOnLock());
        ui.check(position.body, "点击图标时点击下方屏幕", "用于需要穿透式点击的场景",
                FloatSettings.K_CLICK_UNDER, fs.clickScreenUnderIcon());
        AppUi.addSection(root, position);

        TextView note = AppUi.caption(activity,
                "位置保存：普通拖动只临时跟手；执行“移动图标位置”后，下一次拖动才会保存新位置。",
                12);
        note.setPadding(AppUi.dp(activity, 4), 0, AppUi.dp(activity, 4), AppUi.dp(activity, 4));
        root.addView(note);
        return root;
    }

    private SettingsIconPage() {}
}
