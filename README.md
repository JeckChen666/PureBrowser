# PureBrowser

以网页视频发现与下载为核心的 Android 浏览器。当前已完成第一轮产品化（T1–T7）：可安装、可操作的浏览器产品骨架。资源/下载中心与本地视频库的完整产品化属于下一轮，还不是完整 0.1 版。

**产品硬约束：不做自有云端存储、云解析、云同步、账号或后端服务。** 浏览器仍正常联网访问网站。

**下一阶段**：资源中心、下载中心和本地视频库；之后实现限定范围的 HLS 与必要登录访问上下文。第一轮完成记录见 `docs/T1-T7-COMPLETION.md`。

## 当前已实现

- 正式首页、浅/深色与跟随系统主题、本地图标、菜单、设置与关于页。
- 多标签 WebView 浏览器：新建 / 切换 / 关闭、普通链接长按在新标签打开；地址 / 搜索、前进后退、停止 / 刷新、网络错误重试。
- 每个标签独立页面引擎和资源集合；最多保留三个活跃页面会话，回收的页面按保存网址重新加载。最多 50 个标签记录。
- 本地书签添加 / 取消、搜索、名称和网址编辑 / 删除；历史记录、搜索、单条删除 / 清空确认；首页快捷站点添加 / 长按编辑 / 删除。
- 标签网址 / 标题 / 选中标签、书签、历史、快捷站点和主题以 AtomicFile 写入应用私有目录；损坏时尝试备份，备份失败不覆盖唯一副本。无上传。
- 资源候选：被动请求观测、下载回调、只读 DOM video/source 和 Resource Timing 辅助采集。
- MP4 / WebM 等候选、HLS / DASH / blob 的明确分类；签名 query 保留、来源合并、页面切换清理、候选数量上限。
- 忽略常见 TS / M4S 和明显 init/chunk/segment MP4 分片。复杂分片命名仍可能误报，不宣称无遗漏。
- 公开视频直链：用户确认、默认仅 Wi-Fi、系统 DownloadManager、持久化任务记录、进度、错误、取消/删除确认、文件打开。
- 成品初检：支持的文件头、视频轨和可读取样本；不是完整的时长、声画与文件完整性校验。
- NavigationEvent 网页历史返回；安全 URL 限制、无原生网页桥、生产版不放行明文 HTTP。

## 当前未实现

完整本地视频库、资源/下载中心全面改造、HLS / DASH 下载与合并、登录态下载、逐跳 Referer / Cookie / Authorization 管理、手动暂停、自定义后台传输器、完整 Service Worker / 跨域 iframe / MSE 关联、无痕隔离、DRM。
HLS / DASH / blob 只显示候选和限制，不把清单或本地 blob 当成可直接保存的视频。
系统 DownloadManager 只用于公开直链，不附带敏感凭据，避免其自动重定向无条件转发鉴权信息。

## 构建与测试

```zsh
cd /Users/macos/Code/PureBrowser
source "$HOME/.config/android-dev/env.zsh"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# UI 测试会编辑本地浏览数据；只在专用、可丢弃的测试模拟器执行。
# 有真实数据时先备份，不以卸载目标应用来重置测试。
./gradlew :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.example.purebrowser.test/androidx.test.runner.AndroidJUnitRunner
```

应用 ID / namespace：`com.example.purebrowser`（临时）；minSdk 26，compileSdk / targetSdk 36。
AGP 9.0.1，Gradle 9.1.0，Compose compiler 2.3.20，NavigationEvent 1.0.2。
本机 SDK 为 `/Users/macos/Library/Android/sdk`；JDK 25 运行 Gradle，JDK 17 编译。
`local.properties` 为本机路径，不应提交到版本控制。

## 安装运行

```zsh
android --no-metrics emulator start Pixel_10_Pro_XL
android --no-metrics run --device=emulator-5554 --apks=app/build/outputs/apk/debug/app-debug.apk --activity=com.example.purebrowser.MainActivity
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。也可在 Android Studio 打开本目录运行 app。

## 可重复的本地测试页

```zsh
python3 tools/fixtures/serve_video_fixture.py
```

需要开发机已有 ffmpeg，生成两秒测试画面和音频，不使用第三方视频；这不是应用依赖。
建议先执行 `adb -s emulator-5554 reverse tcp:8765 tcp:8765`，在模拟器内打开 `http://127.0.0.1:8765/`。
也可用 `http://10.0.2.2:8765/` 观察页面，但本机模拟器的系统下载服务访问该别名发生过连接超时；文件下载回归使用 adb reverse。
只有 Debug 构建对这两个本机测试地址允许 HTTP；生产版没有例外，服务器只绑定本机回环地址。
页面含签名 MP4、HLS、DASH、分片和假 MP4（HTML 响应），用于验证签名保留、候选过滤及失败说明。

## 项目文档

- `docs/SOURCE-RESEARCH.md`：GitHub 参考项目、许可证、固定提交、采用与不采用的设计。
- `docs/T1-T7-COMPLETION.md`：第一轮交付、验收与边界。
- `docs/IMPLEMENTATION-STATUS.md`：当前测试和功能边界。
- `docs/PRODUCT-PLAN.md`：已确认的纯本地、产品化优先方向；协议增强后置。
- `docs/EXECUTION-PLAN.md`：第一轮 T1–T7 的已完成执行清单。
- `docs/EXECUTION-PLAN-ROUND-2.md`：第二轮 T8–T14 资源/下载/视频库产品化计划，尚未实施。
- `docs/SECURITY-BASELINE.md`：本次安全对齐说明与 Manifest diff。
- `docs/DEVELOPMENT-ENVIRONMENT.md`：最初环境准备的历史记录。
- `CONTEXT.md`：资源线索、视频候选、下载任务、成品等术语。
- `.agents/ANDROID-SKILLS.md`：25 个项目级 Android 技能安装记录。

只下载自有或获授权的内容，不绕过 DRM 或访问控制。本版本不能保证所有网站兼容，且尚未进行真机及大文件后台验证。
