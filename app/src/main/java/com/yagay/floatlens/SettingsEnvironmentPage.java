package com.yagay.floatlens;

import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Environment-driven visibility settings page. */
final class SettingsEnvironmentPage {
    static final class Result {
        final LinearLayout root;
        final EditText hidePackagesEdit;

        Result(LinearLayout root, EditText hidePackagesEdit) {
            this.root = root;
            this.hidePackagesEdit = hidePackagesEdit;
        }
    }

    static Result build(SettingsActivity activity, FloatSettings fs) {
        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        LinearLayout root = AppUi.pageRoot(activity, "环境与显示",
                "与当前应用、键盘和屏幕环境有关的行为。" );

        AppUi.Section behavior = AppUi.section(activity, "显示行为", null);
        ui.check(behavior.body, "键盘出现时避让图标", null,
                FloatSettings.K_IME_AVOID, fs.imeAvoid());
        ui.check(behavior.body, "Quick Move / 智能屏幕入口", null,
                FloatSettings.K_QUICK_MOVE, fs.quickMoveEnabled());
        AppUi.addSection(root, behavior);

        AppUi.Section hidden = AppUi.section(activity, "按应用隐藏",
                "输入包名，使用逗号、空格或换行分隔。" );
        LinearLayout hideBlock = AppUi.settingBlock(activity);
        EditText edit = new EditText(activity);
        AppUi.styleInput(activity, edit);
        edit.setText(fs.prefs().getString(FloatSettings.K_HIDE_PACKAGES, ""));
        edit.setHint("com.example.game\ncom.example.bank");
        edit.setSingleLine(false);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setMinHeight(AppUi.dp(activity, 110));
        edit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) fs.prefs().edit()
                    .putString(FloatSettings.K_HIDE_PACKAGES, edit.getText().toString()).apply();
        });
        hideBlock.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        AppUi.addRow(hidden.body, hideBlock);
        AppUi.addSection(root, hidden);
        return new Result(root, edit);
    }

    private SettingsEnvironmentPage() {}
}
