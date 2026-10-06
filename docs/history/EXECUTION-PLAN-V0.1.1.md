# v0.1.1 执行记录

开始日期：2026-10-02（Asia/Shanghai）。基线：公开 v0.1.0 / 09c1472。

本版所有新任务使用受控传输；独立正式包 `io.github.jeckchen666.purebrowser`，Debug 后缀 `.debug`；不跨包接管 v0.1.0 数据。成品保存到公共 Download/PureBrowser，访问条件默认启用且可关闭。保持纯本地、现有架构与测试栈。

- [x] T15 发行身份、TaskId、schema v3 与兼容接口
- [x] T16 动态扫描、同源 iframe、证据关联与排序
- [x] T17 访问上下文、请求策略与错误分类
- [x] T18 受控传输、队列、前台服务与中断对账
- [x] T19 公共成品、存储抽象与文件操作
- [x] T20 原有产品界面整合
- [x] T21 自动回归、真实 HTTPS 样本、15 分钟后台与权限矩阵
- [x] T22 正式签名、验收说明与发行

## 发布门槛

至少 12 个冻结场景、3 个独立 HTTPS 站点/环境及 10 个授权视频；15 分钟锁屏/后台、真实跨 UID 分享、进程终止对账；API28/36/37；Release HTTPS 烟测与候选升级；无凭据泄漏、假成功、误删或主链路回归。未满足则只交付 RC，不创建 v0.1.1 tag。

## 已确认的范围

公开/签名/无后缀 MP4 与 WebM、限定同源普通 Cookie 和最小来源。无 HLS/DASH 成品、直播、Token 提取、跨站敏感会话、DRM、暂停或续传。签名密钥及密码位于仓库外，历史 tag 和原应用保持不变。

## 最终实绩

- 最终 Debug：57 单元 / 91 API37 全回归通过；Lint 0 错误，提醒见完成记录。
- API28：19 文件/格式/归属/队列场景、真实拒绝权限 1、强停前后独立审计各 1；读写权限仅 maxSdk28，原包不变。
- API36：实际 Cookie/关闭条件/注销/最小 Referer与动态同源 frame 2；正式签名 10 授权视频/3 HTTPS 环境测试 1；签名覆盖升级 seed/check 各 1。
- API37：实际系统 chooser MP4/WebM 独立 UID 读取 1；最终核心屏幕关闭 901 秒传输 1。
- APK 正式签名，无 Debug 回退。当前 versionName=0.1.1，versionCode=4。

详见 V0.1.1-COMPLETION.md。T22 的远端 tag / Release 状态以实际 GitHub 核验为准；发行附件为 APK、SHA256、证书指纹与脱敏验收清单。
