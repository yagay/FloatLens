package com.yagay.floatlens;

/** Circle Select OCR policy kept separate from normal screenshot OCR engine selection. */
final class CircleOcrPolicy {
    static final String K_MODE = "circle_ocr_mode_v1";

    static final int MODE_VIEW_PLUS_MLKIT = 0;
    static final int MODE_VIEW_ONLY = 1;
    static final int MODE_MLKIT_ONLY = 2;

    static int mode(FloatSettings settings) {
        if (settings == null) return MODE_VIEW_PLUS_MLKIT;
        Object raw = settings.prefs().getAll().get(K_MODE);
        if (raw instanceof Number n) return clamp(n.intValue());
        if (raw instanceof String s) {
            try { return clamp(Integer.parseInt(s.trim())); }
            catch (Throwable ignored) {}
        }
        return MODE_VIEW_PLUS_MLKIT;
    }

    static boolean viewEnabled(FloatSettings settings) {
        return mode(settings) != MODE_MLKIT_ONLY;
    }

    static boolean mlKitEnabled(FloatSettings settings) {
        return mode(settings) != MODE_VIEW_ONLY;
    }

    static boolean hybrid(FloatSettings settings) {
        return mode(settings) == MODE_VIEW_PLUS_MLKIT;
    }

    static String summary(int mode) {
        return switch (clamp(mode)) {
            case MODE_VIEW_ONLY -> "仅使用 Accessibility / View 文字，不运行视觉 OCR";
            case MODE_MLKIT_ONLY -> "仅使用 ML Kit 视觉文字识别，不读取 View 文字";
            default -> "View 文字优先；ML Kit 补盲并辅助几何，不使用 PP-OCR";
        };
    }

    private static int clamp(int value) {
        if (value == MODE_VIEW_ONLY) return MODE_VIEW_ONLY;
        if (value == MODE_MLKIT_ONLY) return MODE_MLKIT_ONLY;
        return MODE_VIEW_PLUS_MLKIT;
    }

    private CircleOcrPolicy() {}
}
