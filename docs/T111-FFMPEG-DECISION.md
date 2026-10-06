# T111 FFmpeg 缺口盘点与决策（v0.2.0）

日期：2026-10-06（Asia/Shanghai）。分支 `feat/v0.2.0`（T107 fMP4 双轨接线、T110 AV1 门控已合入）。

## 盘点口径

"缺口"＝生产下载链在实站或参考语料上仍然**诚实拒绝**、且拒绝原因属于"容器/编码/封装形态"的每一类形状。来源：v0.1.9 T103 实站证据（4 个边界站）、v0.2.0 T113 当日实站复测（arhiiv.err.ee、sen.com）、T109 批量验证中的如实失败，以及代码内单一接缝的拒绝常量（`HlsPlaylistParser.excludedTags`、`SegmentFormat.requireTransferSupported`、`HlsDualTrackPlan`/`DualTrackMuxer` 预算、`HlsResolver` 编码门）。

## T107/T110 之后的存量缺口清单

| # | 形状 | 实证来源 | 现拒绝接缝（生产文案） | fMP4/Media3 可覆盖？ | 工作量估计 | 需要 FFmpeg？ |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 裸 AAC（ADTS）独立音轨分片（非 TS 非 fMP4） | watch.thechosen.tv（T103；T109 复测仍拒） | SegmentFormat 分类后单点接缝 | 可：Media3 自带 `AdtsExtractor`，照 `Fmp4SegmentAssembler` 模式加一个 ADTS→MP4 组装器即可，双轨预算/清理语义复用 | 约 2–3 天（含夹具与 androidTest） | 否 |
| 2 | >60 分钟双轨时长预算 | parliamentlive.tv（T103；T109 复测仍拒，整场会议 >60min） | `DualTrackMuxer.MAX_DURATION_US`（策略预算，非形态缺口） | 可：预算本身可调，风险是内存/时长与 DoS 上限的平衡 | 约 0.5 天（含压测） | 否 |
| 3 | 单轨 muxed fMP4 HLS（无独立音轨） | `SegmentFormat.SINGLE_TRACK` 门（T107 注释明示保留拒绝） | `本版暂不支持 fMP4 分片清单`（仅单轨角色） | 可：`Fmp4SegmentAssembler` 本就支持单轨输出（DASH 单轨已用），把单轨 HLS 计划路由到它即可 | 约 2–3 天 | 否 |
| 4 | 加密 HLS（`EXT-X-KEY METHOD≠NONE`） | 解析器即拒（任何站点） | `本版不支持加密 HLS` | 不做：DRM/密钥体系属产品红线 | — | 否（法律与产品决策，FFmpeg 不改变结论） |
| 5 | 清单变量/`EXT-X-DEFINE`、分片级 `EXT-X-BYTERANGE`、`EXT-X-DISCONTINUITY`（插播拼接）、`EXT-X-GAP`、`EXT-X-I-FRAMES-ONLY`、LL-HLS 族、`EXT-X-CONTENT-STEERING` | `HlsPlaylistParser.excludedTags`（sen.com 实站命中其中之一，具体标签待深层诊断，拒绝为设计门） | `清单包含本版不支持的媒体特性` | 可（逐项）：均为清单形状工程——变量替换是模板求值；分片 BYTERANGE 是区间请求（init BYTERANGE 已在 T107 落地，分片侧同构）；DISCONTINUITY 需多段拼接/分段封装；LL-HLS 属直播域不立项 | 每项 1–5 天不等 | 否 |
| 6 | HE-AAC（mp4a.40.5）音轨声明 | dashif bbb_30fps（T103：如实拒绝非 AAC-LC） | `此档位音频编码不是 AAC` | 可：封装不解码，样本直通 `Fmp4SegmentAssembler`（codec 无关）即可；需放开声明门＋夹具验证 | 约 1–2 天 | 否 |
| 7 | AV1 档位 | T110 门控（D19/D20 已冻结） | 无解码器设备隐藏＋软解提示（设计行为） | 已按决策处理：门控是播放能力问题，封装不需解码 | 已落地 | 否（FFmpeg 软解不在本决策范围） |
| 8 | 直播/活动窗口流（live HLS、登录墙、地域墙） | mixch.tv 等（T100/T109） | 无可下载档位／页面不可达 | 不立项：VOD 下载器产品边界 | — | 否 |

T109 批量验证中出现的其余如实失败（站点改版、bot 墙、纯音频站保存断言 MP4 等）属**站点可达性/产品断言**问题，不是格式缺口，不在本表。

## 结论

- 上述清单中**没有任何一项需要转码、解码或 Media3 之外的解封装能力**：#1/#3/#5/#6 全部落在既有 `Fmp4SegmentAssembler`（媒体无关，DASH 已验证）＋ `Mp4Muxer`/`DualTrackMuxer` 的接线范围内；#2 是策略预算；#4/#7/#8 是产品红线或已决策项。
- T107 实证：v0.1.9 的 4 个 fMP4 类边界站之一 arhiiv.err.ee 已在 v0.2.0 以 `protocol=DUAL_TRACK` 产出 208,453,130 B 成品（ftyp ok、时长 608.7s），证明"fMP4/Media3 路径覆盖"不是纸面判断。

**决策：FFmpeg议题按"永不引入"结案。** 引入 FFmpeg（即使 LGPL 最小裁剪，约 8–15 MB 原生库）只能兑换上表"可覆盖"项的提前量，代价是体积、许可合规、原生库停更/安全维护与两套解封装真相源；而所有不可覆盖项（#4/#7/#8）FFmpeg 同样不解决。后续若某类形状（如 DISCONTINUITY 插播拼接）确有用户压力，按上表工作量以 Media3 接线实现，本文件作为对照基线。

## 登记

- R6（必要级）：**通过（结案：永不引入）**。
- 后续如需重开本议题，须先证明上表某项"fMP4/Media3 不可覆盖"且属用户必要场景。
