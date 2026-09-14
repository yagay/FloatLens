# FloatLens

FloatLens 是一个 Android 悬浮取词、View 选择、截图和 OCR 工具。

项目同时支持普通模式和可选的 Root / LSPosed 增强模式。增强权限不是运行前提：没有 Root、没有 LSPosed，或者用户关闭增强模式时，FloatLens 继续使用 Accessibility、Android 系统 API 和 OCR 等普通路径。

## Root / LSPosed 增强模式

入口：`FloatLens → 设置 → 高级权限`

主要开关：

- **启用增强模式**：最高优先级总开关，默认关闭。
- **使用 Root 功能**：允许 Root Provider；只有总开关开启时生效。
- **使用 LSPosed 功能**：允许 LSPosed Provider；只有总开关开启时生效。
- **增强方法失败时回退普通方法**：默认开启，增强后端失败后尽量继续使用普通实现。

### Root 截图

Root 截图需要同时打开：

1. `高级权限 → 启用增强模式`
2. `高级权限 → 使用 Root 功能`
3. `截图与 OCR → Root 截图增强`

任意一个关闭，截图代码都不会进入 Root 截图路径。

设置页提供独立的 Root 授权检测按钮；仅打开设置页不会主动执行 `su`。

### LSPosed

`使用 LSPosed 功能` 控制的是 FloatLens 是否允许功能层选择 LSPosed 增强 Provider。它不会代替 LSPosed 管理器管理模块启用状态或作用域。

设置页还会记录最近一次现有 Hook 通信，用于判断 LSPosed 注入链路是否曾经正常工作。当前仓库中的 LSPosed 模块主要用于 FooView 运行时分析；后续通用 View / WebView / Compose Provider 应继续通过统一的 `PrivilegeManager` 门控。

完整行为、回退规则、开发约束和状态说明见 [`docs/PRIVILEGED_MODE.md`](docs/PRIVILEGED_MODE.md)。

## Build

当前 Android 配置：

- compileSdk 37
- minSdk 31
- targetSdk 37
- Java 17
- libxposed API 102（compileOnly）

GitHub Actions 的 Debug Build 使用 Gradle 9.4.1 + JDK 17 执行 `assembleDebug`。
