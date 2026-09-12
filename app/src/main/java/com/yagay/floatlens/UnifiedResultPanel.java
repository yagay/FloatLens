package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * The one visual implementation for every FloatLens result.
 *
 * This class owns only card content. Window sizing, outside-touch dismissal and centering belong to
 * UnifiedResultDialogFragment. The hierarchy is fixed for every mode: title, image slot, selectable
 * text slot, then OCR / Copy / Save / Close. The action row is therefore always measured after the
 * content and cannot be clipped by a manually calculated popup height.
 */
final class UnifiedResultPanel {
    private final Context context;
    private final FloatSettings settings;
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
    private final int titleHeight;
    private final int actionsHeight;
    private final int contentBudget;

    private ResultSession session;
    private boolean ocrRunning;

    UnifiedResultPanel(Context context, ResultSession initial) {
        this.context = context;
        this.settings = new FloatSettings(context.getApplicationContext());

        Rect usable = ResultUi.usableBounds(context);
        width = ResultUi.standardWidth(context, usable);
        int maxHeight = ResultUi.standardMaxHeight(context, usable);
        titleHeight = ResultUi.dp(context, ResultUi.TITLE_H_DP);
        actionsHeight = ResultUi.dp(context, ResultUi.ACTION_H_DP);
        contentBudget = Math.max(ResultUi.dp(context, 90),
                maxHeight - titleHeight - actionsHeight - ResultUi.dp(context, ResultUi.ROOT_VPAD_DP));

        root = ResultUi.box(context);

        title = ResultUi.heading(context, "FloatLens");
        root.addView(title, new LinearLayout.LayoutParams(-1, titleHeight));

        imageView = new ImageView(context);
        imageView.setAdjustViewBounds(false);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(View.GONE);
        root.addView(imageView, new LinearLayout.LayoutParams(-1, 0));

        textPanel = new LinearLayout(context);
        textPanel.setOrientation(LinearLayout.VERTICAL);
        textPanel.setPadding(0, ResultUi.dp(context, 4), 0, ResultUi.dp(context, 4));
        selection = new TextSelectionSurface(context);
        textPanel.addView(selection, new LinearLayout.LayoutParams(-1, 0, 1f));
        textPanel.setVisibility(View.GONE);
        root.addView(textPanel, new LinearLayout.LayoutParams(-1, 0));
        bindSelectionMenu();

        LinearLayout actions = ResultUi.actionRow(context);
        ocrButton = ResultUi.button(context, "OCR");
        copyButton = ResultUi.button(context, "复制");
        saveButton = ResultUi.button(context, "保存");
        closeButton = ResultUi.button(context, "关闭");
        actions.addView(ocrButton, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(copyButton, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(saveButton, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(closeButton, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(actions, new LinearLayout.LayoutParams(-1, actionsHeight));

        copyButton.setOnClickListener(v -> copyAll());
        render(initial);
    }

    LinearLayout root() { return root; }
    int width() { return width; }
    TextSelectionSurface selection() { return selection; }
    ResultSession session() { return session; }

    void bindActions(Runnable onOcr, Runnable onSave, Runnable onClose) {
        ocrButton.setOnClickListener(v -> { if (onOcr != null) onOcr.run(); });
        saveButton.setOnClickListener(v -> { if (onSave != null) onSave.run(); });
        closeButton.setOnClickListener(v -> { if (onClose != null) onClose.run(); });
    }

    void render(ResultSession next) {
        if (next == null) return;
        session = next;
        selection.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();

        title.setText(next.title());
        boolean showImage = next.showImage(settings);
        boolean showText = next.showText(settings);

        int imageHeight = 0;
        if (showImage) {
            imageView.setImageBitmap(next.image());
            imageView.setVisibility(View.VISIBLE);
            int cap;
            if (showText) {
                cap = Math.min(ResultUi.dp(context, 130),
                        Math.max(ResultUi.dp(context, 72), contentBudget / 2));
            } else if (next.mode() == ResultSession.Mode.SCREENSHOT) {
                cap = contentBudget;
            } else if (next.mode() == ResultSession.Mode.VIEW_IMAGE) {
                cap = Math.min(ResultUi.dp(context, 190), contentBudget);
            } else {
                cap = Math.min(ResultUi.dp(context, 135), contentBudget);
            }
            imageHeight = ResultUi.imageHeight(context, next.image(), width, cap);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(-1, imageHeight));
            ImageShareUtils.attachLongPressShare(context, imageView, next.image());
        } else {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0));
        }

        int textHeight = 0;
        if (showText) {
            int remaining = Math.max(ResultUi.dp(context, 72), contentBudget - imageHeight);
            textHeight = Math.max(ResultUi.dp(context, 96), remaining);
            textHeight = Math.min(textHeight, Math.max(ResultUi.dp(context, 72), contentBudget));
            textPanel.setVisibility(View.VISIBLE);
            textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, textHeight));
            selection.setText(next.displayText());
        } else {
            textPanel.setVisibility(View.GONE);
            textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, 0));
            selection.setText("");
        }

        updateActions();
        root.requestLayout();

        DiagnosticLog.i(context, "RESULT_PANEL", "render mode=" + next.mode()
                + " origin=" + next.originMode()
                + " image=" + showImage + " imageH=" + imageHeight
                + " text=" + showText + " textH=" + textHeight
                + " width=" + width + " wrapContent=true fixedActions=true"
                + " buttons=ocr/copy/save/close");
    }

    void setOcrRunning(boolean running) {
        ocrRunning = running;
        updateActions();
    }

    void clearSelection() { selection.clearSelection(); }

    private void updateActions() {
        ResultSession s = session;
        boolean canOcr = s != null && s.canOcr();
        boolean canCopy = s != null && s.canCopy();
        boolean canSave = s != null && s.canSave();

        ocrButton.setEnabled(canOcr && !ocrRunning);
        ocrButton.setText(ocrRunning ? "识别中…"
                : s != null && s.mode() == ResultSession.Mode.OCR ? "重新识别" : "OCR");
        setEnabledVisual(ocrButton, canOcr && !ocrRunning);

        copyButton.setEnabled(canCopy);
        copyButton.setText("复制");
        setEnabledVisual(copyButton, canCopy);

        saveButton.setEnabled(canSave);
        saveButton.setText("保存");
        setEnabledVisual(saveButton, canSave);

        closeButton.setEnabled(true);
        closeButton.setText("关闭");
        setEnabledVisual(closeButton, true);
    }

    private void setEnabledVisual(Button button, boolean enabled) {
        button.setVisibility(View.VISIBLE);
        button.setAlpha(enabled ? 1f : 0.42f);
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
        if (session == null || !session.canCopy()) return;
        String value = selection.editor().getText().toString();
        if (value.isBlank()) value = session.displayText();
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens", value));
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
    }
}
