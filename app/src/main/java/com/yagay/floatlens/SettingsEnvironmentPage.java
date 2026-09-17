package com.yagay.floatlens;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Environment-driven visibility settings page. */
final class SettingsEnvironmentPage {
    static LinearLayout build(SettingsActivity activity, FloatSettings fs) {
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
        edit.setText(fs.hiddenPackagesRaw());
        edit.setHint("com.example.game\ncom.example.bank");
        edit.setSingleLine(false);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setMinHeight(AppUi.dp(activity, 110));
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                fs.setHiddenPackagesRaw(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        hideBlock.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        AppUi.addRow(hidden.body, hideBlock);
        AppUi.addSection(root, hidden);
        return root;
    }

    private SettingsEnvironmentPage() {}
}
