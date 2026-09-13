package com.yagay.floatlens;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** FloatLens control center. Advanced diagnostics live in DiagnosticsActivity. */
public class MainActivity extends AppCompatActivity {
    private SwitchMaterial overlaySwitch;
    private TextView serviceStatus;
    private boolean syncingOverlaySwitch;

    private PermissionRow overlayPermission;
    private PermissionRow accessibilityPermission;
    private PermissionRow notificationPermission;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = AppUi.pageRoot(this, "FloatLens",
                "悬浮取词 · 截图 · OCR · 自定义文字操作" );

        AppUi.Section running = AppUi.section(this, "运行状态",
                "悬浮图标状态会保存，退出应用或重启后仍按当前设置恢复。" );
        overlaySwitch = AppUi.switchRow(this,
                "悬浮图标",
                "保持后台运行并响应点击、拖动和手势",
                FloatServiceState.isEnabled(this),
                (button, checked) -> onOverlayToggle(checked));
        AppUi.addRow(running.body, AppUi.switchContainer(overlaySwitch));

        serviceStatus = AppUi.caption(this, "正在读取服务状态…", 13);
        serviceStatus.setPadding(AppUi.dp(this, 12), AppUi.dp(this, 8),
                AppUi.dp(this, 12), AppUi.dp(this, 8));
        serviceStatus.setBackground(AppUi.rounded(this, AppUi.surfaceAlt(this), 14));
        AppUi.addRow(running.body, serviceStatus);
        AppUi.addSection(root, running);

        AppUi.Section permissions = AppUi.section(this, "权限",
                "只保留 FloatLens 实际需要的三项权限。" );
        overlayPermission = permissionRow(
                "悬浮窗",
                "用于在其他应用上方显示 FloatLens 图标",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
        AppUi.addRow(permissions.body, overlayPermission.view);

        accessibilityPermission = permissionRow(
                "无障碍服务",
                "用于 View 识别、系统动作和无障碍截图",
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        AppUi.addRow(permissions.body, accessibilityPermission.view);

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission = permissionRow(
                    "通知",
                    "用于前台服务常驻通知",
                    this::handleNotificationPermission);
            AppUi.addRow(permissions.body, notificationPermission.view);
        }
        AppUi.addSection(root, permissions);

        AppUi.Section settings = AppUi.section(this, "设置",
                "常用配置集中在这里，不再把调试项混在主页。" );
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "悬浮图标与手势",
                "图标外观、手势阈值、截图和 OCR",
                () -> startActivity(new Intent(this, SettingsActivity.class))));
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "文字操作菜单",
                "给选中文字添加外部应用或 Intent 操作",
                () -> startActivity(MenuPickerActivity.customIntent(this))));
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "分享菜单",
                "管理“分享”子菜单的应用和顺序",
                () -> startActivity(MenuPickerActivity.targetIntent(this, TargetMenuStore.MODE_SHARE))));
        AppUi.addRow(settings.body, AppUi.navRow(this,
                "打开 / 处理菜单",
                "管理 PROCESS_TEXT 目标和显示顺序",
                () -> startActivity(MenuPickerActivity.targetIntent(this, TargetMenuStore.MODE_PROCESS))));
        AppUi.addSection(root, settings);

        AppUi.Section advanced = AppUi.section(this, "高级",
                "排查问题时再进入，不占用日常主页空间。" );
        AppUi.addRow(advanced.body, AppUi.navRow(this,
                "诊断与调试",
                "FL 日志、Runtime Inspector、Method Probe",
                () -> startActivity(new Intent(this, DiagnosticsActivity.class))));
        AppUi.addSection(root, advanced);

        TextView footer = AppUi.caption(this,
                "FloatLens " + BuildConfig.VERSION_NAME + "  ·  本地 OCR 优先，截图失败时自动使用可用后备方案。",
                12);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(AppUi.dp(this, 4), AppUi.dp(this, 4),
                AppUi.dp(this, 4), AppUi.dp(this, 8));
        root.addView(footer);

        setContentView(AppUi.scrollPage(this, root));
    }

    @Override protected void onResume() {
        super.onResume();
        boolean enabled = FloatServiceState.isEnabled(this);
        syncOverlaySwitch(enabled);
        if (enabled && FloatService.get() == null
                && (Settings.canDrawOverlays(this) || LensAccessibilityService.ready())) {
            FloatServiceState.start(this);
        }
        refreshStatus();
    }

    private void onOverlayToggle(boolean checked) {
        if (syncingOverlaySwitch) return;
        if (checked) {
            if (!Settings.canDrawOverlays(this) && !LensAccessibilityService.ready()) {
                Toast.makeText(this,
                        "请先授予悬浮窗权限或开启 FloatLens 无障碍服务",
                        Toast.LENGTH_LONG).show();
                FloatServiceState.setEnabled(this, false);
                syncOverlaySwitch(false);
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
        boolean accessibilityGranted = LensAccessibilityService.ready();
        boolean notificationsGranted = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        updatePermission(overlayPermission, overlayGranted);
        updatePermission(accessibilityPermission, accessibilityGranted);
        if (notificationPermission != null) updatePermission(notificationPermission, notificationsGranted);

        boolean enabled = FloatServiceState.isEnabled(this);
        boolean running = FloatService.get() != null;
        if (!enabled) {
            serviceStatus.setText("已停止");
            serviceStatus.setTextColor(AppUi.textSecondary(this));
        } else if (running) {
            serviceStatus.setText("正在运行 · 悬浮图标已启用");
            serviceStatus.setTextColor(AppUi.success(this));
        } else {
            serviceStatus.setText("已开启 · 等待服务恢复");
            serviceStatus.setTextColor(AppUi.warning(this));
        }
    }

    private PermissionRow permissionRow(String title, String subtitle, Runnable action) {
        LinearLayout row = AppUi.baseRow(this);
        row.setBackground(AppUi.rounded(this, AppUi.surfaceAlt(this), 14));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = AppUi.text(this, title, 15, false);
        copy.addView(titleView);
        TextView sub = AppUi.caption(this, subtitle, 12);
        sub.setPadding(0, AppUi.dp(this, 2), AppUi.dp(this, 8), 0);
        copy.addView(sub);
        TextView status = AppUi.caption(this, "未授权", 12);
        status.setPadding(0, AppUi.dp(this, 3), 0, 0);
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
