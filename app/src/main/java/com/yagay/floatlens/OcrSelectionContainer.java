package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Self-contained OCR text selection surface for accessibility overlays.
 *
 * It deliberately does not use Android's contextual text ActionMode or EditText selection handles.
 * The text, scrolling, selection highlight and both draggable handles live in one coordinate model.
 */
public final class OcrSelectionContainer extends FrameLayout {
    public interface Listener {
        void onSelectionStarted();
        void onSelectionChanging();
        void onSelectionFinished(String selectedText, Rect anchorOnScreen);
    }

    private static final int ROLE_START = 1;
    private static final int ROLE_END = 2;

    private final ScrollView scrollView;
    private final TextView textView;
    private final HandleView firstHandle;
    private final HandleView secondHandle;
    private final int touchSlop;
    private final int longPressMs;

    private Listener listener;
    private SpannableStringBuilder displayText = new SpannableStringBuilder();
    private BackgroundColorSpan selectionSpan;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private boolean selectionActive;
    private boolean touchDown;
    private boolean selectingByTextDrag;
    private float downX;
    private float downY;
    private Runnable longPressRunnable;

    public OcrSelectionContainer(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);

        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        longPressMs = ViewConfiguration.getLongPressTimeout();

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);
        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        textView = new TextView(context);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(16);
        textView.setBackgroundColor(Color.TRANSPARENT);
        textView.setGravity(Gravity.TOP | Gravity.START);
        textView.setTextIsSelectable(false);
        textView.setLongClickable(false);
        textView.setClickable(true);
        textView.setFocusable(false);
        textView.setPadding(dp(8), dp(5), dp(8), dp(28));
        scrollView.addView(textView, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        firstHandle = new HandleView(context, ROLE_START);
        secondHandle = new HandleView(context, ROLE_END);
        int handleW = dp(42);
        int handleH = dp(48);
        addView(firstHandle, new LayoutParams(handleW, handleH));
        addView(secondHandle, new LayoutParams(handleW, handleH));
        firstHandle.setVisibility(INVISIBLE);
        secondHandle.setVisibility(INVISIBLE);

        installTextTouch();
        installHandleTouch(firstHandle);
        installHandleTouch(secondHandle);
        scrollView.setOnScrollChangeListener((v, sx, sy, oldSx, oldSy) -> updateHandles());
        textView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateHandles());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setText(CharSequence text) {
        clearLongPress();
        selectionActive = false;
        selectionStart = selectionEnd = -1;
        displayText = new SpannableStringBuilder(text == null ? "" : text);
        selectionSpan = null;
        textView.setText(displayText, TextView.BufferType.SPANNABLE);
        hideHandles();
        scrollView.scrollTo(0, 0);
    }

    public String getSelectedText() {
        if (!selectionActive || selectionStart < 0 || selectionEnd <= selectionStart
                || selectionEnd > displayText.length()) return "";
        return displayText.subSequence(selectionStart, selectionEnd).toString();
    }

    public Rect getSelectionAnchorOnScreen() {
        if (!selectionActive) return null;
        Layout layout = textView.getLayout();
        if (layout == null) return null;
        try {
            Path p = new Path();
            layout.getSelectionPath(selectionStart, selectionEnd, p);
            RectF bounds = new RectF();
            p.computeBounds(bounds, true);
            int[] loc = new int[2];
            textView.getLocationOnScreen(loc);
            int left = Math.round(loc[0] + textView.getTotalPaddingLeft() + bounds.left - textView.getScrollX());
            int top = Math.round(loc[1] + textView.getTotalPaddingTop() + bounds.top - textView.getScrollY());
            int right = Math.round(loc[0] + textView.getTotalPaddingLeft() + bounds.right - textView.getScrollX());
            int bottom = Math.round(loc[1] + textView.getTotalPaddingTop() + bounds.bottom - textView.getScrollY());
            if (right <= left) right = left + dp(1);
            if (bottom <= top) bottom = top + dp(18);
            return new Rect(left, top, right, bottom);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void selectAllText() {
        if (displayText.length() == 0) return;
        applySelection(0, displayText.length());
        notifyFinished();
    }

    public void clearSelection() {
        clearLongPress();
        touchDown = false;
        selectingByTextDrag = false;
        requestNoIntercept(false);
        selectionActive = false;
        selectionStart = selectionEnd = -1;
        if (selectionSpan != null) {
            displayText.removeSpan(selectionSpan);
            selectionSpan = null;
        }
        textView.invalidate();
        hideHandles();
    }

    private void installTextTouch() {
        textView.setOnTouchListener((v, event) -> {
            if (event == null || displayText.length() == 0) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    touchDown = true;
                    selectingByTextDrag = false;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    clearLongPress();
                    longPressRunnable = () -> {
                        if (!touchDown || displayText.length() == 0) return;
                        int offset = offsetForRaw(downX, downY);
                        int[] word = wordBounds(offset);
                        if (word[1] <= word[0]) return;
                        selectingByTextDrag = true;
                        requestNoIntercept(true);
                        applySelection(word[0], word[1]);
                        if (listener != null) listener.onSelectionStarted();
                    };
                    textView.postDelayed(longPressRunnable, longPressMs);
                    return false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!selectingByTextDrag) {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (dx * dx + dy * dy > touchSlop * touchSlop) clearLongPress();
                        return false;
                    }
                    requestNoIntercept(true);
                    extendFromInitialSelection(offsetForRaw(event.getRawX(), event.getRawY()));
                    if (listener != null) listener.onSelectionChanging();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    touchDown = false;
                    clearLongPress();
                    if (!selectingByTextDrag) return false;
                    extendFromInitialSelection(offsetForRaw(event.getRawX(), event.getRawY()));
                    selectingByTextDrag = false;
                    requestNoIntercept(false);
                    notifyFinished();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    touchDown = false;
                    clearLongPress();
                    if (selectingByTextDrag) {
                        selectingByTextDrag = false;
                        requestNoIntercept(false);
                        notifyFinished();
                        return true;
                    }
                    return false;
                }
            }
            return false;
        });
    }

    private void installHandleTouch(HandleView handle) {
        handle.setOnTouchListener((v, event) -> {
            if (event == null || !selectionActive) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    requestNoIntercept(true);
                    if (listener != null) listener.onSelectionStarted();
                    handle.setPressed(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    requestNoIntercept(true);
                    autoScroll(event.getRawY());
                    moveHandle(handle, offsetForRaw(event.getRawX(), event.getRawY()));
                    if (listener != null) listener.onSelectionChanging();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    moveHandle(handle, offsetForRaw(event.getRawX(), event.getRawY()));
                    handle.setPressed(false);
                    requestNoIntercept(false);
                    notifyFinished();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    handle.setPressed(false);
                    requestNoIntercept(false);
                    notifyFinished();
                    return true;
                }
            }
            return true;
        });
    }

    private void moveHandle(HandleView handle, int rawOffset) {
        if (!selectionActive || displayText.length() == 0) return;
        int probe = clamp(rawOffset, 0, displayText.length() - 1);
        int[] word = wordBounds(probe);

        if (handle.role == ROLE_START) {
            if (word[0] < selectionEnd) {
                applySelection(word[0], selectionEnd);
            } else {
                int oldEnd = selectionEnd;
                applySelection(oldEnd, word[1]);
                swapRoles(handle);
            }
        } else {
            if (word[1] > selectionStart) {
                applySelection(selectionStart, word[1]);
            } else {
                int oldStart = selectionStart;
                applySelection(word[0], oldStart);
                swapRoles(handle);
            }
        }
    }

    private void swapRoles(HandleView dragged) {
        HandleView other = dragged == firstHandle ? secondHandle : firstHandle;
        int role = dragged.role;
        dragged.role = other.role;
        other.role = role;
        updateHandles();
    }

    private void extendFromInitialSelection(int rawOffset) {
        if (!selectionActive || displayText.length() == 0) return;
        int probe = clamp(rawOffset, 0, displayText.length() - 1);
        int[] word = wordBounds(probe);
        int mid = (selectionStart + selectionEnd) / 2;
        if (rawOffset < mid) applySelection(word[0], selectionEnd);
        else applySelection(selectionStart, word[1]);
    }

    private void applySelection(int start, int end) {
        int a = clamp(Math.min(start, end), 0, displayText.length());
        int b = clamp(Math.max(start, end), 0, displayText.length());
        if (a == b) {
            if (b < displayText.length()) b++;
            else if (a > 0) a--;
        }
        if (a < 0 || b <= a) return;

        if (selectionSpan != null) displayText.removeSpan(selectionSpan);
        selectionSpan = new BackgroundColorSpan(0x884285F4);
        displayText.setSpan(selectionSpan, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        selectionStart = a;
        selectionEnd = b;
        selectionActive = true;
        textView.setText(displayText, TextView.BufferType.SPANNABLE);
        textView.post(this::updateHandles);
    }

    private int offsetForRaw(float rawX, float rawY) {
        if (displayText.length() == 0) return 0;
        int[] loc = new int[2];
        textView.getLocationOnScreen(loc);
        float x = rawX - loc[0];
        float y = rawY - loc[1];
        try {
            return clamp(textView.getOffsetForPosition(x, y), 0, displayText.length());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int[] wordBounds(int offset) {
        if (displayText.length() == 0) return new int[]{0, 0};
        int n = displayText.length();
        int p = clamp(offset, 0, n - 1);
        char c = displayText.charAt(p);
        if (Character.isLowSurrogate(c) && p > 0 && Character.isHighSurrogate(displayText.charAt(p - 1))) {
            return new int[]{p - 1, Math.min(n, p + 1)};
        }
        if (Character.isHighSurrogate(c) && p + 1 < n && Character.isLowSurrogate(displayText.charAt(p + 1))) {
            return new int[]{p, p + 2};
        }
        if (isCjk(c) || !isLatinWordChar(c)) return new int[]{p, Math.min(n, p + 1)};

        int left = p;
        int right = p + 1;
        while (left > 0 && isLatinWordChar(displayText.charAt(left - 1)) && !isCjk(displayText.charAt(left - 1))) left--;
        while (right < n && isLatinWordChar(displayText.charAt(right)) && !isCjk(displayText.charAt(right))) right++;
        return new int[]{left, right};
    }

    private void updateHandles() {
        if (!selectionActive || textView.getLayout() == null || displayText.length() == 0) {
            hideHandles();
            return;
        }
        placeHandle(firstHandle, firstHandle.role == ROLE_START ? selectionStart : selectionEnd);
        placeHandle(secondHandle, secondHandle.role == ROLE_START ? selectionStart : selectionEnd);
    }

    private void placeHandle(HandleView handle, int offset) {
        Layout layout = textView.getLayout();
        if (layout == null) return;
        int safe = clamp(offset, 0, displayText.length());
        int lineProbe = safe == displayText.length() ? Math.max(0, safe - 1) : safe;
        int line = layout.getLineForOffset(lineProbe);
        float x = textView.getTotalPaddingLeft() + layout.getPrimaryHorizontal(safe) - textView.getScrollX();
        float y = textView.getTotalPaddingTop() + layout.getLineBottom(line) - textView.getScrollY();

        int[] textLoc = new int[2];
        int[] selfLoc = new int[2];
        textView.getLocationOnScreen(textLoc);
        getLocationOnScreen(selfLoc);
        float localX = textLoc[0] - selfLoc[0] + x;
        float localY = textLoc[1] - selfLoc[1] + y;

        handle.setTranslationX(localX - handle.getMeasuredWidth() / 2f);
        handle.setTranslationY(localY);
        boolean visible = localY >= -dp(8) && localY <= getHeight() + dp(8)
                && localX >= -dp(20) && localX <= getWidth() + dp(20);
        handle.setVisibility(visible ? VISIBLE : INVISIBLE);
    }

    private void hideHandles() {
        firstHandle.setVisibility(INVISIBLE);
        secondHandle.setVisibility(INVISIBLE);
    }

    private void notifyFinished() {
        updateHandles();
        if (listener != null && selectionActive) {
            listener.onSelectionFinished(getSelectedText(), getSelectionAnchorOnScreen());
        }
    }

    private void autoScroll(float rawY) {
        int[] loc = new int[2];
        scrollView.getLocationOnScreen(loc);
        float localY = rawY - loc[1];
        int edge = dp(30);
        int step = dp(18);
        if (localY < edge) scrollView.scrollBy(0, -step);
        else if (localY > scrollView.getHeight() - edge) scrollView.scrollBy(0, step);
        updateHandles();
    }

    private void requestNoIntercept(boolean disallow) {
        try {
            requestDisallowInterceptTouchEvent(disallow);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
        } catch (Throwable ignored) {}
    }

    private void clearLongPress() {
        if (longPressRunnable == null) return;
        try { textView.removeCallbacks(longPressRunnable); } catch (Throwable ignored) {}
        longPressRunnable = null;
    }

    private static boolean isLatinWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '’';
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(value, max));
    }

    private final class HandleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int role;

        HandleView(Context context, int role) {
            super(context);
            this.role = role;
            paint.setColor(0xFF4285F4);
            paint.setStyle(Paint.Style.FILL);
            setClickable(true);
            setFocusable(false);
            setElevation(dp(14));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float stem = dp(8);
            float radius = isPressed() ? dp(9) : dp(7);
            canvas.drawRect(cx - dp(1), 0, cx + dp(1), stem, paint);
            canvas.drawCircle(cx, stem + radius, radius, paint);
        }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }
    }
}
