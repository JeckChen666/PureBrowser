# PureBrowser 本地发行与密钥管理

正式 applicationId 为 `io.github.jeckchen666.purebrowser`，Debug 为其 `.debug` 变体。
Kotlin/Manifest 类的 namespace 仍为 `com.example.purebrowser`，不是应用安装身份。
两者和 v0.1.0 的 `com.example.purebrowser` 是独立应用，不跨包自动迁移私有数据或网站登录。

## macOS 签名

```sh
python3 tools/release/local_signing.py init
python3 tools/release/local_signing.py certificate
python3 tools/release/local_signing.py build
# 仅在专用测试设备需要对正式签名目标做仪器测试时：
python3 tools/release/local_signing.py build --instrumented
```

密钥默认保存在用户目录 `.config/purebrowser/signing/release.p12`，目录 0700、文件 0600。
密码通过 macOS Security.framework 保存到非同步的 Keychain 项：service `PureBrowser.ReleaseSigning`，account `purebrowser-release`。
不通过命令参数、聊天、日志、源码或明文密码文件传递密码。既有密钥绝不自动覆盖。
初始化使用 RSA 4096、PKCS12、10,000 天证书；这是应用签名身份，不是 HTTPS 服务器证书。

本次建立的证书 SHA-256：
`52fe690aadfd21a1baa4d06d8f75ec7aefa37d59ef43a057b6da4e9cfa84fc90`。

Release 构建关闭 Gradle configuration cache，避免签名参数进入配置快照；缺少签名参数时构建失败，不能回退为 Debug 签名。
其他平台可自行配置 `PB_SIGNING_STORE_FILE`、`PB_SIGNING_STORE_PASSWORD`、`PB_SIGNING_KEY_PASSWORD` 和 `PB_SIGNING_KEY_ALIAS` 后运行 Gradle，keystore 类型为 PKCS12。

## 恢复与长期保管

- 保存 keystore 的加密离线备份，并通过系统 Keychain 的安全方式备份/管理口令；两者缺一不可。
- 同机的恢复副本只能抵御文件误删，不能抵御设备丢失或磁盘损坏；不得将其冒充离线备份。
- 密钥及口令备份由维护者保管，绝不上传仓库、Release、Issue 或公开报告。
- 恢复后先运行 certificate 核对指纹，确认身份一致再签名；不要因为密码丢失创建同名新密钥来冒充原发行身份。
- 初次对外分发前已核对安装包签名与上述指纹。未来签名变化必须单独规划用户升级，不能直接替换。

正式安装包为 `app/build/outputs/apk/release/app-release.apk`。测试 APK 不属于发行附件。
