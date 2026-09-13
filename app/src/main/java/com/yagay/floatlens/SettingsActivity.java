package com.yagay.floatlens;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.util.LinkedHashMap;
import java.util.Map;

public class SettingsActivity extends AppCompatActivity {
    private FloatSettings fs;
    private EditText hidePackagesEdit;
    private final String[] ids = ActionId.availableIds();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        fs = new FloatSettings(this);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20); root.setPadding(pad, pad, pad, dp(36));
        sv.addView(root);

        title(root, "悬浮图标参数");
        seek(root, "图标透明度 / icon_alpha", FloatSettings.K_ALPHA, 10, 100, Math.round(fs.alpha() * 100), "%");
        seek(root, "图标大小 / float_icon_real_size", FloatSettings.K_SIZE, 24, 96, fs.sizeDp(), " dp");
        seek(root, "图标可见比例 / float_icon_show_percentage", FloatSettings.K_SHOW_PERCENT, 10, 100, fs.showPercentage(), "%");
        styleSpinner(root);
        Button customIcon = new Button(this); customIcon.setText("选择自定义/GIF图标 / float_icon_custom_pic"); customIcon.setOnClickListener(v -> { android.content.Intent in = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT); in.setType("image/*"); in.addCategory(android.content.Intent.CATEGORY_OPENABLE); startActivityForResult(in, 401); }); root.addView(customIcon);
        Button slideIcon = new Button(this); slideIcon.setText("选择多张轮播图标 / float_icon_slide_pics"); slideIcon.setOnClickListener(v -> { android.content.Intent in = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT); in.setType("image/*"); in.addCategory(android.content.Intent.CATEGORY_OPENABLE); in.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(in, 402); }); root.addView(slideIcon);
        seek(root, "轮播间隔 / float_icon_slide_interval", FloatSettings.K_SLIDE_INTERVAL, 500, 10000, fs.slideIntervalMs(), " ms");
        check(root, "左右两侧同时显示 / float_on_both_side", FloatSettings.K_BOTH_SIDE, fs.bothSide());
        check(root, "锁屏仍显示 / icon_show_lock_screen", FloatSettings.K_SHOW_ON_LOCK, fs.showOnLock());
        check(root, "自动吸边", FloatSettings.K_SNAP, fs.snap());
        fullscreenModeCheck(root);
        check(root, "隐藏后允许从屏幕边缘滑入唤回 / hide_main_icon_swipe_gesture", FloatSettings.K_HIDE_MAIN_SWIPE, fs.prefs().getBoolean(FloatSettings.K_HIDE_MAIN_SWIPE, true));
        check(root, "单击时点击悬浮图标下方屏幕 / action_click_screen_under_icon", FloatSettings.K_CLICK_UNDER, fs.clickScreenUnderIcon());

        title(root, "手势参数");
        seek(root, "长按时间 / icon_long_press_time", FloatSettings.K_LONG_PRESS, 150, 1000, fs.longPressMs(), " ms");
        seek(root, "双击检测 / icon_db_click_detect_time", FloatSettings.K_DOUBLE_TAP, 150, 600, fs.doubleTapMs(), " ms");
        seek(root, "短按最大持续时间（逆向观察约150ms）", FloatSettings.K_TAP_MAX_MS, 80, 400, fs.tapMaxMs(), " ms");
        seek(root, "手势启动距离", FloatSettings.K_GESTURE_START_DISTANCE, 10, 80, fs.gestureStartDistance(), " dp");
        seek(root, "下滑长短分界 / down_swipe_short_distance_2", FloatSettings.K_DOWN_SHORT_DISTANCE, 50, 600, fs.downShortDistance(), " dp基准");
        seek(root, "侧滑长短分界 / side_swipe_short_distance_2", FloatSettings.K_SIDE_SHORT_DISTANCE, 50, 700, fs.sideShortDistance(), " dp基准");
        check(root, "震动反馈 / vibration_fb", FloatSettings.K_VIBRATE, fs.vibrate());
        check(root, "显示全屏手势轨迹 / showGestureTracking", FloatSettings.K_TRACK, fs.track());
        seek(root, "轨迹透明度 / float_line_alpha", FloatSettings.K_LINE_ALPHA, 10, 100, fs.lineAlpha(), "%");
        seek(root, "轨迹宽度 / float_line_width_2", FloatSettings.K_LINE_WIDTH, 1, 24, fs.lineWidthDp(), " dp");
        check(root, "轨迹颜色渐变 / float_line_color_gradient", FloatSettings.K_LINE_GRADIENT, fs.lineGradient());
        lineStyleSpinner(root);
        EditText lineColors = new EditText(this); lineColors.setHint("轨迹颜色，如 #FFFFFF,#42A5F5"); lineColors.setText(fs.lineColors()); root.addView(lineColors); lineColors.setOnFocusChangeListener((v, has)->{ if(!has) fs.prefs().edit().putString(FloatSettings.K_LINE_COLORS, lineColors.getText().toString()).apply(); });

        title(root, "截图与 OCR");
        seek(root, "高亮 View 停留确认 / view_capture_dwell_ms", FloatSettings.K_VIEW_CAPTURE_DWELL, 200, 2000, fs.viewCaptureDwellMs(), " ms");
        check(root, "截图保留悬浮图标 / screen_capture_keep_icon", FloatSettings.K_KEEP_IN_SCREENSHOT, fs.keepInScreenshot());
        check(root, "截图保留状态栏 / screen_capture_keep_noti_bar", FloatSettings.K_KEEP_STATUS_BAR, fs.keepStatusBarInScreenshot());
        check(root, "优先无障碍截图 / screen_capture_accessibility", FloatSettings.K_ACCESSIBILITY_SCREENSHOT, fs.accessibilityScreenshot());
        check(root, "Root 截图作为增强/后备", FloatSettings.K_ROOT_SCREENSHOT, fs.rootScreenshot());
        check(root, "OCR 显示原选区图片 / ocr_result_show_image", FloatSettings.K_OCR_SHOW_IMAGE, fs.ocrShowImage());
        check(root, "OCR 显示文字 / ocr_result_show_text", FloatSettings.K_OCR_SHOW_TEXT, fs.ocrShowText());
        check(root, "OCR 结果折叠 / ocr_result_show_text_collapse", FloatSettings.K_OCR_COLLAPSE, fs.ocrCollapse());
        ocrEngineSpinner(root);
        try {
            ocrModelControls(root);
        } catch (Throwable t) {
            DiagnosticLog.i(this, "OCR_MODEL_UI", "init failure=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()));
            TextView err = new TextView(this);
            err.setText("本地 OCR 模型管理暂不可用；ML Kit 仍可正常使用。");
            err.setPadding(0, dp(8), 0, dp(8));
            root.addView(err);
        }
        ocrLanguageMultiSelect(root);

        title(root, "环境与显示");
        check(root, "键盘出现时避让悬浮图标", FloatSettings.K_IME_AVOID, fs.imeAvoid());
        check(root, "Quick Move / 智能屏幕入口启用", FloatSettings.K_QUICK_MOVE, fs.quickMoveEnabled());
        check(root, "FL 诊断日志（用于和 fooView 真机行为校准）", FloatSettings.K_DIAGNOSTIC, fs.diagnosticLogging());
        TextView pkgLabel = new TextView(this); pkgLabel.setText("按应用隐藏（包名，逗号/空格/换行分隔）"); root.addView(pkgLabel);
        hidePackagesEdit = new EditText(this); hidePackagesEdit.setText(fs.prefs().getString(FloatSettings.K_HIDE_PACKAGES, "")); hidePackagesEdit.setHint("例如 com.example.game com.example.bank"); root.addView(hidePackagesEdit);
        hidePackagesEdit.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) saveHidePackages(); });

        title(root, "操作映射");
        Map<String, String> map = new LinkedHashMap<>();
        map.put("单击 / action_click", FloatSettings.K_ACTION_CLICK);
        map.put("双击 / action_db_click", FloatSettings.K_ACTION_DOUBLE);
        map.put("长按 / action_long_press", FloatSettings.K_ACTION_LONG);
        map.put("圈选识别 / action_recognize", FloatSettings.K_ACTION_RECOGNIZE);
        map.put("上滑 / gesture_up", FloatSettings.K_ACTION_UP);
        map.put("下滑-短 / gesture_down_short", FloatSettings.K_ACTION_DOWN_SHORT);
        map.put("下滑-长 / gesture_down_long", FloatSettings.K_ACTION_DOWN_LONG);
        map.put("侧滑-短 / gesture_side_short", FloatSettings.K_ACTION_SIDE_SHORT);
        map.put("侧滑-长 / gesture_side_long", FloatSettings.K_ACTION_SIDE_LONG);
        for (var e : map.entrySet()) spinner(root, e.getKey(), e.getValue(), ActionRegistry.defaultForPreference(e.getValue()));

        TextView note = new TextView(this);
        note.setText("FL 位置逻辑（参照 FV）：普通手势时图标只临时跟手，松手恢复原来的贴边位置；只有先触发“移动图标位置”动作，下一次拖动才保存新位置。高亮 View 连续停留达到设定时间后锁定，松手截取整个高亮 View。");
        note.setPadding(0, dp(28), 0, 0);
        root.addView(note);
        setContentView(sv);
    }

    @Override protected void onPause() { saveHidePackages(); super.onPause(); }

    private void saveHidePackages() {
        if (hidePackagesEdit != null && fs != null) fs.prefs().edit().putString(FloatSettings.K_HIDE_PACKAGES, hidePackagesEdit.getText().toString()).apply();
    }

    private void title(LinearLayout r, String s) { TextView t = new TextView(this); t.setText(s); t.setTextSize(20); t.setPadding(0, dp(24), 0, dp(10)); r.addView(t); }
    private void check(LinearLayout r, String label, String key, boolean def) {
        CheckBox c = new CheckBox(this); c.setText(label); c.setChecked(def);
        c.setOnCheckedChangeListener((b, v) -> fs.prefs().edit().putBoolean(key, v).apply()); r.addView(c);
    }
    private void fullscreenModeCheck(LinearLayout r) {
        CheckBox c=new CheckBox(this); c.setText("全屏应用自动隐藏 / hide_icon_when_full_screen（Int模式）"); c.setChecked(fs.fullscreenHideMode()!=0);
        c.setOnCheckedChangeListener((b,v)->fs.prefs().edit().putInt(FloatSettings.K_HIDE_FULLSCREEN,v?2:0).apply()); r.addView(c);
    }
    private void seek(LinearLayout r, String label, String key, int min, int max, int cur, String suffix) {
        TextView t = new TextView(this); t.setText(label + ": " + cur + suffix); r.addView(t);
        SeekBar s = new SeekBar(this); s.setMax(max - min); s.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) { if (!fromUser) return; int v = p + min; t.setText(label + ": " + v + suffix); fs.prefs().edit().putInt(key, v).apply(); }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        r.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    private void spinner(LinearLayout r, String label, String key, String def) {
        TextView t = new TextView(this); t.setText(label); r.addView(t);
        Spinner s = new Spinner(this); String[] labels = new String[ids.length]; int sel = 0; String now = fs.action(key, def);
        for (int i = 0; i < ids.length; i++) { labels[i] = ActionId.label(ids[i]); if (ids[i].equals(now)) sel = i; }
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels); s.setAdapter(a); s.setSelection(sel);
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) { fs.prefs().edit().putString(key, ids[pos]).apply(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        r.addView(s);
    }
    private void styleSpinner(LinearLayout r) {
        TextView t = new TextView(this); t.setText("图标样式 / float_icon_style"); r.addView(t);
        String[] labels = {"蓝色镜头", "深色镜头", "浅色镜头", "自定义图片/GIF", "多图轮播"};
        Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels)); s.setSelection(fs.style());
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) { fs.prefs().edit().putInt(FloatSettings.K_STYLE, pos).apply(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        r.addView(s);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 401 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try { getContentResolver().takePersistableUriPermission(data.getData(), data.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) {}
            fs.prefs().edit().putString(FloatSettings.K_CUSTOM_ICON, data.getData().toString()).putInt(FloatSettings.K_STYLE, 3).apply();
        } else if (requestCode == 402 && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) for (int i=0;i<data.getClipData().getItemCount();i++) { android.net.Uri u=data.getClipData().getItemAt(i).getUri(); if(u!=null){uris.add(u.toString());try{getContentResolver().takePersistableUriPermission(u,data.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Throwable ignored){}} }
            else if (data.getData()!=null) uris.add(data.getData().toString());
            if(!uris.isEmpty()) fs.prefs().edit().putString(FloatSettings.K_SLIDE_PICS, String.join("|",uris)).putInt(FloatSettings.K_STYLE,4).apply();
        }
    }

    private void lineStyleSpinner(LinearLayout r) {
        TextView t=new TextView(this); t.setText("轨迹样式 / float_line_style"); r.addView(t);
        String[] labels={"圆角", "方形", "圆角增强"}; Spinner s=new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels)); s.setSelection(fs.lineStyle());
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){ public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v,int pos,long id){fs.prefs().edit().putInt(FloatSettings.K_LINE_STYLE,pos).apply();} public void onNothingSelected(android.widget.AdapterView<?> p){} }); r.addView(s);
    }
    private void ocrEngineSpinner(LinearLayout r) {
        TextView t = new TextView(this); t.setText("OCR 引擎 / ocr_engine_mode_v2"); r.addView(t);
        String[] labels = {
                "自动：Small 优先，低置信度升级 Medium，失败回退 ML Kit",
                "PP-OCRv6 Medium 高精度",
                "PP-OCRv6 Small 平衡",
                "ML Kit 快速"
        };
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        s.setSelection(fs.ocrEngineMode());
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                fs.prefs().edit().putInt(FloatSettings.K_OCR_ENGINE, pos).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        r.addView(s);
    }

    private void ocrModelControls(LinearLayout root) {
        TextView note = new TextView(this);
        note.setText("本地模型与 APK 分离，下载一次后可离线使用。Small 约 32 MB；Medium 约 139 MB。");
        note.setPadding(0, dp(8), 0, dp(6)); root.addView(note);
        addOcrModelRow(root, OcrModelManager.SMALL);
        addOcrModelRow(root, OcrModelManager.MEDIUM);
    }

    private void addOcrModelRow(LinearLayout root, int model) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        TextView status = new TextView(this); row.addView(status);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button download = new Button(this); download.setText("下载/更新");
        Button remove = new Button(this); remove.setText("删除");
        buttons.addView(download, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(remove, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(buttons); root.addView(row);
        Runnable refresh = () -> {
            try {
                boolean ready = OcrModelManager.isReady(this, model);
                long mb = OcrModelManager.installedBytes(this, model) / (1024 * 1024);
                status.setText(OcrModelManager.displayName(model) + ": " + (ready ? "已下载 " + mb + " MB" : "未下载"));
                remove.setEnabled(ready && !OcrModelManager.isDownloading(model));
                download.setEnabled(!OcrModelManager.isDownloading(model));
            } catch (Throwable t) {
                status.setText("本地模型状态读取失败");
                remove.setEnabled(false);
                download.setEnabled(false);
                DiagnosticLog.i(SettingsActivity.this, "OCR_MODEL_UI", "refresh failure=" + t.getClass().getSimpleName());
            }
        };
        refresh.run();
        download.setOnClickListener(v -> {
            download.setEnabled(false); remove.setEnabled(false);
            try {
                OcrModelManager.download(this, model, new OcrModelManager.Callback() {
                    public void onProgress(String stage, int percent) { status.setText(OcrModelManager.displayName(model) + ": " + stage + " " + percent + "%"); }
                    public void onSuccess() { Toast.makeText(SettingsActivity.this, "模型下载完成", Toast.LENGTH_SHORT).show(); refresh.run(); }
                    public void onFailure(String message) { Toast.makeText(SettingsActivity.this, "下载失败: " + message, Toast.LENGTH_LONG).show(); refresh.run(); }
                });
            } catch (Throwable t) {
                Toast.makeText(SettingsActivity.this, "模型下载初始化失败", Toast.LENGTH_LONG).show();
                DiagnosticLog.i(SettingsActivity.this, "OCR_MODEL_UI", "download launch failure=" + t.getClass().getSimpleName());
                refresh.run();
            }
        });
        remove.setOnClickListener(v -> { try { OcrModelManager.delete(this, model); } catch (Throwable ignored) {} refresh.run(); });
    }

    private void ocrLanguageMultiSelect(LinearLayout r) {
        TextView title = new TextView(this);
        title.setText("OCR 识别语言（可多选） / ocr_languages_v2");
        r.addView(title);

        TextView note = new TextView(this);
        note.setText("简体中文和繁體中文共用中文识别器；PP-OCRv6 使用同一套多语言模型。至少选择一种语言。");
        note.setPadding(0, dp(4), 0, dp(4));
        r.addView(note);

        java.util.Set<String> selected = new java.util.HashSet<>(OcrLanguages.get(this));
        CheckBox simplified = ocrLanguageCheck("简体中文", OcrLanguages.ZH_HANS, selected);
        CheckBox traditional = ocrLanguageCheck("繁體中文", OcrLanguages.ZH_HANT, selected);
        CheckBox english = ocrLanguageCheck("English", OcrLanguages.ENGLISH, selected);
        r.addView(simplified);
        r.addView(traditional);
        r.addView(english);
    }

    private CheckBox ocrLanguageCheck(String label, String code, java.util.Set<String> selected) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTag(code);
        box.setChecked(selected.contains(code));
        box.setOnCheckedChangeListener((button, checked) -> {
            String lang = String.valueOf(button.getTag());
            if (checked) selected.add(lang); else selected.remove(lang);
            if (selected.isEmpty()) {
                selected.add(lang);
                button.setChecked(true);
                Toast.makeText(SettingsActivity.this, "至少选择一种 OCR 语言", Toast.LENGTH_SHORT).show();
                return;
            }
            OcrLanguages.save(SettingsActivity.this, selected);
            DiagnosticLog.i(SettingsActivity.this, "OCR_LANG", "selected=" + selected);
        });
        return box;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
