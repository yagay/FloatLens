package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The one visual implementation for every FloatLens result.
 *
 * The Activity window is always a full-screen transparent native-selection host. This class owns
 * one centered result card. The card hierarchy never changes by mode: title, content slots and the
 * fixed OCR / Copy / Save / Close action row always exist. Only the middle content area changes
 * size, so images or OEM window decor can never push the action row outside the visible result.
 */
final class UnifiedResultPanel {
    private final Context context;
    private final FloatSettings settings;
    private final FrameLayout root;
    private final LinearLayout card;
    private final TextView title;
    private final ImageView imageView;
    private final LinearLayout textPanel;
    private final TextSelectionSurface selection;
    private final Button ocrButton;
    private final Button copyButton;
    private final Button saveButton;
    private final Button closeButton;
    private final int width;
    private final int maxHeight;
    private final int titleHeight;
    private final int actionsHeight;
    private final int contentBudget;

    private ResultSession session;
    private int height;
    private boolean ocrRunning;
    private Runnable closeAction;

    UnifiedResultPanel(Context context, ResultSession initial) {
        this.context = context;
        this.settings = new FloatSettings(context.getApplicationContext());

        Rect usable = ResultUi.usableBounds(context);
        width = ResultUi.standardWidth(context, usable);
        maxHeight = ResultUi.standardMaxHeight(context, usable);
        titleHeight = ResultUi.dp(context, ResultUi.TITLE_H_DP);
        actionsHeight = ResultUi.dp(context, ResultUi.ACTION_H_DP);
        contentBudget = Math.max(ResultUi.dp(context, 90),
                maxHeight - titleHeight - actionsHeight - ResultUi.dp(context, ResultUi.ROOT_VPAD_DP));

        root = new FrameLayout(context);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClickable(true);
        root.setFocusable(true);

        card = ResultUi.box(context);
        card.setClickable(true); // consume taps inside the card so root only handles outside taps
        card.setOnClickListener(v -> { });
        root.addView(card, new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        title = ResultUi.heading(context, "FloatLens");
        card.addView(title, new LinearLayout.LayoutParams(-1, titleHeight));

        imageView = new ImageView(context);
        imageView.setAdjustViewBounds(false);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(View.GONE);
        card.addView(imageView, new LinearLayout.LayoutParams(-1, 0));

        textPanel = new LinearLayout(context);
        textPanel.setOrientation(LinearLayout.VERTICAL);
        textPanel.setPadding(0, ResultUi.dp(context, 4), 0, ResultUi.dp(context, 4));
        selection = new TextSelectionSurface(context);
        textPanel.addView(selection, new LinearLayout.LayoutParams(-1, 0, 1f));
        textPanel.setVisibility(View.GONE);
        card.addView(textPanel, new LinearLayout.LayoutParams(-1, 0));
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
        card.addView(actions, new LinearLayout.LayoutParams(-1, actionsHeight));

        copyButton.setOnClickListener(v -> copyAll());
        render(initial);
    }

    FrameLayout root() { return root; }
    int width() { return width; }
    int height() { return height; }
    TextSelectionSurface selection() { return selection; }
    ResultSession session() { return session; }

    void bindActions(Runnable onOcr, Runnable onSave, Runnable onClose) {
        closeAction = onClose;
        ocrButton.setOnClickListener(v -> { if (onOcr != null) onOcr.run(); });
        saveButton.setOnClickListener(v -> { if (onSave != null) onSave.run(); });
        closeButton.setOnClickListener(v -> { if (onClose != null) onClose.run(); });
        root.setOnClickListener(v -> { if (closeAction != null) closeAction.run(); });
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
                cap = Math.min(ResultUi.dp(context, 130), Math.max(ResultUi.dp(context, 72), contentBudget / 2));
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
            textHeight = Math.max(ResultUi.dp(context, 96), contentBudget - imageHeight);
            // Never let a text minimum expand the card beyond its budget. If both image and text
            // are present, shrink the text area first while keeping enough room for selection.
            textHeight = Math.min(textHeight, Math.max(ResultUi.dp(context, 72), contentBudget - imageHeight));
            textPanel.setVisibility(View.VISIBLE);
            textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, textHeight));
            selection.setText(next.displayText());
        } else {
            textPanel.setVisibility(View.GONE);
            textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, 0));
            selection.setText("");
        }

        height = Math.max(ResultUi.dp(context, 158), Math.min(maxHeight,
                titleHeight + actionsHeight + imageHeight + textHeight
                        + ResultUi.dp(context, ResultUi.ROOT_VPAD_DP)));

        FrameLayout.LayoutParams cardLp = (FrameLayout.LayoutParams) card.getLayoutParams();
        cardLp.width = width;
        cardLp.height = height;
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);

        updateActions();
        card.requestLayout();
        root.requestLayout();

        DiagnosticLog.i(context, "RESULT_PANEL", "render mode=" + next.mode()
                + " origin=" + next.originMode()
                + " image=" + showImage + " text=" + showText
                + " card=" + width + "x" + height
                + " fixedActions=true buttons=ocr/copy/save/close");
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
