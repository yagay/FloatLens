package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Adjustable rectangular selection workspace launched from the floating-icon long press.
 *
 * First drag creates a rectangle. Once released, the rectangle is retained and can be moved from
 * its interior or resized from any edge/corner. Recognition only happens when the user presses one
 * of the explicit action buttons, so releasing a resize/move never accidentally starts OCR.
 */
public final class EditableRegionOverlay {
    private static EditorView active;

    public static synchronized void show(Context c, Bitmap screenshot) {
        if (screenshot == null || screenshot.isRecycled()) return;
        if (active != null) active.close();
        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        EditorView v = new EditorView(app, wm, screenshot);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(v, lp);
            active = v;
        } catch (Throwable t) {
            Toast.makeText(app, "区域选择器启动失败: " + safe(t), Toast.LENGTH_LONG).show();
            try { screenshot.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static final class EditorView extends View {
        private static final int H_NONE = 0, H_MOVE = 1, H_LEFT = 2, H_TOP = 3, H_RIGHT = 4,
                H_BOTTOM = 5, H_TL = 6, H_TR = 7, H_BL = 8, H_BR = 9, H_NEW = 10;
        private static final int A_NONE = 0, A_AUTO = 1, A_OCR = 2, A_VIEW = 3, A_CANCEL = 4;

        private final Context context;
        private final WindowManager wm;
        private final Bitmap screenshot;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF selection = new RectF();
        private final RectF startRect = new RectF();
        private final RectF[] buttons = {new RectF(), new RectF(), new RectF(), new RectF()};
        private final int[] screenLocation = new int[2];

        private boolean hasSelection;
        private int activeHandle = H_NONE;
        private int pressedAction = A_NONE;
        private float downX, downY;
        private boolean closed;

        EditorView(Context c, WindowManager wm, Bitmap screenshot) {
            super(c);
            this.context = c;
            this.wm = wm;
            this.screenshot = screenshot;
            setFocusable(true);
            setClickable(true);
            shadePaint.setColor(0x99000000);
            shadePaint.setStyle(Paint.Style.FILL);
            borderPaint.setColor(Color.WHITE);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(2.5f));
            handlePaint.setColor(Color.WHITE);
            handlePaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(14));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            buttonPaint.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawBitmap(screenshot, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);

            if (!hasSelection || selection.isEmpty()) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), shadePaint);
                drawTopTip(canvas, "拖动框选区域");
                return;
            }

            drawShadeOutside(canvas, selection);
            canvas.drawRect(selection, borderPaint);
            drawHandles(canvas, selection);
            drawTopTip(canvas, "拖动内部移动 · 拖边/角调整");
            drawActions(canvas);
        }

        private void drawShadeOutside(Canvas c, RectF r) {
            c.drawRect(0, 0, getWidth(), Math.max(0, r.top), shadePaint);
            c.drawRect(0, r.bottom, getWidth(), getHeight(), shadePaint);
            c.drawRect(0, r.top, Math.max(0, r.left), r.bottom, shadePaint);
            c.drawRect(r.right, r.top, getWidth(), r.bottom, shadePaint);
        }

        private void drawTopTip(Canvas c, String tip) {
            Paint p = new Paint(textPaint);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(dp(15));
            c.drawText(tip, dp(16), dp(34), p);
        }

        private void drawHandles(Canvas c, RectF r) {
            float radius = dp(5.5f);
            float cx = r.centerX(), cy = r.centerY();
            float[][] pts = {
                    {r.left, r.top}, {cx, r.top}, {r.right, r.top},
                    {r.left, cy}, {r.right, cy},
                    {r.left, r.bottom}, {cx, r.bottom}, {r.right, r.bottom}
            };
            for (float[] p : pts) c.drawCircle(p[0], p[1], radius, handlePaint);
        }

        private void drawActions(Canvas c) {
            float margin = dp(10), gap = dp(7), h = dp(48);
            float totalW = getWidth() - margin * 2 - gap * 3;
            float w = totalW / 4f;
            float top = getHeight() - h - dp(16);
            String[] labels = {"自动识别", "OCR", "View文字", "取消"};
            int[] actions = {A_AUTO, A_OCR, A_VIEW, A_CANCEL};
            for (int i = 0; i < 4; i++) {
                float left = margin + i * (w + gap);
                buttons[i].set(left, top, left + w, top + h);
                buttonPaint.setColor(pressedAction == actions[i] ? 0xEE4C86F7 : 0xDD202124);
                c.drawRoundRect(buttons[i], dp(10), dp(10), buttonPaint);
                c.drawText(labels[i], buttons[i].centerX(), buttons[i].centerY() + dp(5), textPaint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    pressedAction = hitAction(x, y);
                    if (pressedAction != A_NONE) { invalidate(); return true; }
                    downX = x; downY = y;
                    if (!hasSelection) {
                        selection.set(x, y, x, y);
                        startRect.set(selection);
                        activeHandle = H_NEW;
                    } else {
                        startRect.set(selection);
                        activeHandle = hitHandle(x, y);
                        if (activeHandle == H_NONE) {
                            selection.set(x, y, x, y);
                            startRect.set(selection);
                            activeHandle = H_NEW;
                        }
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (pressedAction != A_NONE) {
                        int now = hitAction(x, y);
                        if (now != pressedAction) pressedAction = A_NONE;
                        invalidate();
                        return true;
                    }
                    updateSelection(x, y);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (pressedAction != A_NONE) {
                        int action = pressedAction;
                        pressedAction = A_NONE;
                        if (hitAction(x, y) == action) performAction(action);
                        else invalidate();
                        return true;
                    }
                    updateSelection(x, y);
                    normalizeAndClamp();
                    float min = dp(24);
                    if (selection.width() < min || selection.height() < min) {
                        hasSelection = false;
                        selection.setEmpty();
                    } else hasSelection = true;
                    activeHandle = H_NONE;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    pressedAction = A_NONE;
                    activeHandle = H_NONE;
                    invalidate();
                    return true;
                }
            }
            return true;
        }

        private int hitAction(float x, float y) {
            if (!hasSelection) return A_NONE;
            int[] actions = {A_AUTO, A_OCR, A_VIEW, A_CANCEL};
            for (int i = 0; i < buttons.length; i++) if (buttons[i].contains(x, y)) return actions[i];
            return A_NONE;
        }

        private int hitHandle(float x, float y) {
            float slop = dp(24);
            boolean nearL = Math.abs(x - selection.left) <= slop;
            boolean nearR = Math.abs(x - selection.right) <= slop;
            boolean nearT = Math.abs(y - selection.top) <= slop;
            boolean nearB = Math.abs(y - selection.bottom) <= slop;
            if (nearL && nearT) return H_TL;
            if (nearR && nearT) return H_TR;
            if (nearL && nearB) return H_BL;
            if (nearR && nearB) return H_BR;
            if (nearL && y >= selection.top - slop && y <= selection.bottom + slop) return H_LEFT;
            if (nearR && y >= selection.top - slop && y <= selection.bottom + slop) return H_RIGHT;
            if (nearT && x >= selection.left - slop && x <= selection.right + slop) return H_TOP;
            if (nearB && x >= selection.left - slop && x <= selection.right + slop) return H_BOTTOM;
            if (selection.contains(x, y)) return H_MOVE;
            return H_NONE;
        }

        private void updateSelection(float x, float y) {
            float dx = x - downX, dy = y - downY;
            switch (activeHandle) {
                case H_NEW -> selection.set(downX, downY, x, y);
                case H_MOVE -> {
                    selection.set(startRect);
                    selection.offset(dx, dy);
                    float ox = 0, oy = 0;
                    if (selection.left < 0) ox = -selection.left;
                    else if (selection.right > getWidth()) ox = getWidth() - selection.right;
                    if (selection.top < 0) oy = -selection.top;
                    else if (selection.bottom > getHeight()) oy = getHeight() - selection.bottom;
                    selection.offset(ox, oy);
                }
                case H_LEFT -> selection.set(startRect.left + dx, startRect.top, startRect.right, startRect.bottom);
                case H_TOP -> selection.set(startRect.left, startRect.top + dy, startRect.right, startRect.bottom);
                case H_RIGHT -> selection.set(startRect.left, startRect.top, startRect.right + dx, startRect.bottom);
                case H_BOTTOM -> selection.set(startRect.left, startRect.top, startRect.right, startRect.bottom + dy);
                case H_TL -> selection.set(startRect.left + dx, startRect.top + dy, startRect.right, startRect.bottom);
                case H_TR -> selection.set(startRect.left, startRect.top + dy, startRect.right + dx, startRect.bottom);
                case H_BL -> selection.set(startRect.left + dx, startRect.top, startRect.right, startRect.bottom + dy);
                case H_BR -> selection.set(startRect.left, startRect.top, startRect.right + dx, startRect.bottom + dy);
            }
            normalizeAndClamp();
        }

        private void normalizeAndClamp() {
            float l = Math.min(selection.left, selection.right), r = Math.max(selection.left, selection.right);
            float t = Math.min(selection.top, selection.bottom), b = Math.max(selection.top, selection.bottom);
            selection.set(clamp(l, 0, getWidth()), clamp(t, 0, getHeight()),
                    clamp(r, 0, getWidth()), clamp(b, 0, getHeight()));
        }

        private void performAction(int action) {
            if (action == A_CANCEL) { close(); return; }
            if (!hasSelection || selection.isEmpty()) return;
            Rect screenRect = selectionInScreen();
            Bitmap crop = cropToSelection(screenRect);
            if (crop == null) {
                Toast.makeText(context, "选区截取失败", Toast.LENGTH_SHORT).show();
                return;
            }

            if (action == A_OCR) {
                close(false);
                FloatService f = FloatService.get();
                if (f != null) f.onCircleRecognizeStarted();
                OcrEngine.recognize(context, crop);
                DiagnosticLog.i(context, "REGION_EDIT", "OCR bounds=" + screenRect);
                return;
            }

            List<String> viewText = collectViewText(screenRect);
            if (action == A_VIEW) {
                if (viewText.isEmpty()) {
                    Toast.makeText(context, "选区内没有可提取的 View 文字", Toast.LENGTH_SHORT).show();
                    return;
                }
                close(false);
                showViewText(viewText, crop);
                DiagnosticLog.i(context, "REGION_EDIT", "VIEW_TEXT count=" + viewText.size() + " bounds=" + screenRect);
                return;
            }

            // AUTO: prefer native Accessibility text; OCR is the fallback for canvas/image content.
            if (!viewText.isEmpty()) {
                close(false);
                showViewText(viewText, crop);
                DiagnosticLog.i(context, "REGION_EDIT", "AUTO=view count=" + viewText.size() + " bounds=" + screenRect);
            } else {
                close(false);
                FloatService f = FloatService.get();
                if (f != null) f.onCircleRecognizeStarted();
                OcrEngine.recognize(context, crop);
                DiagnosticLog.i(context, "REGION_EDIT", "AUTO=ocr bounds=" + screenRect);
            }
        }

        private void showViewText(List<String> blocks, Bitmap crop) {
            String joined = String.join("\n", blocks);
            FloatService f = FloatService.get();
            if (f != null) f.onOcrResults(blocks.size());
            ResultOverlay.show(context, joined, blocks, crop);
        }

        private List<String> collectViewText(Rect screenRect) {
            LensAccessibilityService service = LensAccessibilityService.get();
            if (service == null) return List.of();
            List<ScreenCandidate> all = AccessibilityCandidateCollector.collect(service);
            ArrayList<ScreenCandidate> hits = new ArrayList<>();
            for (ScreenCandidate c : all) {
                if (c == null || c.type() != ScreenCandidate.Type.TEXT || !c.hasText()) continue;
                Rect r = c.bounds();
                if (r.isEmpty() || !Rect.intersects(screenRect, r)) continue;
                Rect inter = new Rect();
                if (!inter.setIntersect(screenRect, r)) continue;
                long ia = (long) inter.width() * inter.height();
                long ca = Math.max(1L, (long) r.width() * r.height());
                boolean centerInside = screenRect.contains(r.centerX(), r.centerY());
                if (centerInside || ia * 100L >= ca * 45L) hits.add(c);
            }
            hits.sort(Comparator.comparingInt((ScreenCandidate c) -> c.bounds().top)
                    .thenComparingInt(c -> c.bounds().left)
                    .thenComparingInt(ScreenCandidate::depth));
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (ScreenCandidate c : hits) {
                String s = c.text() == null ? "" : c.text().trim();
                if (!s.isEmpty()) unique.add(s);
            }
            return new ArrayList<>(unique);
        }

        private Rect selectionInScreen() {
            getLocationOnScreen(screenLocation);
            return new Rect(
                    Math.round(selection.left + screenLocation[0]),
                    Math.round(selection.top + screenLocation[1]),
                    Math.round(selection.right + screenLocation[0]),
                    Math.round(selection.bottom + screenLocation[1]));
        }

        private Bitmap cropToSelection(Rect screenRect) {
            try {
                Rect display = wm.getCurrentWindowMetrics().getBounds();
                float sx = screenshot.getWidth() / (float) Math.max(1, display.width());
                float sy = screenshot.getHeight() / (float) Math.max(1, display.height());
                int l = clamp(Math.round((screenRect.left - display.left) * sx), 0, screenshot.getWidth() - 1);
                int t = clamp(Math.round((screenRect.top - display.top) * sy), 0, screenshot.getHeight() - 1);
                int r = clamp(Math.round((screenRect.right - display.left) * sx), l + 1, screenshot.getWidth());
                int b = clamp(Math.round((screenRect.bottom - display.top) * sy), t + 1, screenshot.getHeight());
                return Bitmap.createBitmap(screenshot, l, t, r - l, b - t);
            } catch (Throwable t) {
                DiagnosticLog.i(context, "REGION_EDIT", "crop failed=" + safe(t));
                return null;
            }
        }

        void close() { close(true); }
        private void close(boolean recycleScreenshot) {
            if (closed) return;
            closed = true;
            try { wm.removeView(this); } catch (Throwable ignored) {}
            synchronized (EditableRegionOverlay.class) { if (active == this) active = null; }
            if (recycleScreenshot) try { if (!screenshot.isRecycled()) screenshot.recycle(); } catch (Throwable ignored) {}
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
        private float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
        private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private EditableRegionOverlay() {}
}
