package com.yagay.floatlens;

import android.widget.LinearLayout;
import android.widget.TextView;

/** Screenshot, result and OCR settings page. */
final class SettingsCapturePage {
    static LinearLayout build(SettingsActivity activity, FloatSettings fs) {
        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        LinearLayout root = AppUi.pageRoot(activity, "截图与 OCR",
                "普通截图 OCR 与圈画文字识别共用下面选择的 OCR 引擎和识别语言。圈画首次文字手势会识别整张冻结截图，并在当前圈画工作区内复用完整识别结果；不读取 Accessibility / View 文字。Root / LSPosed 增强开关统一放在“高级权限”中。" );

        AppUi.Section capture = AppUi.section(activity, "截图",
                "状态栏与导航栏范围同时作用于普通截图、OCR 区域截图和圈画模式。" );
        ui.check(capture.body, "截图保留悬浮图标", null,
                FloatSettings.K_KEEP_IN_SCREENSHOT, fs.keepInScreenshot());
        ui.check(capture.body, "截取状态栏",
                "关闭后普通截图和圈画都会排除当前可见的状态栏区域",
                FloatSettings.K_KEEP_STATUS_BAR, fs.keepStatusBarInScreenshot());
        ui.check(capture.body, "截取按键导航栏",
                "关闭后普通截图和圈画都会排除导航栏；开启后两者都会保留导航栏区域",
                CaptureSystemBarsPolicy.K_KEEP_NAVIGATION_BAR,
                CaptureSystemBarsPolicy.keepNavigationBar(activity));
        ui.check(capture.body, "优先无障碍截图",
                "普通截图后端；Root / LSPosed 增强截图在“高级权限”中单独配置",
                FloatSettings.K_ACCESSIBILITY_SCREENSHOT, fs.accessibilityScreenshot());
        AppUi.addSection(root, capture);

        AppUi.Section circleBorder = AppUi.section(activity, "圈画激活提示",
                "边框圆角会根据当前设备和屏幕方向自动适配；它只用于提示激活状态，FloatLens 截图时会自动隐藏。" );
        CircleBorderSettingsUi.add(activity, fs, circleBorder.body);
        AppUi.addSection(root, circleBorder);

        AppUi.Section result = AppUi.section(activity, "OCR / OCR 结果",
                "下面选择的 OCR 引擎同时控制普通截图 OCR 和圈画文字识别。圈画对整张冻结截图识别一次并缓存结果，TAP / 划线 / 涂抹只负责从完整识别结果中精确选择文字。" );
        ui.check(result.body, "显示原选区图片", null,
                FloatSettings.K_OCR_SHOW_IMAGE, fs.ocrShowImage());
        ui.check(result.body, "显示文字", null,
                FloatSettings.K_OCR_SHOW_TEXT, fs.ocrShowText());
        ui.check(result.body, "结果默认折叠", null,
                FloatSettings.K_OCR_COLLAPSE, fs.ocrCollapse());
        ui.ocrEngineSpinner(result.body);
        AppUi.addSection(root, result);

        AppUi.Section models = AppUi.section(activity, "本地 PP-OCRv6 模型",
                "选择 PP-OCRv6 Medium / Small 时，普通截图与圈画都会使用对应本地模型；选择“自动”时按 Small → Medium → ML Kit 的现有策略选择可用引擎。Small 约 32 MB；Medium 约 139 MB。" );
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
                "普通截图 OCR 与圈画文字识别共用语言选择；ML Kit 会按启用语言选择识别器，简体和繁體共用中文识别支持，至少保留一种语言。" );
        ui.addOcrLanguageChecks(languages.body);
        AppUi.addSection(root, languages);
        return root;
    }

    private SettingsCapturePage() {}
}
