# FloatLens 高级权限模式

FloatLens 的 Root / LSPosed 设计原则是：**增强能力是可选层，不是运行前提**。

普通设备、没有 Root 的设备、没有 LSPosed 的设备，或者用户主动关闭“增强模式”的设备，都继续使用原有 Android / Accessibility / OCR 路径。

## 1. 设置入口

进入：

`FloatLens → 设置 → 高级权限`

页面包含四组核心设置：

### 启用增强模式

这是最高优先级的总开关，默认关闭。

- 关闭：FloatLens 功能代码只选择普通 Provider。
- 开启：允许下面单独打开的 Root / LSPosed Provider 参与功能选择。
- 关闭总开关不会清除 Root / LSPosed 子开关的选择，下次重新开启后可以恢复之前的配置。

这意味着可以长期安装带 Root / LSPosed 支持的同一个 APK，而不需要为了普通模式另外安装一个版本。

## 2. Root 功能

### 使用 Root 功能

这个开关只是 Root Provider 的总许可。实际某个功能是否使用 Root，还需要该功能自己的开关也开启。

当前已经接入 Root Provider 的功能是 **截图**。

Root 截图真正生效需要同时满足：

1. `设置 → 高级权限 → 启用增强模式`：开启；
2. `设置 → 高级权限 → 使用 Root 功能`：开启；
3. `设置 → 截图与 OCR → Root 截图增强`：开启。

少任何一个条件，截图后端都不会调用 `su`。

### Root 授权检测

高级权限页面提供“检测 Root 授权”按钮。

进入设置页面本身不会执行 `su`，因此不会因为只是看设置就弹出 KernelSU / Magisk 授权提示。只有：

- 用户主动点击“检测 Root 授权”；或者
- 某个已经满足全部条件的 Root 功能真正被执行；

才会调用 Root。

检测使用 `su -c id`，确认返回结果包含 `uid=0`。检测结果只作为状态显示；功能执行时仍以当次 Root 调用是否成功为准。

## 3. 截图后端选择规则

### 普通模式

当增强模式关闭、Root 功能关闭、或者 Root 截图增强关闭时：

`Accessibility screenshot → 返回结果`

不会进入 `RootCapture`。

### 无障碍优先 + Root 后备

当以下设置同时开启：

- 优先无障碍截图；
- 增强模式；
- 使用 Root 功能；
- Root 截图增强；

路由为：

`Accessibility → 失败 → Root screencap`

### Root 优先

如果关闭“优先无障碍截图”，同时 Root 的三个启用条件都满足：

`Root screencap → 成功`

如果 Root 失败，并且“增强方法失败时回退普通方法”开启：

`Root screencap → 失败 → Accessibility`

如果关闭失败回退，则 Root 失败会直接返回错误。

## 4. LSPosed 功能

### 使用 LSPosed 功能

这个开关控制 **FloatLens 是否允许功能层选择 LSPosed 增强 Provider**。

只有同时满足：

- 启用增强模式；
- 使用 LSPosed 功能；

`PrivilegeManager.canUseLsposed(...)` 才返回 true。

功能代码以后接入 View、WebView、Compose 或其他 Hook Provider 时，都必须通过这个统一入口判断，不能直接因为设备安装了 LSPosed 就启用 Hook 路径。

### 这个开关不会做什么

关闭这个开关不会：

- 卸载 LSPosed；
- 禁用 LSPosed 管理器里的模块；
- 修改 LSPosed 的作用域；
- 强行结束已经被 LSPosed 注入的其他应用进程。

这些属于 LSPosed 框架本身的管理范围。

FloatLens 开关控制的是 **FloatLens 自己是否选择 LSPosed 增强实现**。关闭后，FloatLens 应继续选择 Accessibility / OCR / 系统 API 等普通实现。

## 5. LSPosed Hook 通信状态

FloatLens 原工程已经包含 libxposed API 102 模块和 FooView 运行时分析 Hook。

当已被 LSPosed 注入的作用域进程通过 `FL_HOOK_LOG` 向 FloatLens 回传消息时，FloatLens 会记录最近一次通信时间，并在“高级权限”页面显示：

`已检测到 Hook 通信 · yyyy-MM-dd HH:mm`

这个状态的含义是：

**至少有一个 LSPosed 注入进程成功和 FloatLens 建立过现有 Hook 通信链路。**

它不表示所有应用都已加入 LSPosed 作用域，也不表示未来每一种 LSPosed Provider 都已经在目标应用中可用。

如果显示“尚未检测到 Hook 通信”，应检查：

- LSPosed 是否正常运行；
- FloatLens 模块是否启用；
- 目标作用域是否正确；
- 目标应用是否在模块启用后重新启动。

## 6. 失败回退

`增强方法失败时回退普通方法` 默认开启。

推荐保持开启，因为 Root / Hook 能力会受到以下因素影响：

- Android 版本；
- ROM 差异；
- Root 管理器策略；
- 目标应用版本；
- LSPosed 作用域；
- Hook 点变化。

设计目标是增强功能失败时，尽量不要影响原来已经可用的普通功能。

## 7. 开发约束

以后新增 Root / LSPosed 功能时，不要在业务代码里直接写：

```java
if (rootEnabled) {
    // su ...
}
```

统一使用：

```java
if (PrivilegeManager.canUseRoot(context)) {
    // Root provider
} else {
    // normal provider
}
```

LSPosed 同理：

```java
if (PrivilegeManager.canUseLsposed(context)) {
    // LSPosed provider
} else {
    // Accessibility / OCR / normal provider
}
```

如果功能还有自己的细分开关，则需要同时满足总权限开关和功能开关。截图就是当前范例：

```java
boolean rootAllowed = settings.effectiveRootScreenshot();
```

其中 `effectiveRootScreenshot()` 等价于：

`增强模式 && Root 功能 && Root 截图增强`

## 8. 当前实现边界

本次改动完成的是 **统一权限层、设置 UI、状态显示、Root 截图实际门控和回退机制、LSPosed Provider 门控与现有 Hook 通信状态检测**。

当前仓库里的 LSPosed 代码主要仍是 FooView 运行时分析 / 逆向验证模块，并不是已经完成的“任意 App 通用 View 提取器”。因此设置页不会把“模块已通信”描述成“所有 LSPosed 取词功能都已完成”。

后续增加真正的 LSPosed View / WebView / Compose Provider 时，应继续使用本次建立的 `PrivilegeManager` 门控，这样用户关闭增强模式后仍然可以无缝回到普通 FloatLens 行为。
