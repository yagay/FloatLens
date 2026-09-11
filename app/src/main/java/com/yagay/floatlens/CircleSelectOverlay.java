package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Frozen-screen Circle Select workspace.
 *
 * Touch a recognized OCR word to select it directly on the screenshot, drag across more words to
 * extend the selection, or drag either selection handle to refine the range. Starting on empty
 * space enters free-form circle selection and sends that masked crop through the normal OcrEngine.
 */
public final class CircleSelectOverlay {
    private static WorkspaceView active;

    public static synchronized boolean show(Context c, Bitmap screenshot, Runnable onClosed) {
        if (screenshot == null || screenshot.isRecycled()) return false;
        dismissActive("replace");
        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        WorkspaceView view = new WorkspaceView(app, wm, screenshot, onClosed);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(view, lp);
            active = view;
            view.startSpatialOcr();
            DiagnosticLog.i(app, "CIRCLE_SELECT", "overlay shown "
                    + screenshot.getWidth() + "x" + screenshot.getHeight());
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

        private static final int ACTION_NONE = 0;
        private static final int ACTION_COPY = 1;
        private static final int ACTION_SHARE = 2;
        private static final int ACTION_ALL = 3;
        private static final int ACTION_CLOSE = 4;

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
        private final RectF closeRect = new RectF();
        private final RectF[] actionRects = {new RectF(), new RectF(), new RectF()};

        private List<SpatialOcrEngine.Word> words = List.of();
        private boolean ocrReady;
        private boolean closed;
        private int mode = MODE_NONE;
        private int startIndex = -1;
        private int endIndex = -1;
        private int pressedAction = ACTION_NONE;

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
                drawToolbar(canvas);
            }

            if (circlePoints.size() > 1) {
                Path p = new Path();
                p.moveTo(circlePoints.get(0).x, circlePoints.get(0).y);
                for (int i = 1; i < circlePoints.size(); i++) p.lineTo(circlePoints.get(i).x, circlePoints.get(i).y);
                canvas.drawPath(p, linePaint);
            }

            String status;
            if (!ocrReady) status = "正在识别图片文字… · 空白处可直接圈选";
            else if (words.isEmpty()) status = "未检测到可选文字 · 在空白处圈选识别";
            else status = "点按/拖动图片文字直接选择 · 空白处圈选";
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

        private void drawToolbar(Canvas c) {
            float top = dp(54);
            float left = dp(14);
            float h = dp(42);
            float gap = dp(7);
            float w = Math.min(dp(88), (getWidth() - dp(28) - gap * 2) / 3f);
            String[] labels = {"复制", "分享", "全选"};
            for (int i = 0; i < 3; i++) {
                actionRects[i].set(left + i * (w + gap), top, left + i * (w + gap) + w, top + h);
                c.drawRoundRect(actionRects[i], dp(12), dp(12), toolbarPaint);
                c.drawText(labels[i], actionRects[i].centerX(), actionRects[i].centerY() + dp(5), toolbarTextPaint);
            }
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
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (closeRect.contains(x, y)) {
                        pressedAction = ACTION_CLOSE;
                        return true;
                    }
                    int action = hitToolbar(x, y);
                    if (action != ACTION_NONE) {
                        pressedAction = action;
                        return true;
                    }
                    pressedAction = ACTION_NONE;

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
                    if (pressedAction != ACTION_NONE) return true;
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
                    if (pressedAction != ACTION_NONE) {
                        int action = pressedAction;
                        pressedAction = ACTION_NONE;
                        if (action == ACTION_CLOSE && closeRect.contains(x, y)) close("user_close");
                        else if (action != ACTION_CLOSE && hitToolbar(x, y) == action) performToolbar(action);
                        return true;
                    }

                    if (mode == MODE_CIRCLE) {
                        circlePoints.add(new PointF(x, y));
                        Bitmap crop = createMaskedCrop();
                        circlePoints.clear();
                        mode = MODE_NONE;
                        if (crop != null) {
                            DiagnosticLog.i(context, "CIRCLE_SELECT", "free circle -> OCR "
                                    + crop.getWidth() + "x" + crop.getHeight());
                            close("circle_ocr");
                            OcrEngine.recognize(context, crop);
                        } else invalidate();
                        return true;
                    }

                    if (mode == MODE_TEXT || mode == MODE_START_HANDLE || mode == MODE_END_HANDLE) {
                        mode = MODE_NONE;
                        String selected = selectedText();
                        DiagnosticLog.i(context, "CIRCLE_TEXT", "selected chars=" + selected.length()
                                + " range=" + Math.min(startIndex, endIndex) + ".." + Math.max(startIndex, endIndex));
                        invalidate();
                        return true;
                    }
                    mode = MODE_NONE;
                    return true;
                }

                case MotionEvent.ACTION_CANCEL -> {
                    pressedAction = ACTION_NONE;
                    mode = MODE_NONE;
                    circlePoints.clear();
                    invalidate();
                    return true;
                }
            }
            return true;
        }

        private int hitToolbar(float x, float y) {
            if (!hasTextSelection()) return ACTION_NONE;
            if (actionRects[0].contains(x, y)) return ACTION_COPY;
            if (actionRects[1].contains(x, y)) return ACTION_SHARE;
            if (actionRects[2].contains(x, y)) return ACTION_ALL;
            return ACTION_NONE;
        }

        private void performToolbar(int action) {
            if (action == ACTION_ALL) {
                if (!words.isEmpty()) {
                    startIndex = 0;
                    endIndex = words.size() - 1;
                    invalidate();
                }
                return;
            }
            String value = selectedText();
            if (value.isBlank()) return;
            if (action == ACTION_COPY) {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens", value));
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
            } else if (action == ACTION_SHARE) {
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, value);
                try {
                    context.startActivity(Intent.createChooser(send, "分享文字")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable t) {
                    Toast.makeText(context, "无法分享文字", Toast.LENGTH_SHORT).show();
                }
            }
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

        private Bitmap createMaskedCrop() {
            if (circlePoints.size() < 4 || getWidth() <= 0 || getHeight() <= 0) return null;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (PointF p : circlePoints) {
                minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
            }
            if (maxX - minX < dp(24) || maxY - minY < dp(24)) return null;

            float sx = screenshot.getWidth() / (float) getWidth();
            float sy = screenshot.getHeight() / (float) getHeight();
            int left = clamp(Math.round(minX * sx), 0, screenshot.getWidth() - 1);
            int top = clamp(Math.round(minY * sy), 0, screenshot.getHeight() - 1);
            int right = clamp(Math.round(maxX * sx), left + 1, screenshot.getWidth());
            int bottom = clamp(Math.round(maxY * sy), top + 1, screenshot.getHeight());
            int w = right - left, h = bottom - top;
            if (w <= 1 || h <= 1) return null;

            Bitmap crop = Bitmap.createBitmap(screenshot, left, top, w, h);
            Bitmap masked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(masked);
            canvas.drawColor(Color.WHITE);
            Path path = new Path();
            boolean first = true;
            for (PointF p : circlePoints) {
                float px = p.x * sx - left;
                float py = p.y * sy - top;
                if (first) { path.moveTo(px, py); first = false; }
                else path.lineTo(px, py);
            }
            path.close();
            canvas.save();
            canvas.clipPath(path);
            canvas.drawBitmap(crop, 0, 0, null);
            canvas.restore();
            crop.recycle();
            return masked;
        }

        void close(String reason) {
            if (closed) return;
            closed = true;
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
