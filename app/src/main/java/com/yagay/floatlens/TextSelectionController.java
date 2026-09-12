package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;
import android.text.Selection;
import android.text.Spannable;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

/** Shared native ActionMode/selection lifecycle for every selectable FloatLens TextView. */
final class TextSelectionController {
    interface Observer {
        void onStarted();
        void onChanging();
        void onStable(String selectedText, Rect anchorOnScreen);
    }

    private final Context context;
    private final TextView textView;
    private final long stableDelayMs;
    private final Observer observer;
    private ActionMode actionMode;
    private int lastStart = -1;
    private int lastEnd = -1;
    private long generation;
    private Runnable delayedStable;

    TextSelectionController(Context context, TextView textView, long stableDelayMs, Observer observer) {
        this.context = context;
        this.textView = textView;
        this.stableDelayMs = Math.max(0L, stableDelayMs);
        this.observer = observer;
    }

    void install() {
        textView.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                clearMenu(menu);
                actionMode = mode;
                lastStart = textView.getSelectionStart();
                lastEnd = textView.getSelectionEnd();
                generation++;
                if (observer != null) observer.onStarted();
                scheduleStable();
                DiagnosticLog.i(context, "TEXT_SELECT",
                        "start=" + lastStart + " end=" + lastEnd);
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                clearMenu(menu);
                detectSelectionChange();
                return true;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                if (actionMode != mode) return;
                cancelStable();
                actionMode = null;
                lastStart = lastEnd = -1;
                generation++;
                if (observer != null) observer.onChanging();
                DiagnosticLog.i(context, "TEXT_SELECT", "end");
            }
        });
    }

    void onSelectionChanged(int start, int end) {
        if (actionMode == null || (start == lastStart && end == lastEnd)) return;
        lastStart = start;
        lastEnd = end;
        generation++;
        cancelStable();
        if (observer != null) observer.onChanging();
        scheduleStable();
        DiagnosticLog.i(context, "TEXT_SELECT", "range start=" + start + " end=" + end);
    }

    String selectedText() {
        if (textView.getText() == null) return "";
        int a = textView.getSelectionStart();
        int b = textView.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return "";
        int lo = Math.max(0, Math.min(a, b));
        int hi = Math.min(textView.length(), Math.max(a, b));
        return lo < hi ? textView.getText().subSequence(lo, hi).toString() : "";
    }

    Rect anchor() {
        return FloatMenuAnchor.forTextSelection(textView);
    }

    void selectAll() {
        try {
            CharSequence raw = textView.getText();
            if (raw instanceof Spannable span && span.length() > 0) {
                Selection.setSelection(span, 0, span.length());
                onSelectionChanged(0, span.length());
                scheduleStable();
            }
        } catch (Throwable ignored) { }
    }

    void clearSelection() {
        cancelStable();
        if (actionMode != null) {
            try { actionMode.finish(); } catch (Throwable ignored) { }
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
        } catch (Throwable ignored) { }
    }

    private void detectSelectionChange() {
        if (actionMode == null) return;
        int start = textView.getSelectionStart();
        int end = textView.getSelectionEnd();
        if (start != lastStart || end != lastEnd) onSelectionChanged(start, end);
    }

    private void scheduleStable() {
        if (actionMode == null) return;
        long expected = generation;
        cancelStable();
        delayedStable = () -> {
            if (actionMode == null || expected != generation) return;
            String selected = selectedText().trim();
            if (selected.isEmpty()) return;
            Rect anchor = anchor();
            if (observer != null) observer.onStable(selected, anchor);
            DiagnosticLog.i(context, "TEXT_SELECT", "stable chars=" + selected.length()
                    + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));
        };
        if (stableDelayMs == 0L) textView.post(delayedStable);
        else textView.postDelayed(delayedStable, stableDelayMs);
    }

    private void cancelStable() {
        if (delayedStable != null) {
            try { textView.removeCallbacks(delayedStable); } catch (Throwable ignored) { }
        }
        delayedStable = null;
    }

    private static void clearMenu(Menu menu) {
        if (menu != null) menu.clear();
    }
}
