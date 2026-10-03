# PureBrowser

以网页视频发现与授权保存为核心的纯本地 Android 浏览器。**v0.1.3 小范围 Pre-release**：浏览 → 发现 → 受控下载 → 视频库 → 打开/分享 → 文件管理。

不提供自有云存储、云解析、云同步、账号、遥测或后端；仍可联网访问用户选择的网站。

## 获取与开源

- [GitHub 源码](https://github.com/JeckChen666/PureBrowser) · [v0.1.3 APK / Release](https://github.com/JeckChen666/PureBrowser/releases/tag/v0.1.3)。
- Apache-2.0，见 [LICENSE](LICENSE)、[NOTICE](NOTICE)。贡献与安全说明见 [CONTRIBUTING.md](CONTRIBUTING.md)、[SECURITY.md](SECURITY.md)。
- 正式包：`io.github.jeckchen666.purebrowser`；Debug 包：`io.github.jeckchen666.purebrowser.debug`。最低 Android 8.0 / API26。
- **v0.1.0 的 `com.example.purebrowser` 不会被覆盖，也不自动迁移其私有数据/网站登录。** 后续正式版本沿同包同签名升级。
- [实现与验收](docs/V0.1.3-COMPLETION.md) · [后续版本路线图](docs/VERSION-ROADMAP-0.1.md)。

## v0.1.3：可恢复的本地下载

发行身份 **0.1.3 / versionCode 10**，沿用 v0.1.1 的正式包名与长期签名。

- 有强资源身份的直链受控 Range 续传；完整 HLS 分片检查点、清单/序号核对和中断后重新封装。
- 暂停、取消、继续、另建下载语义分离；冷启动先对账，由用户主动恢复，不静默启动下载。
- schema v5 备份迁移、停止原因/缓存反馈、通知动作、本地隐私分项清理与主动脱敏诊断。
- 修复导航后扫描占位和弹窗焦点/键盘问题，降低大文件传输的重复元数据开销。
- [发行说明](docs/releases/v0.1.3.md) · [最终验收摘要](docs/V0.1.3-COMPLETION.md) · [用户指南](docs/USER-GUIDE.md) · [隐私说明](docs/PRIVACY.md)。
- 129 JVM、候选生产代码对应的 API37 单批 225 项、API28/36 专项已验证；最终签名包另验证升级、浏览入口、真实跨 UID 分享及三个获授权 HTTPS 作品。历史候选与最终包的证据边界见验收摘要。
- **主要在模拟器验证，OEM 后台行为待补测**；真机、多站点大样本和多人长期试用不作为本次 Pre-release 的阻断项，不声称全机型/全网站稳定。

## v0.1.2 阶段新增（当时未单独发行，能力纳入 v0.1.3）

- 未加密、已结束的 MPEG-TS / H.264 + 同组 AAC HLS 点播：显式解析、选择档位、下载并本地封装为独立 MP4。
- 默认优先不超过1080p；清单和每个分片均由现有受控请求策略联网，不转发跨源网站 Cookie。
- 每任务2片并发，临时网络错误有限重试；分片进度与封装阶段分开，不把100%分片当成已保存。
- schema v4 备份迁移、任务私有工作目录、完整校验后公共发布、取消/进程终止对账。
- 仅必要 Media3 extractor/muxer组件，无FFmpeg运行时、无新播放器/云服务。
- 实绩与未满足门槛见 [候选验收](docs/V0.1.2-CANDIDATE.md)；测试站点见 [HLS fixtures](docs/HLS-FIXTURES.md)。不把候选称为已发布v0.1.2。

## v0.1.1 新增

- 活跃页面自动扫描、延迟视频和受控同源 iframe、播放优先、多证据合并与导航隔离。
- 全部新任务使用受控 GET，2 个并发槽、前台通知、取消/重新下载与中断对账。
- MP4/WebM 直链及无后缀可确认视频；确认页可使用或关闭适用网站条件，同源会话和最小来源，凭据不跨源、不写入任务/日志。
- schema v3 与稳定 TaskId，原数据备份迁移；初检后发布到公共 `Download/PureBrowser`，实际读取/分享与归属验证。
- 正式 Release 签名、本地 Keychain 密码与独立发行身份；无云服务或新运行时框架。

以下基础能力自 v0.1.0 延续；涉及“系统下载”的描述仅适用于历史任务，不代表新任务仍使用 DownloadManager。

## 当前已实现

- 正式首页、浅/深色与跟随系统主题、本地图标、菜单、设置与关于页。
- 多标签 WebView 浏览器：新建 / 切换 / 关闭、普通链接长按在新标签打开；地址 / 搜索、前进后退、停止 / 刷新、网络错误重试。
- 每个标签独立页面引擎和资源集合；最多保留三个活跃页面会话，回收的页面按保存网址重新加载。最多 50 个标签记录。
- 本地书签添加 / 取消、搜索、名称和网址编辑 / 删除；历史记录、搜索、单条删除 / 清空确认；首页快捷站点添加 / 长按编辑 / 删除。
- 标签网址 / 标题 / 选中标签、书签、历史、快捷站点和主题以 AtomicFile 写入应用私有目录；损坏时尝试备份，备份失败不覆盖唯一副本。无上传。
- 资源候选：被动请求观测、下载回调、只读 DOM video/source 和 Resource Timing 辅助采集。
- MP4 / WebM 等候选、HLS / DASH / blob 的明确分类；签名 query 保留、来源合并、页面切换清理、候选数量上限。
- 忽略常见 TS / M4S 和明显 init/chunk/segment MP4 分片。复杂分片命名仍可能误报，不宣称无遗漏。
- 公开视频直链：用户确认、默认仅 Wi-Fi、应用受控传输、持久化任务记录、进度、错误、取消/删除确认、文件打开。
- 资源中心分区、来源快照、可编辑安全文件名与单次网络选项；下载分组、诚实未知状态、取消与另建关联重试。
- 私有版本化元数据/旧格式一次迁移；本地视频库、搜索排序、真实缩略图/元信息、显示标题修改、来源恢复。
- 实际文件打开/分享的临时只读授权；仅忘记记录保留文件，删除文件必须确认；进程重启对账与本地默认 Wi-Fi 设置。
- 成品初检：支持的文件头、视频轨和可读取样本；不是完整的时长、声画与文件完整性校验。
- NavigationEvent 网页历史返回；安全 URL 限制、无原生网页桥、生产版不放行明文 HTTP。

## 当前未实现

加密HLS、直播/未结束清单、fMP4/CMAF、BYTERANGE、独立音轨/字幕合并、时间轴不连续、DASH、DRM、复杂跨站/分区会话和 JS Token、完整 Service Worker / 跨域 iframe / MSE 关联、真正无痕。
受支持的HLS可转换为MP4；不支持的HLS、DASH和blob说明限制，不直接保存清单或blob冒充视频。
历史 DownloadManager 任务只对账与管理；新任务不交给系统下载器。正式版仅 HTTPS，Debug 仅指定回环测试地址例外。

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
git checkout v0.1.3
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# UI 测试会编辑本地浏览数据；只在专用、可丢弃的测试模拟器执行。
# 有真实数据时先备份，不以卸载目标应用来重置测试。
# 下方 emulator-5554 仅为示例；用 adb devices 确认专用测试设备序列号后替换。
./gradlew :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w io.github.jeckchen666.purebrowser.debug.test/androidx.test.runner.AndroidJUnitRunner
```

版本：0.1.3，versionCode=10；正式签名与旧 Debug 安装身份不同，不作跨包自动迁移。
正式 applicationId：`io.github.jeckchen666.purebrowser`，namespace：`com.example.purebrowser`；minSdk 26，compileSdk / targetSdk 36。
AGP 9.0.1，Gradle 9.1.0，Compose compiler 2.3.20，NavigationEvent 1.0.2。
`local.properties` 为各开发者的本机路径，不提交到版本控制；历史环境文档仅作验收环境记录，不是跨机器配置模板。

## 安装运行

在 Android Studio 创建自己的模拟器或连接测试手机；完成 Debug 构建后安装：

```sh
adb devices
# 替换为自己的测试设备序列号：
export ANDROID_SERIAL=emulator-5554
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" shell am start -n io.github.jeckchen666.purebrowser.debug/com.example.purebrowser.MainActivity
```

多设备时必须指定实际 serial。项目的 `android` CLI 技能是可选开发辅助；示例机型与模拟器编号不要求其他开发者保持一致。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。正式签名构建：`python3 tools/release/local_signing.py build`，产物 `app/build/outputs/apk/release/app-release.apk`；详见 `docs/RELEASE-SIGNING.md`。

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

- [UI 设计方向：紧凑版浏览器 V4](docs/design/compact-browser-2026-10-03/README.md)：15 个界面总览、顶部网址／底部操作、交互规则及后续验收清单；仅设计方向，尚未实施。

- `docs/releases/v0.1.3.md`：本次正式签名公开测试版的发行说明。
- `docs/V0.1.3-COMPLETION.md`：最终包身份、验收与证据复用边界。
- `docs/releases/v0.1.0.md`：首个开源源码预览的历史发布说明。
- `docs/SOURCE-RESEARCH.md`：GitHub 参考项目、许可证、固定提交、采用与不采用的设计。
- `docs/T1-T7-COMPLETION.md`：第一轮交付、验收与边界。
- `docs/IMPLEMENTATION-STATUS.md`：当前测试和功能边界。
- `docs/PRODUCT-PLAN.md`：已确认的纯本地、产品化优先方向。
- `docs/VERSION-ROADMAP-0.1.md`：v0.1.0–v0.1.3 建议版本范围、用户效果、发布与发行验收门槛；历史规划与当前交付状态分别标注。
- `docs/EXECUTION-PLAN.md`：第一轮 T1–T7 的已完成执行清单。
- `docs/EXECUTION-PLAN-ROUND-2.md`：第二轮 T8–T14 的已完成执行清单。
- `docs/ROUND-2-STATUS.md`：Git 基线、T8 增量与验证边界。
- `docs/SECURITY-BASELINE.md`：本次安全对齐说明与 Manifest diff。
- `docs/DEVELOPMENT-ENVIRONMENT.md`：最初环境准备的历史记录。
- `CONTEXT.md`：资源线索、视频候选、下载任务、成品等术语。
- `.agents/ANDROID-SKILLS.md`：25 个项目级 Android 技能安装记录。

只下载自有或获授权的内容，不绕过 DRM 或访问控制。本版本不能保证所有网站兼容；大文件和后台已在开发机验证，尚无 OEM 真机兼容与多人长期试用证据。

## v0.1.0 历史验证

历史 v0.1.0：33 项单元测试，API37 上 75 项完整回归 + 4 项独立功能验收通过；Lint 0 错误/22 提醒。API28 另做允许/拒绝旧版权限、真实旧 APK 覆盖迁移、小屏与外部播放器烟测。真机/长视频后台/全版本矩阵不在此结论内。全量 UI 测试会修改状态，请按完成文档在专用设备或备份后运行；清理只针对测试样本 ID。

APK：`app/build/outputs/apk/debug/app-debug.apk`；证据与截图：`app/build/reports/round2-product/`。构建产物不提交 Git。

验收文档中的绝对路径、设备编号和本地报告目录是当时的开发环境记录。原始构建报告、截图和私有备份没有提交到公开仓库；请在自己的环境重新构建与验证。

## v0.1.1 最终验证

57 单元 / 91 API37 无跳过全回归；API28 公共文件、队列、实际权限拒绝与 host 强停恢复；API36 Cookie/来源/动态同源 frame、Signed Release 10 授权视频/3 HTTPS 环境及签名升级；API37 实际 chooser MP4/WebM 跨 UID 读取与最终核心 15 分钟锁屏传输。Lint 0 错误/28 提醒。

上述不是全网站或真机长电影认证。先前失败尝试保留，本轮限定结果详见 `docs/V0.1.1-COMPLETION.md`；证据目录为本地 `app/build/reports/v0.1.1/`。发行 APK 不包含测试接收器、测试媒体或 HTTP 例外。
