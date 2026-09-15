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

设置中保留“使用 LSPosed 功能”作为后续 Provider 的用户选择，但 **当前构建没有活动的 LSPosed Provider**。`PrivilegeManager.lsposedProviderAvailable()` 当前仍返回 `false`，因此 LSPosed 不会进入生效模式。

### 推荐作用域

LSPosed API 102 推荐作用域写在 `META-INF/xposed/scope.list`：

```text
system
com.android.systemui
```

其中 `system` 对应 system_server / 系统框架，`com.android.systemui` 对应 SystemUI。`module.prop` 继续使用 `staticScope=false`，因此这两个目标只是 LSPosed 管理器里的推荐作用域，不会锁死用户作用域。

### 框架状态通道

FloatLens 已通过 `io.github.libxposed:service:102.0.0` 接入模块 App ↔ LSPosed 框架的状态通道。`LsposedStatusManager` 只读取框架状态，不安装 Hook。

高级权限页现在可以显示：

- LSPosed 服务是否连接；
- 框架名称、版本与 API；
- `system` 是否在当前作用域；
- `com.android.systemui` 是否在当前作用域；
- system_server 是否实际已经加载 FloatLens 模块；
- SystemUI 是否实际已经加载 FloatLens 模块；
- 当前其他已加载模块进程。

状态来自 `XposedService.getScope()` 和 `XposedService.getRunningTargets()`。因此 FloatLens 可以明确区分：

1. 推荐列表里出现目标；
2. 用户把目标加入作用域；
3. 目标进程实际已经加载模块；
4. 具体 LSPosed 功能 Provider 已经实现并启用。

这四件事不再被混为同一个“LSPosed 已启用”状态。

保留的 `FloatLensModule` 是 libxposed API 102 模块入口，但当前：

- 不安装 `system_server` Hook；
- 不安装 SystemUI Hook；
- 不安装任何第三方应用 Hook；
- 不绕过 `FLAG_SECURE`；
- 不修改 `WindowState` / `SurfaceControl`；
- 不包含针对 FV/fooView 的固定作用域或运行时抓取。

曾经加入过无条件的 system_server `FLAG_SECURE` 绕过，但该实现已经移除。原因是应用进程里的普通 SharedPreferences 开关不能可靠地控制一个已经安装在 system_server 中的全局 Hook；这会造成“UI 显示关闭、系统 Hook 实际仍生效”的错误权限语义。

后续如果重新加入 LSPosed 能力，仍必须满足：

1. `增强模式` 关闭时 LSPosed Provider 不产生功能效果；
2. `使用 LSPosed 功能` 关闭时具体 Hook/Provider 不生效；
3. 框架连接 / 作用域 / 实际加载 / Provider 可用状态分开显示；
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

未来 LSPosed 功能统一使用：

```java
if (PrivilegeManager.canUseLsposed(context)) {
    // controlled LSPosed provider
} else {
    // Accessibility / OCR / normal provider
}
```

业务代码不要直接根据“XposedService 已连接”或“目标进程已加载模块”就启用功能。只有 `PrivilegeManager.lsposedProviderAvailable()` 对应的具体 Provider 真正存在后，才允许业务代码进入 LSPosed 功能路径。
