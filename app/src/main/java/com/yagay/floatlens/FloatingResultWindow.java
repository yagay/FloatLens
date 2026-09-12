package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Single primary result surface for screenshot, View and OCR output.
 *
 * Surfaces that must appear while SystemUI is still expanded are first attached through the
 * AccessibilityService WindowManager (TYPE_ACCESSIBILITY_OVERLAY). Text-capable surfaces migrate
 * the exact same View to a focusable TYPE_APPLICATION_OVERLAY only after shade cleanup, so native
 * Android selection handles/magnifier continue to work without duplicating a second result UI.
 */
final class FloatingResultWindow {
    private static final long OCR_TIMEOUT_MS = 12_000L;
    private static Session active;

    static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return show(c, new Spec(Mode.SCREENSHOT, "区域截图", "", List.of(),
                image, anchor, null, false));
    }

    static boolean showOcr(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        if (c == null) return false;
        return show(c, new Spec(Mode.OCR, "OCR 结果", safe(text), safeBlocks(blocks),
                image, anchor, null, false));
    }

    static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        return showViewText(c, text, image, anchor, false);
    }

    static boolean showViewText(Context c, String text, Bitmap image, Rect anchor,
                                boolean shadeExpandedAtCapture) {
        if (c == null || text == null || text.isBlank()) return false;
        return show(c, new Spec(Mode.VIEW_TEXT, "View 内容", text, List.of(),
                image, anchor, null, shadeExpandedAtCapture));
    }

    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        return showViewImage(c, image, view, anchor, false);
    }

    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor,
                                 boolean shadeExpandedAtCapture) {
        if (c == null || image == null || image.isRecycled()) return false;
        return show(c, new Spec(Mode.VIEW_IMAGE, "View / 图标", buildViewMeta(view), List.of(),
                image, anchor, view, shadeExpandedAtCapture));
    }

    private static synchronized boolean show(Context c, Spec spec) {
        dismissActive("replace");
        Context app = c.getApplicationContext();
        boolean showText = needsText(app, spec);
        boolean shadeBootstrap = showText && spec.mode != Mode.OCR
                && (spec.shadeExpandedAtCapture || FvSystemPanelController.notificationShadeExpanded());
        Rect usable = ResultUi.usableBounds(app);
        int width = ResultUi.standardWidth(app, usable);
        int maxH = ResultUi.standardMaxHeight(app, usable);
        int titleH = ResultUi.dp(app, ResultUi.TITLE_H_DP);
        int actionsH = ResultUi.dp(app, ResultUi.ACTION_H_DP);
        int contentBudget = Math.max(ResultUi.dp(app, 90),
                maxH - titleH - actionsH - ResultUi.dp(app, ResultUi.ROOT_VPAD_DP));

        LinearLayout box = ResultUi.box(app);
        TextView title = ResultUi.heading(app, spec.title);
        box.addView(title, new LinearLayout.LayoutParams(-1, titleH));

        ImageView imageView = null;
        int imageH = 0;
        if (spec.image != null && !spec.image.isRecycled() && shouldShowImage(app, spec.mode)) {
            int imageCap = spec.mode == Mode.VIEW_IMAGE ? ResultUi.dp(app, 190) : ResultUi.dp(app, 135);
            if (spec.mode == Mode.SCREENSHOT) imageCap = contentBudget;
            imageH = ResultUi.imageHeight(app, spec.image, width, imageCap);
            imageView = new ImageView(app);
            imageView.setImageBitmap(spec.image);
            imageView.setAdjustViewBounds(false);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ImageShareUtils.attachLongPressShare(app, imageView, spec.image);
            box.addView(imageView, new LinearLayout.LayoutParams(-1, imageH));
        }

        // Important: pure screenshot results do not need a text editor at all. Constructing the
        // native Editor/ActionMode stack for every screenshot was both wasteful and made a failure
        // in text-selection setup abort otherwise valid screenshot results.
        LinearLayout textPanel = null;
        TextSelectionSurface selection = null;
        if (showText) {
            textPanel = new LinearLayout(app);
            textPanel.setOrientation(LinearLayout.VERTICAL);
            textPanel.setPadding(0, ResultUi.dp(app, 4), 0, ResultUi.dp(app, 4));
            selection = new TextSelectionSurface(app);
            textPanel.addView(selection, new LinearLayout.LayoutParams(-1, 0, 1f));
            box.addView(textPanel, new LinearLayout.LayoutParams(-1, 0));
        }

        LinearLayout actions = ResultUi.actionRow(app);
        Button ocr = canOcr(spec) ? ResultUi.button(app, "OCR") : null;
        Button copy = showText ? ResultUi.button(app, "复制全部") : null;
        Button save = canSave(spec) ? ResultUi.button(app, "保存图片") : null;
        Button close = ResultUi.button(app, "关闭");
        int count = 1 + (ocr == null ? 0 : 1) + (copy == null ? 0 : 1) + (save == null ? 0 : 1);
        if (ocr != null) actions.addView(ocr, new LinearLayout.LayoutParams(0, -1, 1));
        if (copy != null) actions.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));
        if (save != null) actions.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        int textH = showText ? Math.max(ResultUi.dp(app, 96), contentBudget - imageH) : 0;
        if (showText && textPanel != null) {
            textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, textH));
        }

        int height;
        if (spec.mode == Mode.SCREENSHOT) {
            height = Math.min(maxH, titleH + actionsH + imageH + ResultUi.dp(app, ResultUi.ROOT_VPAD_DP));
        } else {
            height = Math.min(maxH, titleH + actionsH + imageH + textH + ResultUi.dp(app, ResultUi.ROOT_VPAD_DP));
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, Math.max(ResultUi.dp(app, 158), height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        if (spec.mode == Mode.SCREENSHOT || shadeBootstrap) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
        position(app, usable, lp, spec.anchor, spec.mode == Mode.SCREENSHOT);

        FvOverlayWindowHost host = new FvOverlayWindowHost(app);
        boolean accessibilityBootstrap = spec.mode == Mode.SCREENSHOT || shadeBootstrap;
        boolean attached = accessibilityBootstrap
                ? host.add(box, lp, "result_window")
                : host.addApplication(box, lp, "result_window");
        if (!attached) return false;

        Session session = new Session(app, spec, host, box, lp, title, imageView,
                textPanel, selection, actions, ocr, copy, save, close, imageH, height, shadeBootstrap);
        active = session;
        if (selection != null) bindSelection(session);
        if (showText) setTextMode(session, spec.text, spec.blocks, false);

        if (ocr != null) ocr.setOnClickListener(v -> beginOcr(session));
        if (copy != null) copy.setOnClickListener(v -> copyAll(session));
        if (save != null) save.setOnClickListener(v -> ScreenshotController.save(app, spec.image));
        close.setOnClickListener(v -> detach(session, "close"));

        DiagnosticLog.i(app, "RESULT_WINDOW", "show mode=" + spec.mode
                + " size=" + lp.width + "x" + lp.height
                + " host=" + (host.isAccessibilityHosted() ? "accessibility" : "application")
                + " type=" + lp.type + " shadeBootstrap=" + shadeBootstrap
                + " textSurface=" + (selection != null)
                + " actions=" + count);

        if (shadeBootstrap) {
            OverlayShadeCoordinator.cleanup(app, spec.shadeExpandedAtCapture,
                    "view_result", collapsed -> box.post(() ->
                            promoteNativeTextHost(session,
                                    collapsed ? "shade_collapsed" : "shade_cleanup_exhausted")));
        }
        return true;
    }

    private static void bindSelection(Session session) {
        if (session.selection == null) return;
        session.selection.setListener(new TextSelectionSurface.Listener() {
            @Override public void onSelectionStarted() {
                FloatActionMenu.dismiss();
                FloatMenuAnchor.clear();
            }

            @Override public void onSelectionChanging() {
                FloatActionMenu.dismiss();
                FloatMenuAnchor.clear();
            }

            @Override public void onSelectionFinished(String selectedText, Rect anchorOnScreen) {
                if (session.detached || session.selection == null) return;
                String value = safe(selectedText).trim();
                if (value.isEmpty()) return;
                FloatActionMenu.showTextAt(session.app, value,
                        session.selection::selectAllText, anchorOnScreen);
            }
        });
    }

    private static boolean ensureTextSurface(Session session) {
        if (session.selection != null && session.textPanel != null) return true;
        try {
            LinearLayout panel = new LinearLayout(session.app);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(0, ResultUi.dp(session.app, 4), 0, ResultUi.dp(session.app, 4));
            TextSelectionSurface selection = new TextSelectionSurface(session.app);
            panel.addView(selection, new LinearLayout.LayoutParams(-1, 0, 1f));
            int actionIndex = Math.max(0, session.box.indexOfChild(session.actions));
            session.box.addView(panel, actionIndex, new LinearLayout.LayoutParams(-1, 0));
            session.textPanel = panel;
            session.selection = selection;
            bindSelection(session);
            DiagnosticLog.i(session.app, "RESULT_WINDOW", "text surface created lazily");
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(session.app, "RESULT_WINDOW", "text surface create failed="
                    + ScreenCaptureBackend.safeMessage(t));
            return false;
        }
    }

    private static synchronized void promoteNativeTextHost(Session session, String reason) {
        if (session == null || session.detached || active != session || !session.nativeHostDeferred) return;
        session.nativeHostDeferred = false;
        boolean ok = ensureNativeTextHost(session);
        DiagnosticLog.i(session.app, "RESULT_WINDOW", "promote native host reason=" + reason
                + " success=" + ok + " type=" + session.windowLayout.type);
    }

    private static synchronized void beginOcr(Session session) {
        if (session == null || session.detached || active != session || session.spec.image == null
                || session.spec.image.isRecycled() || session.ocrRunning) return;
        if (session.selection != null) session.selection.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();

        long gen = ++session.ocrGeneration;
        session.ocrRunning = true;
        if (session.ocrButton != null) {
            session.ocrButton.setEnabled(false);
            session.ocrButton.setText("识别中…");
        }
        Bitmap image = session.spec.image;
        Rect anchor = session.spec.anchor == null ? null : new Rect(session.spec.anchor);
        OcrResultDispatcher.register(image, (text, blocks) -> session.box.post(() ->
                onInlineOcr(session, gen, text, blocks)));

        session.box.postDelayed(() -> {
            synchronized (FloatingResultWindow.class) {
                if (session.detached || active != session || !session.ocrRunning
                        || session.ocrGeneration != gen) return;
                OcrResultDispatcher.cancel(image);
                session.ocrRunning = false;
                if (session.ocrButton != null) {
                    session.ocrButton.setEnabled(true);
                    session.ocrButton.setText("OCR");
                }
                DiagnosticLog.i(session.app, "RESULT_WINDOW", "ocr timeout gen=" + gen);
            }
        }, OCR_TIMEOUT_MS);

        OcrEngine.recognize(session.app, image, anchor);
    }

    private static synchronized void onInlineOcr(Session session, long gen,
                                                  String text, List<String> blocks) {
        if (session == null || session.detached || active != session || gen != session.ocrGeneration) return;
        session.ocrRunning = false;
        if (session.ocrButton != null) {
            session.ocrButton.setEnabled(true);
            session.ocrButton.setText("重新识别");
        }
        session.title.setText("OCR 结果");
        setTextMode(session, safe(text), safeBlocks(blocks), true);
    }

    private static void setTextMode(Session session, String text, List<String> blocks, boolean fromOcr) {
        if (session.detached || !ensureTextSurface(session)) return;
        if (!session.nativeHostDeferred) ensureNativeTextHost(session);
        String shown = text == null || text.trim().isEmpty() ? "未识别到文字" : text.trim();
        session.selection.setText(shown);
        session.textPanel.setVisibility(View.VISIBLE);

        int contentBudget = Math.max(ResultUi.dp(session.app, 100),
                session.windowHeight - ResultUi.dp(session.app, ResultUi.TITLE_H_DP)
                        - ResultUi.dp(session.app, ResultUi.ACTION_H_DP)
                        - ResultUi.dp(session.app, ResultUi.ROOT_VPAD_DP));
        int imageH = session.imageView == null ? 0 : session.imageView.getLayoutParams().height;
        if (fromOcr && session.imageView != null) {
            int newImageH = Math.min(ResultUi.dp(session.app, 130), Math.max(0, contentBudget / 2));
            session.imageView.setLayoutParams(new LinearLayout.LayoutParams(-1, newImageH));
            session.imageView.setVisibility(newImageH > 0 ? View.VISIBLE : View.GONE);
            imageH = newImageH;
        }
        int textH = Math.max(ResultUi.dp(session.app, 96), contentBudget - Math.max(0, imageH));
        session.textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, textH));
        session.box.requestLayout();

        DiagnosticLog.i(session.app, "RESULT_WINDOW", "text mode fromOcr=" + fromOcr
                + " chars=" + shown.length() + " blocks=" + (blocks == null ? 0 : blocks.size())
                + " nativeSelection=true magnifier=true deferred=" + session.nativeHostDeferred);
    }

    private static boolean ensureNativeTextHost(Session session) {
        session.windowLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        session.windowLayout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (session.host.isAccessibilityHosted()) {
            boolean migrated = session.host.migrateToApplication(
                    session.box, session.windowLayout, "result_window_text");
            DiagnosticLog.i(session.app, "RESULT_WINDOW", "native host migrated=" + migrated
                    + " type=" + session.windowLayout.type);
            return migrated;
        }
        return session.host.update(session.box, session.windowLayout, "result_window_text");
    }

    private static void copyAll(Session session) {
        if (session.selection == null) return;
        String value = session.selection.editor().getText().toString();
        if (value.isEmpty()) value = session.spec.text;
        ClipboardManager cm = (ClipboardManager) session.app.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens", safe(value)));
        Toast.makeText(session.app, "已复制", Toast.LENGTH_SHORT).show();
    }

    static synchronized void dismissActive(String reason) {
        if (active != null) detach(active, reason == null ? "dismiss" : reason);
    }

    private static synchronized void detach(Session session, String reason) {
        if (session == null || session.detached) return;
        session.detached = true;
        session.ocrGeneration++;
        if (active == session) active = null;
        if (session.selection != null) session.selection.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        if (session.ocrRunning && session.spec.image != null) {
            OcrResultDispatcher.cancel(session.spec.image);
            OcrEngine.invalidatePending(session.app, "result_window_" + reason);
            session.ocrRunning = false;
        }
        session.host.remove(session.box, "result_window");
        DiagnosticLog.i(session.app, "RESULT_WINDOW", "detach reason=" + reason);
    }

    private static void position(Context c, Rect usable, WindowManager.LayoutParams lp,
                                 Rect anchor, boolean fixedCenter) {
        if (fixedCenter || anchor == null || anchor.isEmpty()) {
            lp.gravity = Gravity.CENTER;
            lp.x = 0;
            lp.y = 0;
            return;
        }
        lp.gravity = Gravity.TOP | Gravity.START;
        int margin = ResultUi.dp(c, 8);
        int gap = ResultUi.dp(c, 10);
        int x = ResultUi.clamp(anchor.centerX() - lp.width / 2,
                usable.left + margin, Math.max(usable.left + margin, usable.right - margin - lp.width));
        int below = anchor.bottom + gap;
        int above = anchor.top - gap - lp.height;
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - lp.height);
        int y = below <= maxY ? below : (above >= minY ? above : ResultUi.clamp(below, minY, maxY));
        lp.x = x;
        lp.y = y;
    }

    private static boolean shouldShowImage(Context c, Mode mode) {
        return mode != Mode.OCR || new FloatSettings(c).ocrShowImage();
    }

    private static boolean needsText(Context c, Spec spec) {
        if (spec.mode == Mode.SCREENSHOT) return false;
        if (spec.mode == Mode.OCR) return new FloatSettings(c).ocrShowText();
        return true;
    }

    private static boolean canOcr(Spec spec) {
        return spec.image != null && !spec.image.isRecycled() && spec.mode != Mode.OCR;
    }

    private static boolean canSave(Spec spec) {
        return spec.image != null && !spec.image.isRecycled()
                && (spec.mode == Mode.SCREENSHOT || spec.mode == Mode.VIEW_IMAGE);
    }

    private static String buildViewMeta(ViewNodeCandidate view) {
        if (view == null) return "图片 View";
        StringBuilder meta = new StringBuilder(view.label());
        if (!view.className().isBlank()) meta.append('\n').append(view.className());
        if (!view.viewId().isBlank()) meta.append('\n').append(view.viewId());
        meta.append('\n').append(view.bounds().toShortString());
        return meta.toString();
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static List<String> safeBlocks(List<String> blocks) { return blocks == null ? List.of() : blocks; }

    private enum Mode { SCREENSHOT, VIEW_TEXT, VIEW_IMAGE, OCR }

    private record Spec(Mode mode, String title, String text, List<String> blocks,
                        Bitmap image, Rect anchor, ViewNodeCandidate view,
                        boolean shadeExpandedAtCapture) {
        Spec {
            anchor = anchor == null ? null : new Rect(anchor);
        }
    }

    private static final class Session {
        final Context app;
        final Spec spec;
        final FvOverlayWindowHost host;
        final LinearLayout box;
        final WindowManager.LayoutParams windowLayout;
        final TextView title;
        final ImageView imageView;
        LinearLayout textPanel;
        TextSelectionSurface selection;
        final LinearLayout actions;
        final Button ocrButton;
        final Button copyButton;
        final Button saveButton;
        final Button closeButton;
        final int initialImageHeight;
        final int windowHeight;
        boolean nativeHostDeferred;
        boolean detached;
        boolean ocrRunning;
        long ocrGeneration;

        Session(Context app, Spec spec, FvOverlayWindowHost host, LinearLayout box,
                WindowManager.LayoutParams windowLayout, TextView title, ImageView imageView,
                LinearLayout textPanel, TextSelectionSurface selection, LinearLayout actions,
                Button ocrButton, Button copyButton, Button saveButton, Button closeButton,
                int initialImageHeight, int windowHeight, boolean nativeHostDeferred) {
            this.app = app;
            this.spec = spec;
            this.host = host;
            this.box = box;
            this.windowLayout = windowLayout;
            this.title = title;
            this.imageView = imageView;
            this.textPanel = textPanel;
            this.selection = selection;
            this.actions = actions;
            this.ocrButton = ocrButton;
            this.copyButton = copyButton;
            this.saveButton = saveButton;
            this.closeButton = closeButton;
            this.initialImageHeight = initialImageHeight;
            this.windowHeight = windowHeight;
            this.nativeHostDeferred = nativeHostDeferred;
        }
    }

    private FloatingResultWindow() {}
}
