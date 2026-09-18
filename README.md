# FloatLens

FloatLens 是一个 Android 悬浮取词、View 选择、截图和 OCR 工具。

项目以普通 Android / Accessibility 能力作为基础路径，并保留可选增强层。没有 Root、没有 LSPosed，或者用户关闭增强模式时，FloatLens 的普通悬浮、Direct View 选择、截图与 OCR 路径仍可工作。

## FV 对齐原则

FloatLens 的悬浮图标拖选行为以已经验证的 fooView/FV 运行时行为为参考，而不是凭外观猜测：

- 拖动后悬浮图标立即临时跟手，普通松手恢复原位置；
- 移动探针处于红色 TRACKING；
- 指针在约 ±3 dp 范围内稳定约 400 ms 后进入 Direct，探针变为黄色 READY；
- Direct 之后在缓存的 TEXT / IMAGE / VIEW 候选之间移动不会再为每个候选重复等待 400 ms；
- Direct 松手后保留约 5 ms 的执行延迟；
- 辅助高亮/冻结/轨迹窗口在无障碍宿主可用时优先使用 Accessibility Overlay，同时保持悬浮图标拥有原 MotionEvent 流。

FV 只决定已经实测确认的交互、时序和窗口行为。截图后端、OCR、结果生命周期、Root / LSPosed 权限边界等 FloatLens 自身架构按项目的单一 owner 规则实现。

## 圈画识别：单一 OCR-only 主链

当前圈画是 **FloatLens 自研圈画**，内部统一使用 `FLCircle*` 命名；它与 Google Circle to Search 无关。以后如果加入 Google 圈画接管，会使用独立的 Google CTS 命名与调用链，不复用当前 `FLCircle*` 名称。

圈画识别只有一条正式主链：

```text
FLCircleController
  → FLCircleCapture
  → FLCircleInlineOverlay
  → FLCircleTextResolver
  → CircleStableOcr
  → CircleSelectionPlanner
  → CircleGestureTextSelector
  → CircleTextSelectionModel
```

进入圈画后先冻结当前截图。第一次文字手势触发所选“整屏识别引擎”建立完整 OCR 索引，并缓存到当前冻结截图会话；后续点击、划线和涂抹直接复用同一份索引。可选的 PP-OCRv6 Tiny / Small / Medium 局部校正只重新识别 `CircleSelectionPlanner` 计算出的手势附近 ROI，不能自行改变用户选择范围。

`CircleSelectionPlanner` 是初始文字选择和校正 ROI 的唯一 owner。`FLCircleInlineOverlay` 只把 planner 返回的 `initialSelectionDocument` 映射进完整 OCR 文档，不再另外执行 tap hit、附近 snap 或 gesture-bounds intersect fallback；手柄拖动只负责用户之后的选区编辑。

正式圈画链**不读取 Accessibility / View 文字作为内容来源**，不合并 View text，不遮罩 View 区域，也不维护第二套 detector-only TextMap。已经删除的旧实验路径包括 `CircleViewTextSnapshot`、`ViewTextOcrMask`、`ViewTextGeometryRefiner`、`CircleTextMap` 和 `PaddleTextDetectorBridge`。

Direct 仍然可以使用 Accessibility 的真实 `node.getText()`；这是 Direct View 提取功能，与普通 OCR / Circle OCR 路径分开。普通“OCR/提取文字”动作统一进入截图 OCR 区域选择器，不再维护第二套全屏 View picker。显式“区域 View 文字”只存在于区域编辑器，并由 `RegionContentResolver` 负责。

## Google Circle to Search 接管（LSPosed，可选）

Android 15/16 上，FloatLens 的 `GoogleCts*` 链路 Hook Android `system_server` 的 Contextual Search 启动边界，而不是依赖 Google App 内部混淆类。系统生成冻结截图后，Hook 只在目标确认为 `com.google.android.googlequicksearchbox` 且“仅抓屏，不搜索”开关开启时接管：把系统截图交给 `GoogleCtsBridgeActivity`，再直接复用 `FLCircle*` 工作区。只有桥接 Activity 成功启动后才阻断原 Google 搜索；截图缺失、OEM 方法不兼容或桥接失败时全部 fail-open，继续原始 Google 圈画。

这条链路与 FloatLens 自研圈画保持隔离：`GoogleCts*` 只负责 Google/Android CTS 的入口接管和系统截图转交，文字 OCR、圈选、截图裁剪和结果 UI 仍由现有 `FLCircle*` / `Circle*` owner 负责。主路径只需要 LSPosed 的 `system` 作用域，不需要把 Google App 加入作用域。

## 统一架构原则

同类底层功能只保留一个 owner：

- 屏幕范围 / density / dp：`ScreenGeometry`
- 通用矩阵坐标转换：`CoordinateMapper`
- 冻结截图 ↔ SCREEN ↔ Overlay：`ScreenBitmapTransform`
- 截图裁剪：`ScreenshotGeometry`
- 截图生命周期：`ScreenshotCaptureSession`
- 截图后端：`ScreenCaptureBackend`
- OCR：`OcrEngine`
- PP OCR 适配：`PaddleOcrBridge → OcrCanonicalGeometry`
- 圈画初始选择 / correction ROI：`CircleSelectionPlanner`
- 动作目录 / 执行：`ActionRegistry / ActionExecutor`
- 识别状态：`RecognitionWorkflowState`
- 结果：`ResultSession → ResultController → UnifiedResultDialogFragment / UnifiedResultPanel`
- 浮动菜单视觉：`FloatingMenuUi`
- 浮动菜单定位：`FloatingMenuPositioner`
- 设置读写：`FloatSettings`

完整规则见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

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

FloatLens 使用 `libxposed-service 102` + Remote Preferences 建立受控 Provider：应用侧写入增强模式、LSPosed Provider 和功能开关，system_server / SystemUI 中的模块运行时只读并监听同一配置。`PrivilegeManager.lsposedProviderAvailable()` 只有在框架服务、Remote Preferences 与实际加载目标都就绪时才返回可用。

第一个功能性 LSPosed Provider 是 **安全窗口截图增强**。它只作用于 FloatLens 自己发起的截图流程：截图前写入一个约 3 秒的短时 lease，system_server 截图 Hook 每次执行都实时验证该 lease；截图完成后立即 disarm，异常情况下 lease 也会自动过期。Hook 只调整系统截图捕获参数，并在截图策略检查阶段短时放行，不永久移除窗口的 `FLAG_SECURE` / secure Surface 标记，也不启用 DRM protected-content 捕获。

开启条件：`增强模式 + LSPosed Provider + 截图与 OCR → LSPosed 安全窗口截图增强`，并要求 system_server 已实际加载 FloatLens 模块。该功能优先走 Accessibility 截图路径；关闭任意门控后立即恢复普通行为。

之前曾加入的无条件全局 `FLAG_SECURE` / `SurfaceControl.Builder.setSecure(false)` 实现已经删除，不会恢复。原先用于抓取 FV/fooView 运行时行为的固定 FV 作用域、Method Probe、对象快照、Hook 日志回传和 Runtime Inspector ZIP 也不属于正式功能。

完整权限与回退规则见 [`docs/PRIVILEGED_MODE.md`](docs/PRIVILEGED_MODE.md)。

## OCR 模型

PP-OCRv6 Tiny / Small / Medium 模型与 APK 分离。成功下载后 FloatLens 会生成本地 SHA-256 完整性清单，并在冷加载模型前校验。

普通 OCR 与 Circle 共用统一 `OcrDocument` 数据契约：

- ML Kit 输出由 `MlKitTextCore` 转换；
- PP-OCR 输出由 `PaddleOcrBridge` 转换，并立即通过 `OcrCanonicalGeometry` 规范化；
- OCR 引擎设置只通过 `FloatSettings` 读取；
- 不再存在单独的 PP detector-only、enhanced/mono preprocessing 或第二套 quality-policy 应用链；
- Circle 可独立选择整屏识别引擎和局部校正引擎。

## Build / CI

当前 Android 配置：

- compileSdk 37
- minSdk 31
- targetSdk 37
- Java 17
- libxposed API 102（compileOnly）
- libxposed service 102（implementation，用于模块 App ↔ 框架状态与 Remote Preferences 通信）

GitHub Actions 的 Debug Build 使用 Gradle 9.4.1 + JDK 17，依次执行：

1. `testDebugUnitTest`
2. `lintDebug`
3. `assembleDebug`
4. 上传 Debug APK artifact

结构性修改只有在最终 HEAD 的这套流程全部通过后才视为源码侧完成。
