# LSPosed 安全窗口截图增强

FloatLens 的安全窗口截图不是永久 FLAG_SECURE 绕过，而是一个只围绕 FloatLens 自己截图操作存在的短时授权。

## 门控

功能只有在以下条件同时满足时才会进入增强路径：

- 高级权限 → 增强模式：开启
- 高级权限 → LSPosed Provider：开启
- 截图与 OCR → LSPosed 安全窗口截图增强：开启
- LSPosed 的 `system` 作用域已实际加载 FloatLens
- Remote Preferences 配置通道可用

## 截图时序

1. App 写入约 3 秒的 monotonic lease；
2. 等待极短的 Remote Preferences 传播窗口；
3. FloatLens 通过 Accessibility 发起截图；
4. system_server 中的 Hook 在每次捕获调用时实时验证 lease；
5. 截图成功或失败后立即 disarm；
6. 即使 App 中断，lease 也会自行过期。

## Hook 边界

`SecureScreenshotHook` 只安装在 system_server：

- 优先适配 `android.window.ScreenCaptureInternal`，兼容旧的 `android.window.ScreenCapture`；
- 仅在 lease 有效时设置 secure-layer capture 参数；
- 对 `WindowState.isSecureLocked()` 的兼容处理同样受 lease 控制；
- surface 创建/初始安全属性设置调用栈继续执行原始逻辑，因此 secure Surface 标记本身不被清掉。

明确禁止恢复旧实现：

- 不 Hook `SurfaceControl.Builder.setSecure(false)`；
- 不永久把 `WindowState.isSecureLocked()` 改为 false；
- 不启用 protected/DRM content capture；
- 不因模块加载本身改变系统截图行为。

## 失败回退

短时 lease 建立失败时，如果用户允许“增强方法失败时回退普通方法”，FloatLens 回到普通 Accessibility 截图。若同时允许 Root 截图，Accessibility 增强路径失败后仍可尝试 Root；Root 路径不假设能够利用 system_server 的 secure-layer Hook。
