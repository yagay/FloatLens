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

少任何一个条件，截图后端都不会调用 `su`。Root 截图使用 `screencap -p` stdout 直接解码；打开设置页本身不会执行 `su`。

## LSPosed

### 推荐作用域

`META-INF/xposed/scope.list`：

```text
system
com.android.systemui
```

`system` 对应 system_server，`com.android.systemui` 对应 SystemUI。`staticScope=false`，所以这里只是推荐作用域。

### 受控 Provider 配置通道

模块 App 通过 `io.github.libxposed:service:102.0.0` 把运行配置写入 LSPosed Remote Preferences 组 `floatlens_runtime`；system_server / SystemUI 中的 `LsposedRuntimeProvider` 只读并监听同一组配置，不读取应用普通 SharedPreferences，也不依赖 Root 文件。

基础门控：

```text
增强模式 = ON
LSPosed Provider = ON
```

`PrivilegeManager.lsposedProviderAvailable()` 还要求：框架 Service 已连接、Remote Preferences 可读写、system_server 或 SystemUI 至少一个推荐目标实际加载模块。

### 安全窗口截图增强

这是第一个功能性 LSPosed Provider。还需要：

```text
截图与 OCR → LSPosed 安全窗口截图增强 = ON
system_server 已加载 FloatLens
```

它不是永久 FLAG_SECURE 绕过。FloatLens 每次自己的 Accessibility 截图前：

1. App 在 Remote Preferences 写入约 3 秒的 `secure_capture_armed_until_elapsed` lease；
2. system_server Hook 每次调用都用 `SystemClock.elapsedRealtime()` 实时确认 lease 仍有效；
3. 截图完成或失败后 App 立即把 lease 清零；
4. 即使 App 异常退出，lease 也会自动过期；超过合理未来窗口的陈旧值会被拒绝。

system_server 的 Hook 做两件事：

- 在 `ScreenCaptureInternal/ScreenCapture` 的 native capture 参数进入系统截图链时，只有 lease 有效才允许抓取 secure layers；
- `WindowState.isSecureLocked()` 只有在 lease 有效、且不是 surface 创建/安全属性设置调用栈时才临时返回非 secure，以兼容截图策略检查。

明确不会做：

- 不恢复旧的 `SurfaceControl.Builder.setSecure(false)` 全局 Hook；
- 不永久清除 Window/Surface 的 secure 标记；
- 不启用 DRM/protected-content 捕获；
- 不给第三方应用进程安装通用截图 Hook；
- lease 无效时 Hook 立即走原始 Android 行为。

安全窗口截图增强优先使用 Accessibility 截图，因为该路径进入 system_server 的 ScreenCapture 链。Root `screencap` 仍是独立后端，不假设它能利用这个 LSPosed Hook。

### 状态页

高级权限页分别显示：

- 框架 Service、名称/版本/API；
- Remote Preferences 是否已同步；
- Provider 是否 active；
- 安全窗口截图功能是否启用；
- 当前短时授权是“进行中”还是“空闲”；
- `system` / SystemUI 作用域；
- system_server / SystemUI 实际加载情况；
- 当前已加载模块进程。

推荐作用域、用户勾选作用域、目标实际加载、Provider 可用、功能开关、短时 lease 是六个不同状态，不互相冒充。

### 为什么不恢复旧全局方案

历史版本曾无条件 Hook `WindowState.isSecureLocked()` 并强制 `SurfaceControl.Builder.setSecure(false)`。该实现已删除，因为应用 SharedPreferences 无法可靠控制已经安装在 system_server 中的长期 Hook，而且会把安全属性本身永久改掉。现在的 Remote Preferences + 短时 lease 只在 FloatLens 自己的截图窗口内改变捕获决策。

## 失败回退

“增强方法失败时回退普通方法”默认开启：

- LSPosed lease 建立失败时可回到普通 Accessibility；
- Accessibility 失败且 Root 截图条件满足时仍可尝试 Root；
- Root 失败时可按原设置回到 Accessibility。

关闭增强模式、LSPosed Provider 或安全窗口截图开关后，不再 arm lease，系统截图 Hook 只执行原始行为。

## FV 运行时抓取

项目不再把 FV/fooView Runtime Inspector 当作正式功能。FV 历史抓取只用于验证悬浮交互时序，不作为 FloatLens 运行时依赖。

## 开发约束

业务层只通过：

```java
PrivilegeManager.canUseRoot(...)
PrivilegeManager.canUseLsposed(...)
```

具体 Hook 还必须经过 `LsposedRuntimeProvider` 的实时门控；安全截图必须进一步检查短时 lease。禁止重新加入无法被应用开关真实关闭的永久 system_server 行为。
