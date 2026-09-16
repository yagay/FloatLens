package com.yagay.floatlens;

/**
 * Circle Select OCR policy compatibility shim.
 *
 * Circle Select now uses one frozen screenshot and the normal screenshot OcrEngine. Legacy mode
 * constants and preference key remain only so older settings/builds do not break; View text is no
 * longer part of Circle recognition.
 */
final class CircleOcrPolicy {
    static final String K_MODE = "circle_ocr_mode_v1";

    static final int MODE_VIEW_PLUS_MLKIT = 0;
    static final int MODE_VIEW_ONLY = 1;
    static final int MODE_MLKIT_ONLY = 2;
    static final int MODE_SCREENSHOT_OCR = MODE_MLKIT_ONLY;

    static int mode(FloatSettings settings) {
        return MODE_SCREENSHOT_OCR;
    }

    static boolean viewEnabled(FloatSettings settings) {
        return false;
    }

    /** Visual OCR is always available through the shared screenshot OcrEngine. */
    static boolean mlKitEnabled(FloatSettings settings) {
        return true;
    }

    static boolean hybrid(FloatSettings settings) {
        return false;
    }

    static String summary(int mode) {
        return "统一使用冻结截图，并复用普通截图 OCR 引擎、模型与语言设置；不读取 Accessibility / View 文字";
    }

    private CircleOcrPolicy() {}
}
