# FloatLens 高级权限模式

FloatLens 的 Root / LSPosed 设计原则是：**增强能力是可选层，不是运行前提**。

没有 Root、没有 LSPosed，或者用户主动关闭“增强模式”时，FloatLens 继续使用 Accessibility、Android 系统 API 和 OCR 等普通路径。

## 设置入口

进入：`FloatLens → 高级 → 高级权限`

### 启用增强模式

这是最高优先级总开关，默认关闭。

- 关闭：功能代码只选择普通 Provider。
- 开启：允许单独启用的 Root / LSPosed Provider 参与功能选择。
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

“使用 LSPosed 功能”控制 FloatLens 是否允许 LSPosed 增强 Provider。作用域、模块启用状态仍由 LSPosed 管理器负责。

### FV 运行时抓取已移除

项目不再包含任何专门针对 FV/fooView 的运行时抓取功能。已经删除：

- `com.fooview.android.fooview` 固定作用域；
- FV 方法 Hook；
- Runtime Inspector；
- Method Probe；
- FV 手势/服务调用链抓取；
- 对象字段快照与 diff；
- Hook 日志广播与接收器；
- Inspector ZIP 导出；
- FV Hook 自检和控制命令。

保留的 `FloatLensModule` 只是干净的 libxposed API 102 模块入口，目前不会对任何第三方应用安装目标特定 Hook。后续如果加入通用 View / WebView / Compose Provider，应通过 `PrivilegeManager.canUseLsposed(...)` 统一门控，并继续保持与具体第三方应用解耦。

## 失败回退

“增强方法失败时回退普通方法”默认开启。建议保持开启，避免不同 ROM、Android 版本、Root 管理器或 Hook 环境差异导致普通功能一起失败。

## 开发约束

Root 功能统一使用：

```java
if (PrivilegeManager.canUseRoot(context)) {
    // Root provider
} else {
    // normal provider
}
```

LSPosed 功能统一使用：

```java
if (PrivilegeManager.canUseLsposed(context)) {
    // generic LSPosed provider
} else {
    // Accessibility / OCR / normal provider
}
```

业务代码不要直接根据设备是否安装 Root/LSPosed 决定行为，也不要重新加入针对单一第三方应用的运行时抓取逻辑。
