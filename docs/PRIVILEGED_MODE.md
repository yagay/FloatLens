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

FloatLens 已建立 **受控 LSPosed Provider 配置通道**，但当前仍没有任何功能性 Hook。这个阶段只解决“App 开关怎样真实控制 system_server / SystemUI 中的模块运行态”这一基础问题。

### 推荐作用域

LSPosed API 102 推荐作用域写在 `META-INF/xposed/scope.list`：

```text
system
com.android.systemui
```

其中 `system` 对应 system_server / 系统框架，`com.android.systemui` 对应 SystemUI。`module.prop` 继续使用 `staticScope=false`，因此这两个目标只是 LSPosed 管理器里的推荐作用域，不会锁死用户作用域。

### App ↔ Hook 进程配置通道

模块 App 通过 `io.github.libxposed:service:102.0.0` 获得 `XposedService`，并把以下运行时配置写入 LSPosed Remote Preferences 组 `floatlens_runtime`：

- `schema_version`；
- `enhanced_mode`；
- `lsposed_enabled`；
- `updated_at`。

system_server / SystemUI 中的 `LsposedRuntimeProvider` 使用 libxposed API 102 的 `getRemotePreferences("floatlens_runtime")` 只读同一组配置，并注册 `OnSharedPreferenceChangeListener`。因此应用里的增强总开关和 LSPosed Provider 开关变化可以直接传递到已经加载的目标进程，不需要读取应用普通 SharedPreferences、不需要共享文件，也不需要 Root。

Provider 只有在以下两个开关同时打开时才进入 active 状态：

```text
增强模式 = ON
LSPosed Provider = ON
```

当前 active 只代表“未来 LSPosed 功能可以进入受控路径”，**不会自动安装任何 Hook**。

### Provider 可用判定

`PrivilegeManager.lsposedProviderAvailable()` 不再恒定返回 `false`，但判定仍然很严格。必须同时满足：

1. 模块 App 已连接 Xposed/LSPosed Service；
2. Remote Preferences 可读写并完成配置同步；
3. `system_server` 或 `com.android.systemui` 至少一个推荐目标已经实际加载 FloatLens 模块。

仅仅安装 LSPosed、看到推荐作用域、或者勾选作用域都不足以让 Provider 被判定可用。

### 状态页

高级权限页可以区分：

- LSPosed 服务是否连接；
- 框架名称、版本与 API；
- Remote Preferences 配置是否已同步；
- Provider 当前是开启还是关闭；
- `system` 是否在当前作用域；
- `com.android.systemui` 是否在当前作用域；
- system_server 是否实际已经加载 FloatLens；
- SystemUI 是否实际已经加载 FloatLens；
- 当前其他已加载模块进程。

状态来自 `XposedService.getScope()`、`XposedService.getRunningTargets()` 和 Remote Preferences。因此 FloatLens 明确区分：

1. 推荐列表里出现目标；
2. 用户把目标加入作用域；
3. 目标进程实际加载模块；
4. 配置通道可用；
5. Provider 开关 active；
6. 将来某个具体 Hook 功能真正启用。

### 当前 Hook 状态

`FloatLensModule` 现在会在模块加载时启动 `LsposedRuntimeProvider` 配置门，但仍然：

- 不安装 `system_server` 功能性 Hook；
- 不安装 SystemUI 功能性 Hook；
- 不安装任何第三方应用 Hook；
- 不绕过 `FLAG_SECURE`；
- 不修改 `WindowState` / `SurfaceControl`；
- 不包含针对 FV/fooView 的固定作用域或运行时抓取。

曾经加入过无条件的 system_server `FLAG_SECURE` 绕过，但该实现已经移除。原因是应用进程里的普通 SharedPreferences 开关不能可靠地控制一个已经安装在 system_server 中的全局 Hook；现在的 Remote Preferences Provider 层就是为了解决这个权限语义问题，但在验证稳定前不会把高风险功能重新接回去。

后续加入任何具体 LSPosed 能力仍必须满足：

1. `增强模式` 关闭时 Provider 不产生功能效果；
2. `启用 LSPosed Provider` 关闭时具体 Hook 不生效；
3. 框架连接 / 作用域 / 实际加载 / 配置通道 / Provider active / 具体 Hook 状态分开显示；
4. 增强失败可以按设置回退 Accessibility / OCR / 普通 Android 路径；
5. 不把设备级全局修改伪装成 FloatLens 单功能开关。

### FV 运行时抓取已移除

项目不再把 FV/fooView Runtime Inspector 当作正式功能。已经删除或不再启用：

- `com.fooview.android.fooview` 固定作用域；
- FV 方法 Hook；
- Runtime Inspector；
- Method Probe；
- 对象字段快照与 diff；
- Hook 日志广播与接收器；
- Inspector ZIP 导出；
- FV Hook 自检和控制命令。

FV 文件和历史运行抓取只作为开发时验证交互行为的参考，不成为 FloatLens 运行时依赖。

## 失败回退

“增强方法失败时回退普通方法”默认开启。建议保持开启，避免不同 ROM、Android 版本、Root 管理器或后续 Hook Provider 差异导致普通功能一起失败。

## 开发约束

Root 功能统一使用：

```java
if (PrivilegeManager.canUseRoot(context)) {
    // Root provider
} else {
    // normal provider
}
```

未来具体 LSPosed 功能统一使用：

```java
if (PrivilegeManager.canUseLsposed(context)) {
    // controlled LSPosed capability
} else {
    // Accessibility / OCR / normal provider
}
```

Hook 进程内部的具体实现还必须检查 `LsposedRuntimeProvider.isActive()`。业务代码不要直接根据“XposedService 已连接”或“目标进程已加载模块”就启用功能。
