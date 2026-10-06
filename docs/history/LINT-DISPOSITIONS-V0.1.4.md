# v0.1.4 Lint 处置台账

本版按当前报告逐项统计；初次集成 Debug 结果为 0 错误／33 提醒。最终结果另见完成摘要，不把提醒写成消除。

| 类别 | 处置 |
| --- | --- |
| OldTargetApi | 保持当前 compile/target 36，不在 UI 版顺带升级平台；API28/36/37 验证覆盖对应范围，不宣称全版本认证 |
| AndroidGradlePluginVersion / GradleDependency / NewerVersionAvailable | 延用已验证依赖组合；更新提示不等于安全缺陷，无大型工具链／导航迁移 |
| ObsoleteSdkInt（v26 图标） | 继承的资源组织提醒，不影响实际 minSdk26 图标；未为本版改图标系统 |
| StaticFieldLeak（DownloadRuntime） | 继承的应用级单例，构造使用 applicationContext；不持有 Activity。新预览缓存只持弱 WebView 引用、会话内位图，不写磁盘 |
| UnusedResources（backup_rules） | 继承；正式 allowBackup=false，数据提取规则保留。未开启云备份 |
| UseKtx（URI/Bitmap/SharedPreferences） | 保留直接平台 API，语义不变；新增预览／偏好同样按明确调用与失败保护处理，不为风格替换增加依赖 |
| UseTomlInstead（Media3 依赖） | 继承的两个有限组件，版本与范围不变，本版不引入播放器／编解码 SDK |

提醒的原始位置和逐条信息保留在本地 `app/build/reports/lint-results-debug.xml/html`；任何新增 Error 或真实泄漏／安全问题仍阻挡发行。
