# APK 下载

[直接下载 XT5 Tether 0.9.2 测试版](https://github.com/az7852/xt5-tether/raw/refs/heads/main/downloads/XT5-Tether-0.9.2-debug.apk)

- 包名：`io.github.xt5tether`
- 版本：`0.9.2`，versionCode `26`
- 系统要求：Android 11+（API 30+）
- 大小：2,550,995 字节
- 构建类型：debug，Android 开发签名
- 源码版本：[1940414](https://github.com/az7852/xt5-tether/commit/1940414a8c7c76ea2c62629709a0de685fd9172c)

此文件与 `artifacts/XT5-Tether-0.9.2-debug.apk`、本地 Gradle 构建输出及验证记录中的真机安装包 SHA-256 一致。已核验 APK v2 签名和 APK 内容摘要，签名证书为 Android Debug；本次上传未更改或重新签名 APK。

在此目录运行以下命令核验下载文件：

```bash
sha256sum -c SHA256SUMS.txt
```

Windows PowerShell：

```powershell
Get-FileHash .\XT5-Tether-0.9.2-debug.apk -Algorithm SHA256
```

与 [SHA256SUMS.txt](SHA256SUMS.txt) 中的摘要比较。安装说明见[项目首页](../README.md#快速上手)。只有签名相同的安装包才能覆盖升级；卸载前请先导出 App 图库原图。
