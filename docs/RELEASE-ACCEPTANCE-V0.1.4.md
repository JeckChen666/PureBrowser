# v0.1.4 发行验收台账（统一 UI 与核心交互改版）

规划日期：2026-10-03（Asia/Shanghai）。状态：**T41–T48 implemented；候选回归已有通过结果，T49最终验收进行中，T50 pending、未发布。** 当前验收输入为`9d9568096b29f3f1551d2962606098723a147755`（code13）；只修复测试fixture的异步root就绪等待，生产代码未动。`55bdbc6`的完整302/JVM156/Lint0+33/host34通过历史保留，但不称当前输入已通过。phone-ready15正在运行，随后计划API28 density240 visual60、core162，再API37完整302；当前输入各批结果均pending，由主代理最后更新。本表任何pending仍不能放行，最终实际结果由主代理据release附件更新。
范围以 [版本与执行计划](EXECUTION-PLAN-V0.1.4.md) 的 P0 为准；视觉与交互依据 [紧凑版 V4](design/compact-browser-2026-10-03/README.md)。
本表适用于正式签名 APK / GitHub Pre-release，不代表商店审核、全网站或全 OEM 认证。

## 1. 必须通过

| 编号 | 必要门槛 | 本版需要的证据 | 状态 |
| --- | --- | --- | --- |
| M1 | 完整 UI 范围与设计一致性 | 5 组／15 个关键状态的真实截图和对应说明；首页、网页、输入、标签网格／列表／搜索、资源、速览、管理、视频库、文件操作、设置、菜单、书签、历史均迁移；不出现重复地址栏或新旧样式割裂；深色主路径补图 | 候选60矩阵／15手机截图通过；真实主路径与final适用性待核验（pending） |
| M2 | 导航、输入、焦点与 WebView 状态 | 编辑取消／提交、IME、前后退、首页、加载／停止／错误重试、工具关闭、专页往返与系统返回；页面位置、活跃标签和网页栈保留；工具切换无多层遮挡／底层误触／键盘反复弹起 | 候选完整回归及状态15专项通过；final输入覆盖待核验（pending） |
| M3 | 标签总览的真实可用性 | 1／24／50 标签和第 51 个被正确拒绝；网格／列表偏好重启保留、标题／URL 搜索、无结果／清空、筛掉当前后的定位、关闭当前／非当前／最后一页、空数据恢复；缓存有上限、不持久化／泄漏截图、不无故加载全部 WebView | 候选标签测试及API36真实24标签12轮通过；final覆盖待核验（pending） |
| M4 | 核心保存闭环与真实任务能力 | 从网页入口各完成一条支持的直链和 HLS 保存；确认／取消／来源冻结／档位正确；速览与完整页一致；任务动作按能力，重复点击不多建任务；等待网络／暂停／恢复／取消／失败／封装／校验／发布／完成及未知大小显示准确；重启对账不静默恢复 | 实现及候选回归已有；final签名保存闭环待核验（pending） |
| M5 | 数据、文件与隐私安全 | 视频库搜索／排序／显示名称、实际外部播放与跨 UID 分享；移除记录保留文件、删除确认及取消、文件外部删除／不可读；历史清理不误删书签／登录／成品；分项清理和诊断预览／主动分享保留；截图缓存随相关清理失效；必要时验证新增偏好或浏览数据迁移 | 候选文件／隐私回归、修复后签名API36 share通过；final包待核验（pending） |
| M6 | 紧凑界面的适配和可访问性 | 浅／深色、约 320dp 窄屏、常规手机、横屏／低高度／分屏与较大窗口；默认及系统放大字体（至少含 1.3×／2.0×）、手势／按键导航、IME；48dp 有效点击区、图标名称／禁用／选中状态与 TalkBack 顺序；必要动作可达、状态不被截断 | 部分候选实测通过；真实OS分屏／实际TalkBack焦点pending，字体final适用性待核验 |
| M7 | 当前生产代码的构建、自动回归与性能 | Debug／Release／测试包构建，JVM 与 API37 完整回归；API28 权限／存储与新版核心 UI，API36 输入／返回／系统限制相关专项；新增交互测试；Lint 0 错误、提醒逐项说明；同设备／WebView／样本下与 v0.1.3 对比开总览、切标签和多次开关面板，无崩溃、ANR、失控缓存或明显持续回退 | 候选301／162／156及Lint0+33、API36 summary21通过；55bdbc6输入302／JVM156／Lint0+33／host34历史通过；9d95680当前phone-ready在跑，visual／API28／完整302重跑与配对pending |
| M8 | 签名升级、最终包与发行材料 | v0.1.3/code10 → 最终候选的同包同证书实际覆盖升级、旧偏好／记录／任务／文件保留；最终签名包核心 HTTPS 与跨 UID 分享冒烟；源码／APK／哈希／证书／版本冻结一致；无测试组件、测试 CA、HTTP 例外或私密内容；完成说明、限制与复用证据完整 | 候选签名share修复后通过；最终包身份／升级／HTTPS／share／freeze及发布pending |

M6 的宽度／字体倍率为本计划的测试采样目标，不代表系统只能使用这些值；在测试环境确实设置并记录，不能靠缩小截图模拟。
正常 UI 至少保留一个标签；M3 的“空数据”是恢复边界，不要求提供无标签工作模式。

### 1.1 当前证据快照与待验边界

- 候选 `full-frozen` 301/301、`api28-verified-final` 162/162、`visual-matrix-01` 60/60、`phone-visual-01` 15/15；JVM156，Debug Lint0错误／33提醒。原始报告在本地 `app/build/reports/v0.1.4/`，失败批次保留，不拼接为完整成功。
- `api36-sidecar-summary.json` 的21项：真实系统字体／窗口三组各6项，后台15分钟／通知拒绝／OS timeout各1项；另3个host IME观察通过，不计为额外JUnit。结果绑定RC2/code12 debug及当时输入指纹，不是final13重跑。
- 签名API36 share修复后通过：debug.test／release.test同名fixture误选；Manifest标签加入 `${applicationId}`，helper读取自身ActivityInfo标签精确匹配，HlsProductAudit统一helper。旧失败保留，不放宽业务断言。
- API36 RC2真实24标签切换／menu／resources12轮通过；旧版API37与RC2 API36初测不同设备／WebView，不能据此放行同设备性能对比。最终同设备API37旧已发行code10 vs final13 baseline配对pending。
- 截图是候选生产组件配合合成fixtures；不是final签名APK截图或真实下载／OS分屏证明。
- 55bdbc6输入API37完整302/302 **历史PASS**，535.936秒，source_unchanged=true（`final13-api37/result.json`）；JVM156、Debug Lint0错误／33提醒、host34 **历史PASS**，不求和为应用覆盖。旧输入指纹`39f0f5ab2ee8419da10ac9d79ebda2e9ae5d9a9c2e26bc11612a68609b4dceb2`，不能作为当前`9d9568096b29f3f1551d2962606098723a147755`输入证明。
- 旧final13 visual **44/60 FAIL**：API37原生411dp误配wide600dp门槛，15skip＋首个HOME root未ready失败；旧phone **14/15 FAIL**：首个HOME root null。失败完整保留，ok=false，不计通过。
- `9d95680`仅修复V014VisualAudit更新serviceflags后的异步root连接：最多等5秒非null，超时仍失败；外国前台窗口／IME拒绝和截图隐私guards不变，生产代码未动。当前phone-ready15正在API37跑，随后API28 density240 visual60／core162、API37完整302重跑，均pending；旧JVM／Lint／host的当前适用绑定或重跑另核验。

- 最终签名smoke计划8项＝之前7项＋`actualTabSwitchAndRepeatedMenuResourcesWithTwentyFourTabs`，**pending，未称8项通过**；要求精确code13签名APK／安装哈希绑定。
- 覆盖升级保留验证计划补旧历史及自定义shortcut的实际UI覆盖；不只已有书签／主题／Wi-Fi偏好／任务视频，也不只核对存储数据。该补测仍pending。

### 1.2 M6：真实字体已有，分屏与实际TalkBack焦点待补

| 子项 | 已确证 | 剩余验收 |
| --- | --- | --- |
| 真实系统字体／低高度 | `api36-native-font13`：系统1.3×；`api36-font20-density540`：2.0×／约320dp；`api36-landscape-font13`：640×360dp／1.3×，各6/6，环境配置与恢复有记录 | 核验对final输入的适用性；不能改写候选身份，无法合法绑定则重跑相关小专项 |
| 真实OS分屏 | 组件宽度矩阵、wm density／size override只证明其各自范围 | **pending**：实际分屏task／窗口bounds、比例变化、IME和必要动作可达；不能拿低高度全屏代替分屏 |
| 实际TalkBack焦点顺序 | 语义自动化已覆盖；API37 lab安装`com.google.android.marvin.talkback` | **pending**：当前串行设备批次结束并确认设备空闲后启用服务、实际逐步焦点截图、模态不穿透背景与关闭后返回焦点、secure settings恢复 |

TalkBack本轮只计划验证实际焦点／交互，不声称听取或核验音频。旧summary明确UIAutomation语义顺序不是TalkBack speech；安装服务或普通输入焦点不等于实际无障碍焦点。没有补证前M6仍pending。

### 1.3 M6 最小 host 检查建议（仅建议，尚未执行）

先与设备使用者确认当前visual／phone等串行批次已结束、无instrumentation运行；本清单不授权中断测试。只使用现有ADB／Android系统能力，不新增app依赖。

**分屏／低高度：**先保存完整`wm size`、`wm density`、系统`font_scale`及原窗口状态，再检查lab实际提供的WMShell命令：

```sh
# SERIAL和OUT必须由执行者选择；OUT为Git忽略的证据目录
adb -s "$SERIAL" shell dumpsys activity service SystemUIService WMShell help
adb -s "$SERIAL" shell dumpsys activity activities > "$OUT/activity-before.txt"
adb -s "$SERIAL" shell dumpsys window windows > "$OUT/window-before.txt"
adb -s "$SERIAL" shell wm size
adb -s "$SERIAL" shell wm density
adb -s "$SERIAL" shell settings get system font_scale
```

按设备help中实际支持的splitscreen子命令进入系统分屏；若未提供可用入口，用系统Recents的分屏操作配合现有Settings作另一侧，不假定旧版`am stack`或固定windowing-mode数字适用。进入和调整比例后各保存WMShell状态、目标Activity配置／task与window bounds和截图，证明两侧stage及目标应用的实际非全屏可见区域；只有一个multi-window字段也不足以证明就是分屏。覆盖地址输入＋IME、底栏、标签／menu／resources、管理页返回。低高度全屏可另用已记录的640×360dp OS配置，但不得用它替代分屏。

**TalkBack实际焦点：**先保存当前user的secure keys `enabled_accessibility_services`与`accessibility_enabled`（保留原值或不存在状态），检查`dumpsys accessibility`的已安装／绑定服务及`dumpsys package com.google.android.marvin.talkback`，确认真实组件。启用时追加TalkBack组件而非覆盖原有服务；确认已bound且无UiAutomation suppression，再逐步采集：

```sh
adb -s "$SERIAL" shell dumpsys accessibility > "$OUT/accessibility-enabled.txt"
adb -s "$SERIAL" exec-out screencap -p > "$OUT/focus-00.png"
# X1/X2/Y取当前截图内容区坐标；一指向右短划，等待焦点稳定
adb -s "$SERIAL" shell input touchscreen swipe "$X1" "$Y" "$X2" "$Y" 150
adb -s "$SERIAL" exec-out screencap -p > "$OUT/focus-01.png"
```

逐次划动并截图，人工索引`step → 实际可见TalkBack焦点框 → 控件 → screenshot`；不快速批量注入。用反向划动检查返回顺序，实际打开／关闭工具或确认层，检查焦点不进入背景且关闭后合理返回。若注入划动未触发TalkBack或看不到焦点框，结果记未验证，不从XML顺序推断通过；可用模拟器原生触控复核，不装新app。

避免在该序列中运行普通`uiautomator dump`／`uiautomator events`：UiAutomation默认可能抑制其他无障碍服务，破坏正在观察的TalkBack状态。普通XML `focused=true`或Tab／DPAD输入焦点也不是TalkBack无障碍焦点证据。截图只证明实际焦点／可达操作，不声称音频已听取或朗读内容正确。

结束及异常退出均恢复：secure原值（原不存在则delete，不能写字面`null`）、原font_scale、原size／density override和原窗口状态；有既有override不能一律`wm reset`。重新读取settings／wm及服务状态，记录恢复一致性；不清除应用数据、不移动用户文件。

## 2. 新增自动化测试最小集合

不提前承诺固定测试数量；以下行为必须有明确覆盖，适合自动化的补自动化，需实机／模拟器观察的保留专项证据。

1. 顶部地址展示／完整编辑／取消／提交，本地建议选择及清空；不产生重复导航或焦点回弹。
2. 底部标签／菜单入口、资源提示、下载速览；关闭工具与专页往返保留网页状态。
3. 输入、面板、专页、网页后退之间的互斥与返回优先级，背景不接受操作。
4. 标签网格／列表／搜索／定位、偏好重启、当前被筛掉与三种关闭边界。
5. 标签预览的 TabId／代次隔离、失效／淘汰、释放与无图占位；不调用无界全标签重建。
6. 资源确认中切页／取消／重复点击、直链／HLS准备状态，不将旧来源发到新页面任务。
7. 速览与完整任务页共享能力，忙碌／不可恢复／未知大小／处理阶段的正确语义。
8. 视频库缺失文件、移除／删除取消、外部打开／分享失败反馈，历史分组与搜索不改变原记录。
9. 深色和大字体下必要动作可达、图标语义存在，诊断和分项清理没有绕过用户确认。

测试使用专用设备或先备份用户数据；清理只针对测试样本，不为跑 UI 测试清空个人浏览与文件。

## 3. 证据与回归规则

- 每份报告注明源码提交、构建身份、签名变体、设备／API、WebView 版本、主题／字体／窗口和测试时间；截图只用自制或授权网页，不公开登录页或私人网址。
- 逐工作包做验证，最终批次覆盖当前生产代码。失败批次保留；修复后重跑相关测试，不把跳过或删测试算通过。
- 测试更新应对应明确的交互合同变化；不得把任意新界面表现改成测试期望来掩盖语义回归。
- UI 生命周期、服务、请求、下载、恢复或存储变动时，重跑相关后台／大文件／长 HLS／故障专项；“主要改 UI”不构成免测理由。
- 下载引擎与配置未变时，可以引用 v0.1.3 的大／长任务、后台与限制证据，但列出来源版本、未变范围和本版补测；不能写成 v0.1.4 重新通过。
- 最终冻结包若仅版本身份变化，可按证据规则复用此前完整回归；仍须针对最终包核验安装哈希、升级和核心签名冒烟。生产代码／配置变化则重跑受影响范围。
- 性能对比先固定可复现实验并记录基线，不在测量前写“零卡顿”或任意分数。明确的持续性能回退须修复；没有测量的数据保持未验证。
- 候选执行证据、冻结源码适用证据、确切最终签名APK证据分开记录。目录名final、报告计数或外层source_commit绑定不能证明旧输入等价；保留原生revision／版本身份，不改旧报告来满足helper。
- freeze helper严格final绑定规则不改；脚本成功只表示本地staging通过，不替代M1–M8逐项放行。文档条件性冻结不是发布许可。
- 大文件／长HLS明确引用v0.1.3历史证据；API36当前后台／OS限制补测另列，sidecar无当前后台HLS运行结论。

## 4. 可选增强

| 编号 | 项目 | 边界 |
| --- | --- | --- |
| O1 | 不同厂商物理设备、OEM 后台策略 | 不把模拟器证据说成真机验证；发现安全／数据／阻断问题仍阻挡发行 |
| O2 | 更大真实 HTTPS 样本、多站点与多人长期试用 | 核心签名 HTTPS 冒烟仍为必要；不重复继承旧版固定样本数量 |
| O3 | 平板／折叠屏专属布局、键鼠强化 | 可变窗口基本可用是必要；专属布局不是本版承诺 |
| O4 | 短时撤销、精细动效与额外视觉打磨 | 不延后必要语义、焦点和点击区域；撤销不能虚报完整页面恢复 |

## 5. 发行阻断与最终状态

误删／数据丢失、凭据或截图泄漏、假完成、跨标签来源错误、重复任务、必要入口不可达、WebView 状态严重回退、系统返回误退出，以及明确的崩溃／ANR／签名升级失败均阻挡发行。
可选项中的测试若发现这些问题，同样阻挡，不因其编号是 O 而忽略。

全部 P0 与 M1–M8 满足、证据与未覆盖项如实记录后，才可冻结 `v0.1.4`。内部候选使用 `0.1.4-rc.N`；本次已提交 `0.1.4 / code13` 最终版本输入用于验收，**不表示T50已完成或已发布**。不提前建立正式tag／Pre-release，不将pending勾为通过。

最终条件性汇总见 [完成摘要的最终验收表](V0.1.4-COMPLETION.md)。目前55bdbc6输入302／JVM156／Lint0+33／host34历史通过保留，旧visual44/60／phone14/15失败保留；9d95680当前phone-ready运行中，visual／API28／完整302重跑、M6分屏／实际TalkBack焦点、同设备配对、签名smoke8项／实际UI覆盖升级及发行身份pending。release附件待验收后生成，主代理据真实结果更新；不默认其已存在。
