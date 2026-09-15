# FloatLens 高级权限模式

FloatLens 的 Root / LSPosed 设计原则是：**增强能力是可选层，不是运行前提，而且应用内开关必须与真实运行行为一致。**

没有 Root、没有 LSPosed，或者用户主动关闭“增强模式”时，FloatLens 继续使用 Accessibility、Android 系统 API 和 OCR 等普通路径。

## 设置入口

进入：`FloatLens → 高级 → 高级权限`

### 启用增强模式

这是最高优先级总开关，默认关闭。

- 关闭：功能代码只选择普通 Provider。
- 开启：只允许已经真实接入、并且单独启用的增强 Provider 参与功能选择。
- 关闭总开关不会清除子开关选择。

## Root

“使用 Root 功能”是 Root Provider 的总许可。当前已接入 Root 的功能是截图。

Root 截图真正生效需要同时满足：

1. `高级权限 → 启用增强模式` 开启；
2. `高级权限 → 使用 Root 功能` 开启；
3. `截图与 OCR → Root 截图增强` 开启。

少任何一个条件，截图后端都不会调用 `su`。

高级权限页提供“检测 Root 授权”按钮。打开设置页本身不会执行 `su`；只有主动检测或真正执行 Root 功能时才调用 Root。

### 截图回退

当无障碍截图优先时：

`Accessibility → 失败 → Root`（仅 Root 条件全部满足时）

当 Root 优先时：

`Root → 成功`

如果 Root 失败并开启“增强方法失败时回退普通方法”：

`Root → 失败 → Accessibility`

## LSPosed

FloatLens 使用 libxposed API 102，并通过 `libxposed-service 102` 建立模块 App ↔ Hook 进程的受控配置通道。

### 推荐作用域

`META-INF/xposed/scope.list`：

```text
system
com.android.systemui
```

其中 `system` 对应 system_server / 系统框架，`com.android.systemui` 对应 SystemUI。`module.prop` 使用 `staticScope=false`，因此只是推荐作用域，不会锁死用户选择。

### App ↔ Hook 进程配置通道

模块 App 将运行时配置写入 LSPosed Remote Preferences 组 `floatlens_runtime`。当前主要字段包括：

- `schema_version`；
- `enhanced_mode`；
- `lsposed_enabled`；
- `secure_screenshot_enabled`；
- `secure_capture_until_ms`；
- `updated_at`。

system_server / SystemUI 中的 `LsposedRuntimeProvider` 使用 `getRemotePreferences("floatlens_runtime")` 只读并监听同一组配置。因此 App 开关可以真实控制已加载目标进程中的 Provider，不需要读取应用普通 SharedPreferences、不需要 Root 共享文件。

Provider 总门只有在以下两个开关同时开启时 active：

```text
增强模式 = ON
LSPosed Provider = ON
```

`PrivilegeManager.lsposedProviderAvailable()` 还要求框架服务已连接、Remote Preferences 可用，并且至少一个推荐目标实际加载 FloatLens 模块。

## LSPosed 安全截图增强

入口：`截图与 OCR → LSPosed 安全截图增强`

这是当前第一个具体 LSPosed 功能。它用于 FloatLens 自己发起截图时临时允许捕获 `FLAG_SECURE` / secure layer 内容，但**不会永久关闭系统安全标记**。

真正生效必须同时满足：

1. `高级权限 → 启用增强模式` 开启；
2. `高级权限 → 启用 LSPosed Provider` 开启；
3. `截图与 OCR → LSPosed 安全截图增强` 开启；
4. system_server 实际加载 FloatLens 模块；
5. 当前存在尚未过期的 FloatLens 截图请求窗口。

### 短时授权窗

每次 `ScreenshotCaptureSession` 真正准备调用截图后端前，App 会通过 Remote Preferences 写入一个默认约 **1.5 秒** 的 `secure_capture_until_ms`。

- 写入成功后留约 80 ms 给目标进程监听器同步，再执行截图；
- 截图成功或失败后主动把授权时间清零；
- 即使 App/回调异常未能及时清零，system_server 仍按时间判断，到期自动失效；
- 不存在截图请求时，已安装 Hook 直接调用原系统方法。

这使 Hook 常驻与“功能常开”分离：Hook 可以在 system_server 启动时安装，但绝大多数时间完全不改变行为。

### system_server Hook 边界

当前第一阶段只在 system_server 安装受控截图 Hook；SystemUI 仍只用于作用域/状态通道，不修改 UI 行为。

实现参考当前 LSPosed DisableFlagSecure 对新 Android 的做法，但增加 FloatLens Provider 和短时请求门控：

1. `com.android.server.wm.WindowState.isSecureLocked()`
   - 普通时间：调用原方法；
   - 短时截图窗：截图判断可返回 `false`；
   - 如果调用栈处于 `setInitialSurfaceControlProperties` / `createSurfaceLocked`，仍调用原方法，避免把窗口 Surface 本身长期改成非安全。
2. `android.window.ScreenCapture*` / `android.view.SurfaceControl` 的 `nativeCaptureDisplay` / `nativeCaptureLayers`
   - 普通时间：完全不修改参数；
   - 短时截图窗：只对本次 capture args 临时打开 secure-layer 捕获字段。

没有加入“始终返回 false”的全局 FLAG_SECURE Hook，也没有在 SystemUI 或第三方应用中永久修改安全状态。

### 当前兼容性边界

源码、单测、Lint 和 APK 编译通过只能证明 Hook 结构/API 合法，**不能证明 OnePlus 15 / OxygenOS 16 的实际 system_server 路径一定与 AOSP 完全一致**。设备验证时应检查 LSPosed 日志中：

- `Installed controlled WindowState.isSecureLocked hook`
- `Installed ... controlled ScreenCapture hooks`
- Provider 状态是否随 App 开关变化

如果安全内容仍为黑屏，需要用设备日志继续定位 OPlus 私有截图链，而不是扩大成无条件全局绕过。

## 状态页

高级权限页会分别显示：

- LSPosed 服务是否连接；
- 框架名称、版本与 API；
- Remote Preferences 是否已同步；
- Provider 当前开关状态；
- `system` / `com.android.systemui` 是否在作用域；
- system_server / SystemUI 是否实际加载模块；
- 当前已加载模块进程。

这些状态不会把“框架存在”“作用域已勾选”“模块已加载”“Provider active”“具体功能正在生效”混为一件事。

## 失败回退

“增强方法失败时回退普通方法”默认开启。LSPosed 安全截图增强只改变截图时的 secure-layer判断，截图后端仍沿用现有 Accessibility / Root 选择与回退逻辑。

## 开发约束

Root 功能统一使用：

```java
if (PrivilegeManager.canUseRoot(context)) {
    // Root provider
} else {
    // normal provider
}
```

具体 LSPosed 功能统一先通过 App 侧：

```java
if (PrivilegeManager.canUseLsposed(context)) {
    // controlled LSPosed capability
}
```

Hook 进程内部还必须检查 `LsposedRuntimeProvider` 的对应功能门。业务代码不要仅根据“XposedService 已连接”或“目标进程已加载模块”就启用功能，也不要重新加入无法被 App 开关真实控制的全局 Hook。
