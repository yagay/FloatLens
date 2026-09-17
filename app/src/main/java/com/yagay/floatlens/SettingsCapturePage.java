package com.yagay.floatlens;

import android.widget.LinearLayout;
import android.widget.TextView;

/** Screenshot, result and OCR settings page. */
final class SettingsCapturePage {
    static LinearLayout build(SettingsActivity activity, FloatSettings fs) {
        SettingsPageUi ui = new SettingsPageUi(activity, fs);
        LinearLayout root = AppUi.pageRoot(activity, "截图与 OCR",
                "普通截图 OCR 与圈画 OCR 分开设置。圈画可以独立选择整屏识别引擎和局部校正引擎；只读取冻结截图，不读取 Accessibility / View 文字。Root / LSPosed 增强开关统一放在“高级权限”中。" );

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

        AppUi.Section circleOcr = AppUi.section(activity, "圈画 OCR",
                "整屏识别负责建立冻结画面的完整文字索引；局部校正会在每次 TAP / 划线 / 涂抹时重新识别手势附近区域。两者结果一致时继续使用整屏结果，不一致时采用局部校正结果。" );
        ui.circleFullOcrEngineSpinner(circleOcr.body);
        ui.circleCorrectionEngineSpinner(circleOcr.body);
        AppUi.addSection(root, circleOcr);

        AppUi.Section result = AppUi.section(activity, "OCR / OCR 结果",
                "这里的 OCR 引擎只控制普通截图 OCR，不再控制圈画模式。" );
        ui.check(result.body, "显示原选区图片", null,
                FloatSettings.K_OCR_SHOW_IMAGE, fs.ocrShowImage());
        ui.check(result.body, "显示文字", null,
                FloatSettings.K_OCR_SHOW_TEXT, fs.ocrShowText());
        ui.check(result.body, "结果默认折叠", null,
                FloatSettings.K_OCR_COLLAPSE, fs.ocrCollapse());
        ui.ocrEngineSpinner(result.body);
        AppUi.addSection(root, result);

        AppUi.Section models = AppUi.section(activity, "本地 PP-OCRv6 模型",
                "Tiny 适合整屏快速索引或轻量校正，Small 更均衡，Medium 精度最高但更慢。选择 PP 模型作为圈画整屏或校正引擎前，请先下载对应模型。Tiny 约 7 MB；Small 约 32 MB；Medium 约 139 MB。" );
        try {
            ui.addOcrModelRow(models.body, OcrModelManager.TINY);
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
                "普通截图 OCR 与圈画识别共用语言选择；ML Kit 会按启用语言选择识别器，简体和繁體共用中文识别支持，至少保留一种语言。" );
        ui.addOcrLanguageChecks(languages.body);
        AppUi.addSection(root, languages);
        return root;
    }

    private SettingsCapturePage() {}
}
