# 当前实现与验证状态

更新日期：2026-10-01（Asia/Shanghai）。第一轮产品化 T1–T7 已完成，完整交付证据见 T1-T7-COMPLETION.md。历史基础版记录保留于 archive/IMPLEMENTATION-STATUS-baseline-2026-10-01.md。

## 已实现

正式首页、统一浅/深色与系统主题、本地图标、多标签（独立资源集合/三个活跃页面缓存）、标签网址与选中恢复；本地快捷站点 CRUD，书签添加/取消/搜索/编辑/删除/打开，历史时间列表/搜索/删除/清空；地址/搜索、键盘/返回、停止/刷新、错误重试、菜单、最小设置与关于。

已有嗅探和公共直链下载仍保留：请求、下载回调、只读 DOM video/source、Resource Timing；签名 query 保留、来源合并、页面代次与容量边界、常见分片过滤；明确区分 MP4/WebM 直链、HLS/DASH/blob；实际确认、系统 DownloadManager、持久任务、删除确认、文件打开、有限容器/视频轨样本初检。关闭来源标签不取消独立下载任务。

不增加自有云端存储、解析、同步、账号、后端或追踪。浏览本地数据与 download_records 分开，使用 IO 串行/AtomicFile 保存并防止损坏数据无备份覆盖。

## 最终验证

- Debug APK/测试 APK 构建通过。
- 单元 19 项通过；仪器 8 项通过（开启本地 fixture，0 跳过）。
- Lint 0 错误、21 提醒。
- 真实 force-stop/重新启动，本地分类数据一致。
- 签名 MP4 经实际 UI 下载、初检、SHA-256 校验；关闭来源标签仍保留任务和文件。
- 三标签页面/返回/资源隔离，书签编辑搜索、快捷站点编辑、主题保存、历史分类清理通过。
- 小屏地址键盘/失败重试/关于页、长按网页链接新标签、成品系统打开手动检查通过。
- 本次自生成数据与下载已清理，保留默认首页运行；目标应用未卸载。

## 重跑

**UI/集成测试会修改主题、书签、快捷站点并清空历史；仅在专用、可丢弃的测试模拟器执行。真实数据必须先备份并在完成后恢复。** 不以卸载目标应用进行测试重置。

先启动 `python3 tools/fixtures/serve_video_fixture.py`，记录其 SHA-256，再运行：

```zsh
source "$HOME/.config/android-dev/env.zsh"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 reverse tcp:8765 tcp:8765
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e videoFixture true \
  -e fixtureSha256 '<服务器打印的 SHA-256>' \
  com.example.purebrowser.test/androidx.test.runner.AndroidJUnitRunner
```

不启用 fixture 时三个集成场景会跳过；本次完整结果启用了 fixture。设备回环地址依赖 adb reverse；8766 应无监听，用于连接失败测试。系统下载服务访问 10.0.2.2 曾超时，因此下载样本使用 127.0.0.1。这不是通过放宽生产网络策略规避。

## 尚未实现或验证

完整资源/下载中心和本地视频库第二轮产品化；HLS/DASH 分片与合并、登录 Cookie/Authorization/逐跳凭据、无痕隔离、完整 Service Worker/iframe/MSE、手动暂停/自定义续传、长视频后台、真机与不同系统版本。假 MP4 下载拒绝的独立端到端验证、所有格式/站点覆盖未完成。
当前只能称第一轮浏览产品骨架；完整 0.1 尚待第二轮，不能以本轮通过替代其验收。
