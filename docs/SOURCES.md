# 实现依据与核验记录

核验日期：2026-10-18。官网提供连接要求和 SDK 支持范围；USB 协议细节来自开源实现，**不应称为富士公开的 PTP 协议规范**。

## 富士官方资料

1. [X‑T5 官方手册：Tethered Photography](https://fujifilm-dsc.com/en/manual/x-t5/connections/tethere/)
   - 依据：X‑T5 USB 联机拍摄；供电/通信设置；AUTO/FIXED 模式差别；使用机身快门的联机流程。
   - 对应：App 连接指南、默认选择 USB AUTO、使用机身快门收图。
2. [X‑T5 官方手册：Network/USB Setting Menus](https://fujifilm-dsc.com/en/manual/x-t5/connections/network_usb_menu/)
   - 依据：USB CARD READER 与 USB TETHER SHOOTING 为不同模式；联机时自动关机设置仍有效；FIXED 默认可能不记录到卡。
   - 对应：模式校验与用户连接说明。
3. [FUJIFILM Digital Camera Control SDK](https://www.fujifilm-x.com/global/camera-control-sdk/)
   - 核验到 X‑T5、Android 11–15 和安卓仅 USB 的官方支持范围；官方 SDK 不提供 RAF 显影。
   - **本项目没有集成或再分发官方 SDK 二进制，也没有将官方 SDK 代码开源。** 当前是自有 Java PTP 实现，结合官方连接文档开发，不代表富士官方认证。后续若集成官方 SDK，应另外阅读其 [EULA](https://www.fujifilm-x.com/global/camera-control-sdk/agreement/) 和使用条件。

## GitHub 协议参考（固定到已核验提交）

| 项目 | 核验提交 | 用途 |
| --- | --- | --- |
| [mikefsq/ptp](https://github.com/mikefsq/ptp) | `0fe3c68516f8c0fe89c963437bbc385d68620b25` | X‑T5 的 USB 标识、临时拍摄存储区、控制优先级、完整下载后释放对象 |
| [petabyt/libfuji](https://github.com/petabyt/libfuji) | `a3bd237ae3fbb76272e5e08887defabd4d6aefdc` | 富士 USB 会话建立次序、不同传输模式、富士协议研究文档 |
| [gphoto/libgphoto2](https://github.com/gphoto/libgphoto2) | `92171a382f185f617e95cb9acecf2d43688b19ab` | X‑T5 产品 ID；不能只依赖 ObjectAdded，需轮询对象；保持相机优先 |

具体源码：

- [ptp/fuji/fuji.go：StillStore 与 X‑T5 ID](https://github.com/mikefsq/ptp/blob/0fe3c68516f8c0fe89c963437bbc385d68620b25/fuji/fuji.go)
- [ptp/fuji/capability.go：NewFrames / Download / Delete](https://github.com/mikefsq/ptp/blob/0fe3c68516f8c0fe89c963437bbc385d68620b25/fuji/capability.go)
- [ptp/fuji/capture.go：PriorityMode 与存卡属性的含义](https://github.com/mikefsq/ptp/blob/0fe3c68516f8c0fe89c963437bbc385d68620b25/fuji/capture.go)
- [libfuji/lib/fuji_usb.c：fujiusb_setup](https://github.com/petabyt/libfuji/blob/a3bd237ae3fbb76272e5e08887defabd4d6aefdc/lib/fuji_usb.c)
- [libfuji/docs/dev.md：传输模式概述](https://github.com/petabyt/libfuji/blob/a3bd237ae3fbb76272e5e08887defabd4d6aefdc/docs/dev.md)
- [libgphoto2/camlibs/ptp2/library.c：Fuji wait_for_event 分支](https://github.com/gphoto/libgphoto2/blob/92171a382f185f617e95cb9acecf2d43688b19ab/camlibs/ptp2/library.c)

没有链接这些项目的 native 库。Java 实现参考上述行为，并添加 Android USB 分包缓存、保存校验、预览与服务生命周期。MIT 参考项目的许可保留在 `THIRD_PARTY_NOTICES.md`；libgphoto2 仅作交叉核对，没有复制或编译其 C 实现。

## Android 官方技术文档

- [USB Host](https://developer.android.com/develop/connectivity/usb/host)：授权、接口/端点发现、工作线程传输、拔出清理。
- [connectedDevice 前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)：USB 授权是运行条件之一。USB 路径先核验 `UsbManager.hasPermission`。0.2.1 同时声明网络前台服务所需的 `CHANGE_NETWORK_STATE`，无需原来仅 USB 版本的 lint 局部抑制。
- [AndroidX ExifInterface 1.4.2](https://developer.android.com/jetpack/androidx/releases/exifinterface)：处理 JPEG 方向，使用维护中的 AndroidX 实现。

## 0.2.0 Wi‑Fi 实现依据

- [X‑T5：无线联机拍摄](https://fujifilm-dsc.com/en/manual/x-t5/connections/tethere/) 与 [富士无线联机设置说明](https://app.fujifilm-dsc.com/en/tether/tether_wireless.html)：同一路由器、无线联机固定、机身确认、指定 IP 和网络隔离注意点。
- [libfuji/discovery.c](https://github.com/petabyt/libfuji/blob/a3bd237ae3fbb76272e5e08887defabd4d6aefdc/lib/discovery.c)：PCSS UDP 51562 广播和 TCP 51560 相机回调，`DSC` / `DSCPORT` / `CAMERANAME` 字段。本项目计算实际子网广播地址，没有沿用参考代码中的固定网段。
- [libfuji/fuji.c](https://github.com/petabyt/libfuji/blob/a3bd237ae3fbb76272e5e08887defabd4d6aefdc/lib/fuji.c)、`fuji_usb.c` 与 `module.c`：无线联机路径的 82 字节握手后复用 USB 形状的 PTP 会话。
- [libgphoto2/fujiptpip.c](https://github.com/gphoto/libgphoto2/blob/92171a382f185f617e95cb9acecf2d43688b19ab/camlibs/ptp2/fujiptpip.c)：握手长度、版本标识 `0x8f53e4f2`、TCP 55740 及部分机型空 DeviceInfo 的兼容行为。没有复制或链接其 C 实现。
- [Android 局域网权限说明](https://developer.android.com/privacy-and-security/local-network-permission)：target SDK 35 使用 `INTERNET` 兼容访问，不额外声明或请求 target SDK 37 的局域网运行时权限。

无线模式没有接入富士仅支持 Android USB 的官方 SDK；使用自行实现的局域网协议。Wi‑Fi 取图存储区与临时对象释放策略继续采用已验证 USB 路径，已在 X‑T5 固件 4.31 上实拍接收两张 JPEG，并由用户确认显示。

## 推断与待实测部分

`0x10000001` 作为 X‑T5 临时拍摄存储区和删除对象作为释放缓冲区的行为来自开源项目的实测记录。本项目对此做了单元测试，并已在目标机身验证 USB 与无线 JPEG 收图。若相机报告其他存储区，本版会停止，不自动扩大到 SD 卡进行删除。

200 ms 轮询间隔是实现选择。相机处理、缩略图支持、文件大小、手机 USB 控制器、存卡设置和渲染时间共同影响实际延迟；代码显示的传输耗时不包含从按快门开始的全部时间。

0.2.1 真机排查发现 VPN 网络也可报告 `WIFI` 传输类型；网络选择同时排除 `TRANSPORT_VPN`，避免把隧道地址误当作相机局域网地址。

## 0.2.3 X‑T5 4.31 实机握手结论

`libfuji/lib/fuji.c` 中初始化报文在偏移 12–27 写入四个固定字段：`0x5d48a5ad, 0x0b7fb287, 0xd0ded5d3, 0`。本机实测随机 UUID 会被拒绝为 `0x201D`，仅清零最后一字仍拒绝；完整使用参考字段后握手及 PTP 会话成功。这些字段的更深含义未得到富士公开文档确认，本实现保留已验证的兼容值，不再将其当成任意 UUID。

发现回调的 `DSCPORT` 实测为 15740。遵循 `libfuji/lib/module.c` 在发现后等待约一秒，再连接相机；遵循 `libfuji/lib/fuji_usb.c` 在无线握手后短暂等待再打开 PTP 会话。本机初次初始化返回 `0x2019`，同一连接延迟重发后成功，因此只对该忙响应重试；无效参数和访问拒绝立即报告。

这些属于开源实现与实机验证结果，并非富士官方公开的线协议规范。

## 0.4.1 保存位置与公共相册

依据 [Android 官方共享媒体存储文档](https://developer.android.com/training/data-storage/shared/media)：应用可读写自身创建的媒体，使用 `IS_PENDING` 在完成写入前隐藏文件，不需要为自身原图申请整机相册读取权限。本项目使用 Images 集合保存 JPEG，Downloads 集合保存 RAF，过滤所有者、目录和接收原图文件名来合并本应用的图库。

接收后先保留经长度校验和 fsync 的私有临时原图，选相册时再完整写入 MediaStore、校验实际大小、fsync 并清除 pending；发布确认后才释放相机临时对象，成功释放后清理私有临时副本。这是本项目的保存策略，不是富士协议要求。正式预览通过 Content URI 读取，不依赖废弃的公共文件 DATA 路径，也没有新增 READ_MEDIA_IMAGES 权限。

## 0.5.0 原图细节预览

依据 [Android BitmapRegionDecoder 官方参考](https://developer.android.com/reference/android/graphics/BitmapRegionDecoder) 按矩形区域解码 JPEG，并依据 [Android 大位图加载指南](https://developer.android.com/topic/performance/graphics/load-bitmap) 控制内存。原文件与原图保存流程不变，没有重新压缩照片。Android 11 使用兼容重载，Android 12 起使用 InputStream 新重载。

概览和可见细节在源像素坐标中绘制，同一个 EXIF 仿射变换处理八种方向，避免旋转整张高分辨率位图造成额外内存峰值。独立解码线程合并连续缩放/拖动请求，过期结果回收，切图后关闭旧解码器；单张细节位图预算最多 40 MiB，内存不足时降低精度，RAF 使用内嵌 JPEG 作为源。

## 0.5.1 传输途中预览

对实际 X‑T5 JPEG 文件头检查发现：EXIF APP1 内嵌 160×120 JPEG，FlashPix APP2 内嵌 640×480 JPEG。此为本机样本实测，不能保证所有机身设置都包含同样的预览。自行实现有界元数据解析器，仅检查前 256 KiB 的完整 APP1/APP2 段、选取分辨率较大的完整 JPEG，沿用父图 EXIF 方向；不发送额外相机操作，不把未完成的主图作为有效文件发布。

本地回放实际 9,592,520 字节样本时，按真实传输的 16 KiB 块喂入，在 114,688 字节处提取到 29,319 字节预览；检查尺寸 640×480，图像可解码。提取、方向注入和有界解析均为本项目实现，没有复制 FlashPix 库。新增阶段耗时日志区分原图下载/落盘与相册发布；减少磁盘小块写入的缓冲调整不代表 Wi‑Fi 吞吐已被实测提高。

## 0.5.2 按显示区域匹配分辨率

根据 PhotoView 当前宽高（物理像素）、EXIF 校正后照片比例及缩放倍率计算需要的源区域和输出尺寸。采样选择不低于显示所需的二次幂精度，再缩到实际输出像素；当中间解码较大时以约 8 MiB 条带读取并缩放合成，避免一次创建完整的 20–40 MP 原像素位图。条带重叠处理采样滤波边缘。以上为本项目显示和内存策略。

适屏时优先匹配画面在窗口中的实际大小，缩放后最多读取原有像素，内存不足时降低精度。窗口旋转或全屏切换会改变解码请求，即使采样倍数未变也重新匹配输出。

JPEG 路径直接提取传输中的嵌入预览，跳过此前先执行 GetThumb 的额外事务，避免快速预览仍被排在该事务之后；RAF 保留设备声明支持时的 GetThumb 路径。是否缩短快门到首图时间仍以真机拍摄日志为准。

## 0.6.x 预览与原图调度

- [libfuji/fuji.c](https://github.com/petabyt/libfuji/blob/a3bd237ae3fbb76272e5e08887defabd4d6aefdc/lib/fuji.c#L562) 使用 GetPartialObject 分段取数据，作为实现思路参考；这不构成 X‑T5 无线联机模式支持该操作的证明。2026-10-19 真机 DeviceInfo 未列出 0x101B，尝试只读分段请求后明确返回 0x2005，已据此加入兼容调度。
- [mikefsq/ptp 的拍摄缓冲区查询](https://github.com/mikefsq/ptp/blob/0fe3c68516f8c0fe89c963437bbc385d68620b25/fuji/capture.go#L96) 将临时拍摄存储区对象作为待接收帧，提供缓冲区生命周期参考。

本项目没有并发操作同一 PTP 字节流，也没有假定可打开第二个相机控制会话。分段可用时使用单协议线程调度：优先新照片文件头，再补旧原图；不支持分段但支持 GetThumb 时，在连续拍摄期间优先最新独立预览，短暂停拍后取完整原图。完整 GetObject 事务仍不可插队，这是当前兼容模式的限制。拍摄序号控制显示顺序，与原图完成保存顺序分开；所有原图沿用校验、fsync、最终存储发布确认后再释放的策略。

## 0.7.0 可见队列与有效速度

在原有 Camera/PTP 事件上增加有界 CaptureQueue，不把未完成原图伪装成已保存文件；最多 32 个条目，每个暂存预览最多 256 KiB。每个条目由拍摄 ID 关联名称、大小、进度与预览，只有最终发布成功才从待保存队列移除。界面和图库使用快照，进度事件不回退已收字节；断开后仍保留“未保存 · 已断开”提示。

原图接收速度按真实接收字节和单次接收开始时间计算，最终日志把排队、接收/私有落盘和公共相册发布分开；不能将总等待时间直接当成 Wi‑Fi 纯传输耗时。自动保存安静期根据用户明确选择改为 3 秒。暂停按钮只改变下一次原图下载的调度，不强行中断已经开始的完整 GetObject。

## 0.7.1 状态区布局与传输诊断

状态信息固定在顶栏中，长文字省略并支持点开完整详情；进度条仍叠加在照片上方，使用 INVISIBLE 保留尺寸。队列控制区、连接和跟随按钮采用固定外框，不随文字及连接状态显隐改变布局。PhotoView 的解码继续按实际显示区域匹配。

新增仅运行于真实设备的原生 [Instrumentation](https://developer.android.com/reference/android/app/Instrumentation) 回归工具，断开且无待保存对象时，使用内存状态验证重连、进度、错误、暂停和队列 0/1/32 的视图边界；不发送相机协议请求，不写照片。测试 APK 不包含在交付的主应用 APK 中。

用户反馈原图约 300 KB/s 后，读取真实设备 QueueTiming：原图接收 0.26–0.92 MB/s，公共相册发布约 0.07–0.16 秒；平板 Wi-Fi 状态为 5 GHz、RSSI -38 dBm，用户确认相机也连接该 5 GHz SSID，路由器显示相机协商速率至少 100 Mbps。协商速率不能代替端到端实测，目前不能确定相机出数、链路重传还是 App 接收的具体责任。

因此增加逐张 ReceiveIO 诊断：分别累计传输 read 调用耗时、输出 sink（含私有文件写入与可选预览提取）耗时、进度回调耗时和最长单次读取等待；与已有排队、完整接收/落盘及相册发布耗时配合使用。诊断不改变缓冲、PTP 操作或传输调度，也不宣称已提升吞吐。

## 0.7.3 接收确认优化

依据 [Linux TCP 官方手册](https://man7.org/linux/man-pages/man7/tcp.7.html) 区分 TCP_NODELAY 与 TCP_QUICKACK：前者控制本机发送方向的 Nagle 行为，后者提示当前接收连接及时确认，但不是永久标志。本实现通过每批接收后的可选回调重新提示，只针对相机套接字。Linux TCP_QUICKACK 选项号为 UAPI 的 12（本机 Linux 头文件亦核对），不调用 Android 隐藏 API。

使用 Android 公开的 [Os.setsockoptInt](https://developer.android.com/reference/android/system/Os#setsockoptInt(java.io.FileDescriptor,int,int,int)) 和 [ParcelFileDescriptor.fromSocket](https://developer.android.com/reference/android/os/ParcelFileDescriptor#fromSocket(java.net.Socket))；本应用最低 API 30，后者在 API 29 起返回独立 dup，单独管理并关闭，不接管 Socket 的原始描述符。失败只停用该提示，继续普通 PTP 接收；保存设置后通过 volatile 开关立即应用，不重连也不改变照片字节。

[Android WifiManager 的低延迟锁](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY) 文档明确指出某些取舍会降低吞吐，因此本次没有将它与 ACK 设置同时强制启用，也不把它当成必然的下载加速。现有同路由器、无线联机固定模式和 libfuji 兼容握手保持不变。

`tools/device/TcpReceiveProbe.java` 是单独的真实设备 TCP 回环 API 检查，不是 Android 模拟器，也不是相机吞吐基准；验证开关行为、原始 Socket 在 dup 关闭后仍可用及字节不变，不附着或关闭主应用。相机实际速度仍以 ReceiveIO / QueueTiming 和相同场景的开关收图对比为准。

0.7.4 补齐关闭方向：从开启切到关闭时，仅一次写入 TCP_QUICKACK=0，明确退出及时确认模式，避免只停止重复提示而保留上一阶段状态；不是每次读取反复强制关闭。日志增加 ackResets 计数，真机回环检查确认关闭时发生一次恢复且不重复，Socket 保持可用。

## 0.7.5 无线省电单项试验

依据同一 WifiManager 公开文档，增加独立且默认关闭的 WIFI_MODE_FULL_LOW_LATENCY 开关，仅在无线联机期间持有 WifiLock。使用已声明的 WAKE_LOCK 权限；使用 application context 创建，连接结束、异常退出与服务销毁时释放。不是系统全局强制模式。测试时保持 ACK 设置一致，仅改变无线低延迟锁，并用 dumpsys wifi 的实际运行模式核对是否生效；未把 isHeld（请求已持有）等同于硬件实际启用。

## 0.8.0 图库多选与删除

依据 [Android 共享媒体文档：移除项目](https://developer.android.com/training/data-storage/shared/media#remove-item) 使用 MediaStore 的项目 URI 删除本应用拥有的媒体；不请求整机相册读取权限，不对集合 URI 执行批量删除。每个媒体项先核验 OWNER_PACKAGE_NAME、RELATIVE_PATH、DISPLAY_NAME 与 IS_PENDING，再带同样条件删除，避免文件归属或保存位置改变后误删。App 私有文件限定为 Captures 的直接完整照片文件，拒绝路径穿越、符号链接与 `.part`。

图库按唯一接收文件名合并私有／相册来源和维持勾选，确认时冻结删除对象；不按不断变化的网格下标删除，也不自动勾选新收到的照片。多选不调用相机 PTP 删除接口。

## 0.9.0–0.9.1 拍摄参数与样式

依照 [AndroidX ExifInterface 官方参考](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface) 读取 FNumber、ExposureTime、PhotographicSensitivity、FocalLength、ExposureBiasValue、FocalLengthIn35mmFilm、MeteringMode、ExposureProgram、ExposureMode、Flash、WhiteBalance、LightSource、ColorSpace、Model、LensModel、DateTimeOriginal 和 Make。与已有方向解析共用同一个 ExifInterface 实例，不调用 saveAttributes，也不增加相机事务。

闪光灯是否触发按 Flash 的最低位判断，防红眼使用相应位；未标定色彩空间不推断为 Adobe RGB。标准白平衡标签只区分自动与手动，不声称能解析富士私有 MakerNote 的色温、胶片模拟。可选参数栏以叠层显示，不改变预览区尺寸。
