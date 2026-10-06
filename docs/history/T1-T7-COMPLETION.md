# T1–T7 第一轮产品化完成记录

日期：2026-10-01（Asia/Shanghai）  
状态：本轮实现、回归与 APK 交付完成。**这是一份可安装、可操作的浏览产品骨架，不是完整 0.1 产品版。**

## 1. 交付范围

| 任务 | 本轮完成内容 | 主要实现位置（相对项目根目录） |
|---|---|---|
| T1 产品外壳 | 统一浅/深色主题、本地图标、地址栏、工具栏、页面/面板路由、空/错误反馈；保持原资源/下载操作 | theme/、MainActivity.kt、ui/components/、BrowserPanels.kt、launcher 资源 |
| T2 本地数据 | 标签、书签、历史、快捷站点、主题；稳定 ID、schemaVersion、容量边界、IO 串行保存、AtomicFile；损坏备份失败不覆盖 | data/browser/、BrowserViewModel.kt |
| T3 多标签 | 新建/切换/关闭/最后标签回首页；独立引擎和资源集合；三个活跃会话缓存；网址/标题/选中恢复；链接长按新标签 | BrowserSession.kt、TabController.kt、TabSwitcher.kt |
| T4 首页 | 正式首页，快捷站点添加/长按编辑/删除/打开；真实最近访问、书签/历史/下载入口；本地占位图标 | HomeScreen.kt |
| T5 书签历史 | 一键收藏/取消，列表搜索/编辑/删除/打开；成功访问记录、连续重复去重、时间、搜索/单条删除/清空确认 | SavedPagesScreen.kt、BrowserRules、BrowserViewModel |
| T6 交互收口 | 地址提交/取消、系统返回、停止/刷新、网络错误重试、主题保存、菜单和关于；键盘/小屏滚动收口 | BrowserScreen.kt、BrowserControls.kt、SettingsScreen.kt |
| T7 验收交付 | 构建、19 个单元测试、8 个仪器测试、Lint、真实进程重启、本地签名视频下载、截图与 Debug APK | app/build/reports/、app/build/outputs/apk/debug/ |

Kotlin 文件位于 `app/src/main/java/com/example/purebrowser/`。书签和历史共用原生列表组件，并非重复两份 UI。
没有增加云端服务、账户、统计、解析接口、数据库框架或新三方依赖；包名、SDK/Gradle 版本保持本轮起始配置。

## 2. 验证结果（最终代码）

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug`：通过。
- 单元测试 **19 项，0 失败、0 错误**；包含危险 URL、签名参数、页面代次、标签关闭/恢复规则和有界历史。
- 仪器测试 **8 项，全部通过，0 跳过**：本地数据往返/损坏恢复 2 项、签名视频下载 1 项、键盘/错误/取消 1 项、基础入口 3 项、产品操作组合 1 项。
- Lint：**0 错误、21 项非阻断提醒**，没有把提醒当作已清零。
- 真实 `am force-stop` 后重新启动：标签记录与选中项、非空书签/历史、快捷站点、深色主题与重启前一致；不是仅 Activity 重建。
- 三标签切换时资源分别为四个候选、独立页面单个候选、首页空集合；切换回来可正常返回前页。关闭后台标签不影响其余记录。
- 本地视频页识别 MP4（签名）、HLS、DASH、假 MP4 共四个候选，常见 TS/init 分片被过滤；签名 query 原样保留。
- 通过实际 UI 确认下载，系统下载器保存测试 MP4；文件头/视频轨/样本初检通过，SHA-256 与生成的视频一致。关闭来源标签后，任务、文件 URI 和下载中心入口仍可用。
- 下载成品通过“打开文件”调用设备播放器；不是用媒体解析器冒充 UI 打开操作。
- 手动检查约 360dp 小窗口：地址编辑、提交后键盘收起、错误重试、设置滚动到关于页、返回，以及网页链接长按打开独立标签。
- 清空历史不删除书签；另手动检查单条历史和书签删除确认。恢复验证覆盖本地数据分类而非全局清空。

测试视频由本地 ffmpeg 生成两秒画面和音频，不使用第三方视频。样本 SHA-256：
`0b56a9c4b987e55d46960a097b6c3e89561d9ebac5d10d245ea438832368c84e`。

## 3. 交付文件

项目根目录：`/Users/macos/Code/PureBrowser`。

- APK：`app/build/outputs/apk/debug/app-debug.apk`（约 12 MiB，Debug 签名，可安装；不是应用商店发布包）。
- APK SHA-256：`830d2074c6ec2342e10e08e823f3cc0df3e358f6f20087dddc19b1e6f65ee032`。
- 构建记录：`app/build/reports/product/build.txt`。
- 仪器测试：`app/build/reports/product/instrumentation.txt`，结尾 `OK (8 tests)`。
- 单元结果：`app/build/test-results/testDebugUnitTest/`。
- Lint：`app/build/reports/lint-results-debug.html`。
- 真实重启：`app/build/reports/product/cold-restart.txt`。
- 手动检查及清理：`app/build/reports/product/manual-checks.txt`、`cleanup.txt`。
- 截图：同目录 `home-light.png`、`home-dark.png`、`tabs-dark.png`、`bookmarks-dark.png`、`history-dark.png`、`small-keyboard.png`、`small-error.png`。

浅色首页为最终恢复后的默认状态；深色首页/标签/书签/历史截图使用自建本地网页，通过真实 UI 生成内容，非静态假数据或设计稿。

## 4. 用户数据与环境收尾

实施前先备份浏览数据/下载记录的存在状态与原始内容。本次两个文件在起始快照中均不存在，因此收尾移除的只是本轮测试创建的记录和截图演示数据，并非用户既有浏览文件。
仅通过应用的二次确认 UI 删除本轮自生成下载任务 ID 19–26 及其对应测试视频；没有清空 Download 目录或其他系统下载。
目标应用始终使用覆盖安装，未卸载；测试 APK 已移除。最后保留目标应用运行于默认单标签首页。
fixture 服务器和本轮端口反向代理已停止；模拟器临时小屏尺寸已复原。

## 5. 实现边界与未验证范围

- 最多 50 个标签记录，最多三个活跃 WebView 会话；回收或进程重启只恢复网址/标题/选中项，不恢复全部表单、滚动位置或网页内部历史。媒体候选不持久化。
- 普通标签共享网站 Cookie 配置，不是账号或无痕隔离。
- HLS / DASH / blob 仅识别与解释限制；没有分片下载、封装、清晰度/音轨选择、登录态鉴权转发或 DRM 绕过。
- 现有 DownloadManager 公共直链功能保留；没有手动暂停/自定义续传、完整资源中心/下载中心产品化、本地视频库或高级播放器。
- 本轮实际验证设备是 API 37 模拟器；小窗口检查不等同于真机/平板/多系统/大字体矩阵。尚未验证长时间后台、大视频、断网恢复及所有视频网站。
- 普通导航默认安全策略不变；没有因测试放宽生产 HTTP、证书或外部 Intent 限制。
- 应用 ID 仍为临时 `com.example.purebrowser`；正式签名、发行渠道与上架准备不在 T1–T7。

## 6. 下一轮

具体 T8–T14 待办见 EXECUTION-PLAN-ROUND-2.md；目前仅完成规划，未实施第二轮。

按已确认优先级，进入 **资源中心 → 下载中心 → 本地视频库 → 文件打开/分享与管理** 的产品化。第一轮与第二轮共同达到 0.1 标准；之后才是限定 HLS 点播和登录访问上下文增强，不增加自有云端能力。
