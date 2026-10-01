# Android 官方项目级技能

- 来源：https://github.com/android/skills
- 官方说明：https://developer.android.com/tools/agents/android-skills?hl=zh-cn
- 安装日期：2026-10-01（Asia/Shanghai）
- 来源提交：`42dc2270e96032bd860bb94511e440aa00a43125`
- 作用域：当前项目；目录为 `.agents/skills/`，没有写入用户级技能目录。
- 安装内容：官方仓库中全部 25 个技能及其附带资源，保持上游内容不变。
- 许可证：Apache-2.0，完整文本见 `skills/LICENSE.txt`。
- 安装仅添加技能文件，不代表已安装 Android CLI、Android SDK、JDK 或 Android Studio。

## 使用

在当前项目的 Codex 会话中，后续请求会按任务匹配相关技能；也可以在提示中明确指定技能，例如 `$android-cli`、`$adaptive`、`$navigation-3`、`$testing-setup`。若新技能未出现，可重新打开项目会话。

## 已安装技能

- `agp-9-upgrade`（上游：`build-system/agp/agp-9-upgrade`）
- `camerax`（上游：`camera/camerax`）
- `appfunctions`（上游：`device-ai/appfunctions`）
- `ml-kit-genai-prompt-api`（上游：`device-ai/ml-kit-genai-prompt-api`）
- `android-cli`（上游：`devtools/android-cli`）
- `restore-credentials`（上游：`identity/restore-credentials`）
- `verified-email`（上游：`identity/verified-email`）
- `adaptive`（上游：`jetpack-compose/adaptive`）
- `migrate-xml-views-to-jetpack-compose`（上游：`jetpack-compose/migration/migrate-xml-views-to-jetpack-compose`）
- `styles`（上游：`jetpack-compose/theming/styles`）
- `media3-cast-integration`（上游：`media/media3-cast-integration`）
- `navigation-3`（上游：`navigation/navigation-3`）
- `navigation-event`（上游：`navigation/navigation-event`）
- `r8-analyzer`（上游：`performance/r8-analyzer`）
- `engage-sdk-integration`（上游：`play/engage-sdk-integration`）
- `play-billing-library-version-upgrade`（上游：`play/play-billing-library-version-upgrade`）
- `play-policy-insights`（上游：`play/play-policy-insights`）
- `android-profiler`（上游：`profilers/android-profiler`）
- `android-intent-security`（上游：`security/android-intent-security`）
- `android-permissions-security`（上游：`security/android-permissions-security`）
- `edge-to-edge`（上游：`system/edge-to-edge`）
- `testing-setup`（上游：`testing/testing-setup`）
- `leanback-to-compose-tv-migration`（上游：`tv/leanback-to-compose-tv-migration`）
- `wear-compose-m3`（上游：`wear/wear-compose-m3`）
- `display-glasses-with-jetpack-compose-glimmer`（上游：`xr/display-glasses-with-jetpack-compose-glimmer`）
