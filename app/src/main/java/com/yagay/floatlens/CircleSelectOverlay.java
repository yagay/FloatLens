package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Frozen-screen Circle Select workspace.
 *
 * OCR words stay directly selectable on the frozen screenshot. FloatLens owns the text action
 * menu, while a rough free-hand gesture snaps to a padded rectangle before OCR.
 */
public final class CircleSelectOverlay {
    private static WorkspaceView active;

    public static synchronized boolean show(Context c, Bitmap screenshot, Runnable onClosed) {
        if (screenshot == null || screenshot.isRecycled()) return false;
        dismissActive("replace");
        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        Rect contentBounds = CircleSelectFrame.contentBounds(app);
        Rect displayBounds = CircleSelectFrame.displayBounds(app);
        WorkspaceView view = new WorkspaceView(app, wm, screenshot, onClosed);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.max(1, contentBounds.width()),
                Math.max(1, contentBounds.height()),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = contentBounds.left - displayBounds.left;
        lp.y = contentBounds.top - displayBounds.top;
        try {
            wm.addView(view, lp);
            active = view;
            view.startSpatialOcr();
            DiagnosticLog.i(app, "CIRCLE_SELECT", "overlay shown "
                    + screenshot.getWidth() + "x" + screenshot.getHeight()
                    + " bounds=" + contentBounds.toShortString()
                    + " offset=" + lp.x + "," + lp.y);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_SELECT", "overlay add failed=" + t);
            return false;
        }
    }

    public static synchronized void dismissActive(String reason) {
        WorkspaceView v = active;
        active = null;
        if (v != null) v.close(reason == null ? "dismiss" : reason);
    }

    private static synchronized void onClosed(WorkspaceView v) {
        if (active == v) active = null;
    }

    private static final class WorkspaceView extends View {
        private static final int MODE_NONE = 0;
        private static final int MODE_TEXT = 1;
        private static final int MODE_START_HANDLE = 2;
        private static final int MODE_END_HANDLE = 3;
        private static final int MODE_CIRCLE = 4;
        private static final long RECT_SNAP_PREVIEW_MS = 170L;

        private final Context context;
        private final WindowManager wm;
        private final Bitmap screenshot;
        private final Runnable onClosed;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint toolbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint toolbarTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<PointF> circlePoints = new ArrayList<>();
        private final RectF snappedCircleRect = new RectF();
        private final RectF closeRect = new RectF();

        private List<SpatialOcrEngine.Word> words = List.of();
        private boolean ocrReady;
        private boolean closed;
        private boolean circleResolving;
        private int mode = MODE_NONE;
        private int startIndex = -1;
        private int endIndex = -1;
        private boolean closePressed;

        WorkspaceView(Context c, WindowManager wm, Bitmap screenshot, Runnable onClosed) {
            super(c);
            context = c;
            this.wm = wm;
            this.screenshot = screenshot;
            this.onClosed = onClosed;
            setClickable(true);
            setFocusable(false);

            shadePaint.setColor(0x33000000);
            selectedPaint.setColor(0x884285F4);
            selectedPaint.setStyle(Paint.Style.FILL);
            linePaint.setColor(Color.WHITE);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(3));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            handlePaint.setColor(0xFF4285F4);
            handlePaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(15));
            textPaint.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            toolbarPaint.setColor(0xEE202124);
            toolbarPaint.setStyle(Paint.Style.FILL);
            toolbarTextPaint.setColor(Color.WHITE);
            toolbarTextPaint.setTextSize(dp(14));
            toolbarTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        void startSpatialOcr() {
            SpatialOcrEngine.recognize(context, screenshot, new SpatialOcrEngine.Callback() {
                @Override public void onSuccess(List<SpatialOcrEngine.Word> result) {
                    if (closed) return;
                    words = result == null ? List.of() : result;
                    ocrReady = true;
                    DiagnosticLog.i(context, "CIRCLE_SELECT", "spatial words=" + words.size());
                    invalidate();
                }

                @Override public void onFailure(Throwable error) {
                    if (closed) return;
                    ocrReady = true;
                    words = List.of();
                    DiagnosticLog.i(context, "CIRCLE_SELECT", "spatial OCR unavailable=" + safe(error));
                    invalidate();
                }
            });
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawBitmap(screenshot, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);
            canvas.drawRect(0, 0, getWidth(), getHeight(), shadePaint);

            if (hasTextSelection()) {
                int lo = Math.min(startIndex, endIndex);
                int hi = Math.max(startIndex, endIndex);
                for (int i = lo; i <= hi && i < words.size(); i++) {
                    canvas.drawRoundRect(toViewRect(words.get(i).bounds()), dp(2), dp(2), selectedPaint);
                }
                drawHandles(canvas, lo, hi);
            }

            if (circlePoints.size() > 1) {
                Path p = new Path();
                p.moveTo(circlePoints.get(0).x, circlePoints.get(0).y);
                for (int i = 1; i < circlePoints.size(); i++) p.lineTo(circlePoints.get(i).x, circlePoints.get(i).y);
                canvas.drawPath(p, linePaint);
            }

            if (!snappedCircleRect.isEmpty()) {
                canvas.drawRoundRect(snappedCircleRect, dp(8), dp(8), selectedPaint);
                canvas.drawRoundRect(snappedCircleRect, dp(8), dp(8), linePaint);
            }

            String status;
            if (circleResolving) status = "已自动吸附为矩形…";
            else if (!ocrReady) status = "正在识别图片文字… · 空白处可直接圈选";
            else if (words.isEmpty()) status = "未检测到可选文字 · 圈画松手自动变为矩形";
            else status = "点按/拖动图片文字直接选择 · 圈画自动吸附矩形";
            canvas.drawText(status, dp(16), dp(34), textPaint);
            drawClose(canvas);
        }

        private void drawClose(Canvas c) {
            float size = dp(38);
            closeRect.set(getWidth() - size - dp(12), dp(10), getWidth() - dp(12), dp(10) + size);
            c.drawRoundRect(closeRect, size / 2f, size / 2f, toolbarPaint);
            Paint p = new Paint(toolbarTextPaint);
            p.setTextSize(dp(22));
            c.drawText("×", closeRect.centerX(), closeRect.centerY() + dp(7), p);
        }

        private void drawHandles(Canvas c, int lo, int hi) {
            if (lo < 0 || hi < 0 || lo >= words.size() || hi >= words.size()) return;
            RectF first = toViewRect(words.get(lo).bounds());
            RectF last = toViewRect(words.get(hi).bounds());
            c.drawCircle(first.left, first.bottom + dp(7), dp(6), handlePaint);
            c.drawCircle(last.right, last.bottom + dp(7), dp(6), handlePaint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (closed) return true;
            if (circleResolving) return true;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    snappedCircleRect.setEmpty();
                    if (closeRect.contains(x, y)) {
                        closePressed = true;
                        return true;
                    }
                    closePressed = false;
                    FloatActionMenu.dismiss();

                    if (hasTextSelection()) {
                        int handle = hitSelectionHandle(x, y);
                        if (handle != MODE_NONE) {
                            mode = handle;
                            return true;
                        }
                    }

                    int hit = findWordAt(x, y);
                    if (hit >= 0) {
                        startIndex = endIndex = hit;
                        mode = MODE_TEXT;
                        circlePoints.clear();
                        invalidate();
                        return true;
                    }

                    startIndex = endIndex = -1;
                    mode = MODE_CIRCLE;
                    circlePoints.clear();
                    circlePoints.add(new PointF(x, y));
                    invalidate();
                    return true;
                }

                case MotionEvent.ACTION_MOVE -> {
                    if (closePressed) return true;
                    if (mode == MODE_TEXT || mode == MODE_START_HANDLE || mode == MODE_END_HANDLE) {
                        int hit = findWordAt(x, y);
                        if (hit >= 0) {
                            if (mode == MODE_START_HANDLE) startIndex = hit;
                            else endIndex = hit;
                            invalidate();
                        }
                        return true;
                    }
                    if (mode == MODE_CIRCLE) {
                        PointF last = circlePoints.isEmpty() ? null : circlePoints.get(circlePoints.size() - 1);
                        if (last == null || Math.abs(last.x - x) >= 1f || Math.abs(last.y - y) >= 1f) {
                            circlePoints.add(new PointF(x, y));
                            invalidate();
                        }
                        return true;
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP -> {
                    if (closePressed) {
                        closePressed = false;
                        if (closeRect.contains(x, y)) close("user_close");
                        return true;
                    }

                    if (mode == MODE_CIRCLE) {
                        circlePoints.add(new PointF(x, y));
                        RectF snapped = snapCircleToRectangle();
                        circlePoints.clear();
                        mode = MODE_NONE;
                        if (snapped != null) {
                            snappedCircleRect.set(snapped);
                            circleResolving = true;
                            invalidate();
                            DiagnosticLog.i(context, "CIRCLE_SELECT", "circle snap rect="
                                    + Math.round(snapped.left) + "," + Math.round(snapped.top) + "-"
                                    + Math.round(snapped.right) + "," + Math.round(snapped.bottom));
                            postDelayed(() -> finishSnappedCircle(new RectF(snapped)), RECT_SNAP_PREVIEW_MS);
                        } else invalidate();
                        return true;
                    }

                    if (mode == MODE_TEXT || mode == MODE_START_HANDLE || mode == MODE_END_HANDLE) {
                        mode = MODE_NONE;
                        String selected = selectedText();
                        DiagnosticLog.i(context, "CIRCLE_TEXT", "selected chars=" + selected.length()
                                + " range=" + Math.min(startIndex, endIndex) + ".." + Math.max(startIndex, endIndex));
                        invalidate();
                        if (!selected.isBlank()) {
                            FloatActionMenu.showText(context, selected, () -> {
                                if (words.isEmpty()) return;
                                startIndex = 0;
                                endIndex = words.size() - 1;
                                invalidate();
                                post(() -> FloatActionMenu.showText(context, selectedText(), null));
                            });
                        }
                        return true;
                    }
                    mode = MODE_NONE;
                    return true;
                }

                case MotionEvent.ACTION_CANCEL -> {
                    closePressed = false;
                    mode = MODE_NONE;
                    circlePoints.clear();
                    snappedCircleRect.setEmpty();
                    invalidate();
                    return true;
                }
            }
            return true;
        }

        private int hitSelectionHandle(float x, float y) {
            int lo = Math.min(startIndex, endIndex);
            int hi = Math.max(startIndex, endIndex);
            if (lo < 0 || hi < 0 || lo >= words.size() || hi >= words.size()) return MODE_NONE;
            RectF first = toViewRect(words.get(lo).bounds());
            RectF last = toViewRect(words.get(hi).bounds());
            float r = dp(24);
            if (distance(x, y, first.left, first.bottom + dp(7)) <= r) {
                return startIndex <= endIndex ? MODE_START_HANDLE : MODE_END_HANDLE;
            }
            if (distance(x, y, last.right, last.bottom + dp(7)) <= r) {
                return startIndex <= endIndex ? MODE_END_HANDLE : MODE_START_HANDLE;
            }
            return MODE_NONE;
        }

        private int findWordAt(float viewX, float viewY) {
            if (words.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return -1;
            int bx = Math.round(viewX * screenshot.getWidth() / (float) getWidth());
            int by = Math.round(viewY * screenshot.getHeight() / (float) getHeight());
            int best = -1;
            long bestArea = Long.MAX_VALUE;
            for (int i = 0; i < words.size(); i++) {
                Rect r = words.get(i).bounds();
                if (!r.contains(bx, by)) continue;
                long area = Math.max(1L, (long) r.width() * r.height());
                if (area < bestArea) {
                    bestArea = area;
                    best = i;
                }
            }
            return best;
        }

        private String selectedText() {
            if (!hasTextSelection()) return "";
            int lo = Math.min(startIndex, endIndex);
            int hi = Math.max(startIndex, endIndex);
            StringBuilder out = new StringBuilder();
            int previousLine = -1;
            String previous = "";
            for (int i = lo; i <= hi && i < words.size(); i++) {
                SpatialOcrEngine.Word w = words.get(i);
                String value = w.text();
                if (value.isBlank()) continue;
                if (out.length() > 0) {
                    if (w.line() != previousLine) out.append('\n');
                    else if (!noSpaceBetween(previous, value)) out.append(' ');
                }
                out.append(value);
                previousLine = w.line();
                previous = value;
            }
            return out.toString().trim();
        }

        private boolean noSpaceBetween(String a, String b) {
            if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
            int ac = a.codePointBefore(a.length());
            int bc = b.codePointAt(0);
            return isCjk(ac) && isCjk(bc);
        }

        private boolean isCjk(int cp) {
            return (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0xF900 && cp <= 0xFAFF)
                    || (cp >= 0x20000 && cp <= 0x2FA1F);
        }

        private boolean hasTextSelection() {
            return startIndex >= 0 && endIndex >= 0 && !words.isEmpty()
                    && startIndex < words.size() && endIndex < words.size();
        }

        private RectF toViewRect(Rect imageRect) {
            float sx = getWidth() / (float) Math.max(1, screenshot.getWidth());
            float sy = getHeight() / (float) Math.max(1, screenshot.getHeight());
            return new RectF(imageRect.left * sx, imageRect.top * sy,
                    imageRect.right * sx, imageRect.bottom * sy);
        }

        private RectF snapCircleToRectangle() {
            if (circlePoints.size() < 4 || getWidth() <= 0 || getHeight() <= 0) return null;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (PointF p : circlePoints) {
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
            }
            float w = maxX - minX;
            float h = maxY - minY;
            if (w < dp(24) || h < dp(24)) return null;
            float padding = Math.max(dp(6), Math.min(dp(18), Math.min(w, h) * 0.08f));
            float left = Math.max(0f, minX - padding);
            float top = Math.max(0f, minY - padding);
            float right = Math.min(getWidth(), maxX + padding);
            float bottom = Math.min(getHeight(), maxY + padding);
            if (right - left < dp(24) || bottom - top < dp(24)) return null;
            return new RectF(left, top, right, bottom);
        }

        private void finishSnappedCircle(RectF viewRect) {
            if (closed) return;
            Bitmap crop = createRectangularCrop(viewRect);
            circleResolving = false;
            snappedCircleRect.setEmpty();
            if (crop == null) {
                invalidate();
                return;
            }
            DiagnosticLog.i(context, "CIRCLE_SELECT", "snapped rectangle -> OCR "
                    + crop.getWidth() + "x" + crop.getHeight());
            close("circle_rect_ocr");
            OcrEngine.recognize(context, crop);
        }

        private Bitmap createRectangularCrop(RectF viewRect) {
            if (viewRect == null || viewRect.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return null;
            float sx = screenshot.getWidth() / (float) getWidth();
            float sy = screenshot.getHeight() / (float) getHeight();
            int left = clamp((int) Math.floor(viewRect.left * sx), 0, screenshot.getWidth() - 1);
            int top = clamp((int) Math.floor(viewRect.top * sy), 0, screenshot.getHeight() - 1);
            int right = clamp((int) Math.ceil(viewRect.right * sx), left + 1, screenshot.getWidth());
            int bottom = clamp((int) Math.ceil(viewRect.bottom * sy), top + 1, screenshot.getHeight());
            int w = right - left;
            int h = bottom - top;
            if (w <= 1 || h <= 1) return null;
            return Bitmap.createBitmap(screenshot, left, top, w, h);
        }

        void close(String reason) {
            if (closed) return;
            closed = true;
            FloatActionMenu.dismiss();
            removeCallbacks(null);
            try { wm.removeView(this); } catch (Throwable ignored) {}
            try { if (!screenshot.isRecycled()) screenshot.recycle(); } catch (Throwable ignored) {}
            CircleSelectOverlay.onClosed(this);
            if (onClosed != null) {
                try { onClosed.run(); } catch (Throwable ignored) {}
            }
            DiagnosticLog.i(context, "CIRCLE_SELECT", "closed reason=" + reason);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
        private float distance(float x1, float y1, float x2, float y2) {
            return (float) Math.hypot(x1 - x2, y1 - y2);
        }
        private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
        private String safe(Throwable t) {
            if (t == null) return "unknown";
            String m = t.getMessage();
            return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
        }
    }

    private CircleSelectOverlay() {}
}
