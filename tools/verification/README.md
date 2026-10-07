# 开发机组合回归

## v0.2.1 主文案静态检查（T118 产出，T119 回归门）

```sh
python3 tools/verification/check_user_copy.py
```

扫描 T118 改写范围内的 UI 字符串字面量（资源面板、设置/关于、下载任务列表与详情、下载通知、CopyMapping），
主文案不允许出现开发/协议术语（清单、档位、直链、MPEG-TS、fMP4、ETag、受控下载器、候选）。
这些词只允许出现在 `// tech-detail:begin` … `// tech-detail:end` 注释标记之间的折叠"技术详情"文案里（或行尾 `// tech-detail`）。
诊断/日志/代码层不在扫描范围（术语保持不变，可诊断性不降级）。有发现时退出码 1，便于 T119 与 JVM/lint 一起挂门。
完整逐条处置见 `docs/COPY-AUDIT-V0.2.1.md`。

`run_v013_regression.py` 只接受专用 **PureBrowser_API37_ReleaseLab** AVD（默认 emulator-5560）。
默认不清空应用数据，不会操作主模拟器或原 v0.1.0 包；请先启动专用 AVD，确认没有其他 instrumentation。
复跑前留出足够标签容量。合成夹具端口 8765、8766、8768 必须空闲。

```sh
source /Users/macos/.config/android-dev/env.zsh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python3 tools/verification/run_v013_regression.py --scope focused \
  --output app/build/reports/v0.1.3/local-acceptance/focused-NEW
python3 tools/verification/run_v013_regression.py --scope full --reset-lab \
  --output app/build/reports/v0.1.3/local-acceptance/full-NEW
python3 -m unittest discover -s tools/verification -p 'test_*.py' -v
```

- focused：会话/前台服务入口 + 隐私 5 项 + 曾失败的浏览/产品流程 + HLS 完整 UI，**11 项**。
  服务入口与完整批次保持相同初始化顺序，避免冷启动 WebKit 被误当作下载用例的一部分。
- browser：服务入口 + 直链嗅探/下载触摸专项，**2 项**，用于快速定位，不替代 full。
- product：服务入口 + 综合产品无障碍 UI 工作流，**2 项**；不代替真实触摸旅程。
- share：服务入口 + 真实 chooser/跨 UID 文件读取，**4 项**；枚举交互窗口并在实际系统选择器内滚动，不直接启动接收器。
- workflow：服务入口 + 双格式/失败恢复无障碍操作专项，**3 项**；普通浏览、直链和 HLS 的触摸旅程仍在 focused/full 中执行。
- full：冻结在 `v013-cohort.json` 的 **225 项**，一次 instrumentation，不通过分批相加放行；在原 223 项之外新增导航/SPA 弃置回调的两项确定性回归。
- 运行器先执行 Debug/测试 APK 构建，构建失败就停止，不能安装上次遗留的测试 APK；记录源码输入 SHA，并检查测试期间未改动源码。
- 自动启动并清理本次的三种合成站点，现场 GET 计算 MP4/WebM 哈希；不复用未知服务器。
- `--reset-lab` 是显式的干净基线选项：仅清空这台可弃用 QA AVD 的 Debug 包私有数据，保留正式包、原 v0.1.0 和公共文件；避免重复回归累积标签/任务影响下一批。授权/屏幕状态的恢复基准取重置之后的状态。禁止对主模拟器照搬清数据。
- 自动授予普通 UI 下载回归所需的通知权限，退出时恢复原授权和屏幕超时；不清除 user-fixed 等权限标记。
  **通知拒绝专项仍另行验证**，并不是要求用户必须授权才能下载。
- 明确开启全部夹具选项，HLS 选择启用后缺权限/Debug 请求策略直接失败，不再静默跳过。
- 记录 APK 哈希、环境、原始输出、开始/结束状态。失败/跳过/少测/进程崩溃/外层超时都返回非零。
- 默认外层 1800 秒，超时终止本批次应用；不通过无限等待或重新调度覆盖旧结果。
- 每次使用新输出目录，原失败日志保留；不把报告/测试媒体/私有数据上传 Git。

该脚本只负责组合回归，不替代 API28、API36、大文件、长 HLS、签名 HTTPS 和升级等专项。
`device_state_backup.py` 是原包历史回归的独立保护工具，不被这个脚本调用。

终端计数只接受具备 class/test 身份的用例结束，不将 `sendStatus(0, evidence)` 的证据消息当作通过；匿名负状态仍拒绝放行。主机计数/构建拒绝回归现为9项。正式签名跨UID收尾使用独立 `SignedReleaseShareAudit`；androidTest APK单独开启debuggable用于读取接收器报告，生产APK不受影响。

## v0.1.5 历史旧版观察汇总（计划失败关闭，不算配对增益）

2026-10-04已按用户决定关闭v015计划，见[收尾结论](../../docs/history/V0.1.5-CLOSEOUT.md)。以下是离线历史证据工具，使用它不代表重开本版网站测试；v016须另立输入／合同。

`summary_v015` 由 `summarize_v015_baseline.py` 离线生成：读取候选登记、已安装正式 code13 APK 的 hash／环境、逐行记录和脱敏证据，保留未测行。网址／版本错配、证据缺失／越界、伪成功状态拒绝接受。初步观察永远不输出成品率或跨站增益，不把 UI「已完成」、地址、前缀或主机文件检查当作 Android 声画／跨 UID 验收。

```sh
python3 tools/verification/summarize_v015_baseline.py \
  --baseline app/build/reports/v0.1.5/next-round/baseline-code13 \
  --output app/build/reports/v0.1.5/next-round/baseline-code13/summary.json
python3 -m unittest discover -s tools/verification -p test_v015_baseline_summary.py -v
```

已有初步观察不等于严格语料冻结：须补上传版本／归属、统一正常操作和候选配对，并完成真实成品验收。输入法把作品 ID 改词、网页变化、原始文件与转码混用等情况必须保留并排除假归因，不能作为新版失败→成功的证据。

### YouTube 有界诊断

新增 `V015YouTubeTransferDiagnosticTest` 的10个 `fixture*` 方法只用内存 transport。选显式方法 cohort 可不触网、不带跳过地验证：同次解析／同轨／同头条件下两种单变量 Range 对照、重复拒绝停止、取消／deadline、响应合同与脱敏。

仅方法 `#sintelRangeSemanticsBoundedDiagnostic` 配合 `-e v015YouTubeTransferDiagnostic true` 才触发实站；不随 `v015RealSites` 自动执行。完整 Range header、64字节 Range header、64字节 query range 三种语义；最多192字节 body观测，每种最多2次安全重定向／12秒 deadline。连续两次访问拒绝或其他非访问故障停止，不重试。真实完整传输必须另用产品路径、另记结果；诊断成功也不是成品成功。
