package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/** Single mutable business state for screenshot, View and OCR results. */
final class ResultSession {
    enum Mode { SCREENSHOT, VIEW_TEXT, VIEW_IMAGE, OCR }

    private final Mode originMode;
    private Mode mode;
    private final Bitmap image;
    private final Rect anchor;
    private String text;
    private List<String> blocks;
    private final String meta;

    private ResultSession(Mode mode, Bitmap image, Rect anchor,
                          String text, List<String> blocks, String meta) {
        this.originMode = mode;
        this.mode = mode;
        this.image = image;
        this.anchor = anchor == null ? null : new Rect(anchor);
        this.text = safe(text);
        this.blocks = safeBlocks(blocks);
        this.meta = safe(meta);
    }

    static ResultSession screenshot(Bitmap image, Rect anchor) {
        return new ResultSession(Mode.SCREENSHOT, image, anchor, "", List.of(), "");
    }

    static ResultSession viewText(String text, Bitmap image, Rect anchor) {
        return new ResultSession(Mode.VIEW_TEXT, image, anchor, text, List.of(), "");
    }

    static ResultSession viewImage(Bitmap image, ViewNodeCandidate view, Rect anchor) {
        return new ResultSession(Mode.VIEW_IMAGE, image, anchor, "", List.of(), buildViewMeta(view));
    }

    static ResultSession ocr(String text, List<String> blocks, Bitmap image, Rect anchor) {
        return new ResultSession(Mode.OCR, image, anchor, text, blocks, "");
    }

    void applyOcr(String text, List<String> blocks) {
        mode = Mode.OCR;
        this.text = safe(text).trim();
        this.blocks = safeBlocks(blocks);
    }

    Mode originMode() { return originMode; }
    Mode mode() { return mode; }
    Bitmap image() { return image; }
    Rect anchor() { return anchor == null ? null : new Rect(anchor); }
    String text() { return text; }
    List<String> blocks() { return new ArrayList<>(blocks); }
    String meta() { return meta; }

    boolean hasImage() { return image != null && !image.isRecycled(); }

    String displayText() {
        return switch (mode) {
            case VIEW_TEXT -> text;
            case VIEW_IMAGE -> meta;
            case OCR -> text.isBlank() ? "未识别到文字" : text;
            case SCREENSHOT -> "";
        };
    }

    boolean hasText() { return !displayText().isBlank(); }
    boolean canOcr() { return hasImage(); }
    boolean canSave() { return hasImage(); }
    boolean canCopy() { return hasText(); }

    boolean showImage(FloatSettings settings) {
        if (!hasImage()) return false;
        return mode != Mode.OCR || settings == null || settings.ocrShowImage();
    }

    boolean showText(FloatSettings settings) {
        if (mode == Mode.SCREENSHOT) return false;
        if (mode == Mode.OCR && settings != null && !settings.ocrShowText()) return false;
        return hasText();
    }

    String title() {
        if (mode != Mode.OCR) {
            return switch (mode) {
                case SCREENSHOT -> "区域截图";
                case VIEW_TEXT -> "View 内容";
                case VIEW_IMAGE -> "View / 图标";
                case OCR -> "OCR 结果";
            };
        }
        return switch (originMode) {
            case SCREENSHOT -> "区域截图 · OCR";
            case VIEW_TEXT, VIEW_IMAGE -> "View 内容 · OCR";
            case OCR -> "OCR 结果";
        };
    }

    boolean notifyCircleOnClose() { return originMode == Mode.OCR; }

    private static String buildViewMeta(ViewNodeCandidate view) {
        if (view == null) return "图片 View";
        StringBuilder meta = new StringBuilder(view.label());
        if (!view.className().isBlank()) meta.append('\n').append(view.className());
        if (!view.viewId().isBlank()) meta.append('\n').append(view.viewId());
        meta.append('\n').append(view.bounds().toShortString());
        return meta.toString();
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static List<String> safeBlocks(List<String> value) {
        return value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
