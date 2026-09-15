package com.yagay.floatlens;

import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;

/** Settings UI for Circle Select OCR. Deliberately excludes PP-OCR choices. */
final class CircleOcrSettingsUi {
    static void add(SettingsActivity activity, FloatSettings fs, LinearLayout parent) {
        String[] labels = {
                "View + ML Kit（推荐）",
                "仅 View（关闭视觉 OCR）"
        };
        int selected = CircleOcrPolicy.mode(fs);

        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                 int pos, long id) {
                int mode = pos == CircleOcrPolicy.MODE_VIEW_ONLY
                        ? CircleOcrPolicy.MODE_VIEW_ONLY : CircleOcrPolicy.MODE_VIEW_PLUS_MLKIT;
                fs.prefs().edit().putInt(CircleOcrPolicy.K_MODE, mode).apply();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        ui.addSpinnerRow(parent, "圈画文字识别", spinner, true);
        LinearLayout note = AppUi.baseRow(activity);
        note.addView(AppUi.caption(activity,
                "圈画不会调用 PP-OCR。View 文字始终优先；ML Kit 只用于 View 无法覆盖的区域和几何辅助。",
                13), new LinearLayout.LayoutParams(-1, -2));
        AppUi.addRow(parent, note);
    }

    private CircleOcrSettingsUi() {}
}
