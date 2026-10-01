# Android 开发环境设置与验证

检查与配置日期：2026-10-01（Asia/Shanghai）。

## 已完成

1. 保留现有 Android Studio、SDK、模拟器与 25 个项目级 Android 技能。
2. 创建 `/Users/macos/.config/android-dev/env.zsh`，设置 JAVA_HOME、ANDROID_HOME 与相关 PATH。
3. 在 `/Users/macos/.zprofile` 与 `/Users/macos/.zshrc` 中加载该配置，并避免重复添加 PATH。
4. 验证新登录 shell 可以找到 `java`、`javac`、`adb`、`emulator` 和 `android`。
5. 使用 Android CLI `empty-activity` 官方模板创建 PureBrowser 基础工程；为了保护已有 `.agents/`，先在临时目录生成，再检查无重名文件后复制到项目根目录。
6. 使用项目 Gradle Wrapper 完成首次构建、模板单元测试与 Lint。
7. 启动既有 Pixel 虚拟设备、安装 APK、启动主界面，并检查前台 Activity、UI 文本和截图。

## 配置备份

- `/Users/macos/.zprofile.backup-android-20261001-172045`
- `/Users/macos/.zshrc.backup-android-20261001-172045`

如需撤回环境设置，可恢复这些备份；这不会移除工程、SDK 或 Gradle 缓存。

## 使用的版本

| 组件 | 版本或位置 |
|---|---|
| Android Studio | Quail 4 / 2026.1.4 Patch 1 |
| Android CLI | 1.0.16486076 |
| Gradle | 9.1.0（项目 Wrapper） |
| Android Gradle Plugin | 9.0.1 |
| Gradle 运行 JDK | Android Studio 自带 JDK 25.0.3 |
| 编译 JDK | Gradle 自动管理的 Temurin 17.0.20.1 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| SDK 根目录 | `/Users/macos/Library/Android/sdk` |
| 虚拟设备 | Pixel_10_Pro_XL / emulator-5554 / x86_64 |
| 应用标识 | com.example.purebrowser（临时，发布前需确认） |

## 此次自动下载的内容

- Android CLI 官方项目模板。
- 模板要求的 Android 36 SDK Platform；原先已有的 Android 37 SDK 保留。
- Gradle 9.1.0。
- Foojay resolver 自动管理的 JDK 17 编译 toolchain。
- 工程构建、测试和 Lint 所需的依赖。
- Android CLI 的设备布局检查服务，安装在模拟器中用于 UI 验证。

未安装全局 Gradle、NDK、CMake、MCP 或额外的模拟器镜像。

## 验证结果

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
BUILD SUCCESSFUL
```

- 模板单元测试：2 项，0 失败，0 错误。
- Lint：通过，0 错误，17 项非阻断提醒（主要为依赖更新和 targetSdk 提示）；详见 `../app/build/reports/lint-results-debug.html`。
- 虚拟设备启动：`sys.boot_completed=1`。
- 安装：1 个 APK，约 11.35 MB。
- 前台 Activity：`com.example.purebrowser/.MainActivity`。
- 主界面文字：`Hello Android!`。
- 截图：`../app/build/reports/environment/first-launch.png`。
- UI 树：`../app/build/reports/environment/layout.json`。

Gradle 首次运行出现过 Java native-access、SDK XML 版本及 native library strip 提示，均未阻止构建。
本次未运行仪器测试或发布版构建，也尚未实现浏览器功能。

## 继续开发

先按 README 中的步骤使用终端或 Android Studio 打开工程，再确认正式包名与产品需求。
构建产物和验证截图在 `app/build/` 下，已通过 `.gitignore` 排除，不应提交到版本控制。

本次优先验证官方模板的现有版本组合，未把所有依赖直接升级到新版本。
后续正式开发前，可另行确认 compileSdk / targetSdk 和依赖升级计划。
