# 贡献指南

感谢你帮助改进 XT5 Tether。当前适配目标是 FUJIFILM X‑T5，较大的协议或机型适配改动建议先开 Issue 讨论。

## 反馈问题

请描述预期行为、实际行为和可重复的步骤，并附上：

- 相机型号、固件版本和 JPEG / RAF / RAW+JPEG 设置。
- Android 设备型号、系统版本与 App 版本。
- USB 或 Wi‑Fi 连接方式；Wi‑Fi 问题请说明网络拓扑。
- 对应时间段的诊断日志或截图，公开前移除个人信息和不需要公开的照片。

请区分首张预览耗时、排队时间、原图接收时间与最终保存时间。

## 开发与 Pull Request

1. Fork 仓库并从默认分支创建功能分支。
2. 按 [README](README.md#构建与测试) 配置 JDK 17 与 Android SDK 35。
3. 保持变更聚焦；行为变化同时更新说明，协议与存储改动补充必要的回归测试。
4. 执行以下检查，再提交 Pull Request：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :device-checks:assembleDebug
```

PR 请说明解决的问题、实现后的行为、验证命令、实机型号及尚未验证的情况。设备检查包仅构建成功时，请不要写成实机通过。

## 协议与照片保存

原图完整性优先。请保留长度核验、同步落盘、发布成功后再释放相机对象的顺序；未完成预览不能作为完整原图导出。图库操作只应作用于本应用拥有的文件，不应发送相机存储卡删除命令。

真实设备检查方法见 [使用指南](docs/USER_GUIDE.md) 和 [实机验收清单](docs/HARDWARE_TEST.md)。设备检查会影响界面或创建测试文件，运行前请先阅读对应步骤，避免与实拍接收交错执行。

提交贡献即表示同意按本项目的 [MIT License](LICENSE) 发布你的贡献。引入第三方代码或依赖时，请保留其许可证并更新 [第三方声明](THIRD_PARTY_NOTICES.md)。
