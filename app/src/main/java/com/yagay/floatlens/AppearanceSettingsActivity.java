package com.yagay.floatlens;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;

/** App appearance plus text-toolbar density and menu-management settings. */
public final class AppearanceSettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = AppUi.pageRoot(this, "界面与菜单",
                "主题、文字菜单显示和各类菜单管理。" );

        AppUi.Section theme = AppUi.section(this, "主题", null);
        addThemeSpinner(theme.body);
        AppUi.addSection(root, theme);

        AppUi.Section textMenu = AppUi.section(this, "文字菜单主栏",
                "数字表示实际操作项目数量；“⋮”始终额外显示，不计入数量。" );
        addMainItemCountSlider(textMenu.body);
        AppUi.addSection(root, textMenu);

        TextView note = AppUi.caption(this,
                "超出主栏数量的自定义操作仍保留在“⋮”菜单中，不会被删除。",
                12);
        note.setPadding(AppUi.dp(this, 4), 0, AppUi.dp(this, 4), AppUi.dp(this, 4));
        root.addView(note);

        AppUi.Section menus = AppUi.section(this, "菜单管理",
                "管理结果菜单中的项目、顺序、隐藏状态和显示名称。" );
        AppUi.addRow(menus.body, AppUi.navRow(this,
                "文字操作菜单",
                "添加、排序和移除自定义文字操作",
                () -> startActivity(MenuPickerActivity.customIntent(this))));
        AppUi.addRow(menus.body, AppUi.navRow(this,
                "分享菜单",
                "管理分享目标和显示顺序",
                () -> startActivity(MenuPickerActivity.targetIntent(this, TargetMenuStore.MODE_SHARE))));
        AppUi.addRow(menus.body, AppUi.navRow(this,
                "打开 / 处理菜单",
                "管理 PROCESS_TEXT 目标和显示顺序",
                () -> startActivity(MenuPickerActivity.targetIntent(this, TargetMenuStore.MODE_PROCESS))));
        AppUi.addRow(menus.body, AppUi.navRow(this,
                "修改菜单显示名称",
                "统一缩短文字、分享和处理菜单中的项目名称",
                () -> startActivity(new android.content.Intent(this, MenuLabelEditorActivity.class))));
        AppUi.addSection(root, menus);

        setContentView(AppUi.scrollPage(this, root));
    }

    private void addThemeSpinner(LinearLayout parent) {
        LinearLayout block = AppUi.settingBlock(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(AppUi.text(this, "主题模式", 14, false));
        TextView sub = AppUi.caption(this, "跟随系统、始终浅色或始终深色", 12);
        sub.setPadding(0, AppUi.dp(this, 2), AppUi.dp(this, 8), 0);
        copy.addView(sub);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        String[] labels = {"跟随系统", "浅色", "深色"};
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(ThemeSettings.mode(this));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 android.view.View view,
                                                 int position, long id) {
                ThemeSettings.setMode(AppearanceSettingsActivity.this, position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(AppUi.dp(this, 128), AppUi.dp(this, 48)));
        block.addView(row);
        AppUi.addRow(parent, block);
    }

    private void addMainItemCountSlider(LinearLayout parent) {
        int current = TextMenuSettings.mainItemCount(this);
        LinearLayout block = AppUi.settingBlock(this);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = AppUi.text(this, "主菜单操作数量", 14, false);
        TextView value = AppUi.caption(this, current + " 个", 13);
        value.setGravity(Gravity.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(value, new LinearLayout.LayoutParams(-2, -2));
        block.addView(top);

        Slider slider = new Slider(this);
        slider.setValueFrom(TextMenuSettings.MIN_MAIN_ITEMS);
        slider.setValueTo(TextMenuSettings.MAX_MAIN_ITEMS);
        slider.setStepSize(1f);
        slider.setValue(current);
        slider.addOnChangeListener((s, next, fromUser) -> {
            int count = Math.round(next);
            TextMenuSettings.setMainItemCount(this, count);
            value.setText(TextMenuSettings.mainItemCount(this) + " 个");
        });
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(Slider slider) { }
            @Override public void onStopTrackingTouch(Slider slider) {
                int count = Math.round(slider.getValue());
                TextMenuSettings.setMainItemCount(AppearanceSettingsActivity.this, count);
                value.setText(TextMenuSettings.mainItemCount(AppearanceSettingsActivity.this) + " 个");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = AppUi.dp(this, 4);
        block.addView(slider, lp);

        TextView hint = AppUi.caption(this,
                "可设置 4～8 个实际操作；复制、全选、分享会计入，‘⋮’不计入并始终额外显示。",
                12);
        hint.setPadding(0, AppUi.dp(this, 2), 0, 0);
        block.addView(hint);

        AppUi.addRow(parent, block);
    }
}
