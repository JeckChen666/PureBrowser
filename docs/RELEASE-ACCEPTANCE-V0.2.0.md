# v0.2.0 发行验收台账

冻结日期：2026-10-07。分支 `feat/v0.2.0`（D17–D21 已按默认建议冻结）。

## 必要级（阻挡交付）

| # | 项 | 状态 |
| --- | --- | --- |
| R1 | 构建＋lint 零错误；JVM 全绿无回归 | **通过（T113）**：`:app:testDebugUnitTest` 531 用例 0 失败 0 跳过（v0.1.9 基线 497＋34）；`:app:lintDebug` 0 errors |
| R2 | （条件，D17）YouTube 会话结论入档；Go→成品＋默认关验证 | **待用户人工登录后执行（T106）** |
| R3 | HLS fMP4 音频：自制夹具 e2e＋实站成品 ≥1 | **通过（T107/T113）**：`HlsFmp4AudioDualTrackTransferTest` 设备 `OK (2 tests)`（T113 修复夹具缺 `#EXT-X-ENDLIST` 后）；arhiiv.err.ee 实站成品 208,453,130 B（protocol=DUAL_TRACK，ftyp ok，608.7s，T107 路径）；sen.com 如实拒绝（排除标签类，T111 #5） |
| R4 | 规则规模化 ≥30 条实测成品入库，抽样成品率 ≥60% | **部分达成（T109，如实登记）**：新增 12 条完整链规则入库（20→32 条），其中成品 5 条（473.8MB HLS/208.4MB DUAL_TRACK/123.7MB/11.9MB/8.5MB）、其余 7 条为生产接缝如实原因；抽样成品率 42% 低于 60%；严格"实测成品"口径未达 30。详见 docs/T109-SCALE-UP-EVIDENCE.md |
| R5 | AV1 门控：无解码器隐藏/软解提示/成品解码校验 | **通过（T110/T113）**：`Av1CapabilityProviderTest`（API37 设备）绿；门控逻辑 JVM 套件覆盖（ResourceSniffer/Hls/Dash Av1Gate 3 类） |
| R6 | FFmpeg 缺口盘点结论入档（结案或方案） | **通过（T111，结案：永不引入）**：docs/T111-FFMPEG-DECISION.md——存量缺口全部可由 fMP4/Media3 接线覆盖或属产品红线，无转码/解封装需求 |
| R7 | 回归：S-A/S-B、既有规则站、直链/HLS/DASH 套件零倒退 | **通过（T113）**：S-A 冒烟（HLS VERIFIED，DOM+RULE，cookie 注入）；S-B 冒烟（HLS 480/720＋FILE VERIFIED）；既有规则站 video.blender.org（peertube-blender-files）成品 SUCCEEDED 110,388,897 B DIRECT ftyp ok；JVM 套件全绿（含直链/HLS/DASH 套件） |
| R8 | 发行身份链：code19、同证书、APK 哈希、tag | 待测（T114 发行时） |

## 增强级（登记，不阻挡）

| # | 项 | 状态 |
| --- | --- | --- |
| E1 | QuickJS 触发统计与立项结论（D21） | 待测（T112） |
| E2 | 规模化 ≥60 站 | 待测 |
| E3 | 完整三率配对语料、多厂商、悬浮按钮、外置规则正式化 | 登记未跑 |
