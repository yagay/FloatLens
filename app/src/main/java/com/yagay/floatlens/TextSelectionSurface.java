package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/** Reusable native Android text-selection surface for the unified result panel. */
public class TextSelectionSurface extends FrameLayout {
    public interface Listener {
        void onSelectionStarted();
        void onSelectionChanging();
        void onSelectionFinished(String selectedText, Rect anchorOnScreen);
    }

    private static final long RESHOW_DELAY_MS = 220L;

    private final ScrollView scrollView;
    private final SelectionEditText textView;
    private final TextSelectionController controller;
    private Listener listener;

    public TextSelectionSurface(Context context) {
        super(context);
        scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);
        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        textView = new SelectionEditText(context);
        textView.owner = this;
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(16);
        textView.setBackgroundColor(Color.TRANSPARENT);
        textView.setSingleLine(false);
        textView.setHorizontallyScrolling(false);
        textView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textView.setKeyListener(null);
        textView.setCursorVisible(false);
        textView.setShowSoftInputOnFocus(false);
        textView.setTextIsSelectable(true);
        textView.setLongClickable(true);
        textView.setFocusable(true);
        textView.setFocusableInTouchMode(true);
        textView.setSelectAllOnFocus(false);
        textView.setPadding(dp(8), dp(5), dp(8), dp(5));
        scrollView.addView(textView, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        controller = new TextSelectionController(context, textView, RESHOW_DELAY_MS,
                new TextSelectionController.Observer() {
                    @Override public void onStarted() {
                        if (listener != null) listener.onSelectionStarted();
                    }

                    @Override public void onChanging() {
                        if (listener != null) listener.onSelectionChanging();
                    }

                    @Override public void onStable(String selectedText, Rect anchorOnScreen) {
                        if (listener != null) listener.onSelectionFinished(selectedText, anchorOnScreen);
                    }
                });
        controller.install();
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setText(CharSequence text) {
        clearSelection();
        textView.setText(text == null ? "" : text);
        scrollView.scrollTo(0, 0);
    }

    public String getSelectedText() { return controller.selectedText(); }
    public Rect getSelectionAnchorOnScreen() { return controller.anchor(); }
    public void selectAllText() { controller.selectAll(); }
    public void clearSelection() { controller.clearSelection(); }
    public EditText editor() { return textView; }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class SelectionEditText extends EditText {
        TextSelectionSurface owner;
        SelectionEditText(Context context) { super(context); }

        @Override protected void onSelectionChanged(int selStart, int selEnd) {
            super.onSelectionChanged(selStart, selEnd);
            if (owner != null && owner.controller != null) {
                owner.controller.onSelectionChanged(selStart, selEnd);
            }
        }
    }
}
