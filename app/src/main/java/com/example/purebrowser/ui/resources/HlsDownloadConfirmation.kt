package com.example.purebrowser.ui.resources

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.RequestPolicy
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.VariantSummary
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.download.hls.HlsDownloadPlan
import com.example.purebrowser.download.hls.HlsPlaylist
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.download.hls.HlsVariant

/**
 * T117 single-screen HLS save. The quality ladder comes from the already-attached
 * detect-and-parse summaries with the ≤1080p-best default preselected and highlighted; switching
 * quality is a row tap. The single 保存视频 action runs the manifest read and plan preparation as
 * its own progress phase (HlsPreparation states surfaced as 正在准备…/正在保存…), and a failure
 * stays on this screen with the rows intact for retry or re-pick. Nothing is fetched before 保存
 * is tapped; a master playlist discovered only at save time surfaces its rows for one confirm.
 */
@Composable
fun HlsDownloadConfirmation(
    draft: DownloadDraft,
    defaultWifiOnly: Boolean,
    resolver: HlsResolver,
    onDismiss: () -> Unit,
    canUseWifi: (() -> Boolean)? = null,
    /** T86 session-reuse offer for rules-sourced candidates; null keeps the dialog unchanged. */
    sessionOffer: com.example.purebrowser.media.rules.SessionToggleOffer? = null,
    /** Persists the toggle choice (registrable domain + enabled) as it happens. */
    onSessionChoice: ((domain: String, enabled: Boolean) -> Unit)? = null,
    onConfirm: (draft: DownloadDraft, fileName: String, wifiOnly: Boolean, plan: HlsDownloadPlan) -> Unit,
) {
    val frozen = remember(draft) { draft.copy(candidate = draft.candidate.copy(sources = draft.candidate.sources.toSet())) }
    val scope = rememberCoroutineScope()
    val preparation = remember(frozen, resolver, scope) { HlsPreparation(resolver, scope) }
    DisposableEffect(preparation) { onDispose { preparation.close() } }
    val summaries = remember(frozen) { frozen.candidate.variants.orEmpty() }
    val defaultSummary = remember(summaries) { SaveDefaults.defaultVariant(summaries) }
    var pickedUrl by rememberSaveable(frozen) { mutableStateOf<String?>(defaultSummary?.url) }
    val baseName = remember(frozen) { SaveDefaults.saveNameSuggestion(frozen.candidate) }
    var nameEdited by rememberSaveable(frozen) { mutableStateOf(false) }
    var fileName by rememberSaveable(frozen) {
        mutableStateOf(mp4SaveName(qualitySuffixedName(baseName, defaultSummary?.height)))
    }
    // A quality change re-prefills the name until the user edits it once.
    LaunchedEffect(pickedUrl, preparation.selected) {
        if (!nameEdited) {
            val height = preparation.selected?.height
                ?: summaries.firstOrNull { it.url == pickedUrl }?.height
                ?: defaultSummary?.height
            fileName = mp4SaveName(qualitySuffixedName(baseName, height))
        }
    }
    var wifiOnly by rememberSaveable(frozen) { mutableStateOf(defaultWifiOnly) }
    var wifiPreviewBlocked by remember(frozen) { mutableStateOf(false) }
    val context = LocalContext.current
    // Evaluate only when the user clicks the explicit save action, never on a network event.
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
    val contextAvailable = RequestPolicy.canUseContext(frozen.sourceUrl, frozen.frameUrl, frozen.reliableSource)
    var useContext by rememberSaveable(frozen) { mutableStateOf(contextAvailable && frozen.useAccessContext) }
    var sessionUse by rememberSaveable(frozen) { mutableStateOf(sessionOffer?.checkedByDefault == true) }
    var submitted by remember(frozen) { mutableStateOf(false) }
    val requestDraft = frozen.copy(useAccessContext = useContext)
    val ready = preparation.readyPlan(requestDraft)
    val canSave = !submitted && !preparation.busy && frozen.candidate.kind == MediaKind.HLS &&
        frozen.candidate.canTryDownload() && fileName.isNotBlank() &&
        fileName.trim() !in setOf(".", "..")
    val dismiss = {
        preparation.close()
        onDismiss()
    }

    ResourceDialog(onDismiss = dismiss) {
        // Resolve window-local controllers inside the Dialog, not its caller.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        ResourceHeading("确认下载 HLS")
        Text(readableResourceName(frozen.candidate.displayName), style = MaterialTheme.typography.titleMedium)
        ResourceMetadata(frozen.candidate)
        ResourceSource(frozen)
        Text("点击“保存视频”时读取播放地址并准备所选清晰度，其余操作不发起请求。仅支持未加密的固定点播视频，成品保存为 MP4。", style = MaterialTheme.typography.bodySmall)
        ResourceOption(
            label = "使用当前网站访问条件",
            description = if (contextAvailable) "只使用适用的同源会话和最小来源；修改后需重新读取播放地址" else "没有可靠页面关联，不使用网站会话",
            checked = useContext,
            enabled = contextAvailable && !submitted,
            onChange = {
                // Clear any parsed metadata synchronously before the new access choice can be saved.
                preparation.accessChanged()
                useContext = it
                pickedUrl = defaultSummary?.url
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
        HlsQualityRows(
            preparation = preparation,
            summaries = summaries,
            defaultUrl = defaultSummary?.url,
            pickedUrl = pickedUrl,
            submitted = submitted,
            onPick = { pickedUrl = it },
        )
        // T119: the notice tracks the live gate — it clears as soon as Wi-Fi is back, and
        // clearing it never restarts anything by itself (the user's next 保存 does).
        if (wifiPreviewBlocked && wifiOnly && runCatching { !wifiAvailable() }.getOrDefault(true)) ResourceStatus(error = true) {
            Text("仅 Wi-Fi 已开启；请连接 Wi-Fi，或关闭“仅 Wi-Fi”后再保存。不会自动重试。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("hls-wifi-required"))
        }
        if (preparation.busy) ResourceStatus {
            Text("正在准备…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("hls-preparing"))
            // Manifest preparation has no meaningful byte or whole-video percentage.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("hls-preparation-progress")
                .semantics { stateDescription = "正在准备保存，尚未创建下载任务" })
        }
        if (submitted) ResourceStatus(modifier = Modifier.testTag("hls-saving")) { Text("正在保存…") }
        preparation.error?.let { message ->
            ResourceStatus(error = true) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("hls-error"))
                Text("可再次点“保存视频”重试，或点选其他清晰度后保存。", style = MaterialTheme.typography.bodySmall)
            }
        }
        ready?.let { plan ->
            ResourceStatus(modifier = Modifier.testTag("hls-plan-ready")) {
                Text("准备完成 · ${plan.media.segments.size} 个分片")
                val seconds = plan.media.durationUs / 1_000_000
                Text("视频时长：${seconds / 60} 分 ${seconds % 60} 秒（按来源声明）")
                plan.audio?.let { audio ->
                    val label = listOfNotNull(
                        audio.rendition.language?.takeIf { it.isNotBlank() },
                        audio.rendition.channels?.takeIf { it.isNotBlank() },
                    ).joinToString(" ")
                    Text(
                        if (label.isBlank()) "音视频分轨保存（自动合并音轨）" else "音视频分轨保存（自动合并音轨 $label）",
                        modifier = Modifier.testTag("hls-dual-track"),
                    )
                }
                Text("成品大小未知；下载完成并封装校验后才保存 MP4。", style = MaterialTheme.typography.bodySmall)
            }
        }
        val parsed = preparation.options?.playlist as? HlsPlaylist.Master
        if (parsed != null && ready == null && !preparation.busy && preparation.error == null) {
            Text("已读取到 ${parsed.variants.size} 个清晰度，点选后保存。", style = MaterialTheme.typography.bodySmall)
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
                mp4SaveName(fileName) != fileName -> "安全文件名：${mp4SaveName(fileName)}"
                else -> "成品统一保存为 MP4。路径、控制字符和特殊字符会过滤。"
            }) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(force = true); keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().testTag("download-file-name"),
        )
        Text(if (Build.VERSION.SDK_INT >= 29) "保存至本机公共 Download/PureBrowser 目录，添加唯一前缀避免覆盖。"
        else "此系统版本不允许写入公共下载；保存至应用专属外部目录，可打开或分享后另存。仍添加唯一前缀避免覆盖。", style = MaterialTheme.typography.bodySmall)
        ResourceOption(
            label = "仅 Wi-Fi",
            description = if (wifiOnly) "无 Wi-Fi 时等待连接" else "允许使用移动网络，可能产生流量费用",
            checked = wifiOnly,
            enabled = !submitted,
            onChange = { wifiOnly = it; wifiPreviewBlocked = false },
        )
        Text("会话不写入任务记录，不跨源转发。重新下载会另建任务并重新读取所选清晰度，不续传；所选清晰度消失时不会偷偷改选其他画质。", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                if (!canSave || submitted || !previewAllowed()) return@Button
                preparation.save(requestDraft, pickedUrl) { plan ->
                    submitted = true
                    preparation.close()
                    focus.clearFocus(force = true)
                    keyboard?.hide()
                    // Read the name at completion time: it may change while the chain runs.
                    onConfirm(requestDraft, mp4SaveName(fileName), wifiOnly, plan)
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hls-save"),
        ) {
            BrowserGlyph(Glyph.DOWNLOAD, "保存视频", Modifier.clearAndSetSemantics {})
            Text(if (preparation.busy) "正在准备…" else "保存视频", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = {
            focus.clearFocus(force = true)
            keyboard?.hide()
            dismiss()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
    }
}

/**
 * The one quality section shared by the HLS save screen and the folded rules screen: parsed master
 * rows once a manifest was read, otherwise the pre-attached detect-and-parse summaries with the
 * default preselected. Pure selection surface — tapping a row never fetches.
 */
@Composable
internal fun HlsQualityRows(
    preparation: HlsPreparation,
    summaries: List<VariantSummary>,
    defaultUrl: String?,
    pickedUrl: String?,
    submitted: Boolean,
    onPick: (String?) -> Unit,
) {
    val master = preparation.options?.playlist as? HlsPlaylist.Master
    if (master != null) {
        Text("选择清晰度", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            master.variants.forEachIndexed { index, variant ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hls-variant-$index")
                        .selectable(selected = preparation.selected == variant, enabled = variant.supported && !submitted,
                            role = Role.RadioButton, onClick = { preparation.select(variant); onPick(variant.url) })
                        // TalkBack: announce the pick state explicitly (selectable sets no description).
                        .semantics { stateDescription = if (preparation.selected == variant) "已选择" else "未选择" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = preparation.selected == variant, onClick = null, enabled = variant.supported && !submitted)
                    Column(Modifier.weight(1f)) {
                        Text(hlsVariantLabel(variant))
                        if (!variant.supported) Text(
                            readableResourceName(variant.unsupportedReason ?: "此清晰度不在本版支持范围"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                        // Gate downgrades (codec/字幕/独立音轨) stay selectable and only warn here.
                        else variant.unsupportedReason?.let {
                            Text(readableResourceName(it), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
        if (preparation.selected == null) Text("没有受支持的清晰度，请返回来源网页重新发现资源。", color = MaterialTheme.colorScheme.error)
    } else if (summaries.isNotEmpty()) {
        Text("选择清晰度", style = MaterialTheme.typography.titleSmall)
        Text("默认优先选择不超过 1080p 的清晰度；点选其他清晰度后保存。", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            summaries.forEachIndexed { index, summary ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hls-variant-$index")
                        .selectable(selected = pickedUrl == summary.url, enabled = !submitted,
                            role = Role.RadioButton, onClick = { onPick(summary.url) })
                        // TalkBack: announce the pick state explicitly (selectable sets no description).
                        .semantics { stateDescription = if (pickedUrl == summary.url) "已选择" else "未选择" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = pickedUrl == summary.url, onClick = null, enabled = !submitted)
                    Column(Modifier.weight(1f)) {
                        Text(
                            summaryVariantLabel(summary) + (if (summary.url == defaultUrl) " · 默认" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        summary.warning?.let {
                            Text(readableResourceName(it), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }
}

internal fun summaryVariantLabel(summary: VariantSummary): String {
    val quality = summary.height?.let { "${it}p" } ?: "分辨率未知"
    val bandwidth = summary.bandwidth?.let { "$it bit/s（来源声明带宽）" } ?: "带宽未知"
    return "$quality · $bandwidth"
}

private fun hlsVariantLabel(variant: HlsVariant): String {
    val resolution = if (variant.width != null && variant.height != null) "${variant.width} × ${variant.height}" else "分辨率未知"
    val bandwidth = variant.bandwidth?.let { "$it bit/s（来源声明带宽）" } ?: "带宽未知"
    return "$resolution · $bandwidth"
}
