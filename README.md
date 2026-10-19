<div align="center">

# XT5 Tether

**把 Android 手机或平板，变成富士 X‑T5 的联机看图屏幕。**

Native Android tethered photo viewer for FUJIFILM X‑T5 over USB and Wi‑Fi.

[![Android checks](https://github.com/az7852/xt5-tether/actions/workflows/android.yml/badge.svg)](https://github.com/az7852/xt5-tether/actions/workflows/android.yml)
[![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)](#兼容性)
[![Version](https://img.shields.io/badge/version-0.9.2%20beta-blue)](app/build.gradle)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[下载 APK](https://github.com/az7852/xt5-tether/raw/refs/heads/main/downloads/XT5-Tether-0.9.2-debug.apk) · [快速上手](#快速上手) · [使用指南](docs/USER_GUIDE.md) · [构建与测试](#构建与测试) · [反馈问题](https://github.com/az7852/xt5-tether/issues)

</div>

---

按下相机快门，照片自动传到 Android 设备上预览、放大和保存。无需电脑中转，适合现场选片与细节检查。

> **当前为 0.9.2 测试版。** USB 收图已有用户实机确认；Wi‑Fi 已在 X‑T5 固件 4.31 与真实手机、小米平板上验证 JPEG 接收。其他固件、RAF 全流程与高强度连拍仍需验证。详细依据见[验证记录](docs/VALIDATION.md)。

## 功能亮点

| 功能 | 说明 |
| --- | --- |
| USB / Wi‑Fi 联机 | USB Host / PTP，或同一路由器下自动发现、手动输入相机 IP |
| 预览优先 | 支持时先显示小预览，再完整保存原图；可切换停拍 3 秒后下载或立即下载 |
| 原图保存 | JPEG / RAF 原始字节保存，支持 App 图库或系统相册；RAF 显示内嵌 JPEG |
| 细节检查 | 双击、双指缩放、全屏查看，按显示区域和缩放倍率读取图片 |
| 手机与平板 | 窄屏底部胶片条，宽屏右侧双列照片栏，支持旋转与窗口变化 |
| 图库管理 | 历史照片网格、多选、批量导出、批量删除与跟随最新照片 |
| 拍摄参数 | 可选 EXIF 参数叠层与完整信息页，缺失字段明确显示为「—」 |
| 本地工作流 | 照片保存在设备本地，应用不提供云端照片上传 |

## 兼容性

| 项目 | 要求 / 已验证情况 |
| --- | --- |
| Android | Android 11+（API 30+）；当前 target / compile SDK 为 35 |
| 相机 | FUJIFILM X‑T5；Wi‑Fi 已验证固件 4.31，目前代码仅接受 X‑T5 |
| USB | Android 设备支持 USB Host / OTG，使用支持数据传输的 USB‑C 线 |
| Wi‑Fi | 相机与 Android 设备连接同一路由器，使用无线联机拍摄固定模式 |
| 文件格式 | JPEG、RAF；RAF 预览为内嵌 JPEG，不进行 RAW 显影 |

应用清单声明 USB Host 为必需硬件。其他富士机型尚未适配。

## 快速上手

### 1. 安装

直接下载 **[XT5 Tether 0.9.2 测试版 APK](https://github.com/az7852/xt5-tether/raw/refs/heads/main/downloads/XT5-Tether-0.9.2-debug.apk)**（约 2.55 MB，Android 11+）。这是此前已在真实手机和平板安装验证的原包，APK v2 签名和内容摘要已核验；可用 [SHA-256 校验文件](downloads/SHA256SUMS.txt)确认下载完整性。

也可从源码构建，或从成功的 [GitHub Actions 构建](https://github.com/az7852/xt5-tether/actions/workflows/android.yml)下载 `XT5-Tether-debug` artifact，解压后安装。下载 Actions artifact 通常需要登录 GitHub。

这是开发签名测试包。不同构建环境的 debug 签名可能不同，签名不一致时不能直接覆盖安装；卸载前请先导出 App 图库原图。

### 2. 连接相机

**USB：**

1. 相机连接模式设为 **USB 联机拍摄自动 / USB TETHER SHOOTING AUTO**。
2. USB 供电/通信设为「自动」或「供电关 / 通信开」，关闭自动关机。
3. 用数据线连接手机或平板，打开 App，选择 **连接 → USB 数据线连接** 并允许 USB 访问。
4. 先使用 JPEG FINE，按相机快门，确认照片自动出现。

**Wi‑Fi：**

1. 拔下 USB 线，将相机与 Android 设备连接到同一路由器。
2. 相机选择 **无线联机拍摄固定 / WIRELESS TETHER SHOOTING FIXED**。
3. App 选择 **连接 → Wi‑Fi 自动发现**，或选择 **Wi‑Fi 输入相机 IP**。
4. 等待绿色 `Wi‑Fi` 状态；如相机请求确认，按 MENU/OK，然后拍一张 JPEG 验证。

Wi‑Fi 使用相机的无线联机模式。蓝牙配对和 XApp 热点传图不属于本项目的连接流程。访客网络、AP 隔离、VPN 或其他相机软件可能影响连接。

### 3. 查看与保存

- 点缩略图回看，点「跟随」恢复自动显示新照片；轻触照片进入全屏，双击或双指缩放。
- **⋮ → 设置 → 照片保存位置**：选择「App 图库」或「本地相册」。
- **⋮ → 设置 → 原图下载时机**：选择停拍 3 秒后下载（默认）或立即下载。
- 长按已保存缩略图进入多选，支持批量导出和删除。

| 保存方式 | 位置 | 卸载 App 后 |
| --- | --- | --- |
| App 图库（默认） | 应用专属 `Pictures/Captures` 目录 | 原图会被删除，请先导出 |
| 本地相册：JPEG | `Pictures/XT5Tether` | 公共原图保留 |
| 本地相册：RAF | `Download/XT5Tether` | 公共原图保留 |

设置只影响之后保存的照片。待保存预览不代表原图已落盘；断电前请等待队列完成。图库删除会永久删除所选 App 原图及本应用保存的同名公共副本，请在确认弹窗中核对范围。相机 SD 卡是否另存由机身设置决定。

完整设置、队列策略、存储行为和设备检查步骤见[使用指南](docs/USER_GUIDE.md)。

## 构建与测试

需要 **JDK 17**、**Android SDK Platform 35** 和 **Build Tools 35.0.0**。项目自带 Gradle Wrapper 8.11.1，使用 Android Gradle Plugin 8.9.2。

```bash
git clone https://github.com/az7852/xt5-tether.git
cd xt5-tether
```

配置 `ANDROID_HOME`，或在根目录创建不提交的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

macOS / Linux：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`，可通过 ADB 安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 执行单元测试、Android lint、主应用和独立设备检查包构建，并上传测试 APK 与报告。设备检查包需要另行在真实设备运行，CI 构建成功不代表相机实机验收通过。

现有验证记录包含 **84 项通过的 JVM 单元测试**，覆盖协议、网络、保存、队列、图片几何、图库选择与 EXIF 格式。逐版结果及待验证项见[验证记录](docs/VALIDATION.md)。

## 项目结构

```text
app/src/main/java/io/github/xt5tether/
├── ptp/                  # PTP 协议、富士发现、联机会话与下载队列
├── MainActivity.java     # 连接、预览、图库与设置界面
├── TetherService.java    # 前台联机服务、状态与日志
├── UsbPtpIo.java         # Android USB 传输适配
├── PhotoLibrary.java     # 原图保存、导出与删除边界
└── PhotoView.java        # 图片显示、方向与缩放
app/src/test/             # JVM 单元测试
app/src/androidTest/      # 真实设备布局检查
device-checks/            # 独立 UID 的设备检查包
tools/device/             # 真实设备诊断工具
docs/                     # 使用说明、实机验收与技术依据
```

应用采用 Java 17 和原生 Android UI，协议层与 Android 传输适配分离。原图经过完整接收、长度核验、同步落盘和最终发布后，才释放相机临时对象。

## 已知限制

- 暂不包含远程快门、实时取景、机身参数控制、蓝牙或 RAW 显影。
- X‑T5 固件 4.31 无线模式实测不支持分段读取；完整原图传输期间，新照片预览可能等待当前事务结束。
- 队列最多跟踪 32 个文件；RAW+JPEG 可能占两个条目，相机缓冲区另有容量限制。
- 无线速度受照片体积、相机与网络影响，不承诺固定帧率或「零延迟」。[网络实测](docs/NETWORK_BENCHMARK.md)中的数值是特定环境下的样本。
- 自动保存失败会保留完整私有原图并停止联机；仍在相机易失缓冲区中的未下载照片可能因相机断电而丢失。
- 后台运行可能受到手机电池管理影响；不同固件、USB 控制器与分屏窗口尚未全面验证。

## 文档与贡献

- [使用指南](docs/USER_GUIDE.md)：完整操作与设置说明。
- [实机验收清单](docs/HARDWARE_TEST.md)：连接、保存和异常处理检查。
- [验证记录](docs/VALIDATION.md)：已完成测试与尚未覆盖的情况。
- [无线接收实测](docs/NETWORK_BENCHMARK.md)：测速方法与原始样本。
- [协议与实现依据](docs/SOURCES.md)：参考资料与实现说明。
- [贡献指南](CONTRIBUTING.md)：反馈、开发和提交变更。

欢迎通过 [Issues](https://github.com/az7852/xt5-tether/issues) 提交可复现问题，或通过 Pull Request 改进。反馈时请提供相机固件、Android 版本、连接方式、复现步骤和相关日志；公开日志前移除个人信息。

## 许可证与致谢

本项目自身代码采用 [MIT License](LICENSE)。第三方依赖及参考项目见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，包括 AndroidX ExifInterface、mikefsq/ptp 和 petabyt/libfuji。

本项目是独立社区项目，与 FUJIFILM 没有隶属或官方支持关系。FUJIFILM 和 X‑T5 为相应权利人的商标。
