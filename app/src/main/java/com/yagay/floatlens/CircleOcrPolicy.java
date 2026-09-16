package com.yagay.floatlens;

/**
 * Circle Select OCR policy.
 *
 * Circle Select now uses one frozen screenshot as the only OCR source. Legacy mode constants and
 * preference key are kept for source/settings compatibility, but View text is no longer part of
 * the recognition path.
 */
final class CircleOcrPolicy {
    static final String K_MODE = "circle_ocr_mode_v1";

    static final int MODE_VIEW_PLUS_MLKIT = 0;
    static final int MODE_VIEW_ONLY = 1;
    static final int MODE_MLKIT_ONLY = 2;

    static int mode(FloatSettings settings) {
        return MODE_MLKIT_ONLY;
    }

    static boolean viewEnabled(FloatSettings settings) {
        return false;
    }

    static boolean mlKitEnabled(FloatSettings settings) {
        return true;
    }

    static boolean hybrid(FloatSettings settings) {
        return false;
    }

    static String summary(int mode) {
        return "统一使用冻结截图进行 ML Kit 文字识别；不读取 Accessibility / View 文字";
    }

    private CircleOcrPolicy() {}
}
