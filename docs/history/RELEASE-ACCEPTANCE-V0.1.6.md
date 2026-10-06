# v0.1.6 发行验收台账（通用嗅探分层增强＋欠账清偿）

日期：2026-10-04（Asia/Shanghai）。分支 `feat/v0.1.6`。开发身份 `0.1.6-dev / versionCode 15`（发行基线仍是 `v0.1.4 / code13`）。
分级依据：[执行计划 §8 测试分级（必要／增强，2026-10-04 用户决策）](EXECUTION-PLAN-V0.1.6.md)。本台账只登记实际结果：未执行的增强项如实写"未执行"，不写成失败，也不预填。

**当前状态：必要级全部达成；增强级登记未执行（按 §8 不阻挡本版交付）；T67 YouTube 为已诊断 No-go、处置待用户决策（见"开放决策"）；T70 已备妥，未打 tag／未推送，等待用户放行。**

## 1. 必要级台账（阻挡交付，须通过）

| 编号 | 门槛（§8 口径） | 实际结果 | 证据 |
| --- | --- | --- | --- |
| R1 构建与 lint | 构建、lint 通过 | **通过**：Debug 构建、`lintDebug` 0 错误／40 已登记提醒（沿袭既有 dispositions，不阻挡） | 本地构建记录；`app/build/reports/lint-results-debug.txt` |
| R2 新增/改动逻辑 JVM 单测与关键负例 | v0.1.6 新增 19 个 JVM 测试类（观测/指纹/探测/会话/DASH/双轨分段/YouTube worker），全套 **282 项 0 失败 0 跳过** | **通过** | `:app:testDebugUnitTest`（2026-10-04）；测试清单见 T61–T68 行 |
| R3 既有套件无回归 | 旧 JVM 套件无回归 | **通过**（同上全量绿）；实机 V015/V016 显式 opt-in 类按各自决策运行，无 CI 依赖真实站点 | 同上 |
| R4 S-A/S-B 各一条真实端到端冒烟（发现→档位→保存→可打开） | 两站各一条，真实正片成品 | **通过**：S-A 373,009,519B MP4（615.7s，1080p，72 分片）；S-B 229,434,350B MP4（707.1s，1080p，71 分片）；均 SUCCEEDED＋FormatCheck.PASSED＋ftyp@4＋成品流式核长 | [T61 冒烟取证·T69 节](V0.1.6-T61-SMOKE-EVIDENCE.md)（`V016CrossSiteSaveSmoke`，2026-10-04） |
| R5 安全硬负例 | 凭据不落盘、注入只读审计、探测预算上限 | **通过**：任务 JSON/日志/诊断导出不含 Cookie 的既有负例维持（`DownloadStore` 序列化白名单字段）；注入脚本只读白名单（fetch/XHR/MSE/Blob/iframe src 仅上报，`PageSignalScriptTest`/`PageSignalBridgeTest`/`MediaUrlFilterTest`）；探测预算（次数/字节/并发/生命周期）负例 `AutoProbeQueueTest` | §8 指定三项各有测试类；T65 行补充会话不跨源负例 |

## 2. 工作包 T61–T70 实际结账

| 编号 | 完成标准（§6） | 实际结果 | 证据 |
| --- | --- | --- | --- |
| T61 | 基线/语料/决策冻结，Step0 取证，台账立账 | **完成**：S-A/S-B 实测失败模式取证（v0.1.5 快照 0 正文命中 → v0.1.6 命中）；D1–D4 与两-tier 分级冻结；本台账即 T61 立账项（随 T70 备妥补齐） | [T61 冒烟取证](V0.1.6-T61-SMOKE-EVIDENCE.md)、[执行计划 §5/§8](EXECUTION-PLAN-V0.1.6.md) |
| T62 | 通用观测扩展（SW 拦截、只读注入、Blob 代理、iframe src） | **完成**：`PageSignal*` 只读注入链路＋A11 SW 兼容回归登记；注入只读性有审计测试（R5） | 提交 f43dd8e；`PageSignalScriptTest`/`PageSignalBridgeTest`/`PageSignalParserTest`/`MediaUrlFilterTest` |
| T63 | 自动验证与检出即解析；TED 7 档不再全拒；UNKNOWN 噪声收敛 | **完成**：限额自动探测队列（Range+CT 合并、会话镜像）＋m3u8 检出即解析入面板（变体/分辨率前移）；清单预算 3→6/epoch＋8 MiB 聚合上限，页面 URL 误报过滤；S-A 四档全 VERIFIED、S-B 2 变体 | 提交 f43dd8e/a6e3bed；`AutoProbeQueueTest`/`ParseOnDetectionCoordinatorTest`/`ProbeInterpreterTest`；[T61 取证两轮](V0.1.6-T61-SMOKE-EVIDENCE.md) |
| T64 | 播放器指纹（flashvars/kvsplayer/html5player/xplayerSettings/stream_data）正反例；S-A/S-B 家族命中 | **完成**：指纹家族判定纯函数＋配置提取正反例测试；S-A（DOM 配置→指纹链路）与 S-B（REQUEST/TIMING 通用观测）真实页命中源＋quality 对 | 提交 f43dd8e；`FamilyDetectorTest`/`ParserTest`；[T61 取证](V0.1.6-T61-SMOKE-EVIDENCE.md) |
| T65 | 会话透传与访问合同；S-A 年龄 Cookie 场景端到端可保存 | **完成**：直链会话透传（`canUseProbedContext` 探测验证关联升级，仅在用户明示同意时）；S-A 年龄 Cookie 端到端随 T69 冒烟闭环——年龄 Cookie 供页面加载，传输按策略匿名放行（token 化清单自带访问），**未放宽任何策略** | 提交 74e4bc3；`RequestPolicyProbedContextTest`/`ResourceSnifferPageUrlTest`/`AutoProbeQueuePageUrlTest`；T69 行 |
| T66 | DASH 受限支持；下载路线 Go/No-go 显式 | **完成（No-go 显式）**：mpd 档位入面板（仅展示）；下载路线 No-go，UI 显式标注不支持 DASH 下载 | 提交 74e4bc3；`MpdCatalogTest`；`BrowserPanels`/`ResourcePresentation` |
| T67 | YouTube 完整传输关口（H02）Go/No-go | **已诊断 No-go（待用户决策）**：三轮有界电池将 403 收窄为按 clen 的**比例窗口门控**（Range 终点约前 1/10 内放行，末片必然在窗口外）；闭区间分段双轨传输已实现仍被窗口拦（最后一片 403）；"仅匿名、无 Cookie/token/pot"硬约束下传输层不可达成，产品审计诚实失败（ACCESS_CONDITION）。**未擅自放宽红线**；处置见"开放决策" | [T67 门控诊断](V0.1.6-T67-GATE-DIAGNOSTIC.md)；提交 43397b1/5a763ea/f28c7d8/68c9404；`V016YouTubeGateDiagnostic`（34 臂复跑） |
| T68 | 传输/封装/长样本/系统（H04/H05/H06） | **必要级完成**：闭区间 4 MiB 分段双轨传输（header 载波）＋设备测试绿（`DualTrackTransferTest`）；JVM 全套 282 项绿（R2/R3）；>30 分钟长样本、≥15 分钟真实后台、API28/36/37 分层矩阵＝**增强级未执行**（下表 E2/E3/E4） | 提交 f28c7d8/68c9404；`DualTrackPlanTest` |
| T69 | 冻结语料三率配对（必要级口径：S-A/S-B 冒烟＋台账登记） | **必要级完成**：两站"发现→档位→保存→可打开"真实成品冒烟各一条全绿（R4）；≥2 非 YouTube 旧失败→新成功（S-A/S-B 即 v0.1.5 失败场景，均走通用层，非站点规则）；完整三率配对语料报告＝**增强级未执行**（E1） | [T61 取证·T69 节](V0.1.6-T61-SMOKE-EVIDENCE.md) |
| T70 | 升级/最终包/发行冻结（H08/H09/H10） | **备妥待放行**：本台账立账、dev 身份 `0.1.6-dev/code15`、执行计划 §9 状态更新；真实 code13 同包同证书覆盖升级、最终签名包身份链、tag＝**未执行**（等待用户对 T67 决策与发行放行；不 tag／不推送） | 本文件；`app/build.gradle.kts`；[执行计划 §9](EXECUTION-PLAN-V0.1.6.md) |

## 3. 增强级登记（未执行，不阻挡本版交付）

| 编号 | 项目 | 状态 |
| --- | --- | --- |
| E1 | 完整跨站配对语料三率报告（含 v015 8 作品/4 服务候选重审配对） | 未执行；S-A/S-B 两条必要级冒烟已覆盖首要挑战场景 |
| E2 | >30 分钟有声双轨实际输入封装 | 未执行 |
| E3 | ≥15 分钟真实后台/锁屏传输 | 未执行 |
| E4 | API28/36/37 分层矩阵 | 未执行（本轮设备测试仅 API37 AVD） |
| E5 | 真实 code13 同包同证书覆盖升级 | 未执行（待发行冻结时执行） |
| E6 | 无障碍全量回归 | 未执行 |
| E7 | 多厂商真机 | 未执行 |

## 4. 开放决策（待用户，不代答）

1. **T67 YouTube 处置**：证据表明匿名硬约束下双轨完整传输被服务端字节窗口门控阻断（非应用缺陷）。两个选项均属产品决策：
   - **接受本版 No-go**：YouTube 保持"探测可见、传输诚实失败"，§7 的"YouTube 真实双轨成品"必要项按 No-go 决策记录调整承诺；
   - **放宽到会话上下文路线**：引入站点播放器上下文/pot 体系的会话传输（超出"仅匿名"红线，需要用户显式同意红线变更，另立方案）。
2. **T70 发行放行**：必要级已达标；是否建立 `v0.1.6` tag／Release、执行 code13 覆盖升级与最终包身份链（E5），待用户对上述决策与发行时机表态后执行。

## 5. 发行身份（当前为开发身份，未冻结）

- `versionName 0.1.6-dev` / `versionCode 15`（`app/build.gradle.kts`；Debug 变体沿用 `-debug` 后缀与 `.debug` 包名）。
- 未创建 tag、未推送、未生成最终签名包；上述均在用户放行后按 H08–H10 执行。
- 敏感站点以登记代号 S-A/S-B 记录，明细仅存本地（D4 来源表述约定）；正式文档不出现研究材料获取方式。
