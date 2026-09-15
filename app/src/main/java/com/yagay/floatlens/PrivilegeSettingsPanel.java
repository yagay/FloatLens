package com.yagay.floatlens;

import android.content.Intent;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Builds the optional Root / LSPosed settings page. */
public final class PrivilegeSettingsPanel {
    private PrivilegeSettingsPanel() {}

    public static LinearLayout build(AppCompatActivity activity, FloatSettings fs) {
        LinearLayout root = AppUi.pageRoot(activity, "高级权限",
                "Root 与 LSPosed 都是可选增强层；关闭增强模式后普通 Android / 无障碍路径保持可用。" );

        TextView modeStatus = AppUi.caption(activity, "", 13);
        TextView rootStatus = AppUi.caption(activity, "", 13);

        AppUi.Section master = AppUi.section(activity, "增强模式总开关",
                "关闭后保留子开关选择，运行时只使用普通 Android / 无障碍实现。" );
        SwitchMaterial enhanced = preferenceSwitch(activity, fs,
                "启用增强模式",
                "开启后才允许已经接入且单独启用的增强 Provider 参与后端选择。",
                FloatSettings.K_ENHANCED_MODE, fs.enhancedMode(),
                () -> refresh(activity, fs, modeStatus, rootStatus));
        AppUi.addRow(master.body, AppUi.switchContainer(enhanced));
        AppUi.addRow(master.body, statusBlock(activity, "当前生效模式", modeStatus));
        AppUi.addSection(root, master);

        AppUi.Section rootSection = AppUi.section(activity, "Root",
                "进入设置页不会自动申请 Root；只有真正执行 Root 功能或主动检测授权时才调用 su。" );
        SwitchMaterial rootSwitch = preferenceSwitch(activity, fs,
                "使用 Root 功能",
                "允许已接入的 Root 增强功能参与后端选择。",
                FloatSettings.K_ROOT_ENABLED, fs.rootEnabled(),
                () -> refresh(activity, fs, modeStatus, rootStatus));
        AppUi.addRow(rootSection.body, AppUi.switchContainer(rootSwitch));

        SwitchMaterial rootScreenshotSwitch = preferenceSwitch(activity, fs,
                "Root 截图增强",
                "仅在“增强模式”和“使用 Root 功能”同时开启时生效；关闭后截图继续使用普通后端。",
                FloatSettings.K_ROOT_SCREENSHOT, fs.rootScreenshot(),
                () -> refresh(activity, fs, modeStatus, rootStatus));
        AppUi.addRow(rootSection.body, AppUi.switchContainer(rootScreenshotSwitch));
        AppUi.addRow(rootSection.body, statusBlock(activity, "Root 状态", rootStatus));

        LinearLayout rootButtons = AppUi.buttonRow(activity);
        MaterialButton checkRoot = AppUi.secondaryButton(activity, "检测 Root 授权");
        checkRoot.setOnClickListener(v -> {
            checkRoot.setEnabled(false);
            rootStatus.setText("正在检测…可能会弹出 Root 管理器授权窗口");
            PrivilegeManager.checkRootAsync(activity, result -> {
                checkRoot.setEnabled(true);
                refresh(activity, fs, modeStatus, rootStatus);
                Toast.makeText(activity,
                        result.granted ? "Root 已授权" : "Root 不可用或未授权",
                        Toast.LENGTH_SHORT).show();
            });
        });
        rootButtons.addView(checkRoot, new LinearLayout.LayoutParams(0, -2, 1f));
        AppUi.addRow(rootSection.body, rootButtons);

        TextView rootNote = AppUi.caption(activity,
                "Root 截图需要同时开启：增强模式、使用 Root 功能、Root 截图增强。缺少任意一项都不会调用 Root 截图。",
                12);
        AppUi.addRow(rootSection.body, simpleBlock(activity, rootNote));
        AppUi.addSection(root, rootSection);

        TextView lsposedStatus = AppUi.caption(activity, "", 13);
        AppUi.Section lsposedSection = AppUi.section(activity, "LSPosed",
                "API 102 Remote Preferences 把应用开关同步到 system_server / SystemUI。安全窗口截图只在 FloatLens 截图的短时 lease 内改变系统捕获行为。" );
        SwitchMaterial lsposedSwitch = preferenceSwitch(activity, fs,
                "启用 LSPosed Provider",
                "允许已经接入的受控 LSPosed 功能使用跨进程配置；具体功能仍需各自开关。",
                FloatSettings.K_LSPOSED_ENABLED, fs.lsposedEnabled(),
                () -> {
                    LsposedStatusManager.syncRuntimeConfigAsync();
                    refresh(activity, fs, modeStatus, rootStatus);
                });
        lsposedSwitch.setEnabled(PrivilegeManager.lsposedProviderAvailable());
        AppUi.addRow(lsposedSection.body, AppUi.switchContainer(lsposedSwitch));

        SwitchMaterial secureScreenshotSwitch = preferenceSwitch(activity, fs,
                "LSPosed 安全窗口截图增强",
                "仅 FloatLens 截图时建立短时授权；需要增强模式、LSPosed Provider 和 system_server 已实际加载模块。不会永久移除 FLAG_SECURE。",
                FloatSettings.K_LSPOSED_SECURE_SCREENSHOT, fs.lsposedSecureScreenshot(),
                () -> {
                    LsposedStatusManager.syncRuntimeConfigAsync();
                    refresh(activity, fs, modeStatus, rootStatus);
                });
        AppUi.addRow(lsposedSection.body, AppUi.switchContainer(secureScreenshotSwitch));
        AppUi.addRow(lsposedSection.body, statusBlock(activity, "LSPosed 实际状态", lsposedStatus));

        LinearLayout lsposedButtons = AppUi.buttonRow(activity);
        MaterialButton refreshLsposed = AppUi.secondaryButton(activity, "刷新状态");
        refreshLsposed.setOnClickListener(v -> {
            lsposedStatus.setText("正在读取 LSPosed 框架与 Provider 状态…");
            LsposedStatusManager.syncRuntimeConfigAsync();
        });
        lsposedButtons.addView(refreshLsposed, new LinearLayout.LayoutParams(0, -2, 1f));

        MaterialButton probeSecure = AppUi.secondaryButton(activity, "测试安全截图");
        probeSecure.setOnClickListener(v -> activity.startActivity(
                new Intent(activity, SecureCaptureProbeActivity.class)));
        lsposedButtons.addView(probeSecure, new LinearLayout.LayoutParams(0, -2, 1f));
        AppUi.addRow(lsposedSection.body, lsposedButtons);

        TextView lsposedNote = AppUi.caption(activity,
                "推荐作用域是 system + com.android.systemui。安全窗口截图 Hook 只安装在 system_server，并且每次调用都实时检查短时授权；不会永久取消窗口的 FLAG_SECURE / secure Surface 标记，也不会开启 DRM protected content 捕获。‘测试安全截图’会打开 FloatLens 自己的 FLAG_SECURE 页面并实际验证返回 Bitmap。",
                12);
        AppUi.addRow(lsposedSection.body, simpleBlock(activity, lsposedNote));

        LsposedStatusManager.Listener lsposedListener = snapshot -> {
            lsposedSwitch.setEnabled(PrivilegeManager.lsposedProviderAvailable());
            refresh(activity, fs, modeStatus, rootStatus);
            refreshLsposed(activity, lsposedStatus);
        };
        lsposedSection.body.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                LsposedStatusManager.addListener(lsposedListener, true);
                LsposedStatusManager.syncRuntimeConfigAsync();
            }

            @Override public void onViewDetachedFromWindow(View v) {
                LsposedStatusManager.removeListener(lsposedListener);
            }
        });
        AppUi.addSection(root, lsposedSection);

        AppUi.Section fallback = AppUi.section(activity, "失败回退",
                "增强后端失败时回到普通方法，避免 Root / Hook 失败影响基础功能。" );
        SwitchMaterial fallbackSwitch = preferenceSwitch(activity, fs,
                "增强方法失败时回退普通方法",
                "例如 Root 截图失败后重新尝试无障碍截图，或 LSPosed lease 建立失败时走普通截图。",
                FloatSettings.K_PRIVILEGE_FALLBACK, fs.privilegeFallback(),
                () -> refresh(activity, fs, modeStatus, rootStatus));
        AppUi.addRow(fallback.body, AppUi.switchContainer(fallbackSwitch));
        AppUi.addSection(root, fallback);

        refresh(activity, fs, modeStatus, rootStatus);
        refreshLsposed(activity, lsposedStatus);
        return root;
    }

    private static SwitchMaterial preferenceSwitch(AppCompatActivity activity, FloatSettings fs,
                                                    String title, String subtitle,
                                                    String key, boolean current, Runnable changed) {
        return AppUi.switchRow(activity, title, subtitle, current, (button, checked) -> {
            fs.prefs().edit().putBoolean(key, checked).apply();
            DiagnosticLog.i(activity, "PRIVILEGE", "setting " + key + "=" + checked);
            if (changed != null) changed.run();
        });
    }

    private static LinearLayout statusBlock(AppCompatActivity activity, String title, TextView status) {
        LinearLayout block = AppUi.settingBlock(activity);
        block.addView(AppUi.text(activity, title, 14, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = AppUi.dp(activity, 4);
        block.addView(status, lp);
        return block;
    }

    private static LinearLayout simpleBlock(AppCompatActivity activity, TextView text) {
        LinearLayout block = AppUi.settingBlock(activity);
        block.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return block;
    }

    private static void refresh(AppCompatActivity activity, FloatSettings fs,
                                TextView modeStatus, TextView rootStatus) {
        String providerNote = !PrivilegeManager.lsposedProviderAvailable() && fs.lsposedEnabled()
                ? " · LSPosed 已选择但 Provider 未就绪" : "";
        modeStatus.setText(PrivilegeManager.modeLabel(fs)
                + (fs.enhancedMode() ? " · 增强总开关已开启" : " · 增强总开关已关闭")
                + providerNote);
        modeStatus.setTextColor(fs.enhancedMode() ? AppUi.success(activity) : AppUi.textPrimary(activity));

        long rootAt = fs.rootLastCheckMs();
        if (rootAt <= 0L) {
            rootStatus.setText("尚未检测。检测只在点击按钮后执行，不会自动调用 su。\n"
                    + "当前 Provider：" + (fs.canUseRoot() ? "允许使用 Root" : "不允许使用 Root"));
        } else {
            String when = DateFormat.format("yyyy-MM-dd HH:mm", rootAt).toString();
            rootStatus.setText((fs.rootLastGranted() ? "已授权" : "未授权 / 不可用")
                    + " · 上次检测 " + when
                    + "\n当前 Provider：" + (fs.canUseRoot() ? "允许使用 Root" : "不允许使用 Root"));
        }
        rootStatus.setTextColor(fs.rootLastGranted() ? AppUi.success(activity) : AppUi.textPrimary(activity));
    }

    private static void refreshLsposed(AppCompatActivity activity, TextView status) {
        LsposedStatusManager.Snapshot s = LsposedStatusManager.snapshot();
        if (!s.serviceConnected) {
            status.setText("框架服务：未连接\n"
                    + "配置通道：不可用\n"
                    + "系统框架：未知 · SystemUI：未知\n"
                    + (s.detail.isBlank() ? "如果刚启用模块，请重启目标进程或设备后再刷新。" : s.detail));
            status.setTextColor(AppUi.textPrimary(activity));
            return;
        }

        String framework = s.frameworkName.isBlank() ? "Xposed" : s.frameworkName;
        String version = s.frameworkVersion.isBlank() ? "" : " " + s.frameworkVersion;
        String scopeLine = "作用域：system " + yesNo(s.systemScopeEnabled)
                + " · SystemUI " + yesNo(s.systemUiScopeEnabled);
        String loadedLine = "实际加载：系统框架 " + loaded(s.systemLoaded)
                + " · SystemUI " + loaded(s.systemUiLoaded);
        String remoteLine = "配置通道：" + (s.remoteConfigReady ? "已同步" : "不可用")
                + " · Provider " + (s.remoteProviderEnabled() ? "已开启" : "已关闭");
        String secureLine = "安全窗口截图：" + (s.remoteSecureScreenshotEnabled ? "已启用" : "未启用")
                + " · 短时授权 " + (s.remoteSecureCaptureArmed() ? "进行中" : "空闲");
        String updatedLine = s.remoteUpdatedAt <= 0L ? ""
                : " · " + DateFormat.format("HH:mm:ss", s.remoteUpdatedAt);
        String processLine = s.runningProcesses.isEmpty()
                ? "已加载进程：无"
                : "已加载进程：" + String.join(", ", s.runningProcesses);
        String detailLine = s.detail.isBlank() ? "" : "\n" + s.detail;
        status.setText("框架服务：已连接 " + framework + version + " · API " + s.apiVersion
                + "\n" + remoteLine + updatedLine
                + "\n" + secureLine
                + "\n" + scopeLine
                + "\n" + loadedLine
                + "\n" + processLine
                + detailLine);
        status.setTextColor(PrivilegeManager.lsposedProviderAvailable()
                ? AppUi.success(activity) : AppUi.textPrimary(activity));
    }

    private static String yesNo(boolean value) {
        return value ? "已启用" : "未启用";
    }

    private static String loaded(boolean value) {
        return value ? "已加载" : "未加载";
    }
}
