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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Native Activity host for the shared UnifiedResultPanel. */
public final class ResultActivity extends AppCompatActivity {
    private static final int MODE_SCREENSHOT = 1;
    private static final int MODE_VIEW_TEXT = 2;
    private static final int MODE_VIEW_IMAGE = 3;
    private static final int MODE_OCR_TEXT = 4;
    private static final String EXTRA_TOKEN = "result_token";
    private static final long OCR_TIMEOUT_MS = 12_000L;

    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static final Map<Long, Payload> PENDING = new ConcurrentHashMap<>();

    public interface InlineResultSink {
        void onResult(String text, List<String> blocks);
    }

    private long token;
    private Payload payload;
    private UnifiedResultPanel panel;
    private boolean ocrRunning;
    private long ocrGeneration;

    public static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return start(c, new Payload(MODE_SCREENSHOT, "", List.of(), image, "", anchor));
    }

    public static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        if (c == null || text == null || text.isBlank()) return false;
        return start(c, new Payload(MODE_VIEW_TEXT, text, List.of(), image, "", anchor));
    }

    public static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return start(c, new Payload(MODE_VIEW_IMAGE, "", List.of(), image,
                buildViewMeta(view), anchor));
    }

    public static boolean showOcr(Context c, String text, List<String> blocks,
                                  Bitmap image, Rect anchor) {
        if (c == null) return false;
        return start(c, new Payload(MODE_OCR_TEXT, safe(text), safeBlocks(blocks),
                image, "", anchor));
    }

    public static void captureNextForImage(Bitmap image, InlineResultSink sink) {
        OcrResultDispatcher.register(image, sink == null ? null : sink::onResult);
    }

    public static void clearInlineForImage(Bitmap image) {
        OcrResultDispatcher.cancel(image);
    }

    private static boolean start(Context c, Payload payload) {
        long token = NEXT.getAndIncrement();
        PENDING.put(token, payload);
        Intent i = new Intent(c, ResultActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            c.startActivity(i);
            DiagnosticLog.i(c, "RESULT_ACTIVITY", "START token=" + token
                    + " mode=" + payload.mode + " chars=" + payload.text.length());
            return true;
        } catch (Throwable t) {
            PENDING.remove(token);
            DiagnosticLog.i(c, "RESULT_ACTIVITY", "START_FAILED " + t);
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        token = getIntent().getLongExtra(EXTRA_TOKEN, 0L);
        payload = PENDING.get(token);
        if (payload == null) {
            finishNoAnim();
            return;
        }

        configureWindow();
        buildPanel();
        overridePendingTransition(0, 0);
        DiagnosticLog.i(this, "RESULT_ACTIVITY", "CREATED token=" + token
                + " mode=" + payload.mode + " unifiedPanel=true");
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setFinishOnTouchOutside(true);
    }

    private void buildPanel() {
        panel = new UnifiedResultPanel(this, panelMode(payload.mode), payload.image,
                payload.text, payload.meta, true);
        panel.bindActions(this::beginInlineOcr,
                () -> {
                    if (payload != null && payload.image != null && !payload.image.isRecycled()) {
                        ScreenshotController.save(this, payload.image);
                    }
                },
                this::closeResult);

        setContentView(panel.root());
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = panel.width();
        lp.height = panel.height();
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;
        getWindow().setAttributes(lp);
        DiagnosticLog.i(this, "RESULT_ACTIVITY", "PANEL size=" + lp.width + "x" + lp.height
                + " mode=" + payload.mode);
    }

    private void beginInlineOcr() {
        if (ocrRunning || payload == null || payload.image == null || payload.image.isRecycled()) return;
        if (panel != null) panel.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();

        long gen = ++ocrGeneration;
        ocrRunning = true;
        if (panel != null) panel.setOcrRunning(true);
        Bitmap image = payload.image;
        Rect anchor = payload.anchor == null ? null : new Rect(payload.anchor);
        OcrResultDispatcher.register(image, (text, blocks) -> runOnUiThread(() ->
                showInlineOcr(gen, text, blocks)));

        if (panel != null) {
            panel.root().postDelayed(() -> {
                if (isFinishing() || isDestroyed() || !ocrRunning || ocrGeneration != gen) return;
                OcrResultDispatcher.cancel(image);
                ocrRunning = false;
                panel.resetOcrButton();
                DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE_TIMEOUT gen=" + gen);
            }, OCR_TIMEOUT_MS);
        }

        OcrEngine.recognize(getApplicationContext(), image, anchor);
    }

    private void showInlineOcr(long gen, String text, List<String> blocks) {
        if (isFinishing() || isDestroyed() || payload == null || panel == null
                || gen != ocrGeneration) return;
        ocrRunning = false;
        panel.setOcrRunning(false);
        String value = safe(text).trim();
        panel.showOcrText(value);
        DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE chars=" + value.length()
                + " blocks=" + (blocks == null ? 0 : blocks.size())
                + " unifiedPanel=true nativeSelection=true magnifier=true");
    }

    private void closeResult() {
        if (payload != null && payload.mode == MODE_OCR_TEXT) {
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
        if (payload != null && ocrRunning && payload.image != null) {
            OcrResultDispatcher.cancel(payload.image);
            OcrEngine.invalidatePending(getApplicationContext(), "result_activity_destroy");
        }
        ocrRunning = false;
        ocrGeneration++;
        if (panel != null) panel.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        PENDING.remove(token);
        panel = null;
        payload = null;
        super.onDestroy();
    }

    private static UnifiedResultPanel.Mode panelMode(int mode) {
        return switch (mode) {
            case MODE_SCREENSHOT -> UnifiedResultPanel.Mode.SCREENSHOT;
            case MODE_VIEW_TEXT -> UnifiedResultPanel.Mode.VIEW_TEXT;
            case MODE_VIEW_IMAGE -> UnifiedResultPanel.Mode.VIEW_IMAGE;
            case MODE_OCR_TEXT -> UnifiedResultPanel.Mode.OCR;
            default -> UnifiedResultPanel.Mode.OCR;
        };
    }

    private static String buildViewMeta(ViewNodeCandidate view) {
        if (view == null) return "图片 View";
        StringBuilder meta = new StringBuilder(view.label());
        if (!view.className().isBlank()) meta.append('\n').append(view.className());
        if (!view.viewId().isBlank()) meta.append('\n').append(view.viewId());
        meta.append('\n').append(view.bounds().toShortString());
        return meta.toString();
    }

    private static String safe(String text) { return text == null ? "" : text; }

    private static List<String> safeBlocks(List<String> blocks) {
        return blocks == null ? List.of() : new ArrayList<>(blocks);
    }

    private static final class Payload {
        final int mode;
        final String text;
        final List<String> blocks;
        final Bitmap image;
        final String meta;
        final Rect anchor;

        Payload(int mode, String text, List<String> blocks, Bitmap image, String meta, Rect anchor) {
            this.mode = mode;
            this.text = safe(text);
            this.blocks = safeBlocks(blocks);
            this.image = image;
            this.meta = safe(meta);
            this.anchor = anchor == null ? null : new Rect(anchor);
        }
    }
}
