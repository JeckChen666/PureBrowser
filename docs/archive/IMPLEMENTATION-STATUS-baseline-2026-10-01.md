# 基础代码实现与验证

日期：2026-10-01（Asia/Shanghai）。按用户要求先实现基础代码，再构建和测试。

## 已实现

单 WebView 的网页访问/搜索/历史返回/刷新/首页；加载错误；媒体候选面板；公开视频直链系统下载；任务持久化和删除确认；文件打开；有限格式初检。
资源观测覆盖请求、下载回调、只读 DOM video/source、Resource Timing。
保留签名 query，合并来源，页面代次抑制旧 DOM 回调，过滤常见分片，限制候选和采集结果大小。
优先展示页面视频元素关联的候选，不把 HLS / DASH / blob 假装成已经支持下载的文件。

## 验证结果

- assembleDebug：通过。
- 单元测试：11 项，0 失败，0 错误。
- 仪器测试：4 项通过，0 失败，0 跳过（启用了本地 fixture 集成测试）。
- Lint：0 错误；21 项非阻断提醒，详细报告在 app/build/reports/lint-results-debug.html。
- 本地网页真实加载，观察到 MP4（带签名）、HLS、DASH，以及假 MP4 共 4 个候选，TS / init.mp4 被排除。
- 用户确认下载操作通过 Compose 语义节点测试，不绕过实际 UI 或直接调用下载器来代替操作。
- 实际下载的测试 MP4 通过文件头、视频轨/样本初检。
- 成品 SHA-256 与自建原视频一致：`0b56a9c4b987e55d46960a097b6c3e89561d9ebac5d10d245ea438832368c84e`。
- 测试视频为 ffmpeg 本地生成的两秒画面和音频，未使用第三方版权视频。

## 回归命令

先运行 tools/fixtures/serve_video_fixture.py，再执行：

```zsh
source "$HOME/.config/android-dev/env.zsh"
adb -s emulator-5554 reverse tcp:8765 tcp:8765
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.videoFixture=true \
  -Pandroid.testInstrumentationRunnerArguments.fixtureSha256=<服务器启动时打印的 SHA256>
```

默认 connectedDebugAndroidTest 不启用本地服务器测试（该测试会跳过），仍执行 3 个基础界面测试。
集成测试默认访问 http://127.0.0.1:8765，必须建立 adb reverse；fixtureBaseUrl 可指定其他已允许的 Debug 测试地址。

## 测试过程中修正与发现

- 校正输入法、网页返回处理和资源面板展开/焦点行为。
- 限制 DOM 回传大小，并将页面视频元素关联的资源排在前面。
- 本机模拟器的系统下载服务访问 10.0.2.2:8765 出现连接超时；WebView 能加载同一页面。
- 使用 adb reverse 的设备回环地址完成下载回归。此测试环境差异未通过放开所有 HTTP、忽略证书或修改生产安全策略规避。
- 没有把格式初检说成完整文件/声画校验，也没有把候选说成必然可下载。

## 明确未实现或未验证

- HLS / DASH 分片下载、合并、清晰度和音轨选择。
- Cookie / Authorization / Referer 的登录态下载、逐跳凭据处理。
- 多标签、历史书签、真正隔离的无痕、独立预览播放器。
- 完整 Service Worker / 跨域 iframe / MSE 映射、所有分片命名识别。
- 手动暂停/自定义续传器、长视频后台、断网恢复、真机、不同系统版本。
- 假 MP4 的拒绝逻辑已有实现，但本轮没有执行它的单独下载端到端测试。
- DRM、付费/授权绕过不属于范围。

## 核心文件

- browser/BrowserEngine.kt、browser/BrowserAddress.kt
- media/MediaCandidate.kt、media/ResourceSniffer.kt
- download/DownloadRepository.kt
- ui/browser/BrowserScreen.kt、ui/browser/BrowserViewModel.kt

路径以上均相对 app/src/main/java/com/example/purebrowser。
开源调研及固定源码链接见 SOURCE-RESEARCH.md；未复制 GPL 项目代码，也未整包集成旧下载 SDK。
优先级已按用户要求调整：下一轮先做首页与视觉、多标签、本地书签历史、资源/下载产品化及必要设置。HLS 点播子集和登录访问上下文紧随产品化版本，而不是当前前置门槛。应用不增加自有云端存储、解析、同步、账号或后端服务；当前测试与能力记录不因规划调整而改变。
