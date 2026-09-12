package com.yagay.floatlens;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** The one official screenshot / View / OCR result host. */
public final class ResultActivity extends AppCompatActivity {
    private static final long OCR_TIMEOUT_MS = 12_000L;

    public interface InlineResultSink {
        void onResult(String text, List<String> blocks);
    }

    private ResultSession session;
    private UnifiedResultPanel panel;
    private boolean ocrRunning;
    private long ocrGeneration;

    /** Compatibility entry points; all routes now converge on ResultController. */
    public static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return ResultController.show(c, ResultSession.screenshot(image, anchor));
    }

    public static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        if (c == null || text == null || text.isBlank()) return false;
        return ResultController.show(c, ResultSession.viewText(text, image, anchor));
    }

    public static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return ResultController.show(c, ResultSession.viewImage(image, view, anchor));
    }

    public static boolean showOcr(Context c, String text, List<String> blocks,
                                  Bitmap image, Rect anchor) {
        if (c == null) return false;
        return ResultController.show(c, ResultSession.ocr(text, blocks, image, anchor));
    }

    public static void captureNextForImage(Bitmap image, InlineResultSink sink) {
        OcrResultDispatcher.register(image, sink == null ? null : sink::onResult);
    }

    public static void clearInlineForImage(Bitmap image) {
        OcrResultDispatcher.cancel(image);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        configureWindow();
        if (!acceptIntent(getIntent(), false)) {
            finishNoAnim();
            return;
        }
        overridePendingTransition(0, 0);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIntent(intent, true);
    }

    private boolean acceptIntent(Intent intent, boolean reuse) {
        long token = intent == null ? 0L : intent.getLongExtra(ResultController.EXTRA_TOKEN, 0L);
        ResultSession next = ResultController.take(token);
        if (next == null) {
            DiagnosticLog.i(this, "RESULT_ACTIVITY", "missing session token=" + token
                    + " reuse=" + reuse);
            return false;
        }

        cancelCurrentOcr(reuse ? "new_intent" : "create");
        session = next;
        if (panel == null) {
            panel = new UnifiedResultPanel(this, session);
            panel.bindActions(this::beginInlineOcr,
                    () -> {
                        if (session != null && session.canSave()) {
                            ScreenshotController.save(this, session.image());
                        }
                    }, this::closeResult);
            setContentView(panel.root());
        } else {
            panel.render(session);
        }
        panel.setOcrRunning(false);
        applyWindowLayout();

        DiagnosticLog.i(this, "RESULT_ACTIVITY", (reuse ? "REUSE" : "CREATED")
                + " token=" + token + " mode=" + session.mode()
                + " origin=" + session.originMode() + " unifiedSession=true");

        // A captured result may reuse an already resumed singleTop Activity. Re-attach the
        // first-frame coordinator here instead of relying only on ActivityLifecycleCallbacks.
        if (reuse) panel.root().post(() -> ResultReadyCoordinator.onResultActivityResumed(this));
        return true;
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        // ResultActivity is the real native-selection host. Do not opt into LAYOUT_IN_SCREEN here:
        // the panel already computes geometry from the usable display bounds, and forcing the decor
        // under system bars can steal space from the fixed bottom action row on OEM dialog windows.
        w.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setFinishOnTouchOutside(true);
    }

    private void applyWindowLayout() {
        if (panel == null || getWindow() == null) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = panel.width();
        // Let WindowManager include its decor/insets around the panel. Using the exact panel height
        // made the bottom OCR / Copy / Save / Close row the first thing clipped on some OEMs when a
        // screenshot filled the whole content budget.
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;
        getWindow().setAttributes(lp);
        panel.root().requestLayout();
        panel.root().post(() -> DiagnosticLog.i(this, "RESULT_ACTIVITY",
                "PANEL requested=" + panel.width() + "x" + panel.height()
                        + " measured=" + panel.root().getWidth() + "x" + panel.root().getHeight()
                        + " windowHeight=wrap_content mode="
                        + (session == null ? "none" : session.mode())));
    }

    private void beginInlineOcr() {
        if (ocrRunning || session == null || !session.canOcr()) return;
        if (panel != null) panel.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();

        long gen = ++ocrGeneration;
        ocrRunning = true;
        panel.setOcrRunning(true);
        Bitmap image = session.image();
        Rect anchor = session.anchor();
        OcrResultDispatcher.register(image, (text, blocks) -> runOnUiThread(() ->
                applyInlineOcr(gen, text, blocks)));

        panel.root().postDelayed(() -> {
            if (isFinishing() || isDestroyed() || !ocrRunning || ocrGeneration != gen) return;
            OcrResultDispatcher.cancel(image);
            ocrRunning = false;
            if (panel != null) panel.setOcrRunning(false);
            DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE_TIMEOUT gen=" + gen);
        }, OCR_TIMEOUT_MS);

        DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE_BEGIN gen=" + gen
                + " sameSession=true mode=" + session.mode());
        OcrEngine.recognize(getApplicationContext(), image, anchor);
    }

    private void applyInlineOcr(long gen, String text, List<String> blocks) {
        if (isFinishing() || isDestroyed() || session == null || panel == null
                || gen != ocrGeneration) return;
        ocrRunning = false;
        session.applyOcr(text, blocks);
        panel.render(session);
        panel.setOcrRunning(false);
        applyWindowLayout();
        DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE chars=" + session.text().length()
                + " blocks=" + session.blocks().size()
                + " sameSession=true nativeSelection=true magnifier=true");
    }

    private void cancelCurrentOcr(String reason) {
        if (session != null && ocrRunning && session.hasImage()) {
            OcrResultDispatcher.cancel(session.image());
            OcrEngine.invalidatePending(getApplicationContext(), "result_activity_" + reason);
        }
        ocrRunning = false;
        ocrGeneration++;
        if (panel != null) panel.setOcrRunning(false);
    }

    private void closeResult() {
        if (session != null && session.notifyCircleOnClose()) {
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished("result_closed");
        }
        finishNoAnim();
    }

    private void finishNoAnim() {
        finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        cancelCurrentOcr("destroy");
        if (panel != null) panel.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        panel = null;
        session = null;
        super.onDestroy();
    }
}
