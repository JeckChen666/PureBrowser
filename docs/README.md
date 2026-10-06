# 文档索引

更新日期：2026-10-06（Asia/Shanghai）。本目录只保留**当前有效**文档与 `releases/`、`design/`、`archive/` 三个既有子目录；全部版本化执行计划、验收台账与证据文档统一移入 `history/`（文件名不变）。历史文档内的"当前/最新"等表述以写作时点为准，不覆盖本索引。

## 一、当前有效

- [VERSION-ROADMAP-0.1.md](VERSION-ROADMAP-0.1.md)：v0.1.x 版本路线图与阶段结账（含 v0.1.5 失败关闭、红线修订记录）。
- [EXECUTION-PLAN-V0.2.0.md](EXECUTION-PLAN-V0.2.0.md)：当前版本 v0.2.0 执行计划（DRAFT，D17–D21 待确认冻结）。
- [USER-GUIDE.md](USER-GUIDE.md)：用户指南与能力边界。
- [PRIVACY.md](PRIVACY.md)：隐私说明。
- [SECURITY-BASELINE.md](SECURITY-BASELINE.md)：安全基线与 Manifest 边界。
- [RELEASE-SIGNING.md](RELEASE-SIGNING.md)：正式签名与本地发行流程。
- [DEVELOPMENT-ENVIRONMENT.md](DEVELOPMENT-ENVIRONMENT.md)：最初开发环境的历史记录，不是跨机器配置模板。
- [SOURCE-RESEARCH.md](SOURCE-RESEARCH.md)：GitHub 参考项目、许可证与采用／不采用设计。
- [HLS-FIXTURES.md](HLS-FIXTURES.md)：受控 HLS 测试夹具的所有权、生成与本地服务说明（`app/src/androidTest/assets/hls` 当前仍在用）。
- [releases/](releases/)：各公开发行版本的发行说明。
- [design/](design/)：UI 设计方向与运行截图取舍。
- [archive/](archive/)：更早的基线快照（产品规划／实现状态的技术基线存档）。

## 二、版本历史（`history/`，从新到旧）

每版一行：发行说明（`releases/`）＋执行计划＋验收台账＋关键证据文档。v0.1.2 未单独发行（能力并入 v0.1.3）；v0.1.5 计划失败关闭、无发行说明，以关闭总结为准。

- **v0.1.9**：[发行说明](releases/v0.1.9.md)｜[执行计划](history/EXECUTION-PLAN-V0.1.9.md)｜[验收台账](history/RELEASE-ACCEPTANCE-V0.1.9.md)｜[T103 验收证据](history/V0.1.9-T103-ACCEPTANCE-EVIDENCE.md)、[T100 规则证据](history/V0.1.9-T100-RULES-EVIDENCE.md)、[AV1 探针](history/V0.1.9-T102-AV1-PROBE.md)
- **v0.1.8**：[发行说明](releases/v0.1.8.md)｜[执行计划](history/EXECUTION-PLAN-V0.1.8.md)｜[验收台账](history/RELEASE-ACCEPTANCE-V0.1.8.md)｜[T89 规则证据](history/V0.1.8-T89-RULES-EVIDENCE.md)、[T88 窗口研究](history/V0.1.8-T88-WINDOW-RESEARCH.md)、[AV1 评估](history/V0.1.8-AV1-EVALUATION.md)
- **v0.1.7**：[发行说明](releases/v0.1.7.md)｜[执行计划](history/EXECUTION-PLAN-V0.1.7.md)｜[验收台账](history/RELEASE-ACCEPTANCE-V0.1.7.md)｜[验证证据](history/V0.1.7-VALIDATION-EVIDENCE.md)
- **v0.1.6**：[发行说明](releases/v0.1.6.md)｜[执行计划](history/EXECUTION-PLAN-V0.1.6.md)｜[验收台账](history/RELEASE-ACCEPTANCE-V0.1.6.md)｜[T61 冒烟取证](history/V0.1.6-T61-SMOKE-EVIDENCE.md)、[T67 门槛诊断](history/V0.1.6-T67-GATE-DIAGNOSTIC.md)、[嗅探增强调研](history/V0.1.6-SNIFFING-ENHANCEMENT-RESEARCH.md)、[承接台账](history/V0.1.6-CARRYOVER.md)
- **v0.1.5（失败关闭，无发行说明）**：[关闭总结](history/V0.1.5-CLOSEOUT.md)｜[执行计划](history/EXECUTION-PLAN-V0.1.5.md)｜[候选记录](history/V0.1.5-CANDIDATE.md)｜[第一轮执行](history/V0.1.5-P1-EXECUTION.md)、[第二轮](history/V0.1.5-ROUND-TWO.md)、[语料前置检查](history/V0.1.5-CORPUS-PREFLIGHT.md)、[挑战样本预检](history/V0.1.5-CHALLENGE-SAMPLE-PREFLIGHT.md)、[样本登记](history/V0.1.5-SAMPLE-REGISTRATION.md)、[兼容语料 v0.1.5](history/COMPATIBILITY-CORPUS-V0.1.5.md)
- **v0.1.4**：[发行说明](releases/v0.1.4.md)｜[执行计划](history/EXECUTION-PLAN-V0.1.4.md)｜[验收台账](history/RELEASE-ACCEPTANCE-V0.1.4.md)｜[完成摘要](history/V0.1.4-COMPLETION.md)、[候选记录](history/V0.1.4-CANDIDATE.md)、[Lint 处置](history/LINT-DISPOSITIONS-V0.1.4.md)
- **v0.1.3**：[发行说明](releases/v0.1.3.md)（[rc.1](releases/v0.1.3-rc.1.md)、[rc.2](releases/v0.1.3-rc.2.md)）｜[执行计划](history/EXECUTION-PLAN-V0.1.3.md)｜[验收台账](history/RELEASE-ACCEPTANCE-V0.1.3.md)｜[完成摘要](history/V0.1.3-COMPLETION.md)、[RC2 开发机补验](history/V0.1.3-RC2-LOCAL-ACCEPTANCE.md)、[开发机验收](history/DEVELOPMENT-MACHINE-ACCEPTANCE-V0.1.3.md)、[兼容语料](history/COMPATIBILITY-CORPUS-V0.1.3.md)、[回归修复](history/REGRESSION-FIXES-V0.1.3.md)、[Lint 处置](history/LINT-DISPOSITIONS-V0.1.3.md)
- **v0.1.2（未单独发行）**：[执行计划](history/EXECUTION-PLAN-V0.1.2.md)｜[候选验收](history/V0.1.2-CANDIDATE.md)（HLS 能力并入 v0.1.3）
- **v0.1.1**：[发行说明](releases/v0.1.1.md)｜[执行计划](history/EXECUTION-PLAN-V0.1.1.md)｜[完成摘要](history/V0.1.1-COMPLETION.md)
- **v0.1.0**：[发行说明](releases/v0.1.0.md)｜[第二轮执行计划](history/EXECUTION-PLAN-ROUND-2.md)｜[第二轮完整验收](history/ROUND-2-COMPLETION.md)｜[第一轮 T1–T7 完成](history/T1-T7-COMPLETION.md)、[第一轮执行清单](history/EXECUTION-PLAN.md)
- **前置规划**：[产品化规划](history/PRODUCT-PLAN.md)、[实现状态历史快照](history/IMPLEMENTATION-STATUS.md)（v0.1.x 各阶段"当前状态"的按期留存）。

### 本次整理移除的中间态文档（证据纪律：不抹历史，仅移除被取代的中间态）

- `NEXT-ACTIONS-V0.1.5.md`：内容已被 [V0.1.5-CLOSEOUT.md](history/V0.1.5-CLOSEOUT.md) 的最终结账取代。
- `V0.1.5-DEVELOPMENT-CHECKPOINT.md`：自述仅为可恢复的源码保存点，终态见关闭总结。
- `ROUND-2-STATUS.md`：T8 阶段中间快照，最终验收见 [ROUND-2-COMPLETION.md](history/ROUND-2-COMPLETION.md)。

## 三、本地资料（不入库）

`research/` 目录被 `.gitignore` 排除，仅在本机保留调研草稿、第三方取证材料与本地实验记录；不入库不构成任何已验证结论，追溯以提交入库的历史文档与代码为准。
