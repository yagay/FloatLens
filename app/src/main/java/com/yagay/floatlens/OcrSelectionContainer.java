package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.InputType;
import android.text.Selection;
import android.text.Spannable;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/**
 * Native Android text-selection surface used by screenshot OCR.
 *
 * This intentionally mirrors ResultActivity's View-text selection model: Android's framework
 * Editor/ActionMode owns the selection handles and magnifier, while FloatLens owns the action menu.
 * It must be hosted in a focusable TYPE_APPLICATION_OVERLAY for the framework Editor to work.
 */
public final class OcrSelectionContainer extends FrameLayout {
    public interface Listener {
        void onSelectionStarted();
        void onSelectionChanging();
        void onSelectionFinished(String selectedText, Rect anchorOnScreen);
    }

    private static final long SELECTION_RESHOW_DELAY_MS = 220L;

    private final ScrollView scrollView;
    private final SelectionAwareEditText textView;
    private Listener listener;
    private ActionMode activeSelectionActionMode;
    private int lastSelectionStart = -1;
    private int lastSelectionEnd = -1;
    private long selectionGeneration;
    private Runnable selectionReshow;

    public OcrSelectionContainer(Context context) {
        super(context);

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);
        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        textView = new SelectionAwareEditText(context);
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
        textView.owner = this;
        scrollView.addView(textView, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Same logic as View text: keep framework ActionMode only for Android's handles/magnifier.
        // FloatLens renders its own FloatActionMenu for the selected substring.
        textView.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                if (menu != null) menu.clear();
                activeSelectionActionMode = mode;
                lastSelectionStart = textView.getSelectionStart();
                lastSelectionEnd = textView.getSelectionEnd();
                selectionGeneration++;
                if (listener != null) listener.onSelectionStarted();
                textView.post(OcrSelectionContainer.this::scheduleSelectionMenu);
                DiagnosticLog.i(getContext(), "OCR_NATIVE_SELECT",
                        "action_start start=" + lastSelectionStart + " end=" + lastSelectionEnd);
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                if (menu != null) menu.clear();
                return true;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                if (activeSelectionActionMode == mode) {
                    cancelSelectionReshow();
                    activeSelectionActionMode = null;
                    lastSelectionStart = lastSelectionEnd = -1;
                    selectionGeneration++;
                    if (listener != null) listener.onSelectionChanging();
                    DiagnosticLog.i(getContext(), "OCR_NATIVE_SELECT", "action_end");
                }
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setText(CharSequence text) {
        clearSelection();
        textView.setText(text == null ? "" : text);
        scrollView.scrollTo(0, 0);
    }

    public String getSelectedText() {
        if (textView.getText() == null) return "";
        int a = textView.getSelectionStart();
        int b = textView.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return "";
        int lo = Math.max(0, Math.min(a, b));
        int hi = Math.min(textView.length(), Math.max(a, b));
        return lo < hi ? textView.getText().subSequence(lo, hi).toString() : "";
    }

    public Rect getSelectionAnchorOnScreen() {
        return FloatMenuAnchor.forTextSelection(textView);
    }

    public void selectAllText() {
        try {
            CharSequence raw = textView.getText();
            if (raw instanceof Spannable span && span.length() > 0) {
                Selection.setSelection(span, 0, span.length());
                textView.post(this::scheduleSelectionMenu);
            }
        } catch (Throwable ignored) {}
    }

    public void clearSelection() {
        cancelSelectionReshow();
        if (activeSelectionActionMode != null) {
            try { activeSelectionActionMode.finish(); } catch (Throwable ignored) {}
        }
        activeSelectionActionMode = null;
        lastSelectionStart = lastSelectionEnd = -1;
        selectionGeneration++;
        try {
            CharSequence raw = textView.getText();
            if (raw instanceof Spannable span) {
                int end = span.length();
                Selection.setSelection(span, end, end);
            }
        } catch (Throwable ignored) {}
    }

    private void onSelectionChanged(int start, int end) {
        if (activeSelectionActionMode == null) return;
        if (start == lastSelectionStart && end == lastSelectionEnd) return;

        lastSelectionStart = start;
        lastSelectionEnd = end;
        selectionGeneration++;
        cancelSelectionReshow();
        if (listener != null) listener.onSelectionChanging();
        scheduleSelectionMenu();
        DiagnosticLog.i(getContext(), "OCR_NATIVE_SELECT",
                "range start=" + start + " end=" + end);
    }

    private void scheduleSelectionMenu() {
        if (activeSelectionActionMode == null) return;
        long generation = selectionGeneration;
        cancelSelectionReshow();
        selectionReshow = () -> {
            if (activeSelectionActionMode == null || generation != selectionGeneration) return;
            String selected = getSelectedText().trim();
            if (selected.isEmpty()) return;
            Rect anchor = getSelectionAnchorOnScreen();
            if (listener != null) listener.onSelectionFinished(selected, anchor);
            DiagnosticLog.i(getContext(), "OCR_NATIVE_SELECT",
                    "stable chars=" + selected.length()
                            + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));
        };
        textView.postDelayed(selectionReshow, SELECTION_RESHOW_DELAY_MS);
    }

    private void cancelSelectionReshow() {
        if (selectionReshow != null) {
            try { textView.removeCallbacks(selectionReshow); } catch (Throwable ignored) {}
        }
        selectionReshow = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class SelectionAwareEditText extends EditText {
        OcrSelectionContainer owner;

        SelectionAwareEditText(Context context) {
            super(context);
        }

        @Override protected void onSelectionChanged(int selStart, int selEnd) {
            super.onSelectionChanged(selStart, selEnd);
            if (owner != null) owner.onSelectionChanged(selStart, selEnd);
        }
    }
}
