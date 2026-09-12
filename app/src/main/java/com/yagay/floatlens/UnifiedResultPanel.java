package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;

/**
 * The single visual implementation for every FloatLens result surface.
 *
 * Window ownership is intentionally outside this class: screenshot results may be hosted in a
 * TYPE_ACCESSIBILITY_OVERLAY while selectable text is hosted in ResultActivity. Both hosts render
 * this exact panel, so title/image/text/actions/layout changes are implemented once.
 */
final class UnifiedResultPanel {
    enum Mode { SCREENSHOT, VIEW_TEXT, VIEW_IMAGE, OCR }

    private final Context context;
    private final Mode mode;
    private final Bitmap image;
    private final LinearLayout root;
    private final android.widget.TextView title;
    private final ImageView imageView;
    private final LinearLayout textPanel;
    private final TextSelectionSurface selection;
    private final Button ocrButton;
    private final Button copyButton;
    private final Button saveButton;
    private final Button closeButton;
    private final int width;
    private final int height;
    private final int contentBudget;

    UnifiedResultPanel(Context context, Mode mode, Bitmap image, String text, String meta,
                       boolean allowNativeSelection) {
        this.context = context;
        this.mode = mode;
        this.image = image;

        Rect usable = ResultUi.usableBounds(context);
        width = ResultUi.standardWidth(context, usable);
        int maxH = ResultUi.standardMaxHeight(context, usable);
        int titleH = ResultUi.dp(context, ResultUi.TITLE_H_DP);
        int actionsH = ResultUi.dp(context, ResultUi.ACTION_H_DP);
        contentBudget = Math.max(ResultUi.dp(context, 90),
                maxH - titleH - actionsH - ResultUi.dp(context, ResultUi.ROOT_VPAD_DP));

        FloatSettings settings = new FloatSettings(context.getApplicationContext());
        boolean showText = initialTextVisible(mode, settings);
        boolean showImage = image != null && !image.isRecycled()
                && (mode != Mode.OCR || settings.ocrShowImage());

        root = ResultUi.box(context);
        title = ResultUi.heading(context, titleForMode(mode));
        root.addView(title, new LinearLayout.LayoutParams(-1, titleH));

        int imageH = 0;
        if (showImage) {
            int cap = mode == Mode.VIEW_IMAGE ? ResultUi.dp(context, 190) : ResultUi.dp(context, 135);
            if (mode == Mode.SCREENSHOT) cap = contentBudget;
            imageH = ResultUi.imageHeight(context, image, width, cap);
            imageView = new ImageView(context);
            imageView.setImageBitmap(image);
            imageView.setAdjustViewBounds(false);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ImageShareUtils.attachLongPressShare(context, imageView, image);
            root.addView(imageView, new LinearLayout.LayoutParams(-1, imageH));
        } else {
            imageView = null;
        }

        if (allowNativeSelection) {
            textPanel = new LinearLayout(context);
            textPanel.setOrientation(LinearLayout.VERTICAL);
            textPanel.setPadding(0, ResultUi.dp(context, 4), 0, ResultUi.dp(context, 4));
            selection = new TextSelectionSurface(context);
            textPanel.addView(selection, new LinearLayout.LayoutParams(-1, 0, 1f));
            textPanel.setVisibility(showText ? View.VISIBLE : View.GONE);
            root.addView(textPanel, new LinearLayout.LayoutParams(-1, 0));
            bindSelectionMenu();
        } else {
            textPanel = null;
            selection = null;
        }

        int textH = showText && selection != null
                ? Math.max(ResultUi.dp(context, 96), contentBudget - imageH) : 0;
        if (showText && textPanel != null) {
            textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, textH));
            selection.setText(initialText(mode, text, meta));
        }

        LinearLayout actions = ResultUi.actionRow(context);
        boolean canOcr = image != null && !image.isRecycled() && mode != Mode.OCR;
        boolean canSave = image != null && !image.isRecycled()
                && (mode == Mode.SCREENSHOT || mode == Mode.VIEW_IMAGE);

        if (canOcr) {
            ocrButton = ResultUi.button(context, "OCR");
            actions.addView(ocrButton, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            ocrButton = null;
        }

        if (showText || (canOcr && allowNativeSelection)) {
            copyButton = ResultUi.button(context, "复制全部");
            copyButton.setVisibility(showText ? View.VISIBLE : View.GONE);
            actions.addView(copyButton, new LinearLayout.LayoutParams(0, -1, 1));
            copyButton.setOnClickListener(v -> copyAll());
        } else {
            copyButton = null;
        }

        if (canSave) {
            saveButton = ResultUi.button(context, "保存图片");
            actions.addView(saveButton, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            saveButton = null;
        }

        closeButton = ResultUi.button(context, "关闭");
        actions.addView(closeButton, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        if (mode == Mode.SCREENSHOT) {
            height = Math.max(ResultUi.dp(context, 158), Math.min(maxH,
                    titleH + actionsH + imageH + ResultUi.dp(context, ResultUi.ROOT_VPAD_DP)));
        } else {
            height = Math.max(ResultUi.dp(context, 158), Math.min(maxH,
                    titleH + actionsH + imageH + textH + ResultUi.dp(context, ResultUi.ROOT_VPAD_DP)));
        }
    }

    LinearLayout root() { return root; }
    int width() { return width; }
    int height() { return height; }
    TextSelectionSurface selection() { return selection; }

    void bindActions(Runnable onOcr, Runnable onSave, Runnable onClose) {
        if (ocrButton != null) ocrButton.setOnClickListener(v -> { if (onOcr != null) onOcr.run(); });
        if (saveButton != null) saveButton.setOnClickListener(v -> { if (onSave != null) onSave.run(); });
        closeButton.setOnClickListener(v -> { if (onClose != null) onClose.run(); });
    }

    void setOcrRunning(boolean running) {
        if (ocrButton == null) return;
        ocrButton.setEnabled(!running);
        ocrButton.setText(running ? "识别中…" : "重新识别");
    }

    void resetOcrButton() {
        if (ocrButton == null) return;
        ocrButton.setEnabled(true);
        ocrButton.setText("OCR");
    }

    void showOcrText(String text) {
        if (selection == null || textPanel == null) return;
        String shown = text == null || text.trim().isEmpty() ? "未识别到文字" : text.trim();
        title.setText("OCR 结果");
        selection.setText(shown);
        textPanel.setVisibility(View.VISIBLE);
        if (copyButton != null) copyButton.setVisibility(View.VISIBLE);

        int imageH = 0;
        if (imageView != null) {
            imageH = Math.min(ResultUi.dp(context, 130), Math.max(0, contentBudget / 2));
            imageView.setLayoutParams(new LinearLayout.LayoutParams(-1, imageH));
            imageView.setVisibility(imageH > 0 ? View.VISIBLE : View.GONE);
        }
        int textH = Math.max(ResultUi.dp(context, 96), contentBudget - imageH);
        textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, textH));
        root.requestLayout();
    }

    void clearSelection() {
        if (selection != null) selection.clearSelection();
    }

    private void bindSelectionMenu() {
        selection.setListener(new TextSelectionSurface.Listener() {
            @Override public void onSelectionStarted() {
                FloatActionMenu.dismiss();
                FloatMenuAnchor.clear();
            }

            @Override public void onSelectionChanging() {
                FloatActionMenu.dismiss();
                FloatMenuAnchor.clear();
            }

            @Override public void onSelectionFinished(String selectedText, Rect anchorOnScreen) {
                String value = selectedText == null ? "" : selectedText.trim();
                if (value.isEmpty()) return;
                FloatActionMenu.showTextAt(context, value, selection::selectAllText, anchorOnScreen);
            }
        });
    }

    private void copyAll() {
        if (selection == null) return;
        String value = selection.editor().getText().toString();
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens", value));
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
    }

    private static boolean initialTextVisible(Mode mode, FloatSettings settings) {
        if (mode == Mode.SCREENSHOT) return false;
        if (mode == Mode.OCR) return settings.ocrShowText();
        return true;
    }

    private static String initialText(Mode mode, String text, String meta) {
        String safeText = text == null ? "" : text;
        String safeMeta = meta == null ? "" : meta;
        return switch (mode) {
            case VIEW_TEXT -> safeText;
            case VIEW_IMAGE -> safeMeta;
            case OCR -> safeText.isBlank() ? "未识别到文字" : safeText;
            default -> "";
        };
    }

    private static String titleForMode(Mode mode) {
        return switch (mode) {
            case SCREENSHOT -> "区域截图";
            case VIEW_TEXT -> "View 内容";
            case VIEW_IMAGE -> "View / 图标";
            case OCR -> "OCR 结果";
        };
    }
}
