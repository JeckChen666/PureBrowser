# V4 设计落地与验收清单

> 本清单记录后续工作，不是本次完成报告。以下实现与运行验证项均未在本次设计归档中执行。

返回 [设计方向与图稿](README.md)。真实业务边界以 [当前实现状态](../../history/IMPLEMENTATION-STATUS.md) 及现有代码为准。

## 1. 代码落点（导航参考，不是要求重写）

| 范围 | 当前入口 |
| --- | --- |
| 浏览外壳与路由 | [BrowserScreen.kt](../../../app/src/main/java/com/example/purebrowser/ui/browser/BrowserScreen.kt)、[BrowserPanels.kt](../../../app/src/main/java/com/example/purebrowser/ui/browser/BrowserPanels.kt) |
| WebView 状态 | [BrowserWebViewHost.kt](../../../app/src/main/java/com/example/purebrowser/ui/browser/BrowserWebViewHost.kt)、[BrowserViewModel.kt](../../../app/src/main/java/com/example/purebrowser/ui/browser/BrowserViewModel.kt) |
| 首页与公共控件 | [HomeScreen.kt](../../../app/src/main/java/com/example/purebrowser/ui/home/HomeScreen.kt)、[BrowserControls.kt](../../../app/src/main/java/com/example/purebrowser/ui/components/BrowserControls.kt)、[BrowserComponents.kt](../../../app/src/main/java/com/example/purebrowser/ui/components/BrowserComponents.kt) |
| 标签总览 | [TabSwitcher.kt](../../../app/src/main/java/com/example/purebrowser/ui/tabs/TabSwitcher.kt) |
| 资源与确认 | [ResourceCenter.kt](../../../app/src/main/java/com/example/purebrowser/ui/resources/ResourceCenter.kt)、[DownloadConfirmation.kt](../../../app/src/main/java/com/example/purebrowser/ui/resources/DownloadConfirmation.kt)、[HlsDownloadConfirmation.kt](../../../app/src/main/java/com/example/purebrowser/ui/resources/HlsDownloadConfirmation.kt)、[HlsPreparation.kt](../../../app/src/main/java/com/example/purebrowser/ui/resources/HlsPreparation.kt) |
| 下载管理 | [DownloadCenterScreen.kt](../../../app/src/main/java/com/example/purebrowser/ui/downloads/DownloadCenterScreen.kt)、[DownloadTaskDetail.kt](../../../app/src/main/java/com/example/purebrowser/ui/downloads/DownloadTaskDetail.kt)、[DownloadUiSupport.kt](../../../app/src/main/java/com/example/purebrowser/ui/downloads/DownloadUiSupport.kt) |
| 视频库／书签／历史 | [VideoLibraryScreen.kt](../../../app/src/main/java/com/example/purebrowser/ui/library/VideoLibraryScreen.kt)、[SavedPagesScreen.kt](../../../app/src/main/java/com/example/purebrowser/ui/library/SavedPagesScreen.kt) |
| 设置与主题 | [SettingsScreen.kt](../../../app/src/main/java/com/example/purebrowser/ui/settings/SettingsScreen.kt)、[Theme.kt](../../../app/src/main/java/com/example/purebrowser/theme/Theme.kt)、[Type.kt](../../../app/src/main/java/com/example/purebrowser/theme/Type.kt) |

## 2. 分步实施建议

### 第一步：主题、公共控件与浏览外壳

- [ ] 建立一致的紧凑字号、图标、间距、圆角与列表组件，不把生成图当像素模板。
- [ ] 顶部地址展示／编辑使用同一个输入入口，底部改为图标导航。
- [ ] 覆盖首页、正常网页、页面加载中、加载失败、键盘显示与关闭状态。
- [ ] 编辑显示完整网址；展示省略不造成站点身份误认。
- [ ] 仅使用已支持的历史／书签数据，不为联想新增未经确认的网络请求。
- [ ] 处理状态栏、系统导航栏、键盘与边到边布局，确认操作不被遮挡。
- [ ] 外壳与主题变化不重建或重载仍在使用的 WebView，不丢失前进后退栈和阅读位置。

### 第二步：标签总览

- [ ] 明确网格、列表、搜索的状态模型、默认值及偏好持久化范围。
- [ ] 定义稳定排序与进入时滚动定位行为，不因点击标签而无提示重排。
- [ ] 定位当前只显示图标；无障碍名称与焦点反馈完整。
- [ ] 确认搜索是否忽略大小写、如何处理空标题／网址、无结果和清空输入。
- [ ] 明确搜索过滤掉当前标签时点击定位按钮的行为（例如清除筛选再定位），避免按钮无反馈。
- [ ] 处理关闭当前／非当前／最后一个标签，以及撤销和不可撤销边界；不默认引入批量关闭。
- [ ] 标签缩略图无图、过期、超长标题、同名不同域名仍可区分。
- [ ] 总览和缩略图加载不触发全部 WebView 重建，不引入无限增长的位图缓存。

### 第三步：资源与下载

- [ ] 资源提示 → 候选确认 → 真实方案 → 明确保存 → 下载速览 → 完整任务页的跳转一致。
- [ ] 保留直链与 HLS 的真实处理差异、来源上下文冻结、档位准备和条件确认。
- [ ] 面板打开时底层不可误触，不与浏览工具栏、系统返回形成多层冲突。
- [ ] 任务动作依据现有能力与状态；点击忙碌或不可用动作有正确反馈，不重复创建任务。
- [ ] 未知总大小使用不确定进度，不伪造精确百分比或文件大小。
- [ ] 等待网络、暂停、取消、失败、封装、校验、发布、完成分别呈现，避免恢复按钮语义错位。
- [ ] 链接失效与网络错误分开；有来源时才提供来源跳转；新建下载不冒充续传。
- [ ] 冷启动及恢复仍遵循现有对账规则，不因展示面板而静默启动任务。

### 第四步：视频库、记录与设置

- [ ] 视频库按可用宽度和字体大小调整列数，三列不是硬限制。
- [ ] 文件缺失／不可读取与历史下载完成区分；未知时长／尺寸不填假值。
- [ ] 打开和分享沿用真实 URI 与外部应用路径；无可处理应用时显示反馈。
- [ ] 显示名称修改不暗示底层物理文件已重命名。
- [ ] 移除记录保留文件；删除文件再次确认；取消确认不执行操作。
- [ ] 书签、历史的搜索与编辑行为逐项对照现有能力，新增项单独安排实现与测试。
- [ ] 网站数据、缓存、历史、下载临时文件分项清理；不出现含糊的一键全删。
- [ ] 本地诊断先预览，再由用户主动分享，不自动上传。
- [ ] 保存目录只读展示；没有实现目录选择器时不展示可点击的导航箭头。

## 3. 跨页面验收矩阵

| 场景 | 重点检查 | 状态 |
| --- | --- | --- |
| 窄屏、低高度窗口、横屏 | 标题截断、按钮可达、列数调整、键盘遮挡 | 待验证 |
| 默认与放大系统字体 | 不重叠、不裁掉关键状态、不锁定字体缩放 | 待验证 |
| 系统手势与按键导航 | 顶部／底部安全区、返回层级、误触 | 待验证 |
| 图标按钮与屏幕阅读器 | 名称、焦点、选中／禁用状态、触控区域 | 待验证 |
| 浅色／深色主题 | 浅色图稿落地；深色补设计与对比度验证，不能删除现有深色支持 | 待验证 |
| 0／1／24／更多标签 | 空态、单标签关闭、密度、搜索、定位、稳定顺序与性能 | 待验证 |
| 长标题／长域名／无缩略图 | 可辨识性、文字截断、占位与操作区域 | 待验证 |
| 输入与弹层交叉 | 键盘关闭、抽屉关闭、网页后退、退出应用的顺序 | 待验证 |
| 下载全生命周期与重启 | 控制能力、错误分类、进度、对账和成品可用性 | 待验证 |
| 文件删除／外部删除／分享 | 不误删、不虚报成功、授权正确、失败可理解 | 待验证 |
| 多页面往返 | 不丢网页滚动、输入内容、标签选择、列表位置 | 待验证 |

## 4. 实施完成后的交付要求

- [ ] 更新真实实现状态，不把本设计文档直接当作完成报告。
- [ ] 提供真实运行截图，与对应概念图并排说明差异；记录设备、字体大小、主题和构建身份。
- [ ] 按变更范围运行已有单元、UI、下载、隐私相关回归，并补充新交互测试。
- [ ] 记录未覆盖项、生成图偏差修正及适配取舍；没有验证的项目保持待验证。
- [ ] 如改变本轮已确认方向，更新设计说明并重新确认，不仅替换图片。
