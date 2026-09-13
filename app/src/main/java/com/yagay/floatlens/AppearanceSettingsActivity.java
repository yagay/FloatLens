package com.yagay.floatlens;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;

/** App appearance plus text-toolbar density settings. */
public final class AppearanceSettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = AppUi.pageRoot(this, "界面与菜单",
                "主题模式和文字菜单主栏显示数量。" );

        AppUi.Section theme = AppUi.section(this, "主题", null);
        addThemeSpinner(theme.body);
        AppUi.addSection(root, theme);

        AppUi.Section textMenu = AppUi.section(this, "文字菜单主栏",
                "数字直接表示主菜单总项目数，复制、全选、分享和“⋮”也计算在内。" );
        addMainItemCountSlider(textMenu.body);
        AppUi.addSection(root, textMenu);

        TextView note = AppUi.caption(this,
                "超出主栏数量的自定义操作仍保留在“⋮”菜单中，不会被删除。",
                12);
        note.setPadding(AppUi.dp(this, 4), 0, AppUi.dp(this, 4), AppUi.dp(this, 4));
        root.addView(note);

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
        TextView title = AppUi.text(this, "主菜单项目总数", 14, false);
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
                "可设置 4～8 个；主栏只显示这个总数，内置项目也计算在内。",
                12);
        hint.setPadding(0, AppUi.dp(this, 2), 0, 0);
        block.addView(hint);

        AppUi.addRow(parent, block);
    }
}
