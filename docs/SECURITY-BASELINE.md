# Best Practices and Security Alignment Update

日期：2026-10-01。作用域为本次基础代码，不代表完整安全审计。

## Security alignment area

WebView 隔离、Safe Intent Redirection、最小权限、凭据与备份隔离。

## Impact and priority

高：不向网页暴露原生桥，不执行网页提供的嵌套 Intent，不绕过证书；下载器不附带可跨域泄漏的 Cookie/Authorization。
中：生产版禁止明文 HTTP，只有 Debug 的模拟器主机 10.0.2.2 和 adb reverse 的 127.0.0.1 例外；旧版存储权限最多到 API 28。

## Scope of changes

- app/src/main/AndroidManifest.xml
- app/src/main/res/xml/network_security_config.xml
- app/src/debug/res/xml/network_security_config.xml
- browser/BrowserAddress.kt、browser/BrowserEngine.kt
- download/DownloadRepository.kt、ui/browser/BrowserScreen.kt
- 没有新增自定义导出 Service / Receiver / Provider；Launcher Activity 保留 exported=true。

## Implementation summary

- 只允许普通 http/https 网页导航，拒绝 file/data/javascript/intent 与带 userinfo 的 URL。
- 关闭 WebView file/content access、混合内容和第三方 Cookie；证书异常保持默认拒绝。
- 唯一 JavaScript 是只读采集，采集结果有数量、长度和页面代次边界；没有 addJavascriptInterface。
- 打开下载文件只使用 DownloadManager 提供的 URI，授予临时读取权限；不解析或转发网页提供的嵌套 Intent。
- 下载由用户确认；删除任务和文件有二次确认；不自动启动网站提供的 APK 或外部应用。
- 限制下载记录备份，不在普通列表显示带签名的 query，不在生产日志中记录媒体 URL。
- 下载只作容器/视频轨样本初检，不宣称可阻止所有恶意媒体或保证完整文件校验。

## Implementation diff

```diff
--- before/app/src/main/AndroidManifest.xml
+++ after/app/src/main/AndroidManifest.xml
@@ -1,8 +1,13 @@
 <?xml version="1.0" encoding="utf-8"?>
 <manifest xmlns:android="http://schemas.android.com/apk/res/android">
 
+    <uses-permission android:name="android.permission.INTERNET" />
+    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
+    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
+
     <application
-        android:allowBackup="true"
+        android:allowBackup="false"
+        android:networkSecurityConfig="@xml/network_security_config"
         android:icon="@mipmap/ic_launcher"
         android:label="@string/app_name"
         android:roundIcon="@mipmap/ic_launcher_round"
```

新增安全配置：

```diff
--- /dev/null
+++ app/src/main/res/xml/network_security_config.xml
@@ -0,0 +1,4 @@
+<?xml version="1.0" encoding="utf-8"?>
+<network-security-config>
+    <base-config cleartextTrafficPermitted="false" />
+</network-security-config>
```

## Testing and verification

单元测试覆盖危险 URL scheme、userinfo、超长输入、签名去重和旧页面回调；构建 / Lint / 仪器测试及本地视频结果记录在 IMPLEMENTATION-STATUS.md。
尚未做全面渗透测试、恶意媒体模糊测试、复杂 iframe / Service Worker 隔离测试。


## T1–T7 产品化追加（2026-10-01）

- 标签会话各自拥有 BrowserEngine / ResourceSniffer；保留页面代次过滤。标签独立资源集合不等于 Cookie、账号或无痕隔离；普通标签共享 WebView 网站会话。
- 新增浏览数据仅写入应用私有目录 `files/browser-state.json`，使用 schemaVersion、稳定 ID、容量边界、IO 串行写入及 AtomicFile。损坏文件备份失败时禁止覆盖；不新增日志、统计或上传端点。
- URL 长度与 scheme 限制继续用于本地书签和快捷站点；列表显示主机而非媒体签名 query。普通链接的新标签操作仍需用户主动长按确认，不处理任意外部 scheme。
- Manifest 仅为 Launcher Activity 增加 `launchMode="singleTask"`，避免重复 Activity 持有不同数据写入器；未增加导出 IPC 组件、权限或三方依赖。
- 原 DownloadManager 和 download_records 边界保留；关闭浏览标签不删除独立任务，删除记录/文件需确认。没有增加 Cookie / Authorization 转发，也没有放宽生产网络安全策略。
- 本轮测试与数据恢复证据见 T1-T7-COMPLETION.md；这不是完整安全审计、真机后台保证或 DRM 绕过能力。


## T8 追加：私有下载记录与源上下文保护

- 新下载元数据写入私有 AtomicFile，与浏览文件/旧下载 XML 分开；容量及关系校验在提交前执行。未来 schema 只读，损坏备份失败不覆盖。
- 仓储只查询本应用记录的系统 ID，不枚举其他下载或扫描手机文件；成品 URI 限制为对应系统下载 ID 的 downloads 内容 URI。
- 选择时冻结来源/UA/页面代次；签名 query 原样保存，DTO 日志表示及测试失败诊断不输出敏感地址或 UA。
- 本轮新增任务明确限制为 HTTPS；Debug 仅允许已有本地 fixture 主机的 HTTP，不放行生产明文下载。仍不附带 Cookie/Authorization，也不保证系统下载器每跳可控。
- 文件缺失/读取拒绝/系统状态不可确认分别处理；格式初检不等同于完整媒体或安全保证。迁移不调用 enqueue/remove，存储提交失败的回滚只针对本次创建的任务。
- 未新增 Manifest 导出组件、权限、后台服务、运行时依赖或云端接口。受控故障、真实系统样本与迁移证据见 ROUND-2-STATUS.md；不是完整安全审计或 API26–28 真机保证。

## T9–T14：文件闭环与权限边界

- 不增加应用运行时依赖、导出组件、全盘/全媒体权限、常驻服务或自有网络端点。API26–28 仍沿用公共 Download 写入权限，拒绝不创建任务。
- 资源提交冻结来源并检查当前候选有效；仅公开直链，原始 query 保留，常规卡片不展示签名。建议文件名过滤路径/控制字符，按码点与 UTF-8 字节留出唯一前缀空间。
- 仓储逐条只访问受跟踪 ID；文件操作再核对存储记录、对应 downloads URI、当前可读取性和有限格式初检。VIEW/SEND 为新构造 Intent，实际内容 URI、ClipData、临时只读授权，不转发网页 Intent、远程媒体链接或额外写入/持久/前缀授权。
- 分享使用系统选择器，调用返回不等于接收成功。跨 UID 样本接收器仅在测试 APK，实际读文件验 SHA；完成后卸载测试 APK，不打入产品 APK。
- 仅忘记记录从同一事务中移除任务/文件索引，不调用系统删除、不自动重新导入旧 XML。活动/状态未确认任务不能被忘记成孤立传输。取消和终态文件删除是分开的确认。
- 删除已知文件持有由受验证 URI 打开的描述符，核对解除链接证据或可确认的消失；不把系统行移除或授权撤销当作物理删除成功，不采用外来绝对路径删除文件。删除无法确认/被拒绝或元数据写入失败不会虚报完成。
- 初次无法解析 URI 与确证曾可读取文件的丢失分别处理；拒绝/未知不假称可播放。刷新读取/发布串行，避免旧快照复活已忘记记录。缩略图只读本地已验证文件、串行 IO、4 MiB 内存缓存，不引入网络解析。
- 当前短样本、API28/API37、受控异常与权限测试不构成完整安全审计、恶意媒体/长视频保证或逐跳受控传输器验证；证据见 ROUND-2-COMPLETION.md。


## v0.1.1 安全对齐（2026-10-02）

独立正式/Debug 身份；全部新传输逐跳 HTTPS 校验、限定同源会话/最小来源、Cookie不保存不跨源，原生桥与 TLS 绕过仍不存在。dataSync Service 和 FileProvider 不导出，通知 PendingIntent不可变，分享只读临时 URI；新资产按存储种类、记录、owner/path/name验证，任意外部 provider 不查询/删除。仅API26–28读写存储，现代版本不申请广泛媒体读取。云备份与设备传输显式排除私有数据；信任/HTTP测试例外只在Debug。原上游技能原样保留，签名密钥/Keychain口令不进仓库。

容器/轨道初检不是安全审计或全片校验。真实测试中的模拟器SystemUI ANR保留，不宣称全机型分享兼容。当前结果以V0.1.1-COMPLETION.md为准。
