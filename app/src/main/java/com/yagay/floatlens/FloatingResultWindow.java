package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Image-only floating result surface.
 *
 * TYPE_ACCESSIBILITY_OVERLAY is reliable for frozen screenshot/image presentation but Android's
 * native text Editor stack (selection handles, magnifier and ActionMode) is not reliable in overlay
 * windows on the target OxygenOS/Android build. Selectable text therefore lives exclusively in
 * ResultActivity. This class owns only screenshot presentation and hands OCR results to that native
 * Activity host after recognition completes.
 */
final class FloatingResultWindow {
    private static final long OCR_TIMEOUT_MS = 12_000L;
    private static Session active;

    static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return showImageResult(c, image, anchor);
    }

    /** Compatibility entry points: all selectable text is intentionally Activity-hosted. */
    static boolean showOcr(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        dismissActive("native_text_activity");
        return ResultActivity.showOcr(c, text, blocks, image, anchor);
    }

    static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        dismissActive("native_text_activity");
        return ResultActivity.showViewText(c, text, image, anchor);
    }

    static boolean showViewText(Context c, String text, Bitmap image, Rect anchor,
                                boolean shadeExpandedAtCapture) {
        return showViewText(c, text, image, anchor);
    }

    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        dismissActive("native_text_activity");
        return ResultActivity.showViewImage(c, image, view, anchor);
    }

    static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor,
                                 boolean shadeExpandedAtCapture) {
        return showViewImage(c, image, view, anchor);
    }

    private static synchronized boolean showImageResult(Context c, Bitmap image, Rect anchor) {
        dismissActive("replace");
        Context app = c.getApplicationContext();
        Rect usable = ResultUi.usableBounds(app);
        int width = ResultUi.standardWidth(app, usable);
        int maxH = ResultUi.standardMaxHeight(app, usable);
        int titleH = ResultUi.dp(app, ResultUi.TITLE_H_DP);
        int actionsH = ResultUi.dp(app, ResultUi.ACTION_H_DP);
        int contentBudget = Math.max(ResultUi.dp(app, 90),
                maxH - titleH - actionsH - ResultUi.dp(app, ResultUi.ROOT_VPAD_DP));

        LinearLayout box = ResultUi.box(app);
        TextView title = ResultUi.heading(app, "区域截图");
        box.addView(title, new LinearLayout.LayoutParams(-1, titleH));

        int imageH = ResultUi.imageHeight(app, image, width, contentBudget);
        ImageView imageView = new ImageView(app);
        imageView.setImageBitmap(image);
        imageView.setAdjustViewBounds(false);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageShareUtils.attachLongPressShare(app, imageView, image);
        box.addView(imageView, new LinearLayout.LayoutParams(-1, imageH));

        LinearLayout actions = ResultUi.actionRow(app);
        Button ocr = ResultUi.button(app, "OCR");
        Button save = ResultUi.button(app, "保存图片");
        Button close = ResultUi.button(app, "关闭");
        actions.addView(ocr, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        int height = Math.min(maxH,
                titleH + actionsH + imageH + ResultUi.dp(app, ResultUi.ROOT_VPAD_DP));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, Math.max(ResultUi.dp(app, 158), height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;

        FvOverlayWindowHost host = new FvOverlayWindowHost(app);
        if (!host.add(box, lp, "result_window")) return false;

        Session session = new Session(app, image, anchor, host, box, ocr);
        active = session;
        ocr.setOnClickListener(v -> beginOcr(session));
        save.setOnClickListener(v -> ScreenshotController.save(app, image));
        close.setOnClickListener(v -> detach(session, "close"));

        DiagnosticLog.i(app, "RESULT_WINDOW", "show mode=SCREENSHOT size="
                + lp.width + "x" + lp.height
                + " host=" + (host.isAccessibilityHosted() ? "accessibility" : "application")
                + " type=" + lp.type + " textSurface=false actions=3");
        return true;
    }

    private static synchronized void beginOcr(Session session) {
        if (session == null || session.detached || active != session
                || session.image == null || session.image.isRecycled() || session.ocrRunning) return;

        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        long gen = ++session.ocrGeneration;
        session.ocrRunning = true;
        session.ocrButton.setEnabled(false);
        session.ocrButton.setText("识别中…");

        Bitmap image = session.image;
        Rect anchor = session.anchor == null ? null : new Rect(session.anchor);
        OcrResultDispatcher.register(image, (text, blocks) -> session.box.post(() ->
                onOcrReady(session, gen, text, blocks)));

        session.box.postDelayed(() -> {
            synchronized (FloatingResultWindow.class) {
                if (session.detached || active != session || !session.ocrRunning
                        || session.ocrGeneration != gen) return;
                OcrResultDispatcher.cancel(image);
                session.ocrRunning = false;
                session.ocrButton.setEnabled(true);
                session.ocrButton.setText("OCR");
                DiagnosticLog.i(session.app, "RESULT_WINDOW", "ocr timeout gen=" + gen);
            }
        }, OCR_TIMEOUT_MS);

        DiagnosticLog.i(session.app, "RESULT_WINDOW", "ocr begin gen=" + gen
                + " nativeTextHost=activity");
        OcrEngine.recognize(session.app, image, anchor);
    }

    private static synchronized void onOcrReady(Session session, long gen,
                                                String text, List<String> blocks) {
        if (session == null || session.detached || active != session || gen != session.ocrGeneration) return;
        session.ocrRunning = false;
        session.ocrButton.setEnabled(true);
        session.ocrButton.setText("重新识别");

        String value = text == null ? "" : text.trim();
        List<String> safeBlocks = blocks == null ? List.of() : blocks;
        boolean shown = ResultActivity.showOcr(session.app, value, safeBlocks,
                session.image, session.anchor);
        DiagnosticLog.i(session.app, "RESULT_WINDOW", "ocr native activity handoff shown=" + shown
                + " chars=" + value.length() + " blocks=" + safeBlocks.size());
        if (shown) {
            detach(session, "ocr_native_activity");
        } else {
            Toast.makeText(session.app, "OCR 结果显示失败", Toast.LENGTH_SHORT).show();
        }
    }

    static synchronized void dismissActive(String reason) {
        if (active != null) detach(active, reason == null ? "dismiss" : reason);
    }

    private static synchronized void detach(Session session, String reason) {
        if (session == null || session.detached) return;
        session.detached = true;
        session.ocrGeneration++;
        if (active == session) active = null;
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        if (session.ocrRunning && session.image != null) {
            OcrResultDispatcher.cancel(session.image);
            OcrEngine.invalidatePending(session.app, "result_window_" + reason);
            session.ocrRunning = false;
        }
        session.host.remove(session.box, "result_window");
        DiagnosticLog.i(session.app, "RESULT_WINDOW", "detach reason=" + reason);
    }

    private static final class Session {
        final Context app;
        final Bitmap image;
        final Rect anchor;
        final FvOverlayWindowHost host;
        final LinearLayout box;
        final Button ocrButton;
        boolean detached;
        boolean ocrRunning;
        long ocrGeneration;

        Session(Context app, Bitmap image, Rect anchor, FvOverlayWindowHost host,
                LinearLayout box, Button ocrButton) {
            this.app = app;
            this.image = image;
            this.anchor = anchor == null ? null : new Rect(anchor);
            this.host = host;
            this.box = box;
            this.ocrButton = ocrButton;
        }
    }

    private FloatingResultWindow() {}
}
