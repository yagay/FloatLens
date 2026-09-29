package com.yagay.YFloat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Compact YFloat control center. Detailed configuration lives in short category pages. */
public class MainActivity extends AppCompatActivity {
    private SwitchMaterial overlaySwitch;
    private TextView serviceStatus;
    private boolean syncingOverlaySwitch;
    private boolean awaitingAccessibilityGrant;

    private PermissionRow overlayPermission;
    private PermissionRow accessibilityPermission;
    private PermissionRow notificationPermission;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable delayedAccessibilityRefresh = () -> {
        if (isFinishing() || isDestroyed()) return;
        maybeStartEnabledFloatService();
        refreshStatus();
    };
    private final Runnable accessibilityStateListener = () -> {
        if (isFinishing() || isDestroyed()) return;
        maybeStartEnabledFloatService();
        refreshStatus();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = AppUi.pageRoot(this, "YFloat",
                "悬浮取词 · 截图 · OCR");

        AppUi.Section service = AppUi.section(this, "悬浮服务",
                "这里只控制运行状态；具体行为放到下面的分类设置。" );
        overlaySwitch = AppUi.switchRow(this,
                "启用悬浮图标",
                "关闭后停止常驻服务，开启状态会自动保存",
                FloatServiceState.isEnabled(this),
                (button, checked) -> onOverlayToggle(checked));
        AppUi.addRow(service.body, AppUi.switchContainer(overlaySwitch));

        LinearLayout statusRow = AppUi.baseRow(this);
        statusRow.addView(AppUi.text(this, "当前状态", 15, false),
                new LinearLayout.LayoutParams(0, -2, 1f));
        serviceStatus = AppUi.statusPill(this, "读取中", false);
        statusRow.addView(serviceStatus, new LinearLayout.LayoutParams(-2, -2));
        AppUi.addRow(service.body, statusRow);
        AppUi.addSection(root, service);

        AppUi.Section permissions = AppUi.section(this, "权限",
                "状态集中显示，需要时直接进入系统设置。" );
        overlayPermission = permissionRow(
                "悬浮窗",
                "在其他应用上方显示 YFloat",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        AppUi.addRow(permissions.body, overlayPermission.view);

        boolean suiteHost = "com.yagay.YSuite".equals(getPackageName());
        accessibilityPermission = permissionRow(
                suiteHost ? "YSuite 统一无障碍" : "无障碍服务",
                suiteHost
                        ? "YFloat 与 YNotify 共用；只需开启一次即可提供识别、截图和界面事件能力"
                        : "View 识别、系统动作和无障碍截图",
                this::openAccessibilitySettings);
        AppUi.addRow(permissions.body, accessibilityPermission.view);

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission = permissionRow(
                    "通知",
                    "前台服务常驻通知",
                    this::handleNotificationPermission);
            AppUi.addRow(permissions.body, notificationPermission.view);
        }
        AppUi.addSection(root, permissions);

        AppUi.Section settings = AppUi.section(this, "功能设置",
                "每一项进入独立短页面，不再把所有设置堆在一起。" );
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "悬浮图标",
                "大小、透明度、样式、贴边和显示行为",
                () -> startActivity(SettingsActivity.intent(this, SettingsActivity.SECTION_ICON))));
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "手势与轨迹",
                "手势参数、轨迹反馈和动作映射",
                () -> startActivity(new Intent(this, GestureHubActivity.class))));
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "截图与 OCR",
                "截图来源、OCR 引擎、模型和识别语言",
                () -> startActivity(SettingsActivity.intent(this, SettingsActivity.SECTION_CAPTURE))));
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "环境与显示",
                "键盘避让、智能入口和按应用隐藏",
                () -> startActivity(SettingsActivity.intent(this, SettingsActivity.SECTION_ENVIRONMENT))));
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "界面与菜单",
                "主题、文字菜单数量和菜单管理",
                () -> startActivity(new Intent(this, AppearanceSettingsActivity.class))));
        AppUi.addSection(root, settings);

        AppUi.Section advanced = AppUi.section(this, "高级", null);
        AppUi.addRow(advanced.body, AppUi.navRow(this,
                "高级权限",
                "Root / LSPosed 可选增强、授权状态与失败回退",
                () -> startActivity(SettingsActivity.intent(this, SettingsActivity.SECTION_PRIVILEGE))));
        AppUi.addRow(advanced.body, AppUi.navRow(this,
                "诊断与调试",
                "YFloat 自身运行日志",
                () -> startActivity(new Intent(this, DiagnosticsActivity.class))));
        AppUi.addSection(root, advanced);

        TextView footer = AppUi.caption(this,
                "YFloat " + BuildConfig.VERSION_NAME,
                12);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, 2, 0, AppUi.dp(this, 4));
        root.addView(footer);

        setContentView(AppUi.scrollPage(this, root));
    }

    @Override protected void onResume() {
        super.onResume();
        boolean returnedFromAccessibilitySettings = awaitingAccessibilityGrant;
        awaitingAccessibilityGrant = false;

        AccessibilityState.addListener(accessibilityStateListener);
        boolean enabled = FloatServiceState.isEnabled(this);
        syncOverlaySwitch(enabled);
        maybeStartEnabledFloatService();
        refreshStatus();

        if (returnedFromAccessibilitySettings) {
            AccessibilityState.Snapshot snapshot = AccessibilityState.snapshot(this);
            if (!snapshot.hostEnabled) {
                String message;
                if (snapshot.sameHostOtherAccessibilityEnabled
                        && "com.yagay.YSuite".equals(getPackageName())) {
                    message = "检测到旧版 YSuite 分模块无障碍授权；请改为开启“YSuite 统一无障碍”";
                } else if (snapshot.otherYFloatEnabled
                        && "com.yagay.YSuite".equals(getPackageName())) {
                    message = "独立版 YFloat 已开启，但 YSuite 统一无障碍仍未开启";
                } else if ("com.yagay.YSuite".equals(getPackageName())) {
                    message = "YSuite 统一无障碍仍未开启";
                } else if (snapshot.otherYFloatEnabled) {
                    message = "另一版本 YFloat 已开启，但当前版本仍未开启";
                } else {
                    message = "YFloat 无障碍仍未开启";
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        }

        mainHandler.removeCallbacks(delayedAccessibilityRefresh);
        mainHandler.postDelayed(delayedAccessibilityRefresh, 400L);
        mainHandler.postDelayed(delayedAccessibilityRefresh, 1400L);
        mainHandler.postDelayed(delayedAccessibilityRefresh, 3000L);
    }

    @Override protected void onPause() {
        AccessibilityState.removeListener(accessibilityStateListener);
        mainHandler.removeCallbacks(delayedAccessibilityRefresh);
        super.onPause();
    }

    private void openAccessibilitySettings() {
        if (AccessibilitySettingsNavigator.open(this)) {
            awaitingAccessibilityGrant = true;
        } else {
            Toast.makeText(this, "无法打开系统无障碍设置", Toast.LENGTH_LONG).show();
        }
    }

    private void maybeStartEnabledFloatService() {
        if (!FloatServiceState.isEnabled(this) || FloatService.get() != null) return;
        if (Settings.canDrawOverlays(this) || AccessibilityState.connected()) {
            FloatServiceState.start(this);
        }
    }

    private void onOverlayToggle(boolean checked) {
        if (syncingOverlaySwitch) return;
        if (checked) {
            boolean overlayGranted = Settings.canDrawOverlays(this);
            AccessibilityState.Snapshot a11y = AccessibilityState.snapshot(this);
            if (!overlayGranted && !a11y.hostEnabled) {
                String message;
                if (a11y.sameHostOtherAccessibilityEnabled
                        && "com.yagay.YSuite".equals(getPackageName())) {
                    message = "检测到旧版 YSuite 无障碍授权；请迁移到“YSuite 统一无障碍”";
                } else if (a11y.otherYFloatEnabled
                        && "com.yagay.YSuite".equals(getPackageName())) {
                    message = "独立版 YFloat 已开启；YSuite 请改为开启统一无障碍";
                } else if ("com.yagay.YSuite".equals(getPackageName())) {
                    message = "请先授予悬浮窗权限或开启 YSuite 统一无障碍";
                } else {
                    message = "请先授予悬浮窗权限或开启当前 YFloat 无障碍服务";
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                FloatServiceState.setEnabled(this, false);
                syncOverlaySwitch(false);
                refreshStatus();
                return;
            }

            if (!overlayGranted && a11y.hostEnabled && !a11y.connected) {
                FloatServiceState.setEnabled(this, true);
                syncOverlaySwitch(true);
                Toast.makeText(this,
                        "无障碍已授权，正在等待系统连接服务",
                        Toast.LENGTH_SHORT).show();
                mainHandler.postDelayed(delayedAccessibilityRefresh, 500L);
                mainHandler.postDelayed(delayedAccessibilityRefresh, 1500L);
                refreshStatus();
                return;
            }

            if (FloatServiceState.start(this)) {
                Toast.makeText(this, "悬浮图标已开启", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "启动失败，已保留开启状态供稍后自动重试", Toast.LENGTH_LONG).show();
            }
        } else {
            FloatServiceState.stop(this);
            Toast.makeText(this, "悬浮图标已关闭", Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private void refreshStatus() {
        boolean overlayGranted = Settings.canDrawOverlays(this);
        AccessibilityState.Snapshot a11y = AccessibilityState.snapshot(this);
        boolean notificationsGranted = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        updatePermission(overlayPermission, overlayGranted);
        updateAccessibilityPermission(accessibilityPermission, a11y);
        if (notificationPermission != null) updatePermission(notificationPermission, notificationsGranted);

        boolean enabled = FloatServiceState.isEnabled(this);
        boolean running = FloatService.get() != null;
        if (!enabled) {
            setServiceStatus("已停止", false);
        } else if (running && a11y.connected) {
            setServiceStatus("运行中 · 无障碍已连接", true);
        } else if (running && a11y.hostEnabled) {
            setServiceStatus("运行中 · 等待无障碍连接", false);
        } else if (running) {
            setServiceStatus("运行中 · 无障碍未开启", false);
        } else if (a11y.hostEnabled && !a11y.connected && !overlayGranted) {
            setServiceStatus("等待无障碍连接", false);
        } else {
            setServiceStatus("等待恢复", false);
        }
    }

    private void setServiceStatus(String text, boolean positive) {
        serviceStatus.setText(text);
        serviceStatus.setTextColor(positive ? AppUi.success(this) : AppUi.warning(this));
        serviceStatus.setBackground(AppUi.rounded(this,
                positive ? AppUi.successSurface(this) : AppUi.warningSurface(this), 999));
    }

    private PermissionRow permissionRow(String title, String subtitle, Runnable action) {
        LinearLayout row = AppUi.baseRow(this);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = AppUi.text(this, title, 15, false);
        copy.addView(titleView);
        TextView sub = AppUi.caption(this, subtitle, 12);
        sub.setPadding(0, AppUi.dp(this, 2), AppUi.dp(this, 8), 0);
        copy.addView(sub);
        TextView status = AppUi.caption(this, "未授权", 12);
        status.setPadding(0, AppUi.dp(this, 2), 0, 0);
        copy.addView(status);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        MaterialButton button = AppUi.compactButton(this, "授权");
        button.setOnClickListener(v -> { if (action != null) action.run(); });
        row.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return new PermissionRow(row, status, button);
    }

    private void updatePermission(PermissionRow row, boolean granted) {
        if (row == null) return;
        row.status.setText(granted ? "已授权" : "未授权");
        row.status.setTextColor(granted ? AppUi.success(this) : AppUi.warning(this));
        row.button.setText(granted ? "设置" : "授权");
    }

    private void updateAccessibilityPermission(
            PermissionRow row,
            AccessibilityState.Snapshot snapshot
    ) {
        if (row == null || snapshot == null) return;
        row.status.setText(snapshot.statusLabel(this));
        row.status.setTextColor(snapshot.hostEnabled ? AppUi.success(this) : AppUi.warning(this));
        if (snapshot.hostEnabled) {
            row.button.setText("设置");
        } else if ("com.yagay.YSuite".equals(getPackageName())) {
            row.button.setText("开启 YSuite");
        } else {
            row.button.setText("授权");
        }
    }

    private void handleNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show();
        }
    }

    private void syncOverlaySwitch(boolean checked) {
        if (overlaySwitch == null || overlaySwitch.isChecked() == checked) return;
        syncingOverlaySwitch = true;
        overlaySwitch.setChecked(checked);
        syncingOverlaySwitch = false;
    }

    private static final class PermissionRow {
        final LinearLayout view;
        final TextView status;
        final MaterialButton button;
        PermissionRow(LinearLayout view, TextView status, MaterialButton button) {
            this.view = view;
            this.status = status;
            this.button = button;
        }
    }
}
