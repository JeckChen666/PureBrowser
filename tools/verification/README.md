# 开发机组合回归

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
