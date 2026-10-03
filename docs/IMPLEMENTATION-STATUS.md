# PureBrowser 当前实现状态

## v0.1.4 当前实现／条件性验收快照

日期：2026-10-03（Asia/Shanghai）。**T41–T48 implemented；T49最终验收进行中，T50 pending、未冻结或发布。** 已发行版本仍为v0.1.3/code10。

- 统一紧凑浅深主题、顶部地址／底部五动作、首页与菜单、标签网格／列表／搜索／有界预览、资源与直链／HLS确认、下载速览／管理、视频库／文件操作、书签／日期历史／设置／关于已实现。
- 候选API37完整301／API28 162／visual60／phone15／JVM156通过，Debug Lint0错误／33提醒。截图是候选生产组件合成fixtures，不是final签名APK。
- API36 sidecar summary21项通过，含真实系统字体／窗口、15分钟后台、通知拒绝和OS限制；host IME观察不另加JUnit；真实OS分屏、实际TalkBack焦点顺序仍pending，不声称TalkBack听音频。
- 签名API36 share修复后通过：debug.test／release.test同名fixture误选，接收器标签加入 `${applicationId}`，helper读取自身ActivityInfo标签精确匹配，HlsProductAudit统一helper。失败历史保留。
- API36 RC2实际24标签切换／menu／resources12轮通过；最终同设备／WebView baseline配对pending，不据跨设备初测宣称性能提升。
- code13先前55bdbc6输入完整302/302 **历史PASS**（535.936秒，source_unchanged=true），JVM156／Debug Lint0错误33提醒／host34也历史PASS。当前fixture修复输入`9d9568096b29f3f1551d2962606098723a147755`：生产代码未动，phone-ready15正在API37跑，随后API28 density240 visual60／core162，再API37完整302重跑，均pending，不把旧输入冒充当前。
- 旧final13 visual44/60（15wide skip＋HOME root未ready失败）和phone14/15（HOME root null）均FAIL，完整保留。V014VisualAudit更新serviceflags后最多等5秒非null root，仍拒绝外国前台窗口／IME，截图隐私guards与业务断言全保留。
- 精确最终签名smoke计划8项（原7＋actualTabSwitchAndRepeatedMenuResourcesWithTwentyFourTabs）、安装哈希／升级／HTTPS／share／freeze与发布pending；覆盖升级补旧历史及自定义shortcut实际UI验证，同设备API37 code10 vs final13配对未完成，结果由主代理更新。
- 大文件／长HLS明确复用v0.1.3历史适用证据，不称本版重跑；不宣称OEM、30样本或7日用户试用通过。

[条件性完成摘要](V0.1.4-COMPLETION.md) · [必要验收台账](RELEASE-ACCEPTANCE-V0.1.4.md) · [候选记录](V0.1.4-CANDIDATE.md) · [执行计划](EXECUTION-PLAN-V0.1.4.md) · [发行说明草稿](releases/v0.1.4.md)。下文旧版“当前”均仅指各自历史时间，不覆盖本节。

## v0.1.3 发行快照

版本 **0.1.3 / versionCode 10**，正式包与长期证书不变。T31–T40 的交付和最终包核验见 [完成摘要](V0.1.3-COMPLETION.md) 与 [发行说明](releases/v0.1.3.md)。下文 rc.1/rc.2 及旧版状态保留为历史，不能用其中“当前/未发行”描述覆盖本节。

## 以下为收尾前历史记录


## v0.1.3 收尾前候选历史更新（不是当前状态）

当前 `0.1.3-rc.2 / code9`。已完成开发机大文件/长HLS、15分钟后台、真实系统限制/网络及签名HTTPS补验；详细实绩和未完成项见 [RC2开发机补验](V0.1.3-RC2-LOCAL-ACCEPTANCE.md)。修复后 API37 单批225项已通过，发行冻结仍待收尾，不创建正式v0.1.3标签。下方rc.1统计保留为历史，不能当作rc.2全量通过。


当前分支 feat/v0.1.3；构建0.1.3-rc.1 / versionCode=8。纯本地恢复引擎、schema v5、任务控制、隐私与诊断已接通。
候选实现及证据见V0.1.3-CANDIDATE.md、EXECUTION-PLAN-V0.1.3.md；真实发行门槛见RELEASE-ACCEPTANCE-V0.1.3.md。
最新公开版本仍为v0.1.1；本次按用户要求调整v0.1.3预览版门槛：真机/30样本5环境/5人7日改可选，安全与文件完整性仍必须。大/长任务、当前后台及OS限制、真实HTTPS烟测和升级仍有补验，见新版验收表；没有创建正式v0.1.3标签。历史候选的未完成记录保留，不冒充已通过。

---

## v0.1.2 历史候选记录（不是当前构建）

# v0.1.2 当前开发状态

当前工作分支：`feat/v0.1.2`。构建身份 `0.1.2-rc.3 / versionCode=7`；最新公开发行仍为v0.1.1。不创建v0.1.2正式标签、不宣称全门槛完成。

HLS受控解析、档位、分片、TS采样提取和独立MP4封装、schema v4、公共文件及产品页面已接通。直链/旧任务兼容保持。候选实绩和缺口以 V0.1.2-CANDIDATE.md 为准；实施任务见 EXECUTION-PLAN-V0.1.2.md。

---

## v0.1.1 已发布历史记录

# 当前实现与验证状态

**当前为 v0.1.1 / versionCode=4，正式签名的小范围 Pre-release。** T15–T22 实现/验收见 V0.1.1-COMPLETION.md；远端发行身份见 Release 和 Git tag。

## 新能力

动态与同源 iframe 媒体发现、播放排序与代次隔离；稳定 TaskId / schema v3 / v2 原始备份迁移；全部新任务受控传输、限定网站会话与最小来源、逐跳凭据保护、2 槽队列、前台服务与中断；MP4/WebM 初检后公共发布；原产品文件管理闭环保留。

正式 package 为 `io.github.jeckchen666.purebrowser`，Debug 为 `.debug` 变体；namespace 仍是 `com.example.purebrowser`。原 v0.1.0 包独立保留，不跨包接管旧数据或登录。

## 最终验证

57 单元、91 API37 全回归；API28 19 文件/队列 + 权限拒绝及独立进程重启；API36 真实网站条件/动态 frame + Signed Release 10 授权视频/3 HTTPS 环境 + 覆盖升级；API37 两种文件的实际跨 UID 分享和最终核心 15 分钟锁屏传输。Lint 0 错误/28 提醒，具体边界见完成记录。

## 限制

无云端业务、HLS/DASH 成品、直播、DRM、复杂跨站鉴权/分区会话/JS Token、暂停续传、真正无痕或完整跨域 frame/MSE 关联。格式初检不是全片解码安全保证；本次不是广泛真机/OEM或长电影后台认证。

APK：`app/build/outputs/apk/release/app-release.apk`；本地证据：`app/build/reports/v0.1.1/`。私有密钥、密码、设备备份、测试 APK不发布。

---

## v0.1.0 历史验收（不是当前能力上限）

# 当前实现与验证状态

**T1–T14 已完成，当前交付为 0.1.0 本地产品预览版，versionCode=2。** 第一轮历史证据见 T1-T7-COMPLETION.md，T8 历史见 ROUND-2-STATUS.md，当前完整验收见 ROUND-2-COMPLETION.md。

## 当前能力

原生首页、多标签 WebView、地址与搜索、导航与错误恢复、本地书签/历史/快捷站点/主题；当前标签媒体发现、资源分区和详情、冻结来源的可编辑下载确认；系统任务分组、取消、关联的新任务重试、来源恢复；幂等本地视频库、真实元信息/异步本地缩略图、搜索排序、显示标题；实际 URI 打开/分享；记录移除与确认物理删除分开；默认网络偏好持久化和真实进程重启对账。

无自有云存储、云解析、账号、同步、遥测或后端。生产新增下载限 HTTPS；Debug 只允许指定本地测试地址。HLS/DASH/blob 仅识别；不转发 Cookie/Authorization/Referer，不绕过 DRM。

## 本次最终验证

- 33 项本地单元测试通过。
- API37：75 项最终全量回归通过，无跳过；另 2 项真实 MP4/WebM 跨 UID 文件分享、1 项 host 强制停止后的进程重启、1 项真实外部删除/失效展示验收通过，共 79 项功能仪器测试。
- Lint 0 错误，22 提醒；保留报告，不将提醒说成已消除。
- API28 专用 AVD：写入权限拒绝无假成功、允许真实保存；基线旧 APK 的真实任务/旧 XML 覆盖迁移，原浏览 JSON/旧 XML 覆盖前后字节一致；启动后旧 XML 不改，原始未知字段保持未知。小屏/导航/外部播放器烟测通过。
- 生成样本的完整字节与 SHA-256 对照；HTML 假 MP4（故意声称视频 MIME）被初检拦截；401/403、未知计数、取消/重试、记录移除保留文件、文件删除同步和失效不冒充可播放均有证据。

## 产物

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，沿用原 applicationId 和 Debug 签名，可覆盖安装。
- 本机最终报告/截图：`app/build/reports/round2-product/`。33 项单元 XML 在 `app/build/test-results/testDebugUnitTest/`。
- 执行计划：EXECUTION-PLAN-ROUND-2.md 全部勾选；变更在项目本地 Git 中，不配置/推送远端。

## 未验证或明确后置

真机、多 OEM/版本/大字体/屏幕完整矩阵、长视频/后台可靠性、全面性能/安全审计、正式商店发行签名与政策申报；HLS/DASH 分片/封装、登录访问上下文、直播、真正无痕、内嵌高级播放器、批量/目录选择、物理文件重命名。有限格式初检不是视频完整性或安全保证。详细边界见完成记录。
