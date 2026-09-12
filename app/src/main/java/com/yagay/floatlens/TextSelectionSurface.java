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
 * Reusable native Android text-selection surface.
 *
 * Android owns the real selection handles and magnifier through its Editor/ActionMode. FloatLens
 * receives stable selection callbacks and renders FloatActionMenu separately. Host this in a
 * focusable TYPE_APPLICATION_OVERLAY (or an Activity window), not TYPE_ACCESSIBILITY_OVERLAY.
 */
public class TextSelectionSurface extends FrameLayout {
    public interface Listener {
        void onSelectionStarted();
        void onSelectionChanging();
        void onSelectionFinished(String selectedText, Rect anchorOnScreen);
    }

    private static final long RESHOW_DELAY_MS = 220L;

    private final ScrollView scrollView;
    private final SelectionEditText textView;
    private Listener listener;
    private ActionMode actionMode;
    private int lastStart = -1;
    private int lastEnd = -1;
    private long generation;
    private Runnable delayedFinish;

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

        textView.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                if (menu != null) menu.clear();
                actionMode = mode;
                lastStart = textView.getSelectionStart();
                lastEnd = textView.getSelectionEnd();
                generation++;
                if (listener != null) listener.onSelectionStarted();
                textView.post(TextSelectionSurface.this::scheduleStableSelection);
                DiagnosticLog.i(getContext(), "TEXT_SELECT",
                        "start=" + lastStart + " end=" + lastEnd);
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
                if (actionMode != mode) return;
                cancelDelayedFinish();
                actionMode = null;
                lastStart = lastEnd = -1;
                generation++;
                if (listener != null) listener.onSelectionChanging();
                DiagnosticLog.i(getContext(), "TEXT_SELECT", "end");
            }
        });
    }

    public void setListener(Listener listener) { this.listener = listener; }

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
                textView.post(this::scheduleStableSelection);
            }
        } catch (Throwable ignored) {}
    }

    public void clearSelection() {
        cancelDelayedFinish();
        if (actionMode != null) {
            try { actionMode.finish(); } catch (Throwable ignored) {}
        }
        actionMode = null;
        lastStart = lastEnd = -1;
        generation++;
        try {
            CharSequence raw = textView.getText();
            if (raw instanceof Spannable span) {
                int end = span.length();
                Selection.setSelection(span, end, end);
            }
        } catch (Throwable ignored) {}
    }

    public EditText editor() { return textView; }

    private void onSelectionChanged(int start, int end) {
        if (actionMode == null || (start == lastStart && end == lastEnd)) return;
        lastStart = start;
        lastEnd = end;
        generation++;
        cancelDelayedFinish();
        if (listener != null) listener.onSelectionChanging();
        scheduleStableSelection();
        DiagnosticLog.i(getContext(), "TEXT_SELECT", "range start=" + start + " end=" + end);
    }

    private void scheduleStableSelection() {
        if (actionMode == null) return;
        long expected = generation;
        cancelDelayedFinish();
        delayedFinish = () -> {
            if (actionMode == null || expected != generation) return;
            String selected = getSelectedText().trim();
            if (selected.isEmpty()) return;
            Rect anchor = getSelectionAnchorOnScreen();
            if (listener != null) listener.onSelectionFinished(selected, anchor);
            DiagnosticLog.i(getContext(), "TEXT_SELECT", "stable chars=" + selected.length()
                    + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));
        };
        textView.postDelayed(delayedFinish, RESHOW_DELAY_MS);
    }

    private void cancelDelayedFinish() {
        if (delayedFinish != null) {
            try { textView.removeCallbacks(delayedFinish); } catch (Throwable ignored) {}
        }
        delayedFinish = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class SelectionEditText extends EditText {
        TextSelectionSurface owner;
        SelectionEditText(Context context) { super(context); }

        @Override protected void onSelectionChanged(int selStart, int selEnd) {
            super.onSelectionChanged(selStart, selEnd);
            if (owner != null) owner.onSelectionChanged(selStart, selEnd);
        }
    }
}
