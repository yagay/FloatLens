package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/** End-to-end FLAG_SECURE probe for the controlled LSPosed screenshot provider. */
public final class SecureCaptureProbeActivity extends AppCompatActivity {
    private static final long STATUS_REFRESH_TIMEOUT_MS = 1_500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private MaterialButton retry;
    private boolean running;
    private int refreshGeneration;
    private LsposedStatusManager.Listener pendingStatusListener;
    private Runnable pendingStatusTimeout;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ThemeSettings.applySystemBars(this);
        setContentView(buildContent());
        getWindow().getDecorView().postDelayed(this::runProbe, 700L);
    }

    @Override
    protected void onDestroy() {
        cancelPendingStatusRefresh();
        super.onDestroy();
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(AppUi.dp(this, 28), AppUi.dp(this, 28),
                AppUi.dp(this, 28), AppUi.dp(this, 28));
        root.setBackgroundColor(Color.rgb(
                SecureCaptureProbePolicy.MARKER_RED,
                SecureCaptureProbePolicy.MARKER_GREEN,
                SecureCaptureProbePolicy.MARKER_BLUE));

        TextView title = AppUi.text(this, "LSPosed 安全窗口截图自检", 20, true);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(-2, -2));

        status = AppUi.caption(this,
                "这是 FloatLens 自己的 FLAG_SECURE 测试窗口。正在验证 system_server Hook 是否能在短时 lease 内抓到这里的标记颜色…",
                14);
        status.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = AppUi.dp(this, 16);
        root.addView(status, statusLp);

        LinearLayout buttons = AppUi.buttonRow(this);
        retry = AppUi.secondaryButton(this, "重新测试");
        retry.setEnabled(false);
        retry.setOnClickListener(v -> runProbe());
        buttons.addView(retry, new LinearLayout.LayoutParams(0, -2, 1f));

        MaterialButton close = AppUi.secondaryButton(this, "返回");
        close.setOnClickListener(v -> finish());
        buttons.addView(close, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, -2);
        buttonsLp.topMargin = AppUi.dp(this, 18);
        root.addView(buttons, buttonsLp);
        return root;
    }

    private void runProbe() {
        if (running) return;
        running = true;
        retry.setEnabled(false);
        status.setText("正在刷新 LSPosed 框架、Remote Preferences 与 system_server 加载状态…");

        final int generation = ++refreshGeneration;
        cancelPendingStatusRefresh();

        pendingStatusListener = snapshot -> {
            if (!running || generation != refreshGeneration) return;
            cancelPendingStatusRefresh();
            evaluatePreconditionsAndCapture();
        };
        LsposedStatusManager.addListener(pendingStatusListener, false);

        pendingStatusTimeout = () -> {
            if (!running || generation != refreshGeneration) return;
            cancelPendingStatusRefresh();
            evaluatePreconditionsAndCapture();
        };
        main.postDelayed(pendingStatusTimeout, STATUS_REFRESH_TIMEOUT_MS);
        LsposedStatusManager.syncRuntimeConfigAsync();
    }

    private void evaluatePreconditionsAndCapture() {
        FloatSettings fs = new FloatSettings(this);
        LsposedStatusManager.Snapshot s = LsposedStatusManager.snapshot();
        if (!PrivilegeManager.canUseLsposedSecureScreenshot(fs)) {
            showResult(false, "前置条件未满足。\n" + gateSummary(fs, s), null);
            return;
        }
        if (LensAccessibilityService.get() == null) {
            showResult(false, "无障碍服务未连接，无法执行安全截图自检。\n" + gateSummary(fs, s), null);
            return;
        }

        status.setText("前置条件已满足，正在建立短时 lease 并调用无障碍截图…");
        DiagnosticLog.i(this, "SECURE_CAPTURE_PROBE", "start " + gateSummary(fs, s));

        ScreenCaptureBackend.captureSecureAccessibility(getApplicationContext(), bitmap ->
                runOnUiThread(() -> evaluate(bitmap)), error ->
                runOnUiThread(() -> showResult(false,
                        "截图调用失败：" + ScreenCaptureBackend.safeMessage(error), error)));
    }

    private String gateSummary(FloatSettings fs, LsposedStatusManager.Snapshot s) {
        String runningTargets = s.runningProcesses.isEmpty()
                ? "无" : String.join(", ", s.runningProcesses);
        return "本地：enhanced=" + fs.enhancedMode()
                + " lsposed=" + fs.lsposedEnabled()
                + " secureScreenshot=" + fs.lsposedSecureScreenshot()
                + "\n框架：service=" + s.serviceConnected
                + " remoteConfig=" + s.remoteConfigReady
                + " systemScope=" + s.systemScopeEnabled
                + " systemLoaded=" + s.systemLoaded
                + "\n远端：enhanced=" + s.remoteEnhancedMode
                + " lsposed=" + s.remoteLsposedEnabled
                + " secureScreenshot=" + s.remoteSecureScreenshotEnabled
                + "\nloadedTargets=" + runningTargets
                + (s.detail.isBlank() ? "" : "\ndetail=" + s.detail);
    }

    private void cancelPendingStatusRefresh() {
        LsposedStatusManager.Listener listener = pendingStatusListener;
        pendingStatusListener = null;
        if (listener != null) LsposedStatusManager.removeListener(listener);
        Runnable timeout = pendingStatusTimeout;
        pendingStatusTimeout = null;
        if (timeout != null) main.removeCallbacks(timeout);
    }

    private void evaluate(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            showResult(false, "截图返回空 Bitmap。", null);
            return;
        }

        int total = 0;
        int matches = 0;
        int centerX = bitmap.getWidth() / 2;
        int centerY = bitmap.getHeight() / 2;
        int stepX = Math.max(1, bitmap.getWidth() / 40);
        int stepY = Math.max(1, bitmap.getHeight() / 40);
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = clamp(centerX + dx * stepX, 0, bitmap.getWidth() - 1);
                int y = clamp(centerY + dy * stepY, 0, bitmap.getHeight() - 1);
                int pixel = bitmap.getPixel(x, y);
                if (SecureCaptureProbePolicy.isMarkerColor(
                        Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                    matches++;
                }
                total++;
            }
        }
        boolean success = SecureCaptureProbePolicy.isSuccessful(matches, total);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        bitmap.recycle();

        if (success) {
            showResult(true,
                    "通过：安全窗口内容已出现在截图 Bitmap 中。采样命中 " + matches + "/" + total
                            + "，Bitmap=" + width + "×" + height + "。",
                    null);
        } else {
            showResult(false,
                    "未通过：截图 API 有返回，但没有抓到 FLAG_SECURE 测试页的标记颜色。采样命中 "
                            + matches + "/" + total + "。这通常表示 system_server Hook 点与当前 OxygenOS 版本不匹配，或安全层仍在更下游被过滤。",
                    null);
        }
    }

    private void showResult(boolean success, String message, Throwable error) {
        cancelPendingStatusRefresh();
        running = false;
        retry.setEnabled(true);
        status.setText((success ? "✅ " : "❌ ") + message);
        DiagnosticLog.i(this, "SECURE_CAPTURE_PROBE",
                (success ? "PASS " : "FAIL ") + message
                        + (error == null ? "" : " error=" + error.getClass().getSimpleName()));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
