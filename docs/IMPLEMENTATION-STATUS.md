# v0.1.3 当前候选状态

当前分支 feat/v0.1.3；构建0.1.3-rc.1 / versionCode=8。纯本地恢复引擎、schema v5、任务控制、隐私与诊断已接通。
候选实现及证据见V0.1.3-CANDIDATE.md、EXECUTION-PLAN-V0.1.3.md；真实发行门槛见RELEASE-ACCEPTANCE-V0.1.3.md。
最新公开版本仍为v0.1.1；v0.1.2真实验收欠项继承，没有创建正式v0.1.3标签。

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
