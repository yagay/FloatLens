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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Pure screenshot/image result hosted by a normal Activity window. */
public final class ScreenshotResultActivity extends AppCompatActivity {
    private static final String EXTRA_TOKEN = "screenshot_result_token";
    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static final Map<Long, Payload> PENDING = new ConcurrentHashMap<>();
    private static final int MARGIN_DP = 12;
    private static final int GAP_DP = 10;

    private long token;
    private Payload payload;
    private boolean ocrRunning;
    private LinearLayout ocrPanel;
    private TextView ocrText;
    private Button ocrButton;
    private Rect popupUsable;
    private int popupWidth;

    public static boolean show(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        long token = NEXT.getAndIncrement();
        PENDING.put(token, new Payload(image, anchor));
        Intent i = new Intent(c, ScreenshotResultActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            c.startActivity(i);
            DiagnosticLog.i(c, "SCREENSHOT_RESULT", "ACTIVITY_START token=" + token
                    + " image=" + image.getWidth() + "x" + image.getHeight());
            return true;
        } catch (Throwable t) {
            PENDING.remove(token);
            DiagnosticLog.i(c, "SCREENSHOT_RESULT", "ACTIVITY_START_FAILED " + t);
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        token = getIntent().getLongExtra(EXTRA_TOKEN, 0L);
        payload = PENDING.get(token);
        if (payload == null || payload.image == null || payload.image.isRecycled()) {
            finish();
            return;
        }

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        setFinishOnTouchOutside(true);

        buildUi();
        overridePendingTransition(0, 0);
        DiagnosticLog.i(this, "SCREENSHOT_RESULT", "ACTIVITY_CREATED token=" + token);
    }

    private void buildUi() {
        Rect usable = usableBounds();
        popupUsable = new Rect(usable);
        int maxW = Math.max(dp(220), usable.width() - dp(MARGIN_DP * 2));
        int width = Math.min(dp(410), maxW);
        popupWidth = width;
        int maxH = Math.min(dp(430), Math.round(usable.height() * .52f));
        int titleH = dp(38);
        int actionsH = dp(50);
        int verticalPadding = dp(16);
        int horizontalPad = dp(14) * 2;
        int imageW = Math.max(dp(160), width - horizontalPad);
        int desiredImageH = Math.round(imageW * (payload.image.getHeight()
                / (float) Math.max(1, payload.image.getWidth())));
        desiredImageH = clamp(desiredImageH, dp(72),
                Math.max(dp(72), maxH - titleH - actionsH - verticalPadding));
        int height = Math.min(maxH, titleH + actionsH + desiredImageH + verticalPadding);
        int minHeight = titleH + actionsH + dp(64) + verticalPadding;
        height = Math.max(Math.min(maxH, minHeight), height);
        final int requestedHeight = height;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(8));
        box.setBackgroundColor(0xF0202124);
        box.setElevation(dp(10));

        TextView title = new TextView(this);
        title.setText("区域截图");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(title, new LinearLayout.LayoutParams(-1, titleH));

        ImageView image = new ImageView(this);
        image.setImageBitmap(payload.image);
        image.setAdjustViewBounds(false);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageShareUtils.attachLongPressShare(this, image, payload.image);
        box.addView(image, new LinearLayout.LayoutParams(-1, 0, 1f));

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
        ocrText = new TextView(this);
        ocrText.setTextColor(Color.WHITE);
        ocrText.setTextSize(16);
        ocrText.setTextIsSelectable(true);
        ocrText.setLongClickable(true);
        ocrText.setPadding(dp(6), dp(3), dp(6), dp(3));
        ocrScroll.addView(ocrText, new ScrollView.LayoutParams(-1, -2));
        ocrPanel.addView(ocrScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        box.addView(ocrPanel, new LinearLayout.LayoutParams(-1, dp(170)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        ocrButton = button("OCR");
        Button save = button("保存图片");
        Button close = button("关闭");
        actions.addView(ocrButton, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
        actions.addView(close, new LinearLayout.LayoutParams(0, -1, 1));
        box.addView(actions, new LinearLayout.LayoutParams(-1, actionsH));

        setContentView(box);
        positionWindow(usable, width, requestedHeight, payload.anchor);

        box.post(() -> DiagnosticLog.i(this, "SCREENSHOT_RESULT_LAYOUT",
                "root=" + box.getWidth() + "x" + box.getHeight()
                        + " titleH=" + title.getHeight()
                        + " imageH=" + image.getHeight()
                        + " actionsH=" + actions.getHeight()
                        + " requestedH=" + requestedHeight));

        ocrButton.setOnClickListener(v -> runOcr(ocrButton));
        save.setOnClickListener(v -> ScreenshotController.save(this, payload.image));
        close.setOnClickListener(v -> finishNoAnim());
    }

    private void runOcr(Button button) {
        if (ocrRunning || payload == null || payload.image == null || payload.image.isRecycled()) return;
        ocrRunning = true;
        button.setEnabled(false);
        button.setText("识别中…");
        Bitmap image = payload.image;
        Rect anchor = payload.anchor == null ? null : new Rect(payload.anchor);
        DiagnosticLog.i(this, "SCREENSHOT_RESULT", "OCR_BUTTON image="
                + image.getWidth() + "x" + image.getHeight() + " mode=inline");

        ResultTextActivity.captureNextForImage(image, (text, blocks) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || payload == null || payload.image != image) return;
            ocrRunning = false;
            button.setEnabled(true);
            button.setText("重新识别");
            showInlineOcr(text);
        }));

        button.postDelayed(() -> {
            if (!ocrRunning) return;
            ResultTextActivity.clearInlineForImage(image);
            ocrRunning = false;
            button.setEnabled(true);
            button.setText("OCR");
            DiagnosticLog.i(this, "SCREENSHOT_RESULT", "OCR_INLINE_TIMEOUT");
        }, 12000L);

        OcrEngine.recognize(getApplicationContext(), image, anchor);
    }

    private void showInlineOcr(String text) {
        if (ocrPanel == null || ocrText == null) return;
        String value = text == null ? "" : text.trim();
        ocrText.setText(value.isEmpty() ? "未识别到文字" : value);
        ocrPanel.setVisibility(View.VISIBLE);

        Rect usable = popupUsable == null ? usableBounds() : new Rect(popupUsable);
        int expandedH = Math.min(dp(610), Math.round(usable.height() * .72f));
        expandedH = Math.max(dp(360), expandedH);
        positionWindow(usable, popupWidth > 0 ? popupWidth : Math.min(dp(410), usable.width()),
                expandedH, payload == null ? null : payload.anchor);
        DiagnosticLog.i(this, "SCREENSHOT_RESULT", "OCR_INLINE chars=" + value.length()
                + " expandedH=" + expandedH);
    }

    private void positionWindow(Rect usable, int width, int height, Rect anchor) {
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = width;
        lp.height = height;
        lp.gravity = Gravity.TOP | Gravity.START;
        int[] xy = choosePosition(usable, anchor, width, height);
        lp.x = xy[0];
        lp.y = xy[1];
        w.setAttributes(lp);
        DiagnosticLog.i(this, "SCREENSHOT_RESULT", "WINDOW size=" + width + "x" + height
                + " pos=" + lp.x + "," + lp.y
                + " anchor=" + (anchor == null ? "none" : anchor.toShortString()));
    }

    private int[] choosePosition(Rect usable, Rect anchor, int w, int h) {
        int margin = dp(MARGIN_DP), gap = dp(GAP_DP);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - w);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - h);
        if (anchor == null || anchor.isEmpty() || !Rect.intersects(usable, anchor)) {
            return new int[]{clamp(usable.centerX() - w / 2, minX, maxX),
                    clamp(usable.centerY() - h / 2, minY, maxY)};
        }

        int cx = anchor.centerX(), cy = anchor.centerY();
        int[][] choices = {
                {cx - w / 2, anchor.bottom + gap},
                {cx - w / 2, anchor.top - gap - h},
                {anchor.right + gap, cy - h / 2},
                {anchor.left - gap - w, cy - h / 2}
        };
        long bestArea = Long.MAX_VALUE;
        int bestShift = Integer.MAX_VALUE;
        int bx = minX, by = minY;
        for (int[] p : choices) {
            int x = clamp(p[0], minX, maxX);
            int y = clamp(p[1], minY, maxY);
            Rect placed = new Rect(x, y, x + w, y + h);
            Rect overlap = new Rect(placed);
            long area = overlap.intersect(anchor) ? (long) overlap.width() * overlap.height() : 0L;
            int shift = Math.abs(x - p[0]) + Math.abs(y - p[1]);
            if (area < bestArea || (area == bestArea && shift < bestShift)) {
                bestArea = area;
                bestShift = shift;
                bx = x;
                by = y;
            }
        }
        return new int[]{bx, by};
    }

    private Rect usableBounds() {
        try {
            var metrics = getWindowManager().getMaximumWindowMetrics();
            Rect r = new Rect(metrics.getBounds());
            var insets = metrics.getWindowInsets()
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

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void finishNoAnim() {
        finish();
        overridePendingTransition(0, 0);
    }

    @Override public void onBackPressed() {
        finishNoAnim();
    }

    @Override protected void onDestroy() {
        if (payload != null && payload.image != null) {
            ResultTextActivity.clearInlineForImage(payload.image);
        }
        if (token != 0L) PENDING.remove(token);
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int clamp(int v, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
    }

    private static final class Payload {
        final Bitmap image;
        final Rect anchor;
        Payload(Bitmap image, Rect anchor) {
            this.image = image;
            this.anchor = anchor == null ? null : new Rect(anchor);
        }
    }
}
