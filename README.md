# PureBrowser

以网页视频发现与保存为核心的 Android 浏览器。**T1–T14 已完成，当前为 0.1.0 本地产品预览版（versionCode=2）**：资源中心 → 任务 → 视频库 → 打开/分享 → 文件管理的闭环已验收，不是任意网站下载器。

**产品硬约束：不做自有云端存储、云解析、云同步、账号、遥测或后端服务。** 浏览器仍正常联网访问用户选择的网站。

交付范围与验收记录见 `docs/ROUND-2-COMPLETION.md`；后续按 v0.1.1 直链增强 → v0.1.2 HLS 成品 → v0.1.3 日常可用推进，见 `docs/VERSION-ROADMAP-0.1.md`。

## 开源与版本

- GitHub：[JeckChen666/PureBrowser](https://github.com/JeckChen666/PureBrowser)。
- 里程碑：`v0.1.0`；[源码预览 Release](https://github.com/JeckChen666/PureBrowser/releases/tag/v0.1.0)，发布说明见 `docs/releases/v0.1.0.md`。
- 原创项目代码采用 [Apache-2.0](LICENSE)；上游技能和依赖保留各自许可证，见 [NOTICE](NOTICE)。
- 本次公开源码，不提供正式安装包；既有本地 APK 为 Debug 预览，正式包名、签名及升级过渡尚待确认。
- 贡献说明见 [CONTRIBUTING.md](CONTRIBUTING.md)，漏洞反馈见 [SECURITY.md](SECURITY.md)。

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
- 资源中心分区、来源快照、可编辑安全文件名与单次网络选项；下载分组、诚实未知状态、取消与另建关联重试。
- 私有版本化元数据/旧格式一次迁移；本地视频库、搜索排序、真实缩略图/元信息、显示标题修改、来源恢复。
- 实际文件打开/分享的临时只读授权；仅忘记记录保留文件，删除文件必须确认；进程重启对账与本地默认 Wi-Fi 设置。
- 成品初检：支持的文件头、视频轨和可读取样本；不是完整的时长、声画与文件完整性校验。
- NavigationEvent 网页历史返回；安全 URL 限制、无原生网页桥、生产版不放行明文 HTTP。

## 当前未实现

HLS / DASH 下载与合并、登录态下载、逐跳 Referer / Cookie / Authorization 管理、手动暂停、自定义后台传输器、完整 Service Worker / 跨域 iframe / MSE 关联、无痕隔离、DRM。
HLS / DASH / blob 只显示候选和限制，不把清单或本地 blob 当成可直接保存的视频。
系统 DownloadManager 只用于公开直链，不附带敏感凭据，避免其自动重定向无条件转发鉴权信息。

## 构建与测试

### 环境准备

- Android Studio 或已配置的 Android SDK（Platform 36、Build Tools 36.0.0）；设备最低 Android 8.0 / API26。
- 当前验收环境使用 JDK 25 运行 Gradle、JDK 17 编译；项目配置了 Java 17 toolchain。
- 在 Android Studio 中打开克隆后的仓库并配置 SDK，或设置自己的 `ANDROID_HOME` / `local.properties`；不要复制历史文档里的开发机路径。
- 使用仓库的 Gradle Wrapper，不需要把官方 Android Skills 或 `android` CLI 当作应用构建前置条件。
- Gradle 首次构建会下载依赖，Java toolchain resolver 也可能下载所需 JDK；这些构建下载不等于应用包含云端业务。

```sh
git clone https://github.com/JeckChen666/PureBrowser.git
cd PureBrowser
git checkout v0.1.0
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# UI 测试会编辑本地浏览数据；只在专用、可丢弃的测试模拟器执行。
# 有真实数据时先备份，不以卸载目标应用来重置测试。
# 下方 emulator-5554 仅为示例；用 adb devices 确认专用测试设备序列号后替换。
./gradlew :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.example.purebrowser.test/androidx.test.runner.AndroidJUnitRunner
```

版本：0.1.0（versionCode=2，替换模板默认值；沿用原包名与 Debug 签名覆盖安装）。
应用 ID / namespace：`com.example.purebrowser`（临时）；minSdk 26，compileSdk / targetSdk 36。
AGP 9.0.1，Gradle 9.1.0，Compose compiler 2.3.20，NavigationEvent 1.0.2。
`local.properties` 为各开发者的本机路径，不提交到版本控制；历史环境文档仅作验收环境记录，不是跨机器配置模板。

## 安装运行

在 Android Studio 创建自己的模拟器或连接测试手机；完成 Debug 构建后安装：

```sh
adb devices
# 替换为自己的测试设备序列号：
export ANDROID_SERIAL=emulator-5554
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" shell am start -n com.example.purebrowser/.MainActivity
```

多设备时必须指定实际 serial。项目的 `android` CLI 技能是可选开发辅助；示例机型与模拟器编号不要求其他开发者保持一致。

APK：`app/build/outputs/apk/debug/app-debug.apk`。也可在 Android Studio 打开本目录运行 app。

## 可重复的本地测试页

```zsh
python3 tools/fixtures/serve_video_fixture.py
```

需要开发机已有 ffmpeg，生成两秒测试画面和音频，不使用第三方视频；这不是应用依赖。
建议先执行 `adb -s "$ANDROID_SERIAL" reverse tcp:8765 tcp:8765`（先设置实际设备序列号），在模拟器内打开 `http://127.0.0.1:8765/`。
也可用 `http://10.0.2.2:8765/` 观察页面，但本机模拟器的系统下载服务访问该别名发生过连接超时；文件下载回归使用 adb reverse。
只有 Debug 构建对这两个本机测试地址允许 HTTP；生产版没有例外，服务器只绑定本机回环地址。
页面含签名 MP4、HLS、DASH、分片和假 MP4（HTML 响应），用于验证签名保留、候选过滤及失败说明。

## 项目文档

- `docs/releases/v0.1.0.md`：首个开源源码预览的发布说明。
- `docs/SOURCE-RESEARCH.md`：GitHub 参考项目、许可证、固定提交、采用与不采用的设计。
- `docs/T1-T7-COMPLETION.md`：第一轮交付、验收与边界。
- `docs/IMPLEMENTATION-STATUS.md`：当前测试和功能边界。
- `docs/PRODUCT-PLAN.md`：已确认的纯本地、产品化优先方向。
- `docs/VERSION-ROADMAP-0.1.md`：v0.1.0–v0.1.3 建议版本范围、用户效果、发布与发行验收门槛；后三版尚未实现。
- `docs/EXECUTION-PLAN.md`：第一轮 T1–T7 的已完成执行清单。
- `docs/EXECUTION-PLAN-ROUND-2.md`：第二轮 T8–T14 的已完成执行清单。
- `docs/ROUND-2-STATUS.md`：Git 基线、T8 增量与验证边界。
- `docs/SECURITY-BASELINE.md`：本次安全对齐说明与 Manifest diff。
- `docs/DEVELOPMENT-ENVIRONMENT.md`：最初环境准备的历史记录。
- `CONTEXT.md`：资源线索、视频候选、下载任务、成品等术语。
- `.agents/ANDROID-SKILLS.md`：25 个项目级 Android 技能安装记录。

只下载自有或获授权的内容，不绕过 DRM 或访问控制。本版本不能保证所有网站兼容，且尚未进行真机及大文件后台验证。

## 本轮验证

33 项单元测试，API37 上 75 项完整回归 + 4 项独立功能验收通过；Lint 0 错误/22 提醒。API28 另做允许/拒绝旧版权限、真实旧 APK 覆盖迁移、小屏与外部播放器烟测。真机/长视频后台/全版本矩阵不在此结论内。全量 UI 测试会修改状态，请按完成文档在专用设备或备份后运行；清理只针对测试样本 ID。

APK：`app/build/outputs/apk/debug/app-debug.apk`；证据与截图：`app/build/reports/round2-product/`。构建产物不提交 Git。

验收文档中的绝对路径、设备编号和本地报告目录是当时的开发环境记录。原始构建报告、截图和私有备份没有提交到公开仓库；请在自己的环境重新构建与验证。
