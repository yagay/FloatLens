package com.yagay.floatlens;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.HashSet;
import java.util.Set;

/** Shared widgets for the focused settings pages. Keeps persistence behavior in one place. */
final class SettingsPageUi {
    private final SettingsActivity activity;
    private final FloatSettings fs;
    private final String[] actionIds = ActionId.availableIds();

    SettingsPageUi(SettingsActivity activity, FloatSettings fs) {
        this.activity = activity;
        this.fs = fs;
    }

    void check(LinearLayout parent, String title, String subtitle,
               String key, boolean current) {
        SwitchMaterial toggle = AppUi.switchRow(activity, title, subtitle, current,
                (button, checked) -> fs.prefs().edit().putBoolean(key, checked).apply());
        AppUi.addRow(parent, AppUi.switchContainer(toggle));
    }

    void fullscreenModeCheck(LinearLayout parent) {
        SwitchMaterial toggle = AppUi.switchRow(activity,
                "全屏应用自动隐藏",
                "进入全屏内容时自动隐藏悬浮图标",
                fs.fullscreenHideMode() != 0,
                (button, checked) -> fs.prefs().edit()
                        .putInt(FloatSettings.K_HIDE_FULLSCREEN, checked ? 2 : 0).apply());
        AppUi.addRow(parent, AppUi.switchContainer(toggle));
    }

    void seek(LinearLayout parent, String label, String key,
              int min, int max, int current, String suffix) {
        LinearLayout block = AppUi.sliderBlock(activity);
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = AppUi.text(activity, label, 14, false);
        TextView value = AppUi.caption(activity, current + suffix, 13);
        value.setGravity(Gravity.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(value, new LinearLayout.LayoutParams(-2, -2));
        block.addView(top);

        Slider slider = new Slider(activity);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(1f);
        slider.setValue(Math.max(min, Math.min(max, current)));
        slider.setMinimumHeight(0);
        slider.setPadding(0, 0, 0, 0);
        slider.addOnChangeListener((s, next, fromUser) -> {
            if (!fromUser) return;
            int intValue = Math.round(next);
            value.setText(intValue + suffix);
            fs.prefs().edit().putInt(key, intValue).apply();
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(-1, AppUi.dp(activity, 34));
        sliderLp.topMargin = AppUi.dp(activity, -1);
        block.addView(slider, sliderLp);
        AppUi.addRow(parent, block);
    }

    void actionSpinner(LinearLayout parent, String label, String key, String def) {
        String[] labels = new String[actionIds.length];
        int selected = 0;
        String now = fs.action(key, def);
        for (int i = 0; i < actionIds.length; i++) {
            labels[i] = ActionId.label(actionIds[i]);
            if (actionIds[i].equals(now)) selected = i;
        }
        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                                 int position, long id) {
                fs.prefs().edit().putString(key, actionIds[position]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        addSpinnerRow(parent, label, spinner, false);
    }

    void styleSpinner(LinearLayout parent) {
        String[] labels = {"蓝色镜头", "深色镜头", "浅色镜头", "自定义图片 / GIF", "多图轮播"};
        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(fs.style());
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                 int pos, long id) {
                fs.prefs().edit().putInt(FloatSettings.K_STYLE, pos).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        addSpinnerRow(parent, "图标样式", spinner, false);
    }

    void lineStyleSpinner(LinearLayout parent) {
        String[] labels = {"圆角", "方形", "圆角增强"};
        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(fs.lineStyle());
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                 int pos, long id) {
                fs.prefs().edit().putInt(FloatSettings.K_LINE_STYLE, pos).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        addSpinnerRow(parent, "轨迹样式", spinner, false);
    }

    void ocrEngineSpinner(LinearLayout parent) {
        String[] labels = {
                "自动：Small → Medium → ML Kit",
                "PP-OCRv6 Medium 高精度",
                "PP-OCRv6 Small 平衡",
                "ML Kit 快速"
        };
        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(fs.ocrEngineMode());
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                 int pos, long id) {
                fs.prefs().edit().putInt(FloatSettings.K_OCR_ENGINE, pos).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        addSpinnerRow(parent, "OCR 引擎", spinner, true);
    }

    void circleFullOcrEngineSpinner(LinearLayout parent) {
        String[] labels = {
                "ML Kit 快速",
                "PP-OCRv6 Tiny 超轻量",
                "PP-OCRv6 Small 平衡",
                "PP-OCRv6 Medium 高精度"
        };
        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(fs.circleFullOcrEngine());
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                 int pos, long id) {
                fs.prefs().edit().putInt(FloatSettings.K_CIRCLE_FULL_OCR_ENGINE, pos).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        addSpinnerRow(parent, "整屏识别引擎", spinner, true);
    }

    void circleCorrectionEngineSpinner(LinearLayout parent) {
        String[] labels = {
                "关闭局部校正",
                "PP-OCRv6 Tiny 超轻量",
                "PP-OCRv6 Small 平衡",
                "PP-OCRv6 Medium 高精度"
        };
        Spinner spinner = new Spinner(activity);
        spinner.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(fs.circleCorrectionEngine());
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,
                                                 int pos, long id) {
                fs.prefs().edit().putInt(FloatSettings.K_CIRCLE_CORRECTION_ENGINE, pos).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        addSpinnerRow(parent, "局部校正引擎", spinner, true);
    }

    void addSpinnerRow(LinearLayout parent, String label, Spinner spinner, boolean vertical) {
        LinearLayout block = AppUi.settingBlock(activity);
        if (vertical) {
            block.addView(AppUi.text(activity, label, 14, false));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, AppUi.dp(activity, 48));
            sp.topMargin = AppUi.dp(activity, 2);
            block.addView(spinner, sp);
        } else {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = AppUi.text(activity, label, 14, false);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 0.82f));
            row.addView(spinner, new LinearLayout.LayoutParams(0, AppUi.dp(activity, 48), 1.18f));
            block.addView(row);
        }
        AppUi.addRow(parent, block);
    }

    void addOcrModelRow(LinearLayout parent, int model) {
        LinearLayout block = AppUi.settingBlock(activity);
        TextView status = AppUi.text(activity, OcrModelManager.displayName(model), 14, false);
        block.addView(status);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton download = AppUi.compactButton(activity, "下载 / 更新");
        MaterialButton remove = AppUi.compactButton(activity, "删除");
        addWeightedButton(buttons, download, true);
        addWeightedButton(buttons, remove, false);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, -2);
        buttonsLp.topMargin = AppUi.dp(activity, 7);
        block.addView(buttons, buttonsLp);
        AppUi.addRow(parent, block);

        Runnable refresh = () -> {
            try {
                boolean ready = OcrModelManager.isReady(activity, model);
                long mb = OcrModelManager.installedBytes(activity, model) / (1024 * 1024);
                status.setText(OcrModelManager.displayName(model) + " · "
                        + (ready ? "已下载 " + mb + " MB" : "未下载"));
                status.setTextColor(ready ? AppUi.success(activity) : AppUi.textPrimary(activity));
                remove.setEnabled(ready && !OcrModelManager.isDownloading(model));
                download.setEnabled(!OcrModelManager.isDownloading(model));
            } catch (Throwable t) {
                status.setText("本地模型状态读取失败");
                status.setTextColor(AppUi.warning(activity));
                remove.setEnabled(false);
                download.setEnabled(false);
                DiagnosticLog.i(activity, "OCR_MODEL_UI",
                        "refresh failure=" + t.getClass().getSimpleName());
            }
        };
        refresh.run();

        download.setOnClickListener(v -> {
            download.setEnabled(false);
            remove.setEnabled(false);
            try {
                OcrModelManager.download(activity, model, new OcrModelManager.Callback() {
                    @Override public void onProgress(String stage, int percent) {
                        status.setText(OcrModelManager.displayName(model) + " · " + stage + " " + percent + "%");
                    }
                    @Override public void onSuccess() {
                        Toast.makeText(activity, "模型下载完成", Toast.LENGTH_SHORT).show();
                        refresh.run();
                    }
                    @Override public void onFailure(String message) {
                        Toast.makeText(activity, "下载失败: " + message, Toast.LENGTH_LONG).show();
                        refresh.run();
                    }
                });
            } catch (Throwable t) {
                Toast.makeText(activity, "模型下载初始化失败", Toast.LENGTH_LONG).show();
                DiagnosticLog.i(activity, "OCR_MODEL_UI",
                        "download launch failure=" + t.getClass().getSimpleName());
                refresh.run();
            }
        });
        remove.setOnClickListener(v -> {
            try { OcrModelManager.delete(activity, model); } catch (Throwable ignored) {}
            refresh.run();
        });
    }

    void addOcrLanguageChecks(LinearLayout parent) {
        Set<String> selected = new HashSet<>(OcrLanguages.get(activity));
        AppUi.addRow(parent, ocrLanguageCheck("简体中文", OcrLanguages.ZH_HANS, selected));
        AppUi.addRow(parent, ocrLanguageCheck("繁體中文", OcrLanguages.ZH_HANT, selected));
        AppUi.addRow(parent, ocrLanguageCheck("English", OcrLanguages.ENGLISH, selected));
    }

    private MaterialCheckBox ocrLanguageCheck(String label, String code, Set<String> selected) {
        MaterialCheckBox box = new MaterialCheckBox(activity);
        box.setUseMaterialThemeColors(true);
        box.setText(label);
        box.setTextColor(AppUi.textPrimary(activity));
        box.setTextSize(14);
        box.setPadding(AppUi.dp(activity, 10), AppUi.dp(activity, 7),
                AppUi.dp(activity, 10), AppUi.dp(activity, 7));
        box.setTag(code);
        box.setChecked(selected.contains(code));
        box.setOnCheckedChangeListener((button, checked) -> {
            String lang = String.valueOf(button.getTag());
            if (checked) selected.add(lang); else selected.remove(lang);
            if (selected.isEmpty()) {
                selected.add(lang);
                button.setChecked(true);
                Toast.makeText(activity, "至少选择一种 OCR 语言", Toast.LENGTH_SHORT).show();
                return;
            }
            OcrLanguages.save(activity, selected);
            DiagnosticLog.i(activity, "OCR_LANG", "selected=" + selected);
        });
        return box;
    }

    void addWeightedButton(LinearLayout row, MaterialButton button, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (first) lp.setMarginEnd(AppUi.dp(activity, 6));
        else lp.setMarginStart(AppUi.dp(activity, 6));
        row.addView(button, lp);
    }
}
