package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Google-style frozen-screen selector whose resolved content stays on the original screen.
 *
 * Unlike the compatibility ResultActivity pipeline, this surface never launches a result popup.
 * Text is highlighted/rendered at its screen anchor and images are marked at their original bounds.
 */
final class GoogleCircleInlineOverlay {
    private static WorkspaceView active;

    static synchronized boolean show(Context c, GoogleCircleCapture.Frame frame,
                                     GoogleCircleContentSnapshot content, Runnable onClosed) {
        if (c == null || frame == null || frame.bitmap == null || frame.bitmap.isRecycled()) {
            return false;
        }
        dismissActive("replace");
        Context app = c.getApplicationContext();
        FlOverlayWindowHost host = new FlOverlayWindowHost(app);
        Rect display = ScreenGeometry.displayBounds(app);
        Rect bounds = frame.screenBounds;
        boolean shadeExpanded = FlSystemPanelController.notificationShadeExpanded();

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (shadeExpanded) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.max(1, bounds.width()), Math.max(1, bounds.height()),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = bounds.left - display.left;
        lp.y = bounds.top - display.top;

        GoogleCircleContentSnapshot safeContent = content == null
                ? GoogleCircleContentSnapshot.empty(display) : content;
        WorkspaceView view = new WorkspaceView(app, host, lp, frame, safeContent,
                onClosed, !shadeExpanded);
        if (!host.add(view, lp, "google_circle_inline")) return false;
        active = view;
        if (!shadeExpanded) view.promoteKeyFocus("initial");
        DiagnosticLog.i(app, "G_CIRCLE_INLINE", "overlay shown frame=" + bounds.toShortString()
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " semanticText=" + safeContent.textCount()
                + " semanticImages=" + safeContent.imageCount()
                + " presentation=original_position");
        return true;
    }

    static synchronized void promoteActiveFocus(String reason) {
        WorkspaceView view = active;
        if (view != null) view.promoteKeyFocus(reason);
    }

    static synchronized void dismissActive(String reason) {
        WorkspaceView view = active;
        active = null;
        if (view != null) view.close(reason == null ? "dismiss" : reason);
    }

    private static synchronized void onClosed(WorkspaceView view) {
        if (active == view) active = null;
    }

    private static final class WorkspaceView extends View {
        private static final int MODE_NONE = 0;
        private static final int MODE_DRAW = 1;
        private static final int MODE_MOVE = 2;
        private static final int MODE_LEFT = 3;
        private static final int MODE_TOP = 4;
        private static final int MODE_RIGHT = 5;
        private static final int MODE_BOTTOM = 6;

        private final Context context;
        private final FlOverlayWindowHost host;
        private final WindowManager.LayoutParams windowLayout;
        private final GoogleCircleCapture.Frame frame;
        private final GoogleCircleContentSnapshot content;
        private final Runnable onClosed;

        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint resultFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint resultBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint resultTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint resultTagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint resultTagTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final ArrayList<PointF> stroke = new ArrayList<>();
        private final RectF closeRect = new RectF();

        private GoogleCircleSelection.Selection selection;
        private RectF editOrigin;
        private PointF editStart;
        private int editMode = MODE_NONE;
        private int recognitionGeneration;
        private boolean recognizing;
        private boolean closed;
        private boolean closePressed;
        private boolean keyFocusEnabled;

        private GoogleCircleResultCoordinator.Kind inlineKind;
        private Rect inlineAnchor = new Rect();
        private String inlineText = "";
        private String inlineSource = "";

        WorkspaceView(Context c, FlOverlayWindowHost host,
                      WindowManager.LayoutParams windowLayout,
                      GoogleCircleCapture.Frame frame,
                      GoogleCircleContentSnapshot content,
                      Runnable onClosed, boolean keyFocusEnabled) {
            super(c);
            context = c;
            this.host = host;
            this.windowLayout = windowLayout;
            this.frame = frame;
            this.content = content;
            this.onClosed = onClosed;
            this.keyFocusEnabled = keyFocusEnabled;
            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);

            shadePaint.setColor(0x50000000);

            strokeGlow.setColor(0x664285F4);
            strokeGlow.setStyle(Paint.Style.STROKE);
            strokeGlow.setStrokeWidth(dp(10));
            strokeGlow.setStrokeCap(Paint.Cap.ROUND);
            strokeGlow.setStrokeJoin(Paint.Join.ROUND);

            strokePaint.setColor(Color.WHITE);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(3));
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            selectionPaint.setColor(Color.WHITE);
            selectionPaint.setStyle(Paint.Style.STROKE);
            selectionPaint.setStrokeWidth(dp(2));

            handlePaint.setColor(0xFF4285F4);
            handlePaint.setStyle(Paint.Style.FILL);

            closePaint.setColor(0xD9222222);
            closeGlyphPaint.setColor(Color.WHITE);
            closeGlyphPaint.setStyle(Paint.Style.STROKE);
            closeGlyphPaint.setStrokeWidth(dp(2));
            closeGlyphPaint.setStrokeCap(Paint.Cap.ROUND);

            hintPaint.setColor(0xD9222222);
            hintTextPaint.setColor(Color.WHITE);
            hintTextPaint.setTextSize(dp(14));
            hintTextPaint.setTextAlign(Paint.Align.CENTER);

            resultFillPaint.setColor(0x664285F4);
            resultFillPaint.setStyle(Paint.Style.FILL);
            resultBorderPaint.setColor(0xFF8AB4F8);
            resultBorderPaint.setStyle(Paint.Style.STROKE);
            resultBorderPaint.setStrokeWidth(dp(2.5f));
            resultTextPaint.setColor(Color.WHITE);
            resultTextPaint.setTextSize(dp(14));
            resultTagPaint.setColor(0xE02B2B2B);
            resultTagPaint.setStyle(Paint.Style.FILL);
            resultTagTextPaint.setColor(Color.WHITE);
            resultTagTextPaint.setTextSize(dp(12));
        }

        void promoteKeyFocus(String reason) {
            if (closed) return;
            if (!keyFocusEnabled) {
                windowLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                if (!host.update(this, windowLayout, "google_circle_inline_focus")) return;
                keyFocusEnabled = true;
            }
            post(() -> {
                if (!closed && isAttachedToWindow()) requestFocus();
            });
            DiagnosticLog.i(context, "G_CIRCLE_INLINE", "focus promoted reason=" + reason);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap bitmap = frame.bitmap;
            if (bitmap == null || bitmap.isRecycled()) return;
            canvas.drawBitmap(bitmap, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);

            if (selection != null) {
                RectF selected = frame.bitmapToView(selection.bounds, getWidth(), getHeight());
                drawOutsideShade(canvas, selected);
                canvas.drawRoundRect(selected, dp(10), dp(10), selectionPaint);
                float radius = dp(6);
                canvas.drawCircle(selected.left, selected.centerY(), radius, handlePaint);
                canvas.drawCircle(selected.right, selected.centerY(), radius, handlePaint);
                canvas.drawCircle(selected.centerX(), selected.top, radius, handlePaint);
                canvas.drawCircle(selected.centerX(), selected.bottom, radius, handlePaint);
            } else {
                canvas.drawColor(0x18000000);
            }

            drawInlineResult(canvas);
            if (!stroke.isEmpty()) drawStroke(canvas);
            drawClose(canvas);
            drawHint(canvas);
        }

        private void drawOutsideShade(Canvas canvas, RectF r) {
            canvas.drawRect(0, 0, getWidth(), Math.max(0, r.top), shadePaint);
            canvas.drawRect(0, Math.min(getHeight(), r.bottom), getWidth(), getHeight(), shadePaint);
            canvas.drawRect(0, Math.max(0, r.top), Math.max(0, r.left),
                    Math.min(getHeight(), r.bottom), shadePaint);
            canvas.drawRect(Math.min(getWidth(), r.right), Math.max(0, r.top),
                    getWidth(), Math.min(getHeight(), r.bottom), shadePaint);
        }

        private void drawStroke(Canvas canvas) {
            Path path = new Path();
            boolean first = true;
            for (PointF p : stroke) {
                PointF view = frame.bitmapToView(p.x, p.y, getWidth(), getHeight());
                if (first) {
                    path.moveTo(view.x, view.y);
                    first = false;
                } else {
                    path.lineTo(view.x, view.y);
                }
            }
            canvas.drawPath(path, strokeGlow);
            canvas.drawPath(path, strokePaint);
        }

        private void drawInlineResult(Canvas canvas) {
            if (inlineKind == null || inlineAnchor == null || inlineAnchor.isEmpty()) return;
            RectF target = screenToView(inlineAnchor);
            if (target.isEmpty()) return;

            canvas.drawRoundRect(target, dp(7), dp(7), resultFillPaint);
            canvas.drawRoundRect(target, dp(7), dp(7), resultBorderPaint);

            if (inlineKind == GoogleCircleResultCoordinator.Kind.IMAGE) {
                drawTag(canvas, target, "图片");
                return;
            }

            if (inlineText == null || inlineText.isBlank()) {
                drawTag(canvas, target, "文字");
                return;
            }
            drawTextAtOriginalPosition(canvas, target, inlineText);
        }

        private void drawTextAtOriginalPosition(Canvas canvas, RectF target, String text) {
            float padding = dp(6);
            float availableWidth = target.width() - padding * 2f;
            float availableHeight = target.height() - padding * 2f;
            if (availableWidth < dp(24) || availableHeight < dp(16)) {
                drawTag(canvas, target, "文字");
                return;
            }

            float size = Math.min(dp(16), Math.max(dp(11), target.height() * 0.24f));
            resultTextPaint.setTextSize(size);
            Paint.FontMetrics fm = resultTextPaint.getFontMetrics();
            float lineHeight = Math.max(dp(13), (fm.descent - fm.ascent) * 1.08f);
            int maxLines = Math.max(1, (int) Math.floor(availableHeight / lineHeight));
            float baseline = target.top + padding - fm.ascent;
            float left = target.left + padding;
            int lines = 0;

            canvas.save();
            canvas.clipRect(target);
            String normalized = text.replace('\r', '\n').replace('\t', ' ').trim();
            String[] paragraphs = normalized.split("\\n", -1);
            outer:
            for (String paragraph : paragraphs) {
                String value = paragraph.trim();
                if (value.isEmpty()) continue;
                int offset = 0;
                while (offset < value.length()) {
                    int count = resultTextPaint.breakText(value, offset, value.length(),
                            true, availableWidth, null);
                    if (count <= 0) break;
                    String line = value.substring(offset, offset + count).trim();
                    if (!line.isEmpty()) {
                        canvas.drawText(line, left, baseline + lines * lineHeight, resultTextPaint);
                        lines++;
                        if (lines >= maxLines) break outer;
                    }
                    offset += count;
                    while (offset < value.length() && Character.isWhitespace(value.charAt(offset))) {
                        offset++;
                    }
                }
            }
            canvas.restore();

            if (lines == 0) drawTag(canvas, target, "文字");
        }

        private void drawTag(Canvas canvas, RectF target, String label) {
            float padX = dp(7);
            float padY = dp(4);
            float textWidth = resultTagTextPaint.measureText(label);
            Paint.FontMetrics fm = resultTagTextPaint.getFontMetrics();
            float height = fm.descent - fm.ascent + padY * 2f;
            float width = textWidth + padX * 2f;
            float left = target.left + dp(4);
            float top = target.top + dp(4);
            if (left + width > getWidth()) left = Math.max(0, getWidth() - width - dp(4));
            if (top + height > getHeight()) top = Math.max(0, getHeight() - height - dp(4));
            RectF tag = new RectF(left, top, left + width, top + height);
            canvas.drawRoundRect(tag, height / 2f, height / 2f, resultTagPaint);
            float baseline = tag.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, tag.left + padX, baseline, resultTagTextPaint);
        }

        private RectF screenToView(Rect screen) {
            Rect workspace = frame.screenBounds;
            float sx = getWidth() / (float) Math.max(1, workspace.width());
            float sy = getHeight() / (float) Math.max(1, workspace.height());
            float left = (screen.left - workspace.left) * sx;
            float top = (screen.top - workspace.top) * sy;
            float right = (screen.right - workspace.left) * sx;
            float bottom = (screen.bottom - workspace.top) * sy;
            RectF out = new RectF(left, top, right, bottom);
            out.left = Math.max(0f, Math.min(getWidth(), out.left));
            out.top = Math.max(0f, Math.min(getHeight(), out.top));
            out.right = Math.max(out.left, Math.min(getWidth(), out.right));
            out.bottom = Math.max(out.top, Math.min(getHeight(), out.bottom));
            return out;
        }

        private void drawClose(Canvas canvas) {
            float size = dp(42);
            float margin = dp(14);
            closeRect.set(margin, margin, margin + size, margin + size);
            canvas.drawOval(closeRect, closePaint);
            float cx = closeRect.centerX();
            float cy = closeRect.centerY();
            float d = dp(7);
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, closeGlyphPaint);
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, closeGlyphPaint);
        }

        private void drawHint(Canvas canvas) {
            String text;
            if (recognizing) {
                text = "正在识别所选内容…";
            } else if (inlineKind == GoogleCircleResultCoordinator.Kind.IMAGE) {
                text = "已在原位置选中图片 · 可继续调整";
            } else if (inlineKind != null) {
                text = "已在原位置显示文字 · 可继续调整";
            } else if (selection == null) {
                text = "圈画 · 涂抹 · 高亮 · 点击";
            } else {
                text = kindLabel(selection.kind) + "  ·  可拖动选区或边界";
            }
            float width = Math.min(getWidth() - dp(32), hintTextPaint.measureText(text) + dp(30));
            float height = dp(38);
            float left = (getWidth() - width) / 2f;
            float top = Math.max(dp(70), getHeight() - height - dp(22));
            RectF pill = new RectF(left, top, left + width, top + height);
            canvas.drawRoundRect(pill, height / 2f, height / 2f, hintPaint);
            Paint.FontMetrics fm = hintTextPaint.getFontMetrics();
            float baseline = pill.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, pill.centerX(), baseline, hintTextPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (closed || event == null) return false;
            float x = event.getX();
            float y = event.getY();
            PointF point = frame.viewToBitmap(x, y, getWidth(), getHeight());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    closePressed = closeRect.contains(x, y);
                    if (closePressed) return true;
                    recognitionGeneration++;
                    recognizing = false;
                    clearInlineResult();
                    if (selection != null) {
                        int hit = hitEditMode(point);
                        if (hit != MODE_NONE) {
                            editMode = hit;
                            editStart = point;
                            editOrigin = new RectF(selection.bounds);
                            invalidate();
                            return true;
                        }
                    }
                    selection = null;
                    stroke.clear();
                    stroke.add(point);
                    editMode = MODE_DRAW;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (closePressed) return true;
                    if (editMode == MODE_DRAW) {
                        addStrokePoint(point);
                    } else if (editMode != MODE_NONE) {
                        updateEditedSelection(point);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (closePressed) {
                        boolean close = closeRect.contains(x, y);
                        closePressed = false;
                        if (close) close("user_close");
                        return true;
                    }
                    if (editMode == MODE_DRAW) {
                        addStrokePoint(point);
                        finishStroke();
                    } else if (editMode != MODE_NONE && selection != null) {
                        scheduleRecognition(260L);
                    }
                    editMode = MODE_NONE;
                    editOrigin = null;
                    editStart = null;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    closePressed = false;
                    editMode = MODE_NONE;
                    editOrigin = null;
                    editStart = null;
                    stroke.clear();
                    invalidate();
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }

        private void addStrokePoint(PointF p) {
            if (stroke.isEmpty()) {
                stroke.add(p);
                return;
            }
            PointF last = stroke.get(stroke.size() - 1);
            float min = bitmapPxForDp(2f);
            if (Math.hypot(p.x - last.x, p.y - last.y) >= min) stroke.add(p);
        }

        private void finishStroke() {
            float tapSlop = bitmapPxForDp(12f);
            float minShape = bitmapPxForDp(34f);
            selection = GoogleCircleSelection.fromStroke(stroke,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight(), tapSlop, minShape,
                    bitmapPxForDp(92f), bitmapPxForDp(70f));
            stroke.clear();
            if (selection == null) return;
            DiagnosticLog.i(context, "G_CIRCLE_GESTURE", "kind=" + selection.kind
                    + " bounds=" + selection.bounds.toShortString()
                    + " presentation=inline");
            scheduleRecognition(220L);
        }

        private int hitEditMode(PointF p) {
            if (selection == null) return MODE_NONE;
            RectF r = selection.bounds;
            float slop = bitmapPxForDp(24f);
            boolean yInside = p.y >= r.top - slop && p.y <= r.bottom + slop;
            boolean xInside = p.x >= r.left - slop && p.x <= r.right + slop;
            if (yInside && Math.abs(p.x - r.left) <= slop) return MODE_LEFT;
            if (yInside && Math.abs(p.x - r.right) <= slop) return MODE_RIGHT;
            if (xInside && Math.abs(p.y - r.top) <= slop) return MODE_TOP;
            if (xInside && Math.abs(p.y - r.bottom) <= slop) return MODE_BOTTOM;
            if (r.contains(p.x, p.y)) return MODE_MOVE;
            return MODE_NONE;
        }

        private void updateEditedSelection(PointF p) {
            if (selection == null || editOrigin == null || editStart == null) return;
            RectF r = new RectF(editOrigin);
            float dx = p.x - editStart.x;
            float dy = p.y - editStart.y;
            float min = bitmapPxForDp(42f);
            switch (editMode) {
                case MODE_MOVE -> r.offset(dx, dy);
                case MODE_LEFT -> r.left = Math.min(r.right - min, editOrigin.left + dx);
                case MODE_TOP -> r.top = Math.min(r.bottom - min, editOrigin.top + dy);
                case MODE_RIGHT -> r.right = Math.max(r.left + min, editOrigin.right + dx);
                case MODE_BOTTOM -> r.bottom = Math.max(r.top + min, editOrigin.bottom + dy);
                default -> {
                    return;
                }
            }
            selection = selection.withBounds(GoogleCircleSelection.clampEditable(r,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight(), min));
        }

        private void scheduleRecognition(long delayMs) {
            if (selection == null || closed) return;
            int generation = ++recognitionGeneration;
            recognizing = true;
            clearInlineResult();
            invalidate();
            GoogleCircleSelection.Selection requestSelection = selection;
            postDelayed(() -> {
                if (closed || generation != recognitionGeneration || selection == null) return;
                GoogleCircleResultCoordinator.resolve(context, frame, content, requestSelection,
                        resolution -> post(() -> onResolution(generation, requestSelection, resolution)));
            }, delayMs);
        }

        private void onResolution(int generation,
                                  GoogleCircleSelection.Selection requestSelection,
                                  GoogleCircleResultCoordinator.Resolution resolution) {
            if (resolution == null) return;
            if (closed || generation != recognitionGeneration || selection != requestSelection) {
                recycle(resolution.crop);
                return;
            }

            recognizing = false;
            inlineKind = resolution.kind;
            inlineAnchor = resolution.screenAnchor == null
                    ? new Rect() : new Rect(resolution.screenAnchor);
            inlineText = resolution.text == null ? "" : resolution.text.trim();
            inlineSource = resolution.source == null ? "" : resolution.source;
            recycle(resolution.crop);

            boolean shown = inlineKind != null && !inlineAnchor.isEmpty()
                    && (inlineKind == GoogleCircleResultCoordinator.Kind.IMAGE
                    || !inlineText.isBlank());
            DiagnosticLog.i(context, "G_CIRCLE_RESULT", "gesture=" + requestSelection.kind
                    + " content=" + resolution.kind
                    + " source=" + inlineSource
                    + " textChars=" + inlineText.length()
                    + " shown=" + shown
                    + " presentation=inline_original_position"
                    + " error=" + (resolution.error == null ? "none"
                    : ScreenCaptureBackend.safeMessage(resolution.error)));

            if (!shown) {
                clearInlineResult();
                Toast.makeText(context, "未识别到可显示的内容", Toast.LENGTH_SHORT).show();
            }
            invalidate();
        }

        private void clearInlineResult() {
            inlineKind = null;
            inlineAnchor = new Rect();
            inlineText = "";
            inlineSource = "";
        }

        @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                close("back");
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }

        void close(String reason) {
            if (closed) return;
            closed = true;
            recognitionGeneration++;
            OcrEngine.invalidateDocumentPending(context, "google_circle_inline_close");
            host.remove(this, "google_circle_inline");
            frame.recycle();
            GoogleCircleInlineOverlay.onClosed(this);
            if (onClosed != null) onClosed.run();
            DiagnosticLog.i(context, "G_CIRCLE_INLINE", "closed reason=" + reason);
        }

        private float bitmapPxForDp(float value) {
            float screenPx = dp(value);
            float sx = frame.bitmap.getWidth() / (float) Math.max(1, frame.screenBounds.width());
            float sy = frame.bitmap.getHeight() / (float) Math.max(1, frame.screenBounds.height());
            return screenPx * (sx + sy) * 0.5f;
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private static String kindLabel(GoogleCircleSelection.Kind kind) {
            return switch (kind) {
                case TAP -> "点击选择";
                case CIRCLE -> "圈选";
                case HIGHLIGHT -> "高亮选择";
                case SCRIBBLE -> "涂抹选择";
            };
        }

        private static void recycle(Bitmap bitmap) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private GoogleCircleInlineOverlay() {}
}
