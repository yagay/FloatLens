package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Activity fallback for devices where a floating result window cannot attach.
 *
 * This class intentionally contains no independent text-selection or OCR-result implementation.
 * It reuses ResultUi, TextSelectionSurface, OcrResultDispatcher and FloatActionMenu so fixes to the
 * primary floating result path automatically apply to the fallback path as well.
 */
public final class ResultActivity extends AppCompatActivity {
    private static final int MODE_SCREENSHOT = 1;
    private static final int MODE_VIEW_TEXT = 2;
    private static final int MODE_VIEW_IMAGE = 3;
    private static final int MODE_OCR_TEXT = 4;
    private static final String EXTRA_TOKEN = "result_token";
    private static final long OCR_TIMEOUT_MS = 12_000L;

    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static final Map<Long, Payload> PENDING = new ConcurrentHashMap<>();

    /** Compatibility only. New inline OCR callers use OcrResultDispatcher directly. */
    public interface InlineResultSink {
        void onResult(String text, List<String> blocks);
    }

    private long token;
    private Payload payload;
    private boolean ocrRunning;
    private long ocrGeneration;

    private LinearLayout box;
    private TextView title;
    private ImageView imageView;
    private LinearLayout textPanel;
    private TextSelectionSurface selection;
    private Button ocrButton;
    private Button copyButton;
    private int windowHeight;

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
        buildContent();
        overridePendingTransition(0, 0);
        DiagnosticLog.i(this, "RESULT_ACTIVITY", "CREATED token=" + token
                + " mode=" + payload.mode + " sharedComponents=true");
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setFinishOnTouchOutside(true);
    }

    private void buildContent() {
        Context app = getApplicationContext();
        Rect usable = ResultUi.usableBounds(this);
        FloatSettings settings = new FloatSettings(app);
        boolean showText = initialTextVisible(settings);
        boolean showImage = payload.image != null && !payload.image.isRecycled()
                && (payload.mode != MODE_OCR_TEXT || settings.ocrShowImage());

        int width = ResultUi.standardWidth(this, usable);
        int maxH = ResultUi.standardMaxHeight(this, usable);
        int titleH = ResultUi.dp(this, ResultUi.TITLE_H_DP);
        int actionsH = ResultUi.dp(this, ResultUi.ACTION_H_DP);
        int contentBudget = Math.max(ResultUi.dp(this, 90),
                maxH - titleH - actionsH - ResultUi.dp(this, ResultUi.ROOT_VPAD_DP));

        box = ResultUi.box(this);
        title = ResultUi.heading(this, titleForMode(payload.mode));
        box.addView(title, new LinearLayout.LayoutParams(-1, titleH));

        int imageH = 0;
        if (showImage) {
            int cap = payload.mode == MODE_VIEW_IMAGE ? ResultUi.dp(this, 190) : ResultUi.dp(this, 135);
            if (payload.mode == MODE_SCREENSHOT) cap = contentBudget;
            imageH = ResultUi.imageHeight(this, payload.image, width, cap);
            imageView = new ImageView(this);
            imageView.setImageBitmap(payload.image);
            imageView.setAdjustViewBounds(false);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ImageShareUtils.attachLongPressShare(this, imageView, payload.image);
            box.addView(imageView, new LinearLayout.LayoutParams(-1, imageH));
        }

        textPanel = new LinearLayout(this);
        textPanel.setOrientation(LinearLayout.VERTICAL);
        textPanel.setPadding(0, ResultUi.dp(this, 4), 0, ResultUi.dp(this, 4));
        selection = new TextSelectionSurface(this);
        textPanel.addView(selection, new LinearLayout.LayoutParams(-1, 0, 1f));
        textPanel.setVisibility(showText ? View.VISIBLE : View.GONE);
        box.addView(textPanel, new LinearLayout.LayoutParams(-1, 0));
        bindSelection();

        int textH = showText ? Math.max(ResultUi.dp(this, 96), contentBudget - imageH) : 0;
        if (showText) {
            textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, textH));
            selection.setText(initialText());
        }

        LinearLayout actions = ResultUi.actionRow(this);
        boolean canOcr = payload.image != null && !payload.image.isRecycled()
                && payload.mode != MODE_OCR_TEXT;
        boolean canSave = payload.image != null && !payload.image.isRecycled()
                && (payload.mode == MODE_SCREENSHOT || payload.mode == MODE_VIEW_IMAGE);

        if (canOcr) {
            ocrButton = ResultUi.button(this, "OCR");
            actions.addView(ocrButton, new LinearLayout.LayoutParams(0, -1, 1));
            ocrButton.setOnClickListener(v -> beginInlineOcr());
        }

        if (showText || canOcr) {
            copyButton = ResultUi.button(this, "复制全部");
            copyButton.setVisibility(showText ? View.VISIBLE : View.GONE);
            actions.addView(copyButton, new LinearLayout.LayoutParams(0, -1, 1));
            copyButton.setOnClickListener(v -> copyAll());
        }

        if (canSave) {
            Button save = ResultUi.button(this, "保存图片");
            actions.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
            save.setOnClickListener(v -> ScreenshotController.save(this, payload.image));
        }

        Button close = ResultUi.button(this, "关闭");
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        close.setOnClickListener(v -> closeResult());
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        if (payload.mode == MODE_SCREENSHOT) {
            windowHeight = Math.min(maxH, titleH + actionsH + imageH
                    + ResultUi.dp(this, ResultUi.ROOT_VPAD_DP));
        } else {
            windowHeight = Math.min(maxH, titleH + actionsH + imageH + textH
                    + ResultUi.dp(this, ResultUi.ROOT_VPAD_DP));
        }
        windowHeight = Math.max(ResultUi.dp(this, 158), windowHeight);

        setContentView(box);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = width;
        lp.height = windowHeight;
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;
        getWindow().setAttributes(lp);
    }

    private void bindSelection() {
        selection.setListener(new TextSelectionSurface.Listener() {
            @Override public void onSelectionStarted() {
                FloatActionMenu.dismiss();
                FloatMenuAnchor.clear();
            }

            @Override public void onSelectionChanging() {
                FloatActionMenu.dismiss();
                FloatMenuAnchor.clear();
            }

            @Override public void onSelectionFinished(String selectedText, Rect anchorOnScreen) {
                if (isFinishing() || isDestroyed()) return;
                String value = safe(selectedText).trim();
                if (value.isEmpty()) return;
                FloatActionMenu.showTextAt(ResultActivity.this, value,
                        selection::selectAllText, anchorOnScreen);
            }
        });
    }

    private void beginInlineOcr() {
        if (ocrRunning || payload == null || payload.image == null || payload.image.isRecycled()) return;
        selection.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();

        long gen = ++ocrGeneration;
        ocrRunning = true;
        if (ocrButton != null) {
            ocrButton.setEnabled(false);
            ocrButton.setText("识别中…");
        }
        Bitmap image = payload.image;
        Rect anchor = payload.anchor == null ? null : new Rect(payload.anchor);
        OcrResultDispatcher.register(image, (text, blocks) -> runOnUiThread(() ->
                showInlineOcr(gen, text, blocks)));

        box.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || !ocrRunning || ocrGeneration != gen) return;
            OcrResultDispatcher.cancel(image);
            ocrRunning = false;
            if (ocrButton != null) {
                ocrButton.setEnabled(true);
                ocrButton.setText("OCR");
            }
            DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE_TIMEOUT gen=" + gen);
        }, OCR_TIMEOUT_MS);

        OcrEngine.recognize(getApplicationContext(), image, anchor);
    }

    private void showInlineOcr(long gen, String text, List<String> blocks) {
        if (isFinishing() || isDestroyed() || payload == null || gen != ocrGeneration) return;
        ocrRunning = false;
        if (ocrButton != null) {
            ocrButton.setEnabled(true);
            ocrButton.setText("重新识别");
        }
        title.setText("OCR 结果");
        String value = safe(text).trim();
        selection.setText(value.isEmpty() ? "未识别到文字" : value);
        textPanel.setVisibility(View.VISIBLE);
        if (copyButton != null) copyButton.setVisibility(View.VISIBLE);

        int contentBudget = Math.max(ResultUi.dp(this, 100),
                windowHeight - ResultUi.dp(this, ResultUi.TITLE_H_DP)
                        - ResultUi.dp(this, ResultUi.ACTION_H_DP)
                        - ResultUi.dp(this, ResultUi.ROOT_VPAD_DP));
        int imageH = 0;
        if (imageView != null) {
            imageH = Math.min(ResultUi.dp(this, 130), Math.max(0, contentBudget / 2));
            imageView.setLayoutParams(new LinearLayout.LayoutParams(-1, imageH));
            imageView.setVisibility(imageH > 0 ? View.VISIBLE : View.GONE);
        }
        int textH = Math.max(ResultUi.dp(this, 96), contentBudget - imageH);
        textPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, textH));
        box.requestLayout();

        DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE chars=" + value.length()
                + " blocks=" + (blocks == null ? 0 : blocks.size())
                + " sharedSelection=true magnifier=true");
    }

    private boolean initialTextVisible(FloatSettings settings) {
        if (payload.mode == MODE_SCREENSHOT) return false;
        if (payload.mode == MODE_OCR_TEXT) return settings.ocrShowText();
        return true;
    }

    private String initialText() {
        return switch (payload.mode) {
            case MODE_VIEW_TEXT -> payload.text;
            case MODE_VIEW_IMAGE -> payload.meta;
            case MODE_OCR_TEXT -> payload.text.isBlank() ? "未识别到文字" : payload.text;
            default -> "";
        };
    }

    private static String titleForMode(int mode) {
        return switch (mode) {
            case MODE_SCREENSHOT -> "区域截图";
            case MODE_VIEW_TEXT -> "View 内容";
            case MODE_VIEW_IMAGE -> "View / 图标";
            case MODE_OCR_TEXT -> "OCR 结果";
            default -> "FloatLens";
        };
    }

    private void copyAll() {
        String value = selection == null ? "" : selection.editor().getText().toString();
        if (value.isBlank() && payload != null) value = payload.text;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens", safe(value)));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
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
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        PENDING.remove(token);
        payload = null;
        super.onDestroy();
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
