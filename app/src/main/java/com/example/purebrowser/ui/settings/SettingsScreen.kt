package com.example.purebrowser.ui.settings

import android.os.Build
import com.example.purebrowser.download.PublishRoutePolicy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.example.purebrowser.media.rules.ImportedRulePolicy
import com.example.purebrowser.media.rules.ImportedRuleStore
import com.example.purebrowser.media.rules.ImportValidation
import com.example.purebrowser.media.rules.ImportValidator
import com.example.purebrowser.privacy.PrivacyCategory
import com.example.purebrowser.privacy.PrivacyClearResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    var pendingCategory by rememberSaveable { mutableStateOf<PrivacyCategory?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Deliberately not saveable: do not persist generated diagnostics with UI/navigation state.
    var report by remember { mutableStateOf<String?>(null) }
    // T90 import channel: the picked document is validated first; only an explicit consent turns
    // it into stored data. The validated text lives in composition state only until the dialog
    // closes — persistence happens through the app-private store, never the picker itself.
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var importedCount by remember { mutableStateOf<Int?>(null) }
    var importBusy by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importBusy = true
        status = null
        scope.launch {
            var valid: ImportValidation.Valid? = null
            var denied: ImportValidation.Denied? = null
            var validText: String? = null
            var signatureLabel: String? = null
            try {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(ImportedRulePolicy.MAX_IMPORT_BYTES + 1)
                        var read = 0
                        while (true) {
                            if (read >= buffer.size) return@use null // oversize marker
                            val n = input.read(buffer, read, buffer.size - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read > ImportedRulePolicy.MAX_IMPORT_BYTES) null else buffer.copyOf(read).toString(Charsets.UTF_8)
                    }
                    when {
                        text == null -> denied = ImportValidation.Denied(ImportValidation.Denied.Reason.OVERSIZE)
                        else -> when (val verdict = ImportValidator.validate(text, builtInCount = com.example.purebrowser.media.rules.RuleSet.load(context).rules.size)) {
                            is ImportValidation.Valid -> {
                                valid = verdict; validText = text
                                signatureLabel = com.example.purebrowser.media.rules.RuleImportSigning
                                    .signatureLabel(context, text)
                            }
                            is ImportValidation.Denied -> denied = verdict
                        }
                    }
                }
                val v = valid
                val t = validText
                val d = denied
                when {
                    v != null && t != null -> pendingImport = PendingImport(t, v.digest, v.rules.size, signatureLabel ?: "")
                    d != null -> status = importDenialText(d.reason)
                    else -> status = "无法读取所选文件，请重试。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "无法读取所选文件，请重试。"
            } finally {
                importBusy = false
            }
        }
    }

    // The browser shell owns system/IME insets; do not add a second safe-area inset here.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()
                .testTag("settingsScreen").verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsGroup("外观") {
                Column(Modifier.selectableGroup()) {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = theme == mode, role = Role.RadioButton, onClick = { select(mode) })
                                .testTag("theme-${mode.name}").padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = theme == mode, onClick = null)
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                SettingsNote("只改变浏览器界面，网页配色由网站决定。")
            }
            SettingsGroup("下载") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = wifiOnly, role = Role.Switch, onValueChange = setWifiOnly)
                        .testTag("defaultWifiOnly").padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("默认仅 Wi-Fi 下载", style = MaterialTheme.typography.bodyMedium)
                        SettingsNote("只影响新任务，可在确认时单独更改。")
                    }
                    Switch(checked = wifiOnly, onCheckedChange = null)
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().testTag("downloadSavePath"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("保存位置（只读）", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer { Text(PublishRoutePolicy.savePathLabel(Build.VERSION.SDK_INT), style = MaterialTheme.typography.bodyMedium) }
                }
                SettingsNote(if (Build.VERSION.SDK_INT >= 29) "本机公共目录，不提供目录选择。显示名称可修改，实际文件名保留唯一前缀。移除记录不会删除文件，删除文件需要另行确认。"
                else "此系统版本不允许应用直接写入公共下载，成品保存在应用专属外部目录，可通过打开或分享另存。不提供目录选择。显示名称可修改，实际文件名保留唯一前缀。移除记录不会删除文件，删除文件需要另行确认。")
            }
            SettingsGroup("站点规则") {
                SettingsNote("导入的规则只保存在本机应用私有存储，不联网分发、不自动更新。导入后与内置规则合并，内置规则始终优先；导入规则的受控抓取额度减半且不允许跨源跳转。分析入口会标注命中来自“导入规则”。")
                OutlinedButton(
                    onClick = {
                        importedCount = com.example.purebrowser.media.rules.ImportedRuleStore.importedCount(context)
                        runCatching { importPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                            .onFailure { status = "无法打开文件选择器，请重试。" }
                    },
                    enabled = !importBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rule-import-pick"),
                ) { Text("导入站点规则", style = MaterialTheme.typography.bodyMedium) }
                if (importBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (importedCount == null) {
                    importedCount = com.example.purebrowser.media.rules.ImportedRuleStore.importedCount(context)
                }
                importedCount?.let { count ->
                    if (count > 0) {
                        SettingsNote("当前已导入 $count 条规则。")
                        TextButton(
                            onClick = {
                                if (com.example.purebrowser.media.rules.ImportedRuleStore.clear(context)) {
                                    importedCount = 0
                                    status = "已清除导入的站点规则。"
                                } else status = "没有已导入的规则可清除。"
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rule-import-clear"),
                        ) { Text("清除已导入规则", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            SettingsGroup("本地数据") {
                SettingsNote("每次只清理你确认的类别，不提供一键全删。网站数据与网页缓存面向本应用的所有网站，不只是当前网页；任何清理都不会删除已保存的视频。")
                PrivacyCategory.entries.forEach { category ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(category.title, style = MaterialTheme.typography.titleSmall)
                        SettingsNote(category.summary)
                        TextButton(
                            onClick = { pendingCategory = category },
                            enabled = !busy && privacyActions.callback(category) != null,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("privacy-${category.name}"),
                        ) { Text(category.actionLabel, style = MaterialTheme.typography.bodyMedium) }
                        if (privacyActions.callback(category) == null) SettingsNote("尚未接入，当前不可用。")
                    }
                }
            }
            SettingsGroup("诊断") {
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
                ) { Text("生成诊断文本", style = MaterialTheme.typography.bodyMedium) }
                if (privacyActions.diagnosticReport == null) SettingsNote("诊断尚未接入，当前不可用。")
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在处理，请等待结果。", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("privacy-result").semantics { liveRegion = LiveRegionMode.Polite })
            }
            SettingsGroup("关于") {
                SettingsNote("标签、书签、历史与设置保存在此设备。不提供应用账号、云同步或云端解析。")
                TextButton(onClick = about,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settingsAboutButton")) {
                    Text("关于 PureBrowser", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // T90 consent dialog: the user must explicitly accept that the imported rules gain the
    // controlled-fetch capability before anything is stored. The canonical SHA-256 is shown for
    // manual verification against the source the file came from (no signing infra in-app).
    pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入站点规则？") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("外部规则将获得受控抓取能力（仅 GET、HTTPS、限额减半、不跨源跳转）。文件来源无法由应用验证，请先自行核对发布方的完整性摘要再确认。", style = MaterialTheme.typography.bodyLarge)
                    Text("签名状态：${pending.signatureLabel}", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("rule-import-signature"))
                    Text("规则条数：${pending.count}", style = MaterialTheme.typography.bodyMedium)
                    Text("完整性摘要（SHA-256，规范化后）：", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer { Text(pending.digest, style = MaterialTheme.typography.bodySmall) }
                    SettingsNote("已签名文件经应用内置维护者公钥（Ed25519）校验；未签名或校验失败的文件仅能靠上方摘要人工核对。确认后规则保存在本机，可随时在设置中清除。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = pending.text
                        pendingImport = null
                        importBusy = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { ImportedRuleStore.save(context, text) }
                            importedCount = ImportedRuleStore.importedCount(context)
                            status = if (saved) "已导入 ${importedCount ?: 0} 条站点规则。" else "导入失败：文件超过大小限制。"
                            importBusy = false
                        }
                    },
                    enabled = !importBusy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("rule-import-confirm"),
                ) { Text("确认导入", style = MaterialTheme.typography.bodyLarge) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("取消", style = MaterialTheme.typography.bodyLarge)
                }
            },
        )
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

/** Validated import awaiting explicit consent; [text] is composition state only, never persisted here. */
private data class PendingImport(
    val text: String,
    val digest: String,
    val count: Int,
    /** T101 Ed25519 verdict label for the consent dialog ("已签名(<keyid>)" or the honest fallback). */
    val signatureLabel: String,
)

private fun importDenialText(reason: ImportValidation.Denied.Reason): String = when (reason) {
    ImportValidation.Denied.Reason.OVERSIZE -> "导入失败：文件超过 512KB 限制。"
    ImportValidation.Denied.Reason.MALFORMED -> "导入失败：不是有效的规则 JSON 文档。"
    ImportValidation.Denied.Reason.UNSUPPORTED_VERSION -> "导入失败：文档 schema 版本低于 v3。"
    ImportValidation.Denied.Reason.NO_RULES -> "导入失败：文档中没有可用的规则条目。"
    ImportValidation.Denied.Reason.CAP_EXCEEDED -> "导入失败：规则条数超过总量上限。"
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
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() })
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val scope=rememberCoroutineScope()
    var licenseText by remember { mutableStateOf<String?>(null) }
    var licenseLoading by remember { mutableStateOf(false) }
    // Query the installed package, including its build suffix; never label a candidate as released.
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "未知"
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()
                .testTag("aboutScreen").verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("PureBrowser", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("本地视频浏览器 · $version", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("aboutRuntimeVersion"))
            SettingsNote("应用 ID：${context.packageName}")
            SettingsGroup("下载能力与边界") {
                SettingsNote("支持 MP4/WebM 直链、无后缀视频与限定同源网站会话。HLS 需显式解析清单、选择档位并准备；仅支持未加密的固定点播 MPEG-TS（H.264 / AAC），封装校验后保存为独立 MP4。")
                SettingsNote("符合条件的直链任务支持暂停与续传：必须有可验证的强 ETag，服务器还需正确支持 Range，并确认资源未改变；不满足条件时需重新下载。限定的静态 HLS 可复用已经完整保存且校验一致的分片，不复用半片；封装、校验或发布阶段不承诺可暂停。重新下载另建任务，HLS 保留所选档位，档位不可用时不会自动改选其他画质。")
                SettingsNote("开发候选增加页面媒体声明、无后缀／Range 请求线索的显式分析与公开站点格式选择。受支持的 H.264＋AAC 完整双轨可尝试封装 MP4；中断或过期需重新分析，不支持双轨续传。YouTube 完整保存仍在验证，解析出地址不等于下载成功。")
                SettingsNote("不支持 HLS 直播、加密分片、HLS 独立音轨／fMP4、通用 DASH、MSE 全覆盖、跨站敏感鉴权或 DRM。blob 本身不是文件地址。版本号以本机安装包为准；开发候选不代表正式发布。")
            }
            SettingsGroup("文件与存储") {
                SettingsNote(if (Build.VERSION.SDK_INT >= 29) "已保存的视频位于本机公共 Download/PureBrowser 目录。"
                else "此系统版本不允许应用直接写入公共下载，已保存的视频位于应用专属外部目录，可通过打开或分享另存。")
                SettingsNote("续传缓存与临时分片留在应用私有存储，会额外占用空间；确认清理已停止任务的缓存后，受影响任务必须重新下载。仅移除记录不会删除设备文件。")
                SettingsNote("Android 10 及以上，发布中的公共副本处于 pending 状态，不作为视频库成品展示；旧版系统使用独立临时文件。下载、封装、校验和发布成功后，才在视频库提供本地预览、打开或分享。")
                SettingsNote("视频库仅索引本应用保存的文件，不扫描整机媒体。文件分享通过 Android 系统选择器交给你选定的应用；不会由本应用自动上传。")
            }
            SettingsGroup("隐私与使用") {
                SettingsNote("网站清理面向本应用 WebView 的所有网站，并需先停止网页、网站会话及相关下载。兼容系统接口仅返回已请求清理（REQUESTED），不能证明所有存储、缓存或 Service Worker 数据已经完全清除；不清理其他应用或整机数据，不删除已保存的视频。")
                SettingsNote("仅保存你拥有或获授权的内容。不绕过 DRM 或访问控制。")
                SettingsNote("基于 Android WebView、Jetpack Compose 和受控下载服务。应用无自有后端，不上传浏览数据。")
            }
            SettingsGroup("开源组件") {
                SettingsNote("AndroidX / Jetpack Compose（Apache 2.0）；Kotlin（Apache 2.0）。Android WebView 和 DownloadManager 由设备系统提供。媒体传输由应用受控核心执行，不使用外部命令下载器。站点解析使用固定版本 YouTube.js 18.1.0（MIT）及其 Apache/BSD/MIT/ISC 依赖，原始许可证随安装包提供。")
                TextButton(onClick={if(!licenseLoading){licenseLoading=true;scope.launch {
                    try { licenseText=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        listOf("youtubei.js-LICENSE.txt","bufbuild-LICENSE.txt","bufbuild-Google-BSD.txt","fflate-LICENSE.txt","meriyah-LICENSE.txt").joinToString("\n\n") { file ->
                            file+"\n"+context.assets.open("site-parser/licenses/$file").bufferedReader().use{it.readText()}
                        }
                    }} finally{licenseLoading=false}
                }}},enabled=!licenseLoading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("site-parser-licenses")){Text("查看站点解析组件许可证")}

            }
        }
    }
    licenseText?.let { text ->
        com.example.purebrowser.ui.resources.ResourceDialog({licenseText=null}) {
            Text("站点解析组件许可证",style=MaterialTheme.typography.titleLarge)
            SelectionContainer { Text(text,style=MaterialTheme.typography.bodySmall) }
            TextButton(onClick={licenseText=null},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("关闭")}
        }
    }

}
