# T9–T14 完成与 0.1.0 验收

状态：**T9–T14 已完成。** 本轮在同一项目、同一包名上完成纯本地产品闭环。版本为 `0.1.0`，versionCode 从模板的 1 递增至 2；原模板 `1.0` 并非已发行产品版本。

## 已交付的功能

| 任务 | 实现 |
|---|---|
| T9 资源中心 | 当前标签资源计数、DOM 直链优先、可尝试直链与折叠未支持资源分区、真实主机/类型/已知大小、详情保留原始签名 URL 与证据。确认时可改安全文件名与网络选项，冻结来源/UA/标签/页面代次；不探测、不自动下载。 |
| T10 下载中心 | 进行中/已完成/失败或需处理分组；排队、系统等待、未知计数与格式/可用性独立展示。确认取消、另建重试任务、保留旧记录/文件、重试关联、来源恢复、忙碌防重复提交。 |
| T11 本地视频库 | 只索引本应用记录且初检通过的视频，保留曾通过初检但已失效的条目；搜索、入库时间排序、真实时长/大小/日期、显示标题修改、异步本地缩略图和占位。缓存上限 4 MiB，串行提取，无网络解析。 |
| T12 文件操作 | 打开/分享实际内容 URI，临时只读授权，系统选择器；操作前复核。仅移除记录不删文件、不调用系统取消/删除；删除需实际文件消失证据，失败保留记录。匹配来源标签/URL，否则开新标签，不覆盖无关页面。 |
| T13 导航/设置 | 首页四个入口采用两列、真实最近保存；下载与视频库互通及一致返回路径。默认仅 Wi-Fi 单独本地持久化，只影响新任务；每次确认可覆盖。目录说明、真实版本、隐私/许可与能力边界。 |
| T14 验收/交付 | 现有 JUnit/Compose 与生成视频 fixture；原包名/签名覆盖安装、真进程重启、权限与迁移烟测；Debug APK、截图、证据与明确限制。未增加 DI、测试框架或应用运行时依赖。 |

## 关键准确性处理

- 传输成功不代表视频可用：HTML 伪装的 MP4 使用假的 `video/mp4` 响应类型，实际文件头初检拦截，不能进入正常视频库/播放/分享。
- 内容提供者初次返回 ENOENT，且本应用从未读到该文件时，不武断说文件丢失；保留为待确认。曾实际读过的文件丢失、读取拒绝与无法确认分别处理。
- 系统任务读取失败或不存在时，已传输字节也保持未知，不编造 0 字节、百分比、速度或剩余时间。
- 系统移除任务后会撤销 URI 授权，故读取失败不能单独证明物理删除。删除前持有已验证文件的描述符；操作后依据解除链接的描述符证据或可确认的目标消失，才同步移除记录。无法确认则保留，不以行删除计数假报成功。
- 刷新读取与发布串行，避免旧快照临时复活已移除记录。原子记录写入失败只回滚本次新系统任务。
- 建议文件名过滤路径/控制字符，按 Unicode 码点截断，并限制 UTF-8 字节，给唯一前缀留出空间。库内改名只改变显示标题。
- 分享启动不声称接收完成；验收另使用独立 UID 的测试接收器实际读取文件并验证 SHA-256。该接收器只在测试 APK 中，不进入应用 APK。

## 最终验证结果

**最终交付 APK：33 项单元测试通过；API37 的 75 项完整回归通过，另有 4 项独立实机模拟验收通过，共 79 项功能仪器测试；Lint 0 错误、22 提醒。** 清理维护命令不计入功能测试数。没有把失败尝试、重复运行或跳过项计作通过。

- `final-build.txt`：assembleDebug、testDebugUnitTest、lintDebug、assembleDebugAndroidTest。
- `final-instrumentation.txt`：75 项，包含 T1–T7 回归、原子/旧格式/损坏/中断/未来 schema、仓储、资源、下载/库及真实样本完整闭环；无跳过。
- `final-runtime-share.txt`：2 项。实际系统选择器 → 独立测试 APK UID → 真正读取 MP4/WebM 内容 URI → 验证散列。接收器无存储权限；调试设备 shell 只读取接收器的结果文件，不代替它读取视频。
- `final-process-restart.txt`：1 项。host 强制停止应用后启动独立 instrumentation，验证文件对账、默认网络偏好、浏览数据、已忘记记录不复活；不只是 Activity 重建。
- `external-file-missing.txt`：1 项。只在核对系统 ID、来源与唯一文件名后外部删除一份样本，验证失效条目保留，URI 不可用、无播放/分享入口。
- `real-legacy-cover-install.txt`：API28 专用 AVD 运行 Git 基线 `22dcf81` 的真实旧 APK，实际生成旧 XML 任务；同包名/Debug 签名覆盖安装前后原浏览 JSON 和旧 XML 字节一致。启动后一次迁移，原始未知字段仍为空；旧 XML 不变，深色主题/书签/快捷站点/历史/标签保留。
- `api28-smoke.txt`、`screens/api28-small-library.png`、`screens/api28-player.png`：旧版写入权限拒绝无任务/假成功，允许后真保存；约 329dp 小屏列表/返回/键盘；从库调用真实外部视频查看器显示生成的 MP4。

报告目录为本机 `app/build/reports/round2-product/`；失败尝试也留在该目录，便于追溯，不提交编译产物。API37 的虚拟设备为 Pixel_10_Pro_XL，API28 为本轮创建的 PureBrowser_API28_Test。

## 样本与重跑

只使用 ffmpeg 本机生成的两秒画面/音频，不依赖第三方视频网站。fixture 提供签名 MP4、WebM、HTML 假视频、401/403、未知长度与慢传输；MP4 和 WebM 对比服务器生成文件的 SHA-256，WebM 散列每次生成可能不同。

```zsh
source "$HOME/.config/android-dev/env.zsh"
python3 tools/fixtures/serve_video_fixture.py
# 另一个终端：使用专用测试设备；若设备有真实数据，先备份受影响的私有状态。
python3 tools/verification/device_state_backup.py backup --device emulator-5554
adb -s emulator-5554 reverse tcp:8765 tcp:8765
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 从 fixture 控制台取本次两个真实散列：
adb -s emulator-5554 shell am instrument -w -r \
  -e videoFixture true -e fixtureSha256 '<MP4 SHA256>' -e fixtureWebmSha256 '<WebM SHA256>' \
  -e notClass 'com.example.purebrowser.library.RuntimeFileShareTest,com.example.purebrowser.ui.browser.RoundTwoRestartAudit,com.example.purebrowser.download.RoundTwoFixtureCleanupTest' \
  com.example.purebrowser.test/androidx.test.runner.AndroidJUnitRunner
```

独立分享、重启、外部删除和旧 APK 烟测按上述证据的条件分别运行。反复 UI 回归会增加测试标签/快捷站点，不能让其累积触达产品上限，再把测试环境故障当成应用失败。重复运行前在强制停止后恢复已备份的受测试影响状态，保留下载记录供检查；不得卸载目标应用、清空用户 Download 目录或枚举删除全局任务。

私有状态备份范围在工具 FILES 常量中列明：浏览 JSON、下载 JSON/原子文件、旧 XML、下载偏好；不声称复制了 WebView 全部配置、账号或整台设备。只清理本轮可信样本 ID，并排除原记录，最后按散列验证恢复；取消 reverse、卸载测试 APK、复原临时显示尺寸并停止本轮辅助 AVD/服务器。

## 支持边界和未验证项

- 0.1 是**公开文件直链的本地产品预览版**，不是任意视频网站下载器。MP4/WebM 生成样本已验证；其他容器/编码取决于平台，不作同等覆盖承诺。
- HLS/DASH/blob 仅识别与解释；不下载分片、不合并、不做直播、登录态/Cookie/Authorization/Referer 转发、云解析或 DRM 绕过。
- 文件在公共 Download 目录；库内显示改名不是物理改名，移除记录不是删除文件。不扫描整机视频，不申请全盘或全媒体访问。
- 外部播放器/分享由用户选择；不保证所有接收应用均支持编码，也不自动上传。
- 格式初检不代表完整视频、所有音轨/画面或安全保证。系统下载器的逐跳控制、长视频/后台、电量/OEM、多字体/屏幕完整矩阵、真机、Play 发布与全面安全/性能审计未验证，保持后置。
- 当前 applicationId 是 `com.example.purebrowser`，Debug 签名用于覆盖测试，不是正式商店发布签名。
