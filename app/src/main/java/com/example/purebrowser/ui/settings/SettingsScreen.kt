package com.example.purebrowser.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.purebrowser.data.browser.ThemeMode
import com.example.purebrowser.privacy.PrivacyCategory
import com.example.purebrowser.privacy.PrivacyClearResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Optional parent-owned integration; null means unavailable, never a clickable no-op.
 * Cleanup callbacks must await LocalPrivacyController.clear(...) and keep browser/session/download
 * exclusion held until it returns. Do not return COMPLETED just after launching background work.
 * History/temp callbacks affect only their named category; none may delete saved videos.
 * Diagnostic generation must return DiagnosticReport.render(...) only (no raw logs/model strings).
 * Sharing is a separate, explicit user action; parent builds a fresh text/plain system chooser.
 */
data class SettingsPrivacyActions(
    val clearHistory: (suspend () -> PrivacyClearResult)? = null,
    val clearSiteData: (suspend () -> PrivacyClearResult)? = null,
    val clearCache: (suspend () -> PrivacyClearResult)? = null,
    val clearDownloadTemp: (suspend () -> PrivacyClearResult)? = null,
    val diagnosticReport: (suspend () -> String)? = null,
    val shareDiagnosticReport: ((String) -> Unit)? = null,
) {
    internal fun callback(category: PrivacyCategory): (suspend () -> PrivacyClearResult)? = when (category) {
        PrivacyCategory.HISTORY -> clearHistory
        PrivacyCategory.SITE_DATA -> clearSiteData
        PrivacyCategory.CACHE -> clearCache
        PrivacyCategory.DOWNLOAD_TEMP -> clearDownloadTemp
    }
}

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    select: (ThemeMode) -> Unit,
    wifiOnly: Boolean,
    setWifiOnly: (Boolean) -> Unit,
    about: () -> Unit,
    privacyActions: SettingsPrivacyActions = SettingsPrivacyActions(),
) {
    val scope = rememberCoroutineScope()
    var pendingCategory by rememberSaveable { mutableStateOf<PrivacyCategory?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Deliberately not saveable: do not persist generated diagnostics with UI/navigation state.
    var report by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionHeading("外观")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (theme == mode) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = theme == mode, role = Role.RadioButton, onClick = { select(mode) })
                                .testTag("theme-${mode.name}").padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = theme == mode, onClick = null)
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            SettingsNote("只改变浏览器界面，网页配色由网站决定。")
            HorizontalDivider()
            SectionHeading("下载")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(value = wifiOnly, role = Role.Switch, onValueChange = setWifiOnly)
                    .testTag("defaultWifiOnly"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("默认仅 Wi-Fi 下载", style = MaterialTheme.typography.bodyLarge)
                    SettingsNote("只影响新任务，可在确认时单独更改。")
                }
                Switch(checked = wifiOnly, onCheckedChange = null)
            }
            SettingsNote("文件保存在本机公共 Download/PureBrowser 目录；显示名称可修改，实际文件名保留唯一前缀。移除记录不会删除文件，删除文件需要另行确认。")
            HorizontalDivider()
            SectionHeading("隐私与清理")
            SettingsNote("每次只清理你确认的类别，不提供一键全删。网站数据与网页缓存面向本应用的所有网站，不只是当前网页；任何清理都不会删除已保存的视频。")
            PrivacyCategory.entries.forEach { category ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(category.title, style = MaterialTheme.typography.titleMedium)
                    SettingsNote(category.summary)
                    OutlinedButton(
                        onClick = { pendingCategory = category },
                        enabled = !busy && privacyActions.callback(category) != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("privacy-${category.name}"),
                    ) {
                        Text(category.actionLabel, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (privacyActions.callback(category) == null) SettingsNote("尚未接入，当前不可用。")
                }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在处理，请等待结果。", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("privacy-result").semantics { liveRegion = LiveRegionMode.Polite })
            }
            HorizontalDivider()
            SectionHeading("本地诊断")
            SettingsNote("仅包含应用版本、系统/WebView 版本、任务状态、失败类别及字节数。任务用本次报告的序号表示，不包含网址、Cookie、认证信息、标题、名称或任务 ID。不会自动上传。")
            OutlinedButton(
                onClick = {
                    val generate = privacyActions.diagnosticReport ?: return@OutlinedButton
                    busy = true
                    status = null
                    scope.launch {
                        try {
                            report = generate()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = "无法生成本地诊断，请稍后重试。"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && privacyActions.diagnosticReport != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("diagnostic-generate"),
            ) { Text("生成诊断文本", style = MaterialTheme.typography.bodyLarge) }
            if (privacyActions.diagnosticReport == null) SettingsNote("诊断尚未接入，当前不可用。")
            HorizontalDivider()
            SectionHeading("本地优先")
            Text("标签、书签、历史与设置保存在此设备。不提供应用账号、云同步或云端解析。", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = about, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("关于 PureBrowser", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    pendingCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingCategory = null },
            title = { Text("确认${category.actionLabel}？") },
            text = {
                Text(category.confirmation, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clear = privacyActions.callback(category) ?: return@TextButton
                        if (busy) return@TextButton
                        pendingCategory = null
                        busy = true
                        status = null
                        scope.launch {
                            try {
                                status = "${category.title}：${clear().message}"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = "${category.title}：${PrivacyClearResult.FAILED.message}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && privacyActions.callback(category) != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("privacy-confirm"),
                ) { Text("确认清理", style = MaterialTheme.typography.bodyLarge) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCategory = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("取消", style = MaterialTheme.typography.bodyLarge)
                }
            },
        )
    }

    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("本地诊断预览") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("只有点击分享才会交给你选择的其他应用。其他应用如何处理或上传文本，由该应用决定。", style = MaterialTheme.typography.bodyLarge)
                    if (privacyActions.shareDiagnosticReport == null) SettingsNote("文本分享尚未接入，当前不可用。")
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val share = privacyActions.shareDiagnosticReport ?: return@TextButton
                        try {
                            share(text)
                            report = null
                        } catch (_: Exception) {
                            report = null
                            status = "无法打开文本分享，请稍后重试。"
                        }
                    },
                    enabled = privacyActions.shareDiagnosticReport != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("diagnostic-share"),
                ) { Text("分享文本", style = MaterialTheme.typography.bodyLarge) }
            },
            dismissButton = {
                TextButton(onClick = { report = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("关闭", style = MaterialTheme.typography.bodyLarge)
                }
            },
        )
    }
}

private val PrivacyCategory.title: String get() = when (this) {
    PrivacyCategory.HISTORY -> "浏览历史"
    PrivacyCategory.SITE_DATA -> "所有网站的数据"
    PrivacyCategory.CACHE -> "网页缓存"
    PrivacyCategory.DOWNLOAD_TEMP -> "下载临时文件"
}
private val PrivacyCategory.actionLabel: String get() = when (this) {
    PrivacyCategory.HISTORY -> "清除浏览历史"
    PrivacyCategory.SITE_DATA -> "清除所有网站数据"
    PrivacyCategory.CACHE -> "清除网页缓存"
    PrivacyCategory.DOWNLOAD_TEMP -> "清除下载临时文件"
}
private val PrivacyCategory.summary: String get() = when (this) {
    PrivacyCategory.HISTORY -> "仅删除本应用保存的历史，不删除书签或已打开的标签。"
    PrivacyCategory.SITE_DATA -> "Cookie、网站存储及网页缓存；可能退出登录或丢失网站离线数据。"
    PrivacyCategory.CACHE -> "仅请求清理 WebView 网页缓存，不清理整机缓存、Cookie 或下载文件。"
    PrivacyCategory.DOWNLOAD_TEMP -> "清理已停止任务的临时文件与续传缓存。受影响任务将无法继续，必须重新下载；不删除活动任务文件或已保存的视频。"
}
private val PrivacyCategory.confirmation: String get() = when (this) {
    PrivacyCategory.HISTORY -> "仅删除本应用保存的浏览历史，无法撤销。不会删除书签、已打开标签、下载记录或已保存的视频。之后访问网页会产生新的历史。"
    PrivacyCategory.SITE_DATA -> "向本应用 WebView 的所有网站请求清除 Cookie、网站存储和网页缓存，不是只清理当前网站。可能退出登录，丢失网站离线数据，无法撤销。\n\n必须先停止所有网页、网站会话和下载活动。旧版系统接口无法保证所有存储类型均已清除。不会删除本应用浏览历史、书签、下载记录或已保存的视频。"
    PrivacyCategory.CACHE -> "仅向本应用所有网站共用的 WebView 请求清除网页缓存（含磁盘缓存），不清理整机缓存。必须先停止网页、网站会话及下载活动；系统接口无法核实所有缓存均已删除。\n\n不会主动删除 Cookie、网站存储、浏览历史、下载记录或已保存的视频。"
    PrivacyCategory.DOWNLOAD_TEMP -> "仅删除本应用已停止任务的临时分片、未发布文件和续传缓存，无法撤销。必须先停止网页、网站会话及下载活动，并等待所有相关写入结束。\n\n清理后，受影响任务将无法继续，必须重新下载。不会删除活动任务文件、下载记录或已经保存的公共视频文件。"
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "未知"
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("PureBrowser", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("本地视频浏览器 · $version", style = MaterialTheme.typography.bodyLarge)
            SettingsNote("应用 ID：${context.packageName}")
            SettingsNote("支持 MP4/WebM 直链、无后缀视频与限定同源网站会话。HLS 需显式解析清单、选择档位并准备；仅支持未加密的固定点播 MPEG-TS（H.264 / AAC），封装校验后保存为独立 MP4。")
            SettingsNote("符合条件的直链任务支持暂停与续传：必须有可验证的强 ETag，服务器还需正确支持 Range，并确认资源未改变；不满足条件时需重新下载。限定的静态 HLS 可复用已经完整保存且校验一致的分片，不复用半片；封装、校验或发布阶段不承诺可暂停。重新下载另建任务，HLS 保留所选档位，档位不可用时不会自动改选其他画质。")
            SettingsNote("不支持 HLS 直播、加密分片、独立音轨、fMP4、DASH、blob、跨站敏感鉴权或 DRM。版本号以本机安装包为准；候选版本标记不代表正式发布。")
            SettingsNote("已保存的视频位于本机公共 Download/PureBrowser 目录。续传缓存与临时分片留在应用私有存储，会额外占用空间；确认清理已停止任务的缓存后，受影响任务必须重新下载。仅移除记录不会删除设备文件。")
            SettingsNote("Android 10 及以上，发布中的公共副本处于 pending 状态，不作为视频库成品展示；旧版系统使用独立临时文件。下载、封装、校验和发布成功后，才在视频库提供本地预览、打开或分享。")
            SettingsNote("网站清理面向本应用 WebView 的所有网站，并需先停止网页、网站会话及相关下载。兼容系统接口仅返回已请求清理（REQUESTED），不能证明所有存储、缓存或 Service Worker 数据已经完全清除；不清理其他应用或整机数据，不删除已保存的视频。")
            SettingsNote("仅保存你拥有或获授权的内容。不绕过 DRM 或访问控制。")
            SettingsNote("视频库仅索引本应用保存的文件，不扫描整机媒体。文件分享通过 Android 系统选择器交给你选定的应用；不会由本应用自动上传。")
            SettingsNote("开源组件：AndroidX / Jetpack Compose（Apache 2.0）；Kotlin（Apache 2.0）。Android WebView 和 DownloadManager 由设备系统提供。资源规则参考记录见项目 docs/SOURCE-RESEARCH.md；未复制第三方下载引擎。")
            SettingsNote("基于 Android WebView、Jetpack Compose 和受控下载服务。应用无自有后端，不上传浏览数据。")
        }
    }
}
