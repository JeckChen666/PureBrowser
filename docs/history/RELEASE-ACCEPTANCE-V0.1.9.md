# v0.1.9 发行验收台账

冻结日期：2026-10-06。分支 `feat/v0.1.9`（D12–D16 已按默认建议冻结）。分级制度沿必要/增强两级。

## 必要级（阻挡交付）

| # | 项 | 状态 |
| --- | --- | --- |
| R1 | 构建＋lint 零错误；JVM 全绿无回归 | **通过（T103）**：JVM 497 用例 0 失败 0 跳过（v0.1.8 基线 447＋50）；lint 0 errors（43 warnings 均既有处置类） |
| R2 | 独立音轨 HLS 成品 ≥1（TED 形态实站）；仅视频/仅音频负例诚实拒绝 | **通过（T103）**：公开 TS 形态独立音轨参考流（mtoczko hls-test-streams test-group）SUCCEEDED 2,854,196 B（DUAL_TRACK，60.1s，ftyp ok）；T100 留池 4 站在新构建下仍于 4 个不同生产接缝如实拒绝（fMP4 清单／BYTERANGE 初始化段／超双轨时长预算／.aac 裸分片）。**验收中发现并修复 DualTrackMuxer LocalSource 读取预算缺陷**（API37 提取器按采样重读样表，实测 ~22.7× 文件体积，原 4×+1MiB 预算截断扫描致成品必败；修为 32×+16MiB／1M 次调用）；诊断与证据见 `V0.1.9-T103-ACCEPTANCE-EVIDENCE.md` |
| R3 | DASH 下载成品 ≥1（H.264 档实站）；AV1/加密档如实标注 | **通过（T103）**：Unified Streaming 公开参考 tears-of-steel MPD（static，avc1×5 档＋mp4a.40.2 AAC-LC，SegmentTimeline）经生产链（DashResolver→计划→enqueue→DashTransfer，音视频配对 368 分片）SUCCEEDED 42,276,491 B（734s，ftyp ok）。dashif bbb_30fps.mpd 音轨为 HE-AAC（AOT 5），生产按非 AAC-LC 如实拒绝（正确行为） |
| R4 | （条件，D12）YouTube 会话电池结论入档；Go→成品＋默认关验证 | **待用户人工登录后执行（T98）** |
| R5 | yt-dlp 首批转写规则实站验证入内置，抽样成品率 ≥60% | **通过（T100）**：64 候选审查（63 次 curl 预检）＋19 站实站批量；入内置 3 条（17→20），抽样成品率 3/3=100%；指针启发式根因与 61 条留池明细见 `V0.1.9-T100-RULES-EVIDENCE.md` |
| R6 | 回归：S-A/S-B、17 条既有规则站、直链/HLS 套件零倒退 | **通过（T103，一项如实登记）**：S-A 冒烟绿（4×HLS VERIFIED，DOM+RULE）；S-B 冒烟绿（7 候选全 VERIFIED）＋完整保存 SUCCEEDED 91,476,375 B（HLS，ftyp ok）；既有规则站成品以同族 `peertube-blender-files` 于 video.blender.org 验证 SUCCEEDED 140,047,185 B（DIRECT）——framatube.org 因上游文件存储整体迁往联邦镜像（fileDownloadUrl 302 到跨源签名 URL，探测按策略不验证）今日如实未达成，规则本身仍命中，非本版回归；JVM 497 绿；lint 0 errors |
| R7 | API28 套件恢复；导入规则 Ed25519 签名/验签/降级负例 | **通过（T101）**：API28 矩阵 37 用例全绿（DualTrack 17＋HlsUi 10＋V016 冒烟 1＋HlsSeparateAudio 2＋fMP4 组装 7；先行修复 4 处夹具/兼容缺陷——capture() 忽略 RESULT_SEEK 死循环、单关键帧夹具永不分片、B 帧 zigzag 时间轴拉伸、JDK21 removeLast API28 缺失——fMP4 组装类在 API28/37 复测全绿，属 T96 既有欠账）。Ed25519：JVM 5 用例绿（签名/验签/篡改拒绝/未签名降级/错钥拒绝＋已提交样本对内置公钥端到端验证）；维护者私钥本地 gitignore，公钥内置 assets/rules/rules-pubkey.txt |
| R8 | 发行身份链 | **通过**（code18；证书 52fe690a…fc90；APK SHA256 见 Release；tag v0.1.9） |

## 增强级（登记，不阻挡）

| # | 项 | 状态 |
| --- | --- | --- |
| E1 | AV1 真机探针与排期结论（D14） | **已结论（T102）：推迟到 v0.2.0**。探针（模拟器，实验室断网边界内无真机）：API37 仅 2 个软件 av01 解码器（≤2048p/40Mbps，无硬解）；API28 零解码器——播放覆盖是产品门槛，minSdk 段不可播放，故不排期；复评门槛与探针复现见 `V0.1.9-T102-AV1-PROBE.md` |
| E2 | 首批转写规则 ≥150 条上限达成 | **未达，如实登记（T100/T103）**：本批仅入内置 3 条（总量 20）。根因是 T99 转写的 jsonExtract 指针取上游 JSON 顶层键（启发式），而引擎只对解析为字符串 URL 的指针产出候选（数组/对象容器为空产出）；另 8 条候选的 fetch 主机不在 hosts 面内与现行 D6 白名单冲突。扩量需指针深挖/数组遍历动作与 D6 决策（schema 演进），登记为后续任务；50–150 目标本版未达 |
| E3 | 完整三率配对语料、多厂商、无障碍全量 | 登记未跑 |
