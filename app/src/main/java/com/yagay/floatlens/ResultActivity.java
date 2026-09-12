package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified result host for screenshot, extracted View text/image and OCR text.
 *
 * Keeping all result surfaces in one Activity avoids four independent copies of window placement,
 * payload storage, buttons, selectable text, ActionMode handling and OCR transitions.
 */
public final class ResultActivity extends AppCompatActivity {
    private static final int MODE_SCREENSHOT = 1;
    private static final int MODE_VIEW_TEXT = 2;
    private static final int MODE_VIEW_IMAGE = 3;
    private static final int MODE_OCR_TEXT = 4;

    private static final String EXTRA_TOKEN = "result_token";
    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static final Map<Long, Payload> PENDING = new ConcurrentHashMap<>();

    private static final int OUTER_MARGIN_DP = 12;
    private static final int ANCHOR_GAP_DP = 10;
    private static final int BOX_HPAD_DP = 14;
    private static final int TITLE_H_DP = 38;
    private static final int ACTION_H_DP = 50;
    private static final int ROOT_VPAD_DP = 16;

    private static final int MENU_COPY = 0x46540001;
    private static final int MENU_SHARE = 0x46540002;
    private static final int MENU_GROUP_PROCESS = 0x46540100;
    private static final int MENU_PROCESS_BASE = 0x46541000;

    // Keep the framework floating toolbar hidden for the whole handle drag. We explicitly show it
    // again after the selection has stopped changing instead of trusting the OEM hide timer.
    private static final long SELECTION_HIDE_HOLD_MS = 10_000L;
    private static final long SELECTION_RESHOW_DELAY_MS = 220L;

    public interface InlineResultSink {
        void onResult(String text, List<String> blocks);
    }

    private static final Object INLINE_LOCK = new Object();
    private static Bitmap inlineImage;
    private static InlineResultSink inlineSink;

    private long token;
    private Payload payload;
    private boolean ocrRunning;
    private boolean circleFinished;

    private ImageView resultImage;
    private LinearLayout ocrPanel;
    private SelectionAwareEditText ocrText;
    private Button ocrButton;
    private Rect popupUsable;
    private int popupWidth;
    private int compactImageHeight;

    private ActionMode activeSelectionActionMode;
    private SelectionAwareEditText activeSelectionText;
    private int lastSelectionStart = -1;
    private int lastSelectionEnd = -1;
    private long selectionGeneration;
    private Runnable selectionReshow;

    private ActionMode activeBlockActionMode;

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
        return start(c, new Payload(MODE_VIEW_IMAGE, "", List.of(), image, buildViewMeta(view), anchor));
    }

    public static boolean showOcr(Context c, String text, List<String> blocks, Bitmap image, Rect anchor) {
        if (c == null) return false;
        InlineResultSink inline = takeInlineSink(image);
        if (inline != null) {
            try {
                String value = text == null ? "" : text;
                List<String> safeBlocks = blocks == null ? List.of() : new ArrayList<>(blocks);
                inline.onResult(value, safeBlocks);
                DiagnosticLog.i(c, "RESULT_ACTIVITY", "INLINE chars=" + value.length()
                        + " blocks=" + safeBlocks.size());
                return true;
            } catch (Throwable t) {
                DiagnosticLog.i(c, "RESULT_ACTIVITY", "INLINE_FAILED " + t);
            }
        }
        return start(c, new Payload(MODE_OCR_TEXT, text, blocks, image, "", anchor));
    }

    public static void captureNextForImage(Bitmap image, InlineResultSink sink) {
        synchronized (INLINE_LOCK) {
            inlineImage = image;
            inlineSink = sink;
        }
    }

    public static void clearInlineForImage(Bitmap image) {
        synchronized (INLINE_LOCK) {
            if (inlineImage == image) {
                inlineImage = null;
                inlineSink = null;
            }
        }
    }

    private static InlineResultSink takeInlineSink(Bitmap image) {
        synchronized (INLINE_LOCK) {
            if (inlineSink == null || inlineImage != image) return null;
            InlineResultSink sink = inlineSink;
            inlineImage = null;
            inlineSink = null;
            return sink;
        }
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

    private static String buildViewMeta(ViewNodeCandidate view) {
        if (view == null) return "图片 View";
        StringBuilder meta = new StringBuilder();
        meta.append(view.label());
        if (!view.className().isBlank()) meta.append("\n").append(view.className());
        if (!view.viewId().isBlank()) meta.append("\n").append(view.viewId());
        meta.append("\n").append(view.bounds().toShortString());
        return meta.toString();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        token = getIntent().getLongExtra(EXTRA_TOKEN, 0L);
        payload = PENDING.get(token);
        if (payload == null) {
            finish();
            return;
        }

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setFinishOnTouchOutside(true);

        switch (payload.mode) {
            case MODE_SCREENSHOT -> buildScreenshot();
            case MODE_VIEW_TEXT -> buildViewText();
            case MODE_VIEW_IMAGE -> buildViewImage();
            case MODE_OCR_TEXT -> buildOcrText();
            default -> finish();
        }
        overridePendingTransition(0, 0);
        DiagnosticLog.i(this, "RESULT_ACTIVITY", "CREATED token=" + token + " mode=" + payload.mode);
    }

    private LinearLayout baseBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(BOX_HPAD_DP), dp(8), dp(BOX_HPAD_DP), dp(8));
        box.setBackgroundColor(0xF0202124);
        box.setElevation(dp(10));
        return box;
    }

    private TextView heading(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER_VERTICAL);
        return title;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    private void buildScreenshot() {
        if (payload.image == null || payload.image.isRecycled()) {
            finishNoAnim();
            return;
        }
        Rect usable = usableBounds();
        popupUsable = new Rect(usable);
        int width = standardWidth(usable);
        popupWidth = width;
        int maxH = standardMaxHeight(usable);
        int titleH = dp(TITLE_H_DP);
        int actionsH = dp(ACTION_H_DP);
        int imageW = Math.max(dp(160), width - dp(BOX_HPAD_DP * 2));
        int desiredImageH = Math.round(imageW * payload.image.getHeight()
                / (float) Math.max(1, payload.image.getWidth()));
        desiredImageH = clamp(desiredImageH, dp(72),
                Math.max(dp(72), maxH - titleH - actionsH - dp(ROOT_VPAD_DP)));
        compactImageHeight = desiredImageH;
        int height = Math.min(maxH, titleH + actionsH + desiredImageH + dp(ROOT_VPAD_DP));
        height = Math.max(Math.min(maxH, titleH + actionsH + dp(64) + dp(ROOT_VPAD_DP)), height);

        LinearLayout box = baseBox();
        box.addView(heading("区域截图"), new LinearLayout.LayoutParams(-1, titleH));

        resultImage = new ImageView(this);
        resultImage.setImageBitmap(payload.image);
        resultImage.setAdjustViewBounds(false);
        resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageShareUtils.attachLongPressShare(this, resultImage, payload.image);
        box.addView(resultImage, new LinearLayout.LayoutParams(-1, 0, 1f));

        ocrPanel = new LinearLayout(this);
        ocrPanel.setOrientation(LinearLayout.VERTICAL);
        ocrPanel.setVisibility(View.GONE);
        ocrPanel.setPadding(0, dp(4), 0, dp(4));
        TextView ocrHeading = new TextView(this);
        ocrHeading.setText("OCR 文字");
        ocrHeading.setTextColor(0xFFBBBBBB);
        ocrHeading.setTextSize(13);
        ocrHeading.setGravity(Gravity.CENTER_VERTICAL);
        ocrPanel.addView(ocrHeading, new LinearLayout.LayoutParams(-1, dp(26)));

        ScrollView ocrScroll = new ScrollView(this);
        ocrScroll.setVerticalScrollBarEnabled(true);
        ocrScroll.setScrollbarFadingEnabled(false);
        ocrText = selectableText("");
        ocrScroll.addView(ocrText, new ScrollView.LayoutParams(-1, -2));
        ocrPanel.addView(ocrScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        box.addView(ocrPanel, new LinearLayout.LayoutParams(-1, dp(170)));

        LinearLayout actions = actionRow();
        ocrButton = button("OCR");
        Button save = button("保存图片");
        Button close = button("关闭");
        actions.addView(ocrButton, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        setContentView(box);
        positionWindow(usable, width, height, payload.anchor);
        ocrButton.setOnClickListener(v -> runInlineOcr());
        save.setOnClickListener(v -> ScreenshotController.save(this, payload.image));
        close.setOnClickListener(v -> finishNoAnim());
    }

    private void runInlineOcr() {
        if (ocrRunning || payload.image == null || payload.image.isRecycled()) return;
        ocrRunning = true;
        ocrButton.setEnabled(false);
        ocrButton.setText("识别中…");
        Bitmap image = payload.image;
        Rect anchor = payload.anchor == null ? null : new Rect(payload.anchor);

        captureNextForImage(image, (text, blocks) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || payload == null || payload.image != image) return;
            ocrRunning = false;
            ocrButton.setEnabled(true);
            ocrButton.setText("重新识别");
            showInlineOcr(text);
        }));

        ocrButton.postDelayed(() -> {
            if (!ocrRunning) return;
            clearInlineForImage(image);
            ocrRunning = false;
            ocrButton.setEnabled(true);
            ocrButton.setText("OCR");
            DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE_TIMEOUT");
        }, 12_000L);

        OcrEngine.recognize(getApplicationContext(), image, anchor);
    }

    private void showInlineOcr(String text) {
        if (ocrPanel == null || ocrText == null || resultImage == null) return;
        String value = text == null ? "" : text.trim();
        String shown = value.isEmpty() ? "未识别到文字" : value;
        ocrText.setText(shown);

        // Keep the outer Activity frame completely fixed. Only redistribute its existing content.
        WindowManager.LayoutParams windowLp = getWindow().getAttributes();
        int stableHeight = windowLp.height > 0 ? windowLp.height : dp(260);
        int stableWidth = windowLp.width > 0 ? windowLp.width
                : (popupWidth > 0 ? popupWidth : dp(320));
        int contentBudget = Math.max(0,
                stableHeight - dp(TITLE_H_DP) - dp(ACTION_H_DP) - dp(ROOT_VPAD_DP));
        int headingH = dp(26);
        int panelPad = dp(8);
        int innerTextW = Math.max(dp(120), stableWidth - dp(40));
        int desiredTextH = estimateTextHeight(shown, innerTextW);

        int minTextH = Math.min(dp(48), Math.max(0, contentBudget - headingH - panelPad));
        int preferredImageH = Math.min(clamp(compactImageHeight, dp(64), dp(160)),
                Math.max(0, contentBudget - headingH - panelPad - minTextH));
        int maxTextH = Math.max(0, contentBudget - preferredImageH - headingH - panelPad);
        int textH = Math.min(desiredTextH, maxTextH);
        if (textH < minTextH) textH = minTextH;
        int panelH = Math.min(contentBudget, headingH + textH + panelPad);
        int imageH = Math.max(0, contentBudget - panelH);

        resultImage.setLayoutParams(new LinearLayout.LayoutParams(-1, imageH));
        resultImage.setVisibility(imageH > 0 ? View.VISIBLE : View.GONE);
        ocrPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, panelH));
        ocrPanel.setVisibility(View.VISIBLE);

        DiagnosticLog.i(this, "RESULT_ACTIVITY", "OCR_INLINE chars=" + value.length()
                + " frame=" + stableWidth + "x" + stableHeight
                + " imageH=" + imageH + " textH=" + textH + " panelH=" + panelH
                + " windowFixed=true pos=" + windowLp.x + "," + windowLp.y);
    }

    private void buildViewText() {
        Rect usable = usableBounds();
        int width = standardWidth(usable);
        int maxH = standardMaxHeight(usable);
        int titleH = dp(TITLE_H_DP), actionsH = dp(ACTION_H_DP);
        int bodyMax = Math.max(dp(80), maxH - titleH - actionsH - dp(ROOT_VPAD_DP + 2));

        LinearLayout box = baseBox();
        box.addView(heading("View 内容"), new LinearLayout.LayoutParams(-1, titleH));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int desired = 0;

        if (payload.image != null && !payload.image.isRecycled()) {
            int imageH = addImage(body, payload.image, width - dp(BOX_HPAD_DP * 2), dp(130));
            desired += imageH + dp(4);
        }
        SelectionAwareEditText text = selectableText(payload.text);
        body.addView(text, new LinearLayout.LayoutParams(-1, -2));
        desired += textHeight(payload.text, width - dp(44), 16f, 16);

        scroll.addView(body);
        int bodyH = clamp(desired, dp(70), bodyMax);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = actionRow();
        boolean hasImage = payload.image != null && !payload.image.isRecycled();
        Button ocr = hasImage ? button("OCR") : null;
        Button copy = button("复制全部");
        Button close = button("关闭");
        if (ocr != null) actions.addView(ocr, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        setContentView(box);
        int height = Math.min(maxH, titleH + actionsH + bodyH + dp(ROOT_VPAD_DP + 2));
        positionWindow(usable, width, height, payload.anchor);
        if (ocr != null) ocr.setOnClickListener(v -> runDetachedOcr());
        copy.setOnClickListener(v -> copyText(payload.text, "FloatLens View"));
        close.setOnClickListener(v -> finishNoAnim());
    }

    private void buildViewImage() {
        if (payload.image == null || payload.image.isRecycled()) {
            finishNoAnim();
            return;
        }
        Rect usable = usableBounds();
        int width = standardWidth(usable);
        int maxH = standardMaxHeight(usable);
        int titleH = dp(TITLE_H_DP), actionsH = dp(ACTION_H_DP);
        int bodyMax = Math.max(dp(120), maxH - titleH - actionsH - dp(ROOT_VPAD_DP));

        LinearLayout box = baseBox();
        box.addView(heading("View / 图标"), new LinearLayout.LayoutParams(-1, titleH));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        int imageH = addImage(body, payload.image, width - dp(BOX_HPAD_DP * 2), dp(190));
        SelectionAwareEditText meta = selectableText(payload.meta);
        body.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        int desired = imageH + dp(6) + textHeight(payload.meta, width - dp(44), 15f, 14);
        int bodyH = clamp(desired, dp(120), bodyMax);

        scroll.addView(body);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = actionRow();
        Button ocr = button("OCR");
        Button save = button("保存图片");
        Button close = button("关闭");
        actions.addView(ocr, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        setContentView(box);
        int height = Math.min(maxH, titleH + bodyH + actionsH + dp(ROOT_VPAD_DP));
        positionWindow(usable, width, height, payload.anchor);
        ocr.setOnClickListener(v -> runDetachedOcr());
        save.setOnClickListener(v -> ScreenshotController.save(this, payload.image));
        close.setOnClickListener(v -> finishNoAnim());
    }

    private void buildOcrText() {
        FloatSettings fs = new FloatSettings(this);
        Rect usable = usableBounds();
        int width = preferredWidth(usable, payload.text, payload.blocks, payload.image);
        int innerW = Math.max(dp(120), width - dp(32));
        int maxH = standardMaxHeight(usable);
        int reserved = dp(TITLE_H_DP + ACTION_H_DP + ROOT_VPAD_DP + 6);
        int maxBodyH = Math.max(dp(72), maxH - reserved);

        LinearLayout box = baseBox();
        box.addView(heading("OCR 结果"), new LinearLayout.LayoutParams(-1, dp(TITLE_H_DP)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int desiredBodyH = 0;

        if (fs.ocrShowImage() && payload.image != null && !payload.image.isRecycled()) {
            desiredBodyH += addImage(body, payload.image, innerW, dp(135)) + dp(4);
        }
        if (fs.ocrShowText()) {
            SelectionAwareEditText all = selectableText(payload.text);
            body.addView(all, new LinearLayout.LayoutParams(-1, -2));
            desiredBodyH += textHeight(payload.text, innerW - dp(16), 16f, 14);

            if (!fs.ocrCollapse() && payload.blocks.size() > 1) {
                TextView h = new TextView(this);
                h.setText("识别块（点按文本处理）");
                h.setTextColor(0xFFBBBBBB);
                h.setTextSize(13);
                h.setPadding(dp(8), dp(8), dp(8), dp(3));
                body.addView(h);
                desiredBodyH += dp(31);
                for (String block : payload.blocks) {
                    TextView tv = blockView(block);
                    tv.setOnClickListener(v -> showBlockActionMode(tv, block));
                    body.addView(tv, new LinearLayout.LayoutParams(-1, -2));
                    desiredBodyH += textHeight(block, innerW - dp(16), 16f, 10);
                }
            }
        }
        if (desiredBodyH <= 0) {
            TextView empty = blockView("无可显示内容");
            body.addView(empty);
            desiredBodyH = dp(48);
        }

        scroll.addView(body);
        int bodyH = clamp(desiredBodyH, dp(56), maxBodyH);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = actionRow();
        Button copy = button("复制全部");
        Button close = button("关闭");
        actions.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, dp(ACTION_H_DP)));

        setContentView(box);
        int height = clamp(reserved + bodyH, dp(158), maxH);
        positionWindow(usable, width, height, payload.anchor);
        copy.setOnClickListener(v -> copyText(payload.text, "FloatLens OCR"));
        close.setOnClickListener(v -> finishWithCircle("result_closed"));
    }

    private void runDetachedOcr() {
        if (payload.image == null || payload.image.isRecycled()) return;
        Bitmap image = payload.image;
        Rect anchor = payload.anchor == null ? null : new Rect(payload.anchor);
        finishNoAnim();
        OcrEngine.recognize(getApplicationContext(), image, anchor);
    }

    private SelectionAwareEditText selectableText(String value) {
        SelectionAwareEditText tv = new SelectionAwareEditText(this);
        tv.setText(value == null ? "" : value);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setBackgroundColor(Color.TRANSPARENT);
        tv.setGravity(Gravity.TOP | Gravity.START);
        tv.setSingleLine(false);
        tv.setHorizontallyScrolling(false);
        tv.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tv.setKeyListener(null);
        tv.setCursorVisible(false);
        tv.setShowSoftInputOnFocus(false);
        tv.setTextIsSelectable(true);
        tv.setLongClickable(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setSelectAllOnFocus(false);
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));

        tv.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                activeSelectionActionMode = mode;
                activeSelectionText = tv;
                lastSelectionStart = tv.getSelectionStart();
                lastSelectionEnd = tv.getSelectionEnd();
                refreshProcessItems(menu, tv);
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                refreshProcessItems(menu, tv);
                // OEM TextView editors can re-show the toolbar while a handle is moving. If a
                // selection-change generation is still pending, immediately push it back hidden.
                if (activeSelectionActionMode == mode && selectionReshow != null) {
                    postHideSelectionToolbar(tv, mode, selectionGeneration);
                }
                return true;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                Intent target = item.getIntent();
                if (target != null && Intent.ACTION_PROCESS_TEXT.equals(target.getAction())) {
                    String selected = selectedText(tv);
                    if (selected.isEmpty()) return false;
                    try {
                        startActivity(new Intent(target)
                                .putExtra(Intent.EXTRA_PROCESS_TEXT, selected)
                                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true));
                    } catch (Throwable t) {
                        Toast.makeText(ResultActivity.this,
                                "无法打开文本处理应用", Toast.LENGTH_SHORT).show();
                    }
                    mode.finish();
                    return true;
                }
                return false;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {
                if (activeSelectionActionMode == mode) clearSelectionActionMode(tv);
            }
        });

        tv.setSelectionChangedListener((start, end) -> onSelectionChanged(tv, start, end));
        return tv;
    }

    private void onSelectionChanged(SelectionAwareEditText tv, int start, int end) {
        ActionMode mode = activeSelectionActionMode;
        if (mode == null || activeSelectionText != tv) return;
        if (start < 0 || end < 0 || start == end) return;
        if (start == lastSelectionStart && end == lastSelectionEnd) return;

        // The ActionMode callback is created after the initial long-press selection. Every later
        // range change is therefore a real handle adjustment (or equivalent framework adjustment).
        lastSelectionStart = start;
        lastSelectionEnd = end;
        long generation = ++selectionGeneration;

        if (selectionReshow != null) tv.removeCallbacks(selectionReshow);
        postHideSelectionToolbar(tv, mode, generation);
        tv.postDelayed(() -> postHideSelectionToolbar(tv, mode, generation), 16L);
        tv.postDelayed(() -> postHideSelectionToolbar(tv, mode, generation), 32L);

        selectionReshow = () -> {
            if (activeSelectionActionMode != mode || activeSelectionText != tv
                    || generation != selectionGeneration) return;
            try { mode.hide(0L); } catch (Throwable ignored) {}
            selectionReshow = null;
            DiagnosticLog.i(ResultActivity.this, "RESULT_TEXT_MENU",
                    "HANDLE_ADJUST_END selected=" + selectedText(tv).length());
        };
        tv.postDelayed(selectionReshow, SELECTION_RESHOW_DELAY_MS);
        DiagnosticLog.i(this, "RESULT_TEXT_MENU", "HANDLE_ADJUST_HIDE range=" + start + ":" + end);
    }

    private void postHideSelectionToolbar(SelectionAwareEditText tv, ActionMode mode, long generation) {
        tv.post(() -> {
            if (activeSelectionActionMode != mode || activeSelectionText != tv
                    || generation != selectionGeneration) return;
            try { mode.hide(SELECTION_HIDE_HOLD_MS); } catch (Throwable ignored) {}
        });
    }

    private void clearSelectionActionMode(SelectionAwareEditText tv) {
        if (selectionReshow != null && tv != null) tv.removeCallbacks(selectionReshow);
        selectionReshow = null;
        activeSelectionActionMode = null;
        activeSelectionText = null;
        lastSelectionStart = lastSelectionEnd = -1;
        selectionGeneration++;
    }

    private void refreshProcessItems(Menu menu, EditText tv) {
        if (menu == null) return;
        try { menu.removeGroup(MENU_GROUP_PROCESS); } catch (Throwable ignored) {}
        String selected = selectedText(tv);
        if (!selected.isEmpty()) addProcessTextItems(menu, selected);
    }

    private String selectedText(EditText tv) {
        if (tv == null || tv.getText() == null) return "";
        int a = tv.getSelectionStart(), b = tv.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return "";
        int lo = Math.max(0, Math.min(a, b));
        int hi = Math.min(tv.length(), Math.max(a, b));
        return lo < hi ? tv.getText().subSequence(lo, hi).toString() : "";
    }

    private void addProcessTextItems(Menu menu, String value) {
        if (menu == null || value == null || value.isBlank()) return;
        Intent base = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, value)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> handlers;
        try { handlers = pm.queryIntentActivities(base, PackageManager.MATCH_ALL); }
        catch (Throwable t) { handlers = new ArrayList<>(); }

        Set<String> existing = new HashSet<>();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem old = menu.getItem(i);
            Intent oi = old == null ? null : old.getIntent();
            if (oi != null && Intent.ACTION_PROCESS_TEXT.equals(oi.getAction())
                    && oi.getComponent() != null) {
                existing.add(oi.getComponent().flattenToString());
            }
        }

        int order = 10;
        int id = MENU_PROCESS_BASE;
        if (handlers == null) return;
        for (ResolveInfo ri : handlers) {
            if (ri == null || ri.activityInfo == null) continue;
            String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
            if (!existing.add(key)) continue;
            CharSequence label;
            try { label = ri.loadLabel(pm); }
            catch (Throwable ignored) { label = ri.activityInfo.name; }
            Intent target = new Intent(base).setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            MenuItem item = menu.add(MENU_GROUP_PROCESS, id++, order++,
                    label == null ? ri.activityInfo.name : label);
            item.setIntent(target);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
    }

    private TextView blockView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16);
        tv.setClickable(true);
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        return tv;
    }

    private void showBlockActionMode(TextView anchor, String block) {
        final String value = block == null ? "" : block.trim();
        if (value.isEmpty()) return;
        if (activeBlockActionMode != null) {
            try { activeBlockActionMode.finish(); } catch (Throwable ignored) {}
            activeBlockActionMode = null;
        }

        ActionMode.Callback2 cb = new ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, MENU_COPY, 0, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(Menu.NONE, MENU_SHARE, 1, "分享").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                addProcessTextItems(menu, value);
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == MENU_COPY) {
                    copyText(value, "FloatLens OCR");
                    mode.finish();
                    return true;
                }
                if (item.getItemId() == MENU_SHARE) {
                    shareText(value);
                    mode.finish();
                    return true;
                }
                Intent target = item.getIntent();
                if (target != null) {
                    try { startActivity(target); }
                    catch (Throwable t) {
                        Toast.makeText(ResultActivity.this, "无法打开文本处理应用", Toast.LENGTH_SHORT).show();
                    }
                    mode.finish();
                    return true;
                }
                return false;
            }
            @Override public void onDestroyActionMode(ActionMode mode) {
                if (activeBlockActionMode == mode) activeBlockActionMode = null;
            }
            @Override public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                outRect.set(0, 0, Math.max(1, anchor.getWidth()), Math.max(1, anchor.getHeight()));
            }
        };

        try { activeBlockActionMode = anchor.startActionMode(cb, ActionMode.TYPE_FLOATING); }
        catch (Throwable t) {
            DiagnosticLog.i(this, "RESULT_TEXT_MENU", "BLOCK_ACTIONMODE_FAILED " + t);
        }
    }

    private void shareText(String value) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, value);
        try { startActivity(Intent.createChooser(share, "分享文字")); }
        catch (Throwable t) {
            Toast.makeText(this, "无法打开分享菜单", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyText(String value, String label) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, value == null ? "" : value));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN
                && activeBlockActionMode != null) {
            ActionMode old = activeBlockActionMode;
            activeBlockActionMode = null;
            try { old.finish(); } catch (Throwable ignored) {}
        }
        return super.dispatchTouchEvent(ev);
    }

    private int addImage(LinearLayout body, Bitmap image, int innerW, int maxHeightPx) {
        if (image == null || image.isRecycled() || image.getWidth() <= 0 || image.getHeight() <= 0) return 0;
        float ratio = image.getHeight() / (float) image.getWidth();
        int h = clamp(Math.round(innerW * ratio), dp(44), maxHeightPx);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(image);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageShareUtils.attachLongPressShare(this, iv, image);
        body.addView(iv, new LinearLayout.LayoutParams(-1, h));
        return h;
    }

    private int standardWidth(Rect usable) {
        int maxW = Math.max(dp(220), usable.width() - dp(OUTER_MARGIN_DP * 2));
        return Math.min(dp(410), maxW);
    }

    private int standardMaxHeight(Rect usable) {
        int byScreen = Math.round(usable.height() * .52f);
        int available = Math.max(dp(170), usable.height() - dp(OUTER_MARGIN_DP * 2));
        return Math.min(available, Math.max(dp(190), Math.min(byScreen, dp(430))));
    }

    private int preferredWidth(Rect usable, String text, List<String> blocks, Bitmap image) {
        int minW = Math.min(dp(220), Math.max(1, usable.width() - dp(OUTER_MARGIN_DP * 2)));
        int maxW = Math.max(minW, Math.min(dp(410), Math.max(1, usable.width() - dp(OUTER_MARGIN_DP * 2))));
        int desired = minW;
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setTextSize(spPx(16f));
        desired = Math.max(desired, measuredTextWidth(text, p) + dp(48));
        if (blocks != null) {
            for (String block : blocks) desired = Math.max(desired, measuredTextWidth(block, p) + dp(48));
        }
        if (image != null && !image.isRecycled() && image.getWidth() > 0) {
            float density = Math.max(.1f, getResources().getDisplayMetrics().density);
            int sourceDpWidth = Math.round(image.getWidth() / density);
            desired = Math.max(desired, dp(Math.min(360, Math.max(170, sourceDpWidth))) + dp(32));
        }
        return clamp(desired, minW, maxW);
    }

    private int measuredTextWidth(String text, TextPaint p) {
        if (text == null || text.isEmpty()) return 0;
        float max = 0f;
        for (String line : text.split("\\n", -1)) max = Math.max(max, p.measureText(line));
        return Math.round(max);
    }

    private int textHeight(String text, int widthPx, float textSp, int extraDp) {
        return wrappedLines(text, widthPx, textSp) * dp(22) + dp(extraDp);
    }

    private int wrappedLines(String text, int widthPx, float textSp) {
        if (text == null || text.isBlank() || widthPx <= 0) return 1;
        try {
            TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            p.setTextSize(spPx(textSp));
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), p, widthPx)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();
            return Math.max(1, layout.getLineCount());
        } catch (Throwable ignored) {
            return Math.max(1, text.split("\\n", -1).length);
        }
    }

    private int estimateTextHeight(String value, int widthPx) {
        String safe = value == null || value.isEmpty() ? " " : value;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTextSize(spPx(16f));
        try {
            StaticLayout layout = StaticLayout.Builder.obtain(safe, 0, safe.length(), paint,
                            Math.max(1, widthPx))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(true)
                    .build();
            return Math.max(dp(40), layout.getHeight() + dp(10));
        } catch (Throwable ignored) {
            return dp(56);
        }
    }

    private Rect usableBounds() {
        try {
            WindowManager wm = getWindowManager();
            android.view.WindowMetrics metrics = wm.getMaximumWindowMetrics();
            Rect r = new Rect(metrics.getBounds());
            android.graphics.Insets insets = metrics.getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            r.left += insets.left;
            r.top += insets.top;
            r.right -= insets.right;
            r.bottom -= insets.bottom;
            if (!r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        return new Rect(0, 0, getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
    }

    private void positionWindow(Rect usable, int width, int height, Rect anchor) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = width;
        lp.height = height;
        lp.gravity = Gravity.TOP | Gravity.START;
        int[] xy = choosePosition(usable, normalizeAnchor(anchor, usable), width, height);
        lp.x = xy[0];
        lp.y = xy[1];
        getWindow().setAttributes(lp);
        DiagnosticLog.i(this, "RESULT_ACTIVITY", "WINDOW mode=" + payload.mode
                + " size=" + width + "x" + height + " pos=" + lp.x + "," + lp.y);
    }

    private int[] choosePosition(Rect usable, Rect anchor, int w, int h) {
        int margin = dp(OUTER_MARGIN_DP), gap = dp(ANCHOR_GAP_DP);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - w);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - h);
        if (anchor == null || anchor.isEmpty()) {
            return new int[]{clamp(usable.centerX() - w / 2, minX, maxX),
                    clamp(usable.centerY() - h / 2, minY, maxY)};
        }
        int cx = anchor.centerX(), cy = anchor.centerY();
        ArrayList<Placement> choices = new ArrayList<>();
        choices.add(new Placement(cx - w / 2, anchor.bottom + gap, 0));
        choices.add(new Placement(cx - w / 2, anchor.top - gap - h, 1));
        choices.add(new Placement(anchor.right + gap, cy - h / 2, 2));
        choices.add(new Placement(anchor.left - gap - w, cy - h / 2, 3));
        for (Placement p : choices) {
            p.fit = p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY;
            p.cx = clamp(p.x, minX, maxX);
            p.cy = clamp(p.y, minY, maxY);
            Rect placed = new Rect(p.cx, p.cy, p.cx + w, p.cy + h);
            Rect overlap = new Rect(placed);
            p.overlap = overlap.intersect(anchor) ? (long) overlap.width() * overlap.height() : 0L;
            p.shift = Math.abs(p.cx - p.x) + Math.abs(p.cy - p.y);
        }
        choices.sort(Comparator.comparing((Placement p) -> !p.fit)
                .thenComparingLong(p -> p.overlap)
                .thenComparingInt(p -> p.shift)
                .thenComparingInt(p -> p.preference));
        Placement best = choices.get(0);
        return new int[]{best.cx, best.cy};
    }

    private Rect normalizeAnchor(Rect anchor, Rect usable) {
        if (anchor == null || anchor.isEmpty()) return null;
        Rect r = new Rect(anchor);
        return r.intersect(usable) ? r : null;
    }

    private void finishWithCircle(String reason) {
        finishActionModes();
        if (!circleFinished) {
            circleFinished = true;
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished(reason);
        }
        finishNoAnim();
    }

    private void finishActionModes() {
        if (activeBlockActionMode != null) {
            try { activeBlockActionMode.finish(); } catch (Throwable ignored) {}
            activeBlockActionMode = null;
        }
        if (activeSelectionActionMode != null) {
            SelectionAwareEditText tv = activeSelectionText;
            try { activeSelectionActionMode.finish(); } catch (Throwable ignored) {}
            clearSelectionActionMode(tv);
        }
    }

    private void finishNoAnim() {
        finishActionModes();
        finish();
        overridePendingTransition(0, 0);
    }

    @Override public void onBackPressed() {
        if (activeBlockActionMode != null) {
            ActionMode old = activeBlockActionMode;
            activeBlockActionMode = null;
            try { old.finish(); } catch (Throwable ignored) {}
            return;
        }
        if (activeSelectionActionMode != null) {
            ActionMode old = activeSelectionActionMode;
            SelectionAwareEditText tv = activeSelectionText;
            try { old.finish(); } catch (Throwable ignored) {}
            clearSelectionActionMode(tv);
            return;
        }
        if (payload != null && payload.mode == MODE_OCR_TEXT) finishWithCircle("result_back");
        else finishNoAnim();
    }

    @Override protected void onDestroy() {
        finishActionModes();
        if (payload != null && payload.mode == MODE_SCREENSHOT && payload.image != null) {
            clearInlineForImage(payload.image);
        }
        if (token != 0L) PENDING.remove(token);
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private float spPx(float sp) { return sp * getResources().getDisplayMetrics().scaledDensity; }
    private int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
    }

    private static final class SelectionAwareEditText extends EditText {
        interface SelectionListener { void onChanged(int start, int end); }
        private SelectionListener listener;
        SelectionAwareEditText(Context context) { super(context); }
        void setSelectionChangedListener(SelectionListener listener) { this.listener = listener; }
        @Override protected void onSelectionChanged(int selStart, int selEnd) {
            super.onSelectionChanged(selStart, selEnd);
            if (listener != null) listener.onChanged(selStart, selEnd);
        }
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
            this.text = text == null ? "" : text;
            this.blocks = blocks == null ? new ArrayList<>() : new ArrayList<>(blocks);
            this.image = image;
            this.meta = meta == null ? "" : meta;
            this.anchor = anchor == null ? null : new Rect(anchor);
        }
    }

    private static final class Placement {
        final int x, y, preference;
        int cx, cy, shift;
        long overlap;
        boolean fit;
        Placement(int x, int y, int preference) {
            this.x = x;
            this.y = y;
            this.preference = preference;
        }
    }
}
