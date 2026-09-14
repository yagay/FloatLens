# FloatLens

FloatLens 是一个 Android 悬浮取词、View 选择、截图和 OCR 工具。

项目同时支持普通模式和可选的 Root / LSPosed 增强模式。增强权限不是运行前提：没有 Root、没有 LSPosed，或者用户关闭增强模式时，FloatLens 继续使用 Accessibility、Android 系统 API 和 OCR 等普通路径。

## Root / LSPosed 增强模式

入口：`FloatLens → 高级 → 高级权限`

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

`使用 LSPosed 功能` 只控制 FloatLens 是否允许 LSPosed 增强 Provider。模块启用状态和作用域仍由 LSPosed 管理器负责。

原先用于抓取 FV/fooView 运行时行为的代码已经完全移除，包括固定 FV 作用域、方法 Hook、Method Probe、对象快照、Hook 日志回传和 Runtime Inspector ZIP。现在保留的是一个干净的 LSPosed 模块入口，不会对 FV 安装任何 Hook。

完整权限与回退规则见 [`docs/PRIVILEGED_MODE.md`](docs/PRIVILEGED_MODE.md)。

## Build

当前 Android 配置：

- compileSdk 37
- minSdk 31
- targetSdk 37
- Java 17
- libxposed API 102（compileOnly）

GitHub Actions 的 Debug Build 使用 Gradle 9.4.1 + JDK 17 执行 `assembleDebug`。
