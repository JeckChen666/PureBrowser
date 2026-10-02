# GitHub 资源嗅探源码调研

日期：2026-10-01（Asia/Shanghai）。本报告基于已下载并读取的源码与根目录 LICENSE，不只基于项目宣传。

## 参考项目与固定版本

### m-salehi-v/mrowser
- 仓库：https://github.com/m-salehi-v/mrowser
- 查阅提交：`24fbe7def23df0a9b4ddf9e9048351b5488820f8`
- 许可证：核心代码 MIT（已读取根目录许可证）；README 另提示广告过滤名单含 GPL-3.0，本次未采用这些资源。
- 仓库 API 显示的最近 push：2026-09-28T12:52:57Z；不等同于安全性或质量背书。
- 核心代码：https://github.com/m-salehi-v/mrowser/blob/24fbe7def23df0a9b4ddf9e9048351b5488820f8/app/src/main/kotlin/net/mrowser/stream/SniffingWebViewClient.kt
- 可参考内容：WebView 回调、URL 分类、线程安全候选集合与页面代次

### warren-bank/Android-WebCast
- 仓库：https://github.com/warren-bank/Android-WebCast
- 查阅提交：`50a1a643a1f4495737d3402765331268139ce7b2`
- 许可证：GPL-2.0（已读取根目录许可证）
- 仓库 API 显示的最近 push：2024-11-26T05:13:45Z；不等同于安全性或质量背书。
- 核心代码：https://github.com/warren-bank/Android-WebCast/blob/50a1a643a1f4495737d3402765331268139ce7b2/android-studio-project/WebCast/src/main/java/com/github/warren_bank/webcast/webview/BrowserWebViewClient_VideoDetector.java
- 可参考内容：网络与下载回调的媒体发现；这里只分析设计，不移植 GPL 源码

### JeffMony/VideoDownloader
- 仓库：https://github.com/JeffMony/VideoDownloader
- 查阅提交：`c9ef29447b66f897261edad70403e42fdf61deb9`
- 许可证：Apache-2.0（已读取根目录许可证）
- 仓库 API 显示的最近 push：2023-11-14T16:18:21Z；不等同于安全性或质量背书。
- 核心代码：https://github.com/JeffMony/VideoDownloader/blob/c9ef29447b66f897261edad70403e42fdf61deb9/library/src/main/java/com/jeffmony/downloader/m3u8/M3U8Utils.java
- 可参考内容：HLS 标签、相对路径、分片任务及恢复；后续借鉴，不直接集成旧 SDK

### xifangczy/cat-catch
- 仓库：https://github.com/xifangczy/cat-catch
- 查阅提交：`36eb589df41c516d81f0b39b3dd4a821f2b563b4`
- 许可证：GPL-3.0（已读取根目录许可证）
- 仓库 API 显示的最近 push：2026-09-30T19:13:36Z；不等同于安全性或质量背书。
- 核心代码：https://github.com/xifangczy/cat-catch/blob/36eb589df41c516d81f0b39b3dd4a821f2b563b4/js/background.js
- 可参考内容：多证据检测、过滤与资源面板；扩展 webRequest API 不可直接用于 Android WebView

## 本次实现采用的设计

- 被动 shouldInterceptRequest 观测，返回 null，不替代页面请求。
- URL / MIME / DOM 来源分开，候选集合与 WebView 生命周期分开。
- 使用页面代次阻止旧 DOM 回调污染新页面；保留完整 query 进行去重，不剥离签名。
- 过滤常见 ts/m4s 以及明显 init/chunk/segment MP4 分片；不宣称能判断所有分片。
- 用只读 evaluateJavascript 读取 video/source 与 Resource Timing；没有 addJavascriptInterface、XHR/fetch 重写或证书绕过。
- HLS、DASH、blob 单独标识，不把清单本身当最终视频文件。

## 没有直接搬入的内容

- 本次应用源码为独立实现，没有复制这四个项目的源码或打包其库。
- 未直接引入 VideoDownloader：仓库较旧，包含旧构建链和 FFmpeg 合并路径，需评估当前 AGP、原生 ABI、16 KB 页、许可证和维护成本。
- 未复用忽略证书校验、明文打印媒体 URL / 清单等行为。
- 尚未传递 Cookie / Authorization / Referer；系统 DownloadManager 自动重定向无法在这里逐跳做凭据的 origin 过滤，避免把敏感请求头无条件交给它。
- GPL 项目如未来直接复制或链接，需先确定项目发行及许可证方案；本次只作功能和设计参考。

## 后续值得深入的参考

优先围绕 Apache-2.0 的 HLS 解析/任务思想编写现代实现，再评估当前维护的 Media3 能力及必要的文件封装。
应先支持明确的 HLS 点播子集，而不是从成熟项目拷入所有功能和风险。

## v0.1.2 实施更新（2026-10-02）
本版独立实现HLS解析/受控访问/分片调度，仅引入官方Media3 1.11.1 extractor/muxer及必要传递依赖，未移植上述GPL代码或旧下载SDK。TS采样提取和真正MP4封装已做设备纵切；网络不由Media3管理。访问上下文和公共成品沿用v0.1.1。本文前述“尚未传递Cookie”等为2026-10-01历史调研状态，不是当前实现状态。新依赖归属见NOTICE和APK中的licenses。
