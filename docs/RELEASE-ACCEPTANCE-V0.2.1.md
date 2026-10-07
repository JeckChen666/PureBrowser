# v0.2.1 发行验收台账

冻结日期：2026-10-07。分支 `feat/v0.2.1`（D22 委托默认 B；D23–D25 按默认冻结）。

## 必要级（阻挡交付）

| # | 项 | 状态 |
| --- | --- | --- |
| R1 | 构建＋lint 零错误；JVM 全绿无回归（UI 合并不改任务语义的硬门槛） | ✅（T119 复跑：547 JVM 0 失败 0 跳过；lint 0 错误/48 登记警告） |
| R2 | LOGO：四密度＋自适应＋单色层；启动器/关于/通知/README 一致 | 待测（T116） |
| R3 | 链路：三站默认路径 ≤2 步、换档 +1、快捷 1 步（实机实测） | ⚠️ 部分达标（S-A/S-B 2 步建任务、快捷 1 次长按建任务；规则站面板路径 2 步被 fMP4 边界拒绝，适配器路径 4 步建任务，见下方 T119 证据） |
| R4 | 提示语：审计清单入档；主文案无协议术语（脚本可检）；失败→建议映射生效 | ✅（T119：check_user_copy 全量 23 文件 0 发现；对话框入扫描范围并按术语表改写 37 处） |
| R5 | 回归：S-A/S-B/规则站保存、直链/HLS/DASH 套件、TalkBack 不倒退 | ✅（T119：重写套件 connected 全绿含 API28 权限审计；TalkBack 服务实开走查＋语义断言） |
| R6 | 发行身份链：code20、同证书、APK 哈希、tag | 待测（T120） |

## 增强级（登记，不阻挡）

| # | 项 | 状态 |
| --- | --- | --- |
| E1 | 可读性走查扩展（大字体/深色/窄屏截图集） | 登记未跑 |
| E2 | 悬置议题（规模化补足、YouTube 会话等） | 按用户指示悬置 |

## T119 体验验收证据（2026-10-07，API37 ReleaseLab 实机）

链路步数（新 `V02xTwoStepPathTest`，驱动真实 MainActivity／WebView／生产保存链，host 哈希脱敏）：

| 站点 | 路径 | 步数 | 任务创建 | 备注 |
| --- | --- | --- | --- | --- |
| S-A（host 33efade2，附年龄 Cookie） | 面板默认：点卡片 保存 → 点 保存视频 | 2 | ✅ HLS QUEUED 597 分片，mediaUrl 匹配 | 预挂 1 档（1080p）默认选中，无额外确认步 |
| S-B（host 8115bf6e） | 面板默认：点卡片 保存 → 点 保存视频 | 2 | ✅ HLS QUEUED 65 分片，mediaUrl 匹配 | 预挂 3 档默认选中 |
| 规则站 video.blender.org（host fa7769da） | 面板默认（嗅探 HLS 阶梯） | 2（未建任务） | ❌ | PeerTube HLS 为 fMP4，生产硬边界拒绝：`清单包含本版不支持的媒体特性`；第 3 步重试同拒 |
| 规则站 video.blender.org | 站点适配器：分析当前视频 → 解析可用格式 → 继续确认保存 → 开始下载 | 4 | ✅ DIRECT QUEUED（54.6 MB 渐进 MP4） | 该站受支持的路径；≤2 步口径在规则站未达 |
| S-A 快捷路径 | 长按候选卡片（用默认设置快速保存） | 1 | ✅ HLS QUEUED | 折叠解析＋记忆默认档生效 |

回归与可访问性：

- 重写套件 connected 全绿：HlsUiTest、HlsCompactUiTest、HlsBrowserJourneyTest（fixture 全链）、ResourceUiTest、ResourceCompactUiTest、DownloadQuickSheetUiTest、DownloadLibraryUiTest（API37），LegacyPermissionUiAudit 于 API28 AVD 拒绝路径通过（撤销遗留权限后运行）。T119 修复的旧断言：动作名 尝试下载→保存、计数/来源/大小/时长/原因文案对齐 T118 术语、DASH 自 v0.1.9 起可下载、编解码档位自 v0.1.9 起可选＋告警。
- TalkBack：API37 实机启用 TalkBack（touchExploration 生效）冷启动走查无崩溃、节点描述完整；新增语义断言：资源行 `onClickLabel=打开保存选项`、`onLongClickLabel=用默认设置快速保存`，清晰度行显式 `stateDescription 已选择/未选择`。
- 行为修复：三个保存确认的 仅 Wi-Fi 阻断提示改为跟随实时网络状态（Wi-Fi 恢复即消失，仍不自动重试）。
