# v0.1.7 发行验收台账

冻结日期：2026-10-04（最后更新 2026-10-05，T78/T79 实测结账）。分支 `feat/v0.1.7`。开发身份 `0.1.7-dev / versionCode 16`（发行基线 `v0.1.4 / code13`）。分级制度沿 [v0.1.6 台账](RELEASE-ACCEPTANCE-V0.1.6.md) §测试分级；本台账只登记实际结果，未执行项如实标注。

## 必要级（阻挡交付）

| # | 项 | 状态 | 实测结果与证据 |
| --- | --- | --- | --- |
| R1 | 构建＋lint 零错误；JVM 全绿无回归 | **通过** | `:app:testDebugUnitTest` 339/339 全绿（2026-10-05）；本轮修复 3 处失败（b19a3df：`RuleSetTest` 构造器 JSON 反斜杠转义、`SiteRulesCoordinatorTest`×2 因 coroutines-test 1.10 将 `backgroundScope` 任务排除在 `advanceUntilIdle` 之外改用 `StandardTestDispatcher`，断言未放宽）；`lintDebug` 0 错误／40 提醒（沿袭 v0.1.6 登记，不阻挡） |
| R2 | 站点规则层：解释器安全（白名单动作、正则上限、有界降级）负例 | **通过** | `RuleEngineTest`（host 双面匹配、预算耗尽带部分结果有界退出、findings/requests/DOM 查询与节点上限、非 http 属性丢弃）＋`RuleSetTest`（解析安全、捕获源去重封顶）＋`SiteRulesAssetTest`，全绿于 339 套件 |
| R3 | 规则层 ≥2 站真实新增成品（发现→提取→保存→可打开） | **部分达成（1/2）** | S-C 完整成品 309,105,790B／910.2s MP4（tube-html5-alt-watch）；S-D 规则与档位提取全部生效（master＋5 档 VERIFIED）但该站清单全族 AV1/fMP4（`#EXT-X-MAP`）被本版白名单诚实拒绝，页面对 H.264 回退主清单零引用——能力边界非倒退；reddit/VK/anyporn 登录墙、Dailymotion/Vimeo iframe 无手势且跨源响应截获受浏览器同源边界限制（Vimeo 冒烟 0 候选，如实登记）；S-E（通用层）2,445,509B／30.0s 真实 MP4 端到端但**不计规则层口径**（该站 URL 不命中任何规则路径）。详见 [V0.1.7-VALIDATION-EVIDENCE](V0.1.7-VALIDATION-EVIDENCE.md) |
| R4 | 回归：S-A/S-B 冒烟、公开直链/HLS 套件零倒退 | **通过** | S-A 附年龄 Cookie：`media=5 byProbe={VERIFIED=5}`，HLS 四档 240/480/720/1080 全 VERIFIED＋FILE 预览 6,180,220B；S-B 首令牌过期按既定流程复取新 URL 后通过：master VERIFIED＋3 变体＋渐进 mp4 39,972,182B；JVM 全量 339 绿无回归（取证见证据文档 R4 节） |
| R5 | （若 D1 Go）YouTube 双轨有声成品＋默认关闭路径零变化 | **未执行——待用户人工登录后执行** | T74 会话电池结论依赖真实登录会话；SESS 路线本轮不跑、不造假。默认关闭路径零变化由 339 项 JVM 全绿间接覆盖（无 YouTube 会话代码路径变更） |
| R6 | 发行身份链：code16、同证书、APK 哈希、tag | **部分（其余待 T80）** | 开发身份 `versionCode 16 / 0.1.7-dev` 已落 `app/build.gradle.kts`；v0.1.4（code13）↔v0.1.6（code15）签名证书一致（SHA-256 `52fe690a…fc90`，apksigner）；v0.1.6 包哈希 `dc5b3835…61cc1`；v0.1.7 最终签名包、哈希与 tag 属 T80，未创建、未推送 |

## 增强级（登记，不阻挡）

| # | 项 | 状态 | 实测结果 |
| --- | --- | --- | --- |
| E1 | >30 分钟有声双轨长样本封装与资源界限 | **通过** | 31.0 分钟 authored avc1+aac 对（视频 9,725,338B／1,859,916ms，音频 7,711,608B／1,859,453ms，A/V 漂移 0.46s）经本机回环 ServerSocket（支持闭区间 Range）走真实 `DualTrackTransfer`＋`DualTrackMuxer`：SUCCEEDED、FormatCheck.PASSED、时长/尺寸界限与"无暂存/缓存残留"断言全过（传输＋封装 105.6s）。测试侧夹具生成器修复两处：2s 网格非单调时间戳（muxer 拒写）、音频帧距规律性校验（改为按各自 span 网格循环＋音频环数对齐视频时长） |
| E2 | ≥15 分钟真实后台/锁屏传输 | **通过** | S-B 真实 HLS（67 分片／669s）RUNNING（2/67 分片）后按 HOME 后台 **900,042ms**，唤醒后 SUCCEEDED：225,350,712B 传输、成品 219,020,663B MP4（669.4s，HLS 协议）（`V017BackgroundTransferE2Test`，arg 注入，输出脱敏） |
| E3 | API28/36 分层矩阵 | **已执行（发现 1 项产品缺口，如实登记）** | API36：28/28 全绿（DualTrackTransferTest 17＋HlsUiTest 10＋V016CrossSiteSmoke 无参 1）。API28：HlsUiTest 10/10（修正 f43dd8e 起遗留的断言文案漂移＋小视口 performScrollTo）＋冒烟无参 OK；DualTrackTransferTest 12/17，5 项**仅在 legacy 发布步**失败：targetSdk 36 应用在 Android 9 上 WRITE_EXTERNAL_STORAGE 运行时授权不映射 `sdcard_rw` gid（进程组实测无 1015），公共 `Download/PureBrowser` 创建文件 EACCES（诊断实测 `createNewFile: Permission denied`）——下载/封装/校验全部先行通过。登记产品跟进（v0.1.8 候选：API<29 发布回退应用专属外部目录），非本版回归 |
| E4 | code13→code15 真实覆盖升级 | **通过** | 全新安装 v0.1.4（code13）→ UI 实操植书签（example.org，"已加入书签"）→ `adb install -r` v0.1.6（code15，同证书）→ 启动后书签面板 **"1 / 1 条书签 · Example Domain / example.org"** 存活，标签页会话亦恢复 |
| E5 | 完整三率配对语料/多厂商/无障碍全量 | 登记未跑 | 沿 v0.1.6 口径；必要级三率口径由 R3/R4 覆盖 |

## 本轮登记的跟进项（不阻挡 T80，建议 v0.1.8 评估）

1. **API<29 legacy 公共发布路径**：targetSdk 36 应用在 Android 9 无 `sdcard_rw` gid，双轨/直轨公共成品发布必败（E3 实测）；候选修复为应用专属外部目录回退。
2. **`.mp4` 结尾的 HLS 主清单 kind 分类沿用**：S-E 正片主清单（mpegurl MIME、3 变体 VERIFIED）因 URL 后缀被判 `kind=FILE`，生产 UI 按 kind 路由无法走 HLS 下载；候选修复为探测确认 MIME 后回填 kind（v0.1.6 起行为，非 v0.1.7 引入）。
3. **S-D（xplayer 家族）AV1/fMP4 边界**：该站 2026-10 起仅引用 AV1 清单族（`#EXT-X-MAP`），解析器按白名单拒绝属预期诚实失败；fMP4 支持如列入 v0.1.8 主线（FFmpeg 引入评估）可一并 revisit。
