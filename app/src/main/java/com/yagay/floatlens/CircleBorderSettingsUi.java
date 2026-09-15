package com.yagay.floatlens;

import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Focused settings widgets for the Circle Select active-state border. */
final class CircleBorderSettingsUi {
    private static final String[] COLOR_LABELS = {
            "蓝色（默认）", "绿色", "青色", "紫色", "橙色", "红色", "白色"
    };
    private static final int[] COLOR_VALUES = {
            0xFF4285F4, 0xFF34A853, 0xFF00B8D4, 0xFF9C6ADE,
            0xFFFF8A00, 0xFFEA4335, 0xFFFFFFFF
    };

    static void add(SettingsActivity activity, FloatSettings fs, LinearLayout parent) {
        SwitchMaterial enabled = AppUi.switchRow(activity,
                "显示圈画激活边框",
                "圈画激活时沿完整屏幕边缘显示提示；截图时自动隐藏，不会进入截图",
                fs.circleBorderEnabled(),
                (button, checked) -> {
                    fs.prefs().edit().putBoolean(FloatSettings.K_CIRCLE_BORDER_ENABLED, checked).apply();
                    CircleActiveBorderOverlay.refreshStyle(activity);
                });
        AppUi.addRow(parent, AppUi.switchContainer(enabled));

        Spinner color = new Spinner(activity);
        color.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, COLOR_LABELS));
        color.setSelection(indexForColor(fs.circleBorderColor()));
        color.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                 int position, long id) {
                int selected = COLOR_VALUES[Math.max(0, Math.min(COLOR_VALUES.length - 1, position))];
                if (fs.circleBorderColor() == selected) return;
                fs.prefs().edit().putInt(FloatSettings.K_CIRCLE_BORDER_COLOR, selected).apply();
                CircleActiveBorderOverlay.refreshStyle(activity);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        addSpinnerRow(activity, parent, "边框颜色", color);

        LinearLayout block = AppUi.sliderBlock(activity);
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = AppUi.text(activity, "边框粗细", 14, false);
        TextView value = AppUi.caption(activity, fs.circleBorderWidthDp() + "dp", 13);
        value.setGravity(Gravity.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(value, new LinearLayout.LayoutParams(-2, -2));
        block.addView(top);

        Slider width = new Slider(activity);
        width.setValueFrom(1f);
        width.setValueTo(8f);
        width.setStepSize(1f);
        width.setValue(fs.circleBorderWidthDp());
        width.setMinimumHeight(0);
        width.setPadding(0, 0, 0, 0);
        width.addOnChangeListener((slider, next, fromUser) -> {
            if (!fromUser) return;
            int dp = Math.round(next);
            value.setText(dp + "dp");
            fs.prefs().edit().putInt(FloatSettings.K_CIRCLE_BORDER_WIDTH_DP, dp).apply();
            CircleActiveBorderOverlay.refreshStyle(activity);
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(-1, AppUi.dp(activity, 34));
        sliderLp.topMargin = AppUi.dp(activity, -1);
        block.addView(width, sliderLp);
        AppUi.addRow(parent, block);
    }

    private static void addSpinnerRow(SettingsActivity activity, LinearLayout parent,
                                      String label, Spinner spinner) {
        LinearLayout block = AppUi.settingBlock(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = AppUi.text(activity, label, 14, false);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 0.82f));
        row.addView(spinner, new LinearLayout.LayoutParams(0, AppUi.dp(activity, 48), 1.18f));
        block.addView(row);
        AppUi.addRow(parent, block);
    }

    private static int indexForColor(int color) {
        for (int i = 0; i < COLOR_VALUES.length; i++) {
            if (COLOR_VALUES[i] == color) return i;
        }
        return 0;
    }

    private CircleBorderSettingsUi() { }
}
