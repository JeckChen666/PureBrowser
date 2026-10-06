# v0.1.3 Lint 提醒逐项处置

Debug Lint：0错误，31条提醒。不是关闭检查的baseline；逐项理由如下。

| 检查 | 源码位置 | 处置 |
|---|---|---|
| OldTargetApi | `app/build.gradle.kts:13` | 沿用target36；APK预览非商店审核承诺，渠道要求另核。 |
| AndroidGradlePluginVersion | `gradle/wrapper/gradle-wrapper.properties:3` | 沿用已验证AGP，升级另立任务。 |
| AndroidGradlePluginVersion | `gradle/libs.versions.toml:2` | 沿用已验证AGP，升级另立任务。 |
| GradleDependency | `gradle/libs.versions.toml:3` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| GradleDependency | `gradle/libs.versions.toml:4` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| GradleDependency | `gradle/libs.versions.toml:4` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| GradleDependency | `gradle/libs.versions.toml:4` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| GradleDependency | `gradle/libs.versions.toml:6` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| GradleDependency | `gradle/libs.versions.toml:14` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| GradleDependency | `gradle/libs.versions.toml:14` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| GradleDependency | `gradle/libs.versions.toml:15` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| GradleDependency | `gradle/libs.versions.toml:17` | 按本轮固定栈约束保留已验证版本，升级需单独回归。 |
| NewerVersionAvailable | `gradle/libs.versions.toml:11` | 沿用当前依赖版本，升级另立任务。 |
| NewerVersionAvailable | `gradle/libs.versions.toml:13` | 沿用当前依赖版本，升级另立任务。 |
| NewerVersionAvailable | `gradle/libs.versions.toml:13` | 沿用当前依赖版本，升级另立任务。 |
| ObsoleteSdkInt | `app/src/main/res/mipmap-anydpi-v26:目录` | 保留现有v26图标目录，min26兼容有效。 |
| StaticFieldLeak | `app/src/main/java/com/example/purebrowser/download/DownloadRuntime.kt:181` | 生产get仅保存applicationContext；构造私有，不保存Activity/WebView，进程级所有者为明确设计。 |
| UnusedResources | `app/src/main/res/xml/backup_rules.xml:8` | 历史模板backup_rules未启用；应用allowBackup=false并使用data_extraction_rules。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/DownloadPreferences.kt:9` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/DownloadRepository.kt:255` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/library/LocalVideoThumbnail.kt:118` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/library/LocalVideoThumbnail.kt:168` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/ManagedFileStore.kt:152` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/ManagedFileStore.kt:160` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/ManagedFileStore.kt:161` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/ManagedFileStore.kt:162` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/ManagedFileStore.kt:166` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/ManagedFileStore.kt:177` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseKtx | `app/src/main/java/com/example/purebrowser/download/ManagedFileStore.kt:190` | 风格建议；保留已验证实现，不引入行为变化。 |
| UseTomlInstead | `app/build.gradle.kts:66` | 保留显式固定Media3版本，NOTICE已记录。 |
| UseTomlInstead | `app/build.gradle.kts:67` | 保留显式固定Media3版本，NOTICE已记录。 |
