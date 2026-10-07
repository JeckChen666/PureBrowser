package com.example.purebrowser.ui.resources

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.DownloadRules
import com.example.purebrowser.download.RequestPolicy
import com.example.purebrowser.download.dash.DashDownloadPlan
import com.example.purebrowser.download.dash.DashResolver
import com.example.purebrowser.download.hls.HlsDownloadPlan
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.rules.FormatChoice
import com.example.purebrowser.media.rules.FormatChoiceRow
import com.example.purebrowser.media.rules.FormatPreference
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.Glyph

/**
 * T87→T117 folded save screen for rules-sourced candidates whose rule reported a structured format
 * set (folded into `MediaCandidate.variants`): preference chips and rows with the canonical Best
 * preselected live on the SAME screen as the file name and toggles, and the single 保存视频 action
 * submits the picked row directly — manifest rows run their read/prepare as the save's own progress
 * phase right here (HlsPreparation/DashPreparation states, surfaced as 正在准备…/正在保存…).
 * A failure keeps the rows on screen for retry or re-pick; nothing is fetched before 保存.
 */
@Composable
fun RuleFormatConfirmation(
    draft: DownloadDraft,
    defaultWifiOnly: Boolean,
    hlsResolver: HlsResolver,
    dashResolver: DashResolver,
    onDismiss: () -> Unit,
    canUseWifi: (() -> Boolean)? = null,
    /** T86 session-reuse offer for rules-sourced candidates; null keeps the dialog unchanged. */
    sessionOffer: com.example.purebrowser.media.rules.SessionToggleOffer? = null,
    /** Persists the toggle choice (registrable domain + enabled) as it happens. */
    onSessionChoice: ((domain: String, enabled: Boolean) -> Unit)? = null,
    onConfirm: (draft: DownloadDraft, fileName: String, wifiOnly: Boolean, plan: HlsDownloadPlan?, dashPlan: DashDownloadPlan?) -> Unit,
) {
    val frozen = remember(draft) { draft.copy(candidate = draft.candidate.copy(sources = draft.candidate.sources.toSet())) }
    val rows = remember(frozen) { FormatChoice.rows(frozen.candidate.variants.orEmpty()) }
    val heights = remember(rows) { FormatChoice.heights(rows) }
    val containers = remember(rows) { FormatChoice.containers(rows) }
    var submitted by remember(frozen) { mutableStateOf(false) }
    // Preference mode: 0 = 最佳, 1 = 指定清晰度, 2 = 指定容器; Best preselects the same row quick-save uses.
    var mode by rememberSaveable(frozen) { mutableStateOf(0) }
    var wantedHeight by rememberSaveable(frozen) { mutableStateOf(-1) }
    var wantedExt by rememberSaveable(frozen) { mutableStateOf("") }
    var manualUrl by rememberSaveable(frozen) { mutableStateOf<String?>(null) }
    /** Manifest phase: a manifest-kind row was saved; its quality rows replace the format rows. */
    var manifestStage by remember(frozen) { mutableStateOf(false) }
    var manifestDraft by remember(frozen) { mutableStateOf<DownloadDraft?>(null) }
    var hlsPickedUrl by remember(frozen) { mutableStateOf<String?>(null) }
    var dashPickedHeight by remember(frozen) { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val hlsPreparation = remember(frozen, hlsResolver, scope) { HlsPreparation(hlsResolver, scope) }
    val dashPreparation = remember(frozen, dashResolver, scope) { DashPreparation(dashResolver, scope) }
    DisposableEffect(hlsPreparation, dashPreparation) { onDispose { hlsPreparation.close(); dashPreparation.close() } }

    val preference: FormatPreference? = when (mode) {
        1 -> heights.firstOrNull { it == wantedHeight }?.let { FormatPreference.FixedHeight(it) }
        2 -> containers.firstOrNull { it == wantedExt }?.let { FormatPreference.FixedExt(it) }
        else -> FormatPreference.Best
    }
    val computed = preference?.let { FormatChoice.select(rows, it) }
    val selected = rows.firstOrNull { it.url == manualUrl } ?: computed

    val baseName = remember(frozen) { SaveDefaults.saveNameSuggestion(frozen.candidate) }
    var nameEdited by rememberSaveable(frozen) { mutableStateOf(false) }
    var fileName by rememberSaveable(frozen) {
        mutableStateOf(qualitySuffixedName(baseName, FormatChoice.select(rows, FormatPreference.Best)?.height))
    }
    // A quality change re-prefills the name until the user edits it once.
    LaunchedEffect(selected?.height, hlsPreparation.selected, dashPreparation.selected, manifestStage) {
        if (!nameEdited) {
            val height = if (manifestStage) hlsPreparation.selected?.height ?: dashPreparation.selected?.height
            else selected?.height
            fileName = if (manifestStage) mp4SaveName(qualitySuffixedName(baseName, height))
            else qualitySuffixedName(baseName, height)
        }
    }
    var wifiOnly by rememberSaveable(frozen) { mutableStateOf(defaultWifiOnly) }
    var wifiPreviewBlocked by remember(frozen) { mutableStateOf(false) }
    val context = LocalContext.current
    val wifiAvailable = remember(context, canUseWifi) {
        canUseWifi ?: {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            val capabilities = if (manager != null && network != null) manager.getNetworkCapabilities(network) else null
            capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
    fun previewAllowed(): Boolean {
        val allowed = !wifiOnly || runCatching { wifiAvailable() }.getOrDefault(false)
        wifiPreviewBlocked = !allowed
        return allowed
    }
    val accessTarget = manifestDraft ?: frozen
    val contextAvailable = RequestPolicy.canUseContext(accessTarget.sourceUrl, accessTarget.frameUrl, accessTarget.reliableSource)
    var useContext by rememberSaveable(frozen) { mutableStateOf(contextAvailable && frozen.useAccessContext) }
    var sessionUse by rememberSaveable(frozen) { mutableStateOf(sessionOffer?.checkedByDefault == true) }
    val busy = hlsPreparation.busy || dashPreparation.busy
    val failure = hlsPreparation.error ?: dashPreparation.error
    val canSave = !submitted && !busy && fileName.isNotBlank() && fileName.trim() !in setOf(".", "..") &&
        (manifestStage || selected != null)

    fun startManifestSave(target: DownloadDraft) {
        val request = target.copy(useAccessContext = useContext)
        if (target.candidate.kind == MediaKind.HLS) {
            hlsPreparation.save(request, hlsPickedUrl) { plan ->
                submitted = true
                hlsPreparation.close()
                onConfirm(request, mp4SaveName(fileName), wifiOnly, plan, null)
            }
        } else {
            dashPreparation.save(request, dashPickedHeight) { plan ->
                submitted = true
                dashPreparation.close()
                onConfirm(request, mp4SaveName(fileName), wifiOnly, null, plan)
            }
        }
    }

    ResourceDialog(onDismiss = onDismiss) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        ResourceHeading("保存视频")
        Text(readableResourceName(frozen.candidate.displayName), style = MaterialTheme.typography.titleMedium)
        ResourceMetadata(frozen.candidate)
        ResourceSource(frozen)
        Text("站点规则报告了 ${rows.size} 个可选格式，已按“最佳”预选；点选其他格式或改用偏好后保存。清单类地址在保存时读取并准备所选清晰度。", style = MaterialTheme.typography.bodySmall)
        ResourceOption(
            label = "使用当前网站访问条件",
            description = if (contextAvailable) "只使用适用的同源会话和最小来源；修改后需重新读取清单" else "没有可靠页面关联，不使用网站会话",
            checked = useContext,
            enabled = contextAvailable && !submitted,
            onChange = {
                hlsPreparation.accessChanged()
                dashPreparation.accessChanged()
                useContext = it
                hlsPickedUrl = null
                dashPickedHeight = null
            },
            modifier = Modifier.testTag("download-use-context"),
        )
        sessionOffer?.let { offer ->
            ResourceSessionOption(
                offer = offer,
                checked = sessionUse,
                enabled = !submitted,
                onChange = { enabled ->
                    sessionUse = enabled
                    // Rule-session reuse only affects future rule fetches, never this download.
                    onSessionChoice?.invoke(offer.domain, enabled)
                },
            )
        }
        if (!manifestStage) {
            Text("画质偏好", style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preferenceRow("最佳", "按分辨率、码率与容器的既定排序自动选择", mode == 0 && manualUrl == null) { mode = 0; manualUrl = null }
                preferenceRow("指定清晰度", "从可用高度中选择；无精确档时选择不超过该高度的次优档", mode == 1 && manualUrl == null) {
                    mode = 1; manualUrl = null
                    if (wantedHeight <= 0 && heights.isNotEmpty()) wantedHeight = heights.first()
                }
                preferenceRow("指定容器", "限定 mp4 / webm / ts 容器后按既定排序选择", mode == 2 && manualUrl == null) {
                    mode = 2; manualUrl = null
                    if (wantedExt !in containers) wantedExt = containers.firstOrNull() ?: ""
                }
            }
            if (mode == 1) {
                Text("清晰度", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    heights.forEach { height ->
                        choiceRow(
                            label = "${height}p",
                            tag = "rule-format-height-$height",
                            selected = wantedHeight == height,
                            enabled = !submitted,
                        ) { wantedHeight = height; manualUrl = null }
                    }
                }
            }
            if (mode == 2) {
                Text("容器", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    containers.forEach { ext ->
                        choiceRow(
                            label = ext,
                            tag = "rule-format-ext-$ext",
                            selected = wantedExt == ext,
                            enabled = !submitted,
                        ) { wantedExt = ext; manualUrl = null }
                    }
                }
            }
            Text("可选格式", style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEachIndexed { index, row ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rule-format-row-$index")
                            .selectable(selected = selected === row, enabled = !submitted,
                                role = Role.RadioButton, onClick = { manualUrl = row.url }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = selected === row, onClick = null, enabled = !submitted)
                        Column(Modifier.padding(end = 8.dp)) {
                            Text(FormatChoice.label(row))
                            if (row.kind == MediaKind.HLS || row.kind == MediaKind.DASH) Text(
                                "清单地址，保存时将读取并准备所选清晰度",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            val target = manifestDraft
            if (target?.candidate?.kind == MediaKind.HLS) {
                HlsQualityRows(
                    preparation = hlsPreparation,
                    summaries = emptyList(),
                    defaultUrl = null,
                    pickedUrl = hlsPickedUrl,
                    submitted = submitted,
                    onPick = { hlsPickedUrl = it },
                )
            } else if (target?.candidate?.kind == MediaKind.DASH) {
                DashQualityRows(
                    preparation = dashPreparation,
                    summaries = emptyList(),
                    defaultHeight = null,
                    pickedHeight = dashPickedHeight,
                    submitted = submitted,
                    onPick = { dashPickedHeight = it },
                )
            }
            TextButton(
                onClick = {
                    hlsPreparation.accessChanged()
                    dashPreparation.accessChanged()
                    manifestStage = false
                    manifestDraft = null
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rule-format-repick"),
            ) { Text("重新选择格式") }
        }
        if (wifiPreviewBlocked) ResourceStatus(error = true) {
            Text("仅 Wi-Fi 已开启；请连接 Wi-Fi，或关闭“仅 Wi-Fi”后再保存。不会自动重试。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rule-format-wifi-required"))
        }
        if (busy) ResourceStatus {
            Text("正在准备…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rule-format-preparing"))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("rule-format-preparation-progress")
                .semantics { stateDescription = "正在准备保存，尚未创建下载任务" })
        }
        if (submitted) ResourceStatus { Text("正在保存…") }
        failure?.let { message ->
            ResourceStatus(error = true) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rule-format-error"))
                Text("可再次点“保存视频”重试，或重新点选格式后再保存。", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it.take(200); nameEdited = true },
            label = { Text("文件名") },
            singleLine = true, enabled = !submitted,
            isError = fileName.isBlank() || fileName.trim() in setOf(".", ".."),
            supportingText = { Text(when {
                fileName.isBlank() || fileName.trim() in setOf(".", "..") -> "请输入有效的文件名"
                else -> "可修改名称，请保留文件扩展名。路径、控制字符和特殊字符会过滤。"
            }) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(force = true); keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().testTag("download-file-name"),
        )
        ResourceOption(
            label = "仅 Wi-Fi",
            description = if (wifiOnly) "无 Wi-Fi 时等待连接" else "允许使用移动网络，可能产生流量费用",
            checked = wifiOnly,
            enabled = !submitted,
            onChange = { wifiOnly = it; wifiPreviewBlocked = false },
        )
        Button(
            onClick = {
                if (!canSave || submitted) return@Button
                if (manifestStage) {
                    val target = manifestDraft ?: return@Button
                    if (!previewAllowed()) return@Button
                    startManifestSave(target)
                } else {
                    val row = selected ?: return@Button
                    if (row.kind == MediaKind.HLS || row.kind == MediaKind.DASH) {
                        if (!previewAllowed()) return@Button
                        val target = frozen.copy(candidate = frozen.candidate.copy(url = row.url, kind = row.kind, variants = null))
                        manifestStage = true
                        manifestDraft = target
                        hlsPickedUrl = null
                        dashPickedHeight = null
                        startManifestSave(target)
                    } else {
                        submitted = true
                        focus.clearFocus(force = true)
                        keyboard?.hide()
                        onConfirm(frozen.copy(useAccessContext = useContext), DownloadRules.safeFileName(fileName), wifiOnly, null, null)
                    }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rule-format-save"),
        ) {
            BrowserGlyph(Glyph.DOWNLOAD, "保存视频", Modifier.clearAndSetSemantics {})
            Text(if (busy) "正在准备…" else "保存视频", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = {
            focus.clearFocus(force = true)
            keyboard?.hide()
            onDismiss()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
    }
}

@Composable
private fun preferenceRow(label: String, description: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun choiceRow(label: String, tag: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
