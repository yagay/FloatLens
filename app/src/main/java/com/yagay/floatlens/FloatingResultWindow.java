package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.List;

/**
 * Floating host for image-first results.
 *
 * The visible content is always UnifiedResultPanel. This class owns only the overlay WindowManager
 * lifecycle and OCR handoff to ResultActivity when native text selection is required.
 */
final class FloatingResultWindow {
    private static final long OCR_TIMEOUT_MS = 12_000L;
    private static Session active;

    static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return showImageResult(c, image, anchor);
    }

    /** Selectable text is intentionally Activity-hosted but renders the same UnifiedResultPanel. */
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
        UnifiedResultPanel panel = new UnifiedResultPanel(app,
                UnifiedResultPanel.Mode.SCREENSHOT, image, "", "", false);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                panel.width(), panel.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;

        FvOverlayWindowHost host = new FvOverlayWindowHost(app);
        if (!host.add(panel.root(), lp, "result_window")) return false;

        Session session = new Session(app, image, anchor, host, panel);
        active = session;
        panel.bindActions(() -> beginOcr(session),
                () -> ScreenshotController.save(app, image),
                () -> detach(session, "close"));

        DiagnosticLog.i(app, "RESULT_WINDOW", "show mode=SCREENSHOT size="
                + lp.width + "x" + lp.height
                + " host=" + (host.isAccessibilityHosted() ? "accessibility" : "application")
                + " type=" + lp.type + " unifiedPanel=true textSurface=false actions=3");
        return true;
    }

    private static synchronized void beginOcr(Session session) {
        if (session == null || session.detached || active != session
                || session.image == null || session.image.isRecycled() || session.ocrRunning) return;

        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        long gen = ++session.ocrGeneration;
        session.ocrRunning = true;
        session.panel.setOcrRunning(true);

        Bitmap image = session.image;
        Rect anchor = session.anchor == null ? null : new Rect(session.anchor);
        OcrResultDispatcher.register(image, (text, blocks) -> session.panel.root().post(() ->
                onOcrReady(session, gen, text, blocks)));

        session.panel.root().postDelayed(() -> {
            synchronized (FloatingResultWindow.class) {
                if (session.detached || active != session || !session.ocrRunning
                        || session.ocrGeneration != gen) return;
                OcrResultDispatcher.cancel(image);
                session.ocrRunning = false;
                session.panel.resetOcrButton();
                DiagnosticLog.i(session.app, "RESULT_WINDOW", "ocr timeout gen=" + gen);
            }
        }, OCR_TIMEOUT_MS);

        DiagnosticLog.i(session.app, "RESULT_WINDOW", "ocr begin gen=" + gen
                + " nativeTextHost=activity unifiedPanel=true");
        OcrEngine.recognize(session.app, image, anchor);
    }

    private static synchronized void onOcrReady(Session session, long gen,
                                                String text, List<String> blocks) {
        if (session == null || session.detached || active != session || gen != session.ocrGeneration) return;
        session.ocrRunning = false;
        session.panel.setOcrRunning(false);

        String value = text == null ? "" : text.trim();
        List<String> safeBlocks = blocks == null ? List.of() : blocks;
        boolean shown = ResultActivity.showOcr(session.app, value, safeBlocks,
                session.image, session.anchor);
        DiagnosticLog.i(session.app, "RESULT_WINDOW", "ocr native activity handoff shown=" + shown
                + " chars=" + value.length() + " blocks=" + safeBlocks.size()
                + " unifiedPanel=true");
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
        session.host.remove(session.panel.root(), "result_window");
        DiagnosticLog.i(session.app, "RESULT_WINDOW", "detach reason=" + reason);
    }

    private static final class Session {
        final Context app;
        final Bitmap image;
        final Rect anchor;
        final FvOverlayWindowHost host;
        final UnifiedResultPanel panel;
        boolean detached;
        boolean ocrRunning;
        long ocrGeneration;

        Session(Context app, Bitmap image, Rect anchor, FvOverlayWindowHost host,
                UnifiedResultPanel panel) {
            this.app = app;
            this.image = image;
            this.anchor = anchor == null ? null : new Rect(anchor);
            this.host = host;
            this.panel = panel;
        }
    }

    private FloatingResultWindow() {}
}
