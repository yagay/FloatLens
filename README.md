# FloatLens

FloatLens 是一个 Android 悬浮取词、View 选择、截图和 OCR 工具。

项目以普通 Android / Accessibility 能力作为基础路径，并保留可选增强层。没有 Root、没有 LSPosed，或者用户关闭增强模式时，FloatLens 的普通悬浮、View 选择、截图与 OCR 路径仍可工作。

## FV 对齐原则

FloatLens 的悬浮图标拖选行为以已经验证的 fooView/FV 运行时行为为参考，而不是凭外观猜测：

- 拖动后悬浮图标立即临时跟手，普通松手恢复原位置；
- 移动探针处于红色 TRACKING；
- 指针在约 ±3 dp 范围内稳定约 400 ms 后进入 Direct，探针变为黄色 READY；
- Direct 之后在缓存的 TEXT / IMAGE / VIEW 候选之间移动不会再为每个候选重复等待 400 ms；
- Direct 松手后保留约 5 ms 的执行延迟；
- 辅助高亮/冻结/轨迹窗口在无障碍宿主可用时优先使用 Accessibility Overlay，同时保持悬浮图标拥有原 MotionEvent 流。

FV 只能决定已经实测确认的交互、时序和窗口行为。截图后端、OCR、结果生命周期、Root / LSPosed 权限边界等 FloatLens 自身架构按项目的单一 owner 规则实现。

## Root / LSPosed 增强模式

入口：`FloatLens → 高级 → 高级权限`

### Root

Root 截图需要同时打开：

1. `高级权限 → 启用增强模式`
2. `高级权限 → 使用 Root 功能`
3. `截图与 OCR → Root 截图增强`

任意一个关闭，截图代码都不会进入 Root 截图路径。设置页提供独立的 Root 授权检测按钮；仅打开设置页不会主动执行 `su`。Root 截图通过 `screencap -p` 标准输出直接解码，不依赖共享临时 PNG 文件。

### LSPosed

当前构建使用 libxposed API 102，并推荐作用域：

```text
system
com.android.systemui
```

`staticScope=false`，所以它们只是推荐项，不会锁死用户作用域。

FloatLens 已通过 `libxposed-service 102` 接入框架状态检测。高级权限页可以区分：LSPosed 服务是否连接、两个推荐作用域是否已经启用，以及 system_server / SystemUI 是否真的已经加载 FloatLens 模块。

**当前仍没有活动的 LSPosed 功能 Provider，也不会安装 system_server、SystemUI 或第三方应用的功能性 Hook。** 框架已连接或模块已加载不等于某个增强功能已经启用；只有未来具体 Provider 真正接入后，`PrivilegeManager` 才会允许业务代码进入 LSPosed 路径。

之前加入的全局 `FLAG_SECURE` system_server Hook 已移除，因为应用自己的 SharedPreferences 开关不能可靠控制一个已经安装在 system_server 中的全局 Hook。原先用于抓取 FV/fooView 运行时行为的固定 FV 作用域、Method Probe、对象快照、Hook 日志回传和 Runtime Inspector ZIP 也不属于正式功能。

完整权限与回退规则见 [`docs/PRIVILEGED_MODE.md`](docs/PRIVILEGED_MODE.md)。

## OCR 模型

PP-OCRv6 Small / Medium 模型与 APK 分离。成功下载后 FloatLens 会生成本地 SHA-256 完整性清单，并在冷加载模型前校验；ML Kit 多语言流程采用保守的 completed-tier early-stop，只有当前图像层级的已启用语言全部完成且质量足够时才提前结束。

## Build / CI

当前 Android 配置：

- compileSdk 37
- minSdk 31
- targetSdk 37
- Java 17
- libxposed API 102（compileOnly）
- libxposed service 102（implementation，用于模块 App ↔ 框架状态通信）

GitHub Actions 的 Debug Build 使用 Gradle 9.4.1 + JDK 17，依次执行：

1. `testDebugUnitTest`
2. `lintDebug`
3. `assembleDebug`
4. 上传 Debug APK artifact

结构性修改只有在最终 HEAD 的这套流程全部通过后才视为源码侧完成。