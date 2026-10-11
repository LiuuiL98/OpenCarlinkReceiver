# OpenCarlinkReceiver

用于研究 ICCOA Carlink 兼容性的实验性基础安卓主机接收。

> **当前状态：** 仅为测试原型。本项目尚未实现 ICCOA Carlink 协议，也不保证与任何品牌的手机兼容。真正的协议支持需要经过验证的规范或授权的 SDK 加上实际设备测试。

## 目标

- 广泛的安卓兼容性，目标为 **Android 4.4 (API 19)+**。
- 以 Java 为主的轻量级安卓应用。
- USB 设备诊断以及 Wi-Fi 功能/状态诊断。
- MediaCodec 功能检测和 SurfaceView 渲染基础。
- 将传输、协议、渲染和诊断模块分开，以便后续可以添加已验证的 Carlink 支持。
- GitHub Actions 构建工作流。

## Build

在 Android Studio 中打开这个仓库，或者用 Gradle 8.9 和 JDK 17 构建：

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## 首版原型功能

- 设备/API/ABI 概览。
- USB 主机功能和已连接的 USB 设备列表。
- Wi-Fi 状态和 Wi-Fi Direct 功能可用性。
- Android MediaCodec 可用的 H.264 解码器。
- 一个基本的接收屏幕和诊断日志。

## 重要的协议说明

- USB 对 Android 可见并不意味着建立了 ICCOA 会话。
- Wi-Fi 连接并不意味着支持 Carlink。
- 不要随意创造协议数据包格式或握手信息。
- 协议适配器只是一个占位符，直到相关规范/SDK 和法律整合条件得到验证。
- Android 4.4 支持描述的是应用的基础平台，而不是保证与每个 SDK 或手机兼容。

## 发展路线

1. 构建并安装诊断原型。
2. 收集来自真实主机和支持的手机的诊断数据。
3. 确认 ICCOA Carlink SDK 的可用性、API 级别支持、本地 ABI 要求以及集成/审批条款。
4. 为一个手机型号实现并测试真实会话。
5. 添加视频、音频、触控/控制通道，处理重连，然后扩大设备覆盖范围。
6. 只有在基本投屏功能可用后，再研究桌面融合功能。

## License

还没有选择许可证。在添加之前，默认保留所有权利。分发前请查看第三方 SDK 条款。
