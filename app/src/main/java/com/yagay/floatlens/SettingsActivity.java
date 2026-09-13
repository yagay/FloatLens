package com.yagay.floatlens;

import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Human-facing FloatLens settings. Internal preference keys are intentionally hidden from UI. */
public class SettingsActivity extends AppCompatActivity {
    private FloatSettings fs;
    private EditText hidePackagesEdit;
    private final String[] ids = ActionId.availableIds();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        fs = new FloatSettings(this);

        LinearLayout root = AppUi.pageRoot(this, "FloatLens 设置",
                "按功能分组。修改后立即生效，不需要单独保存。" );

        AppUi.Section icon = AppUi.section(this, "悬浮图标",
                "外观、停靠位置和显示行为" );
        seek(icon.body, "透明度", FloatSettings.K_ALPHA,
                10, 100, Math.round(fs.alpha() * 100), "%");
        seek(icon.body, "图标大小", FloatSettings.K_SIZE,
                24, 96, fs.sizeDp(), " dp");
        seek(icon.body, "贴边后可见比例", FloatSettings.K_SHOW_PERCENT,
                10, 100, fs.showPercentage(), "%");
        styleSpinner(icon.body);

        LinearLayout iconButtons = AppUi.buttonRow(this);
        MaterialButton customIcon = AppUi.secondaryButton(this, "自定义 / GIF");
        customIcon.setOnClickListener(v -> {
            android.content.Intent in = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            in.setType("image/*");
            in.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            startActivityForResult(in, 401);
        });
        MaterialButton slideIcon = AppUi.secondaryButton(this, "多图轮播");
        slideIcon.setOnClickListener(v -> {
            android.content.Intent in = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            in.setType("image/*");
            in.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            in.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(in, 402);
        });
        addWeightedButton(iconButtons, customIcon, true);
        addWeightedButton(iconButtons, slideIcon, false);
        AppUi.addRow(icon.body, iconButtons);

        seek(icon.body, "轮播间隔", FloatSettings.K_SLIDE_INTERVAL,
                500, 10000, fs.slideIntervalMs(), " ms");
        check(icon.body, "左右两侧同时显示", "在屏幕两侧都保留悬浮图标",
                FloatSettings.K_BOTH_SIDE, fs.bothSide());
        check(icon.body, "锁屏仍显示", null,
                FloatSettings.K_SHOW_ON_LOCK, fs.showOnLock());
        check(icon.body, "自动吸边", "拖动结束后自动贴近屏幕边缘",
                FloatSettings.K_SNAP, fs.snap());
        fullscreenModeCheck(icon.body);
        check(icon.body, "边缘滑入唤回", "隐藏图标后可从屏幕边缘滑入恢复",
                FloatSettings.K_HIDE_MAIN_SWIPE,
                fs.prefs().getBoolean(FloatSettings.K_HIDE_MAIN_SWIPE, true));
        check(icon.body, "点击图标时点击下方屏幕", "用于需要穿透式点击的场景",
                FloatSettings.K_CLICK_UNDER, fs.clickScreenUnderIcon());
        AppUi.addSection(root, icon);

        AppUi.Section gesture = AppUi.section(this, "手势与轨迹",
                "点击、长按和滑动的判定阈值" );
        seek(gesture.body, "长按时间", FloatSettings.K_LONG_PRESS,
                150, 1000, fs.longPressMs(), " ms");
        seek(gesture.body, "双击间隔", FloatSettings.K_DOUBLE_TAP,
                150, 600, fs.doubleTapMs(), " ms");
        seek(gesture.body, "短按最大持续时间", FloatSettings.K_TAP_MAX_MS,
                80, 400, fs.tapMaxMs(), " ms");
        seek(gesture.body, "手势启动距离", FloatSettings.K_GESTURE_START_DISTANCE,
                10, 80, fs.gestureStartDistance(), " dp");
        seek(gesture.body, "下滑长短分界", FloatSettings.K_DOWN_SHORT_DISTANCE,
                50, 600, fs.downShortDistance(), " dp");
        seek(gesture.body, "侧滑长短分界", FloatSettings.K_SIDE_SHORT_DISTANCE,
                50, 700, fs.sideShortDistance(), " dp");
        check(gesture.body, "震动反馈", null,
                FloatSettings.K_VIBRATE, fs.vibrate());
        check(gesture.body, "显示手势轨迹", null,
                FloatSettings.K_TRACK, fs.track());
        seek(gesture.body, "轨迹透明度", FloatSettings.K_LINE_ALPHA,
                10, 100, fs.lineAlpha(), "%");
        seek(gesture.body, "轨迹宽度", FloatSettings.K_LINE_WIDTH,
                1, 24, fs.lineWidthDp(), " dp");
        check(gesture.body, "轨迹颜色渐变", null,
                FloatSettings.K_LINE_GRADIENT, fs.lineGradient());
        lineStyleSpinner(gesture.body);

        LinearLayout colorsBlock = AppUi.settingBlock(this);
        TextView colorsTitle = AppUi.text(this, "轨迹颜色", 14, false);
        colorsBlock.addView(colorsTitle);
        EditText lineColors = new EditText(this);
        AppUi.styleInput(this, lineColors);
        lineColors.setHint("例如 #FFFFFF,#42A5F5");
        lineColors.setText(fs.lineColors());
        lineColors.setSingleLine(true);
        lineColors.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) fs.prefs().edit()
                    .putString(FloatSettings.K_LINE_COLORS, lineColors.getText().toString()).apply();
        });
        LinearLayout.LayoutParams colorLp = new LinearLayout.LayoutParams(-1, -2);
        colorLp.topMargin = AppUi.dp(this, 7);
        colorsBlock.addView(lineColors, colorLp);
        AppUi.addRow(gesture.body, colorsBlock);
        AppUi.addSection(root, gesture);

        AppUi.Section capture = AppUi.section(this, "截图与 OCR",
                "View 停留、截图内容、OCR 引擎和本地模型" );
        seek(capture.body, "高亮 View 停留确认", FloatSettings.K_VIEW_CAPTURE_DWELL,
                200, 2000, fs.viewCaptureDwellMs(), " ms");
        check(capture.body, "截图保留悬浮图标", null,
                FloatSettings.K_KEEP_IN_SCREENSHOT, fs.keepInScreenshot());
        check(capture.body, "截图保留状态栏", null,
                FloatSettings.K_KEEP_STATUS_BAR, fs.keepStatusBarInScreenshot());
        check(capture.body, "优先无障碍截图", "不可用时自动尝试其他后备方案",
                FloatSettings.K_ACCESSIBILITY_SCREENSHOT, fs.accessibilityScreenshot());
        check(capture.body, "Root 截图增强", "作为增强和后备截图来源",
                FloatSettings.K_ROOT_SCREENSHOT, fs.rootScreenshot());
        check(capture.body, "OCR 显示原选区图片", null,
                FloatSettings.K_OCR_SHOW_IMAGE, fs.ocrShowImage());
        check(capture.body, "OCR 显示文字", null,
                FloatSettings.K_OCR_SHOW_TEXT, fs.ocrShowText());
        check(capture.body, "OCR 结果默认折叠", null,
                FloatSettings.K_OCR_COLLAPSE, fs.ocrCollapse());
        ocrEngineSpinner(capture.body);
        try {
            ocrModelControls(capture.body);
        } catch (Throwable t) {
            DiagnosticLog.i(this, "OCR_MODEL_UI",
                    "init failure=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()));
            TextView err = AppUi.caption(this,
                    "本地 OCR 模型管理暂不可用；ML Kit 仍可正常使用。", 13);
            AppUi.addRow(capture.body, err);
        }
        ocrLanguageMultiSelect(capture.body);
        AppUi.addSection(root, capture);

        AppUi.Section environment = AppUi.section(this, "环境与显示",
                "键盘避让、智能入口和按应用隐藏" );
        check(environment.body, "键盘出现时避让图标", null,
                FloatSettings.K_IME_AVOID, fs.imeAvoid());
        check(environment.body, "Quick Move / 智能屏幕入口", null,
                FloatSettings.K_QUICK_MOVE, fs.quickMoveEnabled());

        LinearLayout hideBlock = AppUi.settingBlock(this);
        hideBlock.addView(AppUi.text(this, "按应用隐藏", 14, false));
        TextView hideHint = AppUi.caption(this,
                "输入包名，使用逗号、空格或换行分隔。", 12);
        hideHint.setPadding(0, AppUi.dp(this, 3), 0, AppUi.dp(this, 7));
        hideBlock.addView(hideHint);
        hidePackagesEdit = new EditText(this);
        AppUi.styleInput(this, hidePackagesEdit);
        hidePackagesEdit.setText(fs.prefs().getString(FloatSettings.K_HIDE_PACKAGES, ""));
        hidePackagesEdit.setHint("com.example.game\ncom.example.bank");
        hidePackagesEdit.setSingleLine(false);
        hidePackagesEdit.setGravity(Gravity.TOP | Gravity.START);
        hidePackagesEdit.setMinHeight(AppUi.dp(this, 82));
        hidePackagesEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveHidePackages();
        });
        hideBlock.addView(hidePackagesEdit, new LinearLayout.LayoutParams(-1, -2));
        AppUi.addRow(environment.body, hideBlock);
        AppUi.addSection(root, environment);

        AppUi.Section actions = AppUi.section(this, "操作映射",
                "为点击、长按和滑动手势指定动作" );
        Map<String, String> map = new LinkedHashMap<>();
        map.put("单击", FloatSettings.K_ACTION_CLICK);
        map.put("双击", FloatSettings.K_ACTION_DOUBLE);
        map.put("长按", FloatSettings.K_ACTION_LONG);
        map.put("圈选识别", FloatSettings.K_ACTION_RECOGNIZE);
        map.put("上滑", FloatSettings.K_ACTION_UP);
        map.put("下滑（短）", FloatSettings.K_ACTION_DOWN_SHORT);
        map.put("下滑（长）", FloatSettings.K_ACTION_DOWN_LONG);
        map.put("侧滑（短）", FloatSettings.K_ACTION_SIDE_SHORT);
        map.put("侧滑（长）", FloatSettings.K_ACTION_SIDE_LONG);
        for (var e : map.entrySet()) {
            actionSpinner(actions.body, e.getKey(), e.getValue(),
                    ActionRegistry.defaultForPreference(e.getValue()));
        }
        AppUi.addSection(root, actions);

        TextView note = AppUi.caption(this,
                "位置逻辑：普通手势拖动只临时跟手；只有先执行“移动图标位置”，下一次拖动才会保存新位置。",
                12);
        note.setPadding(AppUi.dp(this, 4), 0, AppUi.dp(this, 4), AppUi.dp(this, 8));
        root.addView(note);

        setContentView(AppUi.scrollPage(this, root));
    }

    @Override protected void onPause() {
        saveHidePackages();
        super.onPause();
    }

    private void saveHidePackages() {
        if (hidePackagesEdit != null && fs != null) {
            fs.prefs().edit().putString(FloatSettings.K_HIDE_PACKAGES,
                    hidePackagesEdit.getText().toString()).apply();
        }
    }

    private void check(LinearLayout parent, String title, String subtitle,
                       String key, boolean current) {
        SwitchMaterial toggle = AppUi.switchRow(this, title, subtitle, current,
                (button, checked) -> fs.prefs().edit().putBoolean(key, checked).apply());
        AppUi.addRow(parent, AppUi.switchContainer(toggle));
    }

    private void fullscreenModeCheck(LinearLayout parent) {
        SwitchMaterial toggle = AppUi.switchRow(this,
                "全屏应用自动隐藏",
                "进入全屏内容时自动隐藏悬浮图标",
                fs.fullscreenHideMode() != 0,
                (button, checked) -> fs.prefs().edit()
                        .putInt(FloatSettings.K_HIDE_FULLSCREEN, checked ? 2 : 0).apply());
        AppUi.addRow(parent, AppUi.switchContainer(toggle));
    }

    private void seek(LinearLayout parent, String label, String key,
                      int min, int max, int current, String suffix) {
        LinearLayout block = AppUi.settingBlock(this);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = AppUi.text(this, label, 14, false);
        TextView value = AppUi.caption(this, current + suffix, 13);
        value.setGravity(Gravity.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(value, new LinearLayout.LayoutParams(-2, -2));
        block.addView(top);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int next = progress + min;
                value.setText(next + suffix);
                fs.prefs().edit().putInt(key, next).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(-1, -2);
        seekLp.topMargin = AppUi.dp(this, 4);
        block.addView(seek, seekLp);
        AppUi.addRow(parent, block);
    }

    private void actionSpinner(LinearLayout parent, String label, String key, String def) {
        String[] labels = new String[ids.length];
        int selected = 0;
        String now = fs.action(key, def);
        for (int i = 0; i < ids.length; i++) {
            labels[i] = ActionId.label(ids[i]);
            if (ids[i].equals(now)) selected = i;
        }
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                                 int position, long id) {
                fs.prefs().edit().putString(key, ids[position]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        addSpinnerRow(parent, label, spinner, false);
    }

    private void styleSpinner(LinearLayout parent) {
        String[] labels = {"蓝色镜头", "深色镜头", "浅色镜头", "自定义图片 / GIF", "多图轮播"};
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
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

    private void lineStyleSpinner(LinearLayout parent) {
        String[] labels = {"圆角", "方形", "圆角增强"};
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
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

    private void ocrEngineSpinner(LinearLayout parent) {
        String[] labels = {
                "自动：Small → Medium → ML Kit",
                "PP-OCRv6 Medium 高精度",
                "PP-OCRv6 Small 平衡",
                "ML Kit 快速"
        };
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
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

    private void addSpinnerRow(LinearLayout parent, String label, Spinner spinner, boolean vertical) {
        LinearLayout block = AppUi.settingBlock(this);
        if (vertical) {
            block.addView(AppUi.text(this, label, 14, false));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, AppUi.dp(this, 50));
            sp.topMargin = AppUi.dp(this, 4);
            block.addView(spinner, sp);
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = AppUi.text(this, label, 14, false);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 0.8f));
            row.addView(spinner, new LinearLayout.LayoutParams(0, AppUi.dp(this, 50), 1.2f));
            block.addView(row);
        }
        AppUi.addRow(parent, block);
    }

    private void ocrModelControls(LinearLayout parent) {
        TextView heading = AppUi.text(this, "本地 PP-OCRv6 模型", 15, true);
        heading.setPadding(AppUi.dp(this, 2), AppUi.dp(this, 6), 0, 0);
        parent.addView(heading);
        TextView note = AppUi.caption(this,
                "模型与 APK 分离，下载一次后可离线使用。Small 约 32 MB；Medium 约 139 MB。", 12);
        note.setPadding(AppUi.dp(this, 2), AppUi.dp(this, 3), 0, AppUi.dp(this, 7));
        parent.addView(note);
        addOcrModelRow(parent, OcrModelManager.SMALL);
        addOcrModelRow(parent, OcrModelManager.MEDIUM);
    }

    private void addOcrModelRow(LinearLayout parent, int model) {
        LinearLayout block = AppUi.settingBlock(this);
        TextView status = AppUi.text(this, OcrModelManager.displayName(model), 14, false);
        block.addView(status);

        LinearLayout buttons = AppUi.buttonRow(this);
        MaterialButton download = AppUi.compactButton(this, "下载 / 更新");
        MaterialButton remove = AppUi.compactButton(this, "删除");
        addWeightedButton(buttons, download, true);
        addWeightedButton(buttons, remove, false);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, -2);
        buttonsLp.topMargin = AppUi.dp(this, 7);
        block.addView(buttons, buttonsLp);
        AppUi.addRow(parent, block);

        Runnable refresh = () -> {
            try {
                boolean ready = OcrModelManager.isReady(this, model);
                long mb = OcrModelManager.installedBytes(this, model) / (1024 * 1024);
                status.setText(OcrModelManager.displayName(model) + " · "
                        + (ready ? "已下载 " + mb + " MB" : "未下载"));
                remove.setEnabled(ready && !OcrModelManager.isDownloading(model));
                download.setEnabled(!OcrModelManager.isDownloading(model));
            } catch (Throwable t) {
                status.setText("本地模型状态读取失败");
                remove.setEnabled(false);
                download.setEnabled(false);
                DiagnosticLog.i(SettingsActivity.this, "OCR_MODEL_UI",
                        "refresh failure=" + t.getClass().getSimpleName());
            }
        };
        refresh.run();

        download.setOnClickListener(v -> {
            download.setEnabled(false);
            remove.setEnabled(false);
            try {
                OcrModelManager.download(this, model, new OcrModelManager.Callback() {
                    @Override public void onProgress(String stage, int percent) {
                        status.setText(OcrModelManager.displayName(model) + " · " + stage + " " + percent + "%");
                    }
                    @Override public void onSuccess() {
                        Toast.makeText(SettingsActivity.this, "模型下载完成", Toast.LENGTH_SHORT).show();
                        refresh.run();
                    }
                    @Override public void onFailure(String message) {
                        Toast.makeText(SettingsActivity.this, "下载失败: " + message, Toast.LENGTH_LONG).show();
                        refresh.run();
                    }
                });
            } catch (Throwable t) {
                Toast.makeText(SettingsActivity.this, "模型下载初始化失败", Toast.LENGTH_LONG).show();
                DiagnosticLog.i(SettingsActivity.this, "OCR_MODEL_UI",
                        "download launch failure=" + t.getClass().getSimpleName());
                refresh.run();
            }
        });
        remove.setOnClickListener(v -> {
            try { OcrModelManager.delete(this, model); } catch (Throwable ignored) {}
            refresh.run();
        });
    }

    private void ocrLanguageMultiSelect(LinearLayout parent) {
        TextView title = AppUi.text(this, "OCR 识别语言", 15, true);
        title.setPadding(AppUi.dp(this, 2), AppUi.dp(this, 7), 0, 0);
        parent.addView(title);
        TextView note = AppUi.caption(this,
                "可多选；简体和繁體共用中文识别器。至少保留一种语言。", 12);
        note.setPadding(AppUi.dp(this, 2), AppUi.dp(this, 3), 0, AppUi.dp(this, 5));
        parent.addView(note);

        Set<String> selected = new HashSet<>(OcrLanguages.get(this));
        parent.addView(ocrLanguageCheck("简体中文", OcrLanguages.ZH_HANS, selected));
        parent.addView(ocrLanguageCheck("繁體中文", OcrLanguages.ZH_HANT, selected));
        parent.addView(ocrLanguageCheck("English", OcrLanguages.ENGLISH, selected));
    }

    private MaterialCheckBox ocrLanguageCheck(String label, String code, Set<String> selected) {
        MaterialCheckBox box = new MaterialCheckBox(this);
        box.setUseMaterialThemeColors(true);
        box.setText(label);
        box.setTextColor(AppUi.textPrimary(this));
        box.setTag(code);
        box.setChecked(selected.contains(code));
        box.setOnCheckedChangeListener((button, checked) -> {
            String lang = String.valueOf(button.getTag());
            if (checked) selected.add(lang); else selected.remove(lang);
            if (selected.isEmpty()) {
                selected.add(lang);
                button.setChecked(true);
                Toast.makeText(SettingsActivity.this,
                        "至少选择一种 OCR 语言", Toast.LENGTH_SHORT).show();
                return;
            }
            OcrLanguages.save(SettingsActivity.this, selected);
            DiagnosticLog.i(SettingsActivity.this, "OCR_LANG", "selected=" + selected);
        });
        return box;
    }

    private void addWeightedButton(LinearLayout row, MaterialButton button, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (first) lp.setMarginEnd(AppUi.dp(this, 6));
        else lp.setMarginStart(AppUi.dp(this, 6));
        row.addView(button, lp);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 401 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                getContentResolver().takePersistableUriPermission(data.getData(),
                        data.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
            fs.prefs().edit().putString(FloatSettings.K_CUSTOM_ICON, data.getData().toString())
                    .putInt(FloatSettings.K_STYLE, 3).apply();
        } else if (requestCode == 402 && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    android.net.Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri == null) continue;
                    uris.add(uri.toString());
                    try {
                        getContentResolver().takePersistableUriPermission(uri,
                                data.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignored) {}
                }
            } else if (data.getData() != null) {
                uris.add(data.getData().toString());
            }
            if (!uris.isEmpty()) {
                fs.prefs().edit().putString(FloatSettings.K_SLIDE_PICS, String.join("|", uris))
                        .putInt(FloatSettings.K_STYLE, 4).apply();
            }
        }
    }
}
