# V0.2.0 T109 规则规模化实站验证证据

日期：2026-10-06（Asia/Shanghai）。分支 `feat/v0.2.0`，基线 commit `e34ddda`（T107/T108/T110 已合入）。环境：PureBrowser_API37_ReleaseLab AVD（API 37）、生产装配 `BrowserSession`、`V016CrossSiteSmoke`（发现级）＋ `V016CrossSiteSaveSmoke`（发现→档位→保存→可打开）。输出 host 一律哈希脱敏。候选池不含成人站（74 条中 0 条命中 S-A/S-B 域；grep 复核 0 次），本文不涉及成人站名称。

## 装载方式

验证用构建＝内置 20 条（不动）＋ `tools/rules-transcriber/output/candidates.json` 全 74 条候选以**导入通道**（`filesDir/imported-site-rules.json`，v3 文档 32,748 B，装载器 `parseImported` 同校验）注入，规则命中经候选 `sources=[RULE]` 观察。T108 的 fetch.hosts 扩展在内置与导入两档均生效。

## 运行预算（如实）

- 发现级：45 站（measured=true 19 站优先、fetch.hosts 8 站、其余 18 站）＋3 站复用（medaltv 冒烟基线、arhiiv/sen 兼作 T113 腿）＝**48 页次**（超出 ~40 站预算约 8 页次，如实登记）。
- 保存级：16 次运行（含 arhiiv 2 次、godresource 2 次、tvc 2 次、masters 2 次）。
- 验证机 curl：T113 腿 8 次（sen 页 1、arhiiv 页 1、语料核对 6）；T109 腿 0 次新增。

## 完整链规则（发现→可寻址候选→成品 ≥100KiB MP4 或如实原因）＝ 12 条入库

| 规则 id | 规则直接贡献 | 成品/如实结果（生产接缝原文） |
| --- | --- | --- |
| ytdlp-errarhiiv | 规则命中（T100 已证） | **SUCCEEDED 208,453,130 B，protocol=DUAL_TRACK，ftyp ok，608,704 ms**（T107 fMP4 双轨实站成品，见 T113 腿） |
| ytdlp-gab | 是（RULE→直链 MP4 11.9MB VERIFIED） | **SUCCEEDED 11,947,252 B DIRECT ftyp ok 104.2s**（桌面 UA） |
| ytdlp-livestreamfails | 否（页面请求发现） | **SUCCEEDED 8,538,700 B DIRECT ftyp ok 26.0s**（站点级验证；规则 path 面早于站点改版，如实登记） |
| ytdlp-ichinanalivevod | 否（REQUEST 发现；fetch.hosts 规则） | **SUCCEEDED 123,653,711 B DIRECT ftyp ok 549.5s**（站点级验证） |
| ytdlp-masters | 是（RULE→直链 MP4 86.7MB VERIFIED＋HLS 主清单 VERIFIED） | **SUCCEEDED 473,766,293 B，protocol=HLS，ftyp ok，1,039,381 ms（17.3 分钟内容）**（首次保存被测试看门狗打断于 100/104 分片，saveTimeoutMs 放宽后复测成功） |
| ytdlp-godresource | 是（RULE→HLS 主清单 VERIFIED） | 传输本体健康（851MB、620/3058 分片），多时长内容被**测试看门狗**如实打断（非生产缺陷） |
| ytdlp-tvc | 是（RULE→直链 MP4 110MB/64MB×3 VERIFIED） | 保存回访两次页面不可达（站点侧，2 次尝试即止） |
| ytdlp-zhihu | 是（RULE→FHD/SD 直链 MP4 VERIFIED） | 保存回访 HTTP 403（bot 墙；发现级已 VERIFIED） |
| ytdlp-sen | 规则 API 探测 FAILED；主清单页面请求 VERIFIED（4 变体，T103） | 如实拒绝：`清单包含本版不支持的媒体特性`（排除标签类，T111 盘点 #5） |
| ytdlp-thechosen | 是（RULE＋REQUEST→主清单 5 变体 2160p VERIFIED） | 如实拒绝：`本版只支持待内容校验的 MPEG-TS 分片`（裸 AAC 音轨，T111 盘点 #1） |
| ytdlp-asobichannel | 否（fetch.hosts 规则；主清单 4 变体 1080p 页面请求 VERIFIED） | 如实拒绝：`本版不支持加密 HLS`（AES-128，产品红线） |
| ytdlp-twentythreevideo | 否（REQUEST/DOM→直链 MP4 8.8MB VERIFIED） | 如实拒绝：`网站会话下载遇到跨源跳转，请返回来源重新发现`（跨源签名跳转策略） |

**入库结果：12 条新增（id 前缀 `ytdlp-`，文档 version 3，总量 20→32 条，16,761 B ≤ 512KiB 上限），既有 20 条零改动。** 凭据标记/排除站扫描（SiteRulesAssetTest 红线集）0 命中。

## masters 重试（补记）

`masters2`：saveTimeoutMs 放宽至 25 分钟后重跑——104/104 分片、MUXING 完成，**SUCCEEDED 473,766,293 B（protocol=HLS，ftyp ok，1,039,381 ms）**。首次运行的打断纯为测试装置看门狗（480s），非产品缺陷。

## 其余 33 站如实分类（不入库）

- **无候选（页面加载正常但无可寻址媒体）**：cccplaylist、kommunetv、kikaplaylist（桌面 UA）、tubetugraz、rinsefmartistplaylist、ichinanaliveclip、discoverynetworksde（桌面 UA）、graspop、microsoftembed、ondemandkorea、tele5（桌面 UA）、microsoftmedius、s4cseries、telequebecsquat、sportdeutschland、cozytv、nate、israelnationalnews、younowlive、allstarprofile、americastestkitchen、audioboom、flextv、netappvideo（23 站）——多为需交互起播/登录/DRM/纯音频 SPA。
- **候选出现但不可寻址（kind UNKNOWN 或探针 FAILED）**：mixchmovie（RULE 参与，VERIFIED video/mp4 2.8MB 但 kind=UNKNOWN）、discogsreleaseplaylist、mirrativuser、parliamentliveuk（本日仅规则碎屑候选）、reverbnation（mp3 音频）、vimeoalbum、zhihu API 碎屑、asobichannel API 碎屑、gab 碎屑、crowdbunker（HLS 媒体清单无变体）、goodgame（直播清单 text/plain）、nintendo（Cloudinary h265 变体）。
- **页面不可达**：toutv（地域/加载失败）、tvc 保存回访、zhihu 保存回访 403。
- **drtvlive**：主清单 6 变体 VERIFIED，如实拒绝 `本版只支持具有结束标记的固定点播清单`（直播，产品边界）——链完整但直播站永无 VOD 成品，**不入库**（与 mixch 同类处置）。

## R4 口径（如实）

- 入库 12 条新规则中：**成品 5 条**（errarhiiv 208.4MB DUAL_TRACK、masters 473.8MB HLS、ichinanalivevod 123.7MB DIRECT、gab 11.9MB DIRECT、livestreamfails 8.5MB DIRECT）＋如实原因 7 条。抽样成品率 5/12≈42%，**低于 ≥60% 目标**。
- 对照 R4 原文"≥30 条实测成品入库"：以"实测成品"严格口径本批差距大（全库含历史实测成品约 15/32）；以"实站验证完整链（成品或如实原因）"口径为 12/12。**按未达标如实登记**，未虚报。根因同 T100：候选指针集命中"解析为字符串 URL"的比例仍低（多为数组容器/登录/交互起播），且本批可实测站点池被 bot 墙、地域墙、直播形态进一步压缩。
- 样本导入包：`tools/rules-transcriber/sample-import.json` 更新为 **8 条**本批新验证规则（errarhiiv/godresource/masters/tvc/gab/livestreamfails/ichinanalivevod/twentythreevideo），维护者 Ed25519 签名（keyid `pb-k-c7dff25a`）＋独立 `.sig`，`sign_rules verify` 通过。

## 与 T111 的接口

本批如实拒绝中：裸 AAC 音轨（thechosen）、排除标签类（sen）、加密 HLS（asobichannel）、直播清单（drtvlive/mixch）已作为 T111 缺口盘点输入（docs/T111-FFMPEG-DECISION.md）。

## 本任务新增/修改的文件

| 文件 | 性质 |
| --- | --- |
| `app/src/main/assets/rules/site-rules.json` | 12 条新验证规则入库（20→32 条，version 3 不变，既有 20 条零改动） |
| `tools/rules-transcriber/sample-import.json`（＋`.sig`） | 样本导入包更新为 8 条本批新验证规则，维护者 Ed25519 重签（keyid `pb-k-c7dff25a`），`sign_rules verify` 通过 |
| `app/src/androidTest/java/com/example/purebrowser/download/HlsFmp4AudioDualTrackTransferTest.kt` | T113 装置修复：fixture 的 video.m3u8 缺 `#EXT-X-ENDLIST`，被生产 VOD 门如实拒绝（首次设备运行即暴露——该夹具在 T107 合入时未经设备运行）；补齐后设备 `OK (2 tests)` |

T113 回归（同日，生产构建 32 条内置规则、导入文档已清除）：S-A 冒烟（HLS VERIFIED，DOM+RULE，cookie 注入）；S-B 冒烟（HLS 480/720＋FILE VERIFIED）；`Av1CapabilityProviderTest` 设备 `OK (2 tests)`；既有规则站 video.blender.org（`peertube-blender-files`）成品 SUCCEEDED 110,388,897 B DIRECT ftyp ok 464.1s；JVM `:app:testDebugUnitTest` 531 用例 0 失败；`:app:lintDebug` 0 errors（43 warnings 4 hints，与 v0.1.9 处置基线一致）。
