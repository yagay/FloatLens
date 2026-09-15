package com.yagay.floatlens;

import android.widget.LinearLayout;
import android.widget.TextView;

/** Screenshot, result and OCR settings page. */
final class SettingsCapturePage {
    static LinearLayout build(SettingsActivity activity, FloatSettings fs) {
        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        LinearLayout root = AppUi.pageRoot(activity, "截图与 OCR",
                "截图来源、结果显示和 OCR 模型分开管理。Root / LSPosed 增强开关统一放在“高级权限”中。" );

        AppUi.Section capture = AppUi.section(activity, "截图",
                "悬浮拖选采用已验证的 FV 时序：移动时红色探针，稳定约 400 ms 后进入黄色 Direct 状态；不会对每个 View 再重复等待。" );
        ui.check(capture.body, "截图保留悬浮图标", null,
                FloatSettings.K_KEEP_IN_SCREENSHOT, fs.keepInScreenshot());
        ui.check(capture.body, "截图保留状态栏", null,
                FloatSettings.K_KEEP_STATUS_BAR, fs.keepStatusBarInScreenshot());
        ui.check(capture.body, "优先无障碍截图",
                "普通截图后端；Root / LSPosed 增强截图在“高级权限”中单独配置",
                FloatSettings.K_ACCESSIBILITY_SCREENSHOT, fs.accessibilityScreenshot());
        AppUi.addSection(root, capture);

        AppUi.Section result = AppUi.section(activity, "OCR 结果", null);
        ui.check(result.body, "显示原选区图片", null,
                FloatSettings.K_OCR_SHOW_IMAGE, fs.ocrShowImage());
        ui.check(result.body, "显示文字", null,
                FloatSettings.K_OCR_SHOW_TEXT, fs.ocrShowText());
        ui.check(result.body, "结果默认折叠", null,
                FloatSettings.K_OCR_COLLAPSE, fs.ocrCollapse());
        ui.ocrEngineSpinner(result.body);
        AppUi.addSection(root, result);

        AppUi.Section models = AppUi.section(activity, "本地 PP-OCRv6 模型",
                "模型与 APK 分离。Small 约 32 MB；Medium 约 139 MB。" );
        try {
            ui.addOcrModelRow(models.body, OcrModelManager.SMALL);
            ui.addOcrModelRow(models.body, OcrModelManager.MEDIUM);
        } catch (Throwable t) {
            DiagnosticLog.i(activity, "OCR_MODEL_UI",
                    "init failure=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()));
            TextView err = AppUi.caption(activity,
                    "本地模型管理暂不可用；ML Kit 仍可正常使用。", 13);
            LinearLayout row = AppUi.baseRow(activity);
            row.addView(err, new LinearLayout.LayoutParams(-1, -2));
            AppUi.addRow(models.body, row);
        }
        AppUi.addSection(root, models);

        AppUi.Section languages = AppUi.section(activity, "识别语言",
                "可多选；简体和繁體共用中文识别器，至少保留一种语言。" );
        ui.addOcrLanguageChecks(languages.body);
        AppUi.addSection(root, languages);
        return root;
    }

    private SettingsCapturePage() {}
}
