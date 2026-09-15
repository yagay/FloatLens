package com.yagay.floatlens;

import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;

/** Settings UI for Circle Select OCR. Deliberately excludes PP-OCR choices. */
final class CircleOcrSettingsUi {
    static void add(SettingsActivity activity, FloatSettings fs, LinearLayout parent) {
        String[] labels = {
                "View + ML Kit（推荐）",
                "仅 View",
                "仅 ML Kit"
        };
        int selected = CircleOcrPolicy.mode(fs);

        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                 int pos, long id) {
                int mode = switch (pos) {
                    case CircleOcrPolicy.MODE_VIEW_ONLY -> CircleOcrPolicy.MODE_VIEW_ONLY;
                    case CircleOcrPolicy.MODE_MLKIT_ONLY -> CircleOcrPolicy.MODE_MLKIT_ONLY;
                    default -> CircleOcrPolicy.MODE_VIEW_PLUS_MLKIT;
                };
                fs.prefs().edit().putInt(CircleOcrPolicy.K_MODE, mode).apply();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        ui.addSpinnerRow(parent, "圈画文字识别", spinner, true);
        LinearLayout note = AppUi.baseRow(activity);
        note.addView(AppUi.caption(activity,
                "View + ML Kit：View 文字优先、ML Kit 补盲/辅助几何；仅 View：完全不跑视觉 OCR；仅 ML Kit：适合不暴露 Accessibility 文字的应用。三种模式都不会调用 PP-OCR。",
                13), new LinearLayout.LayoutParams(-1, -2));
        AppUi.addRow(parent, note);
    }

    private CircleOcrSettingsUi() {}
}
