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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.example.purebrowser.download.DownloadRules
import com.example.purebrowser.download.RequestPolicy
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.download.hls.HlsDownloadPlan
import com.example.purebrowser.download.hls.HlsPlaylist
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.download.hls.HlsVariant

/** Kept separate so existing direct-download callers and their three-argument callback stay intact. */
@Composable
fun HlsDownloadConfirmation(
    draft: DownloadDraft,
    defaultWifiOnly: Boolean,
    resolver: HlsResolver,
    onDismiss: () -> Unit,
    canUseWifi: (() -> Boolean)? = null,
    onConfirm: (draft: DownloadDraft, fileName: String, wifiOnly: Boolean, plan: HlsDownloadPlan) -> Unit,
) {
    val frozen = remember(draft) { draft.copy(candidate = draft.candidate.copy(sources = draft.candidate.sources.toSet())) }
    val scope = rememberCoroutineScope()
    val preparation = remember(frozen, resolver, scope) { HlsPreparation(resolver, scope) }
    DisposableEffect(preparation) { onDispose { preparation.close() } }
    val suggestion = remember(frozen) {
        hlsFileName(suggestedFileName(frozen.candidate))
    }
    var fileName by rememberSaveable(frozen) { mutableStateOf(suggestion) }
    var wifiOnly by rememberSaveable(frozen) { mutableStateOf(defaultWifiOnly) }
    var wifiPreviewBlocked by remember(frozen) { mutableStateOf(false) }
    val context = LocalContext.current
    // Evaluate only when the user clicks an explicit preview action, never on a network event.
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
    var submitted by remember(frozen) { mutableStateOf(false) }
    val requestDraft = frozen.copy(useAccessContext = useContext)
    val safeName = hlsFileName(fileName)
    val ready = preparation.readyPlan(requestDraft)
    val canConfirm = ready != null && frozen.candidate.kind == MediaKind.HLS && frozen.candidate.canTryDownload() && fileName.isNotBlank() &&
        fileName.trim() !in setOf(".", "..") && !submitted
    val dismiss = {
        preparation.close()
        onDismiss()
    }

    ResourceDialog(onDismiss = dismiss) {
        // Resolve window-local controllers inside the Dialog, not its caller.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val dismissWithKeyboard = {
            focus.clearFocus(force = true)
            keyboard?.hide()
            dismiss()
        }
        ResourceHeading("确认下载 HLS")
        Text(readableResourceName(frozen.candidate.displayName), style = MaterialTheme.typography.titleMedium)
        ResourceMetadata(frozen.candidate)
        ResourceSource(frozen)
        Text("仅在你点击解析或准备时读取清单；选择档位不会自动请求子清单。仅支持未加密的固定点播 MPEG-TS（H.264 / AAC），合并为独立 MP4。不支持直播、DRM、独立音轨、fMP4 或续传。", style = MaterialTheme.typography.bodySmall)
        ResourceOption(
            label = "使用当前网站访问条件",
            description = if (contextAvailable) "只使用适用的同源会话和最小来源；修改后需重新解析清单" else "没有可靠页面关联，不使用网站会话",
            checked = useContext,
            enabled = contextAvailable && !submitted,
            onChange = {
                // Clear the prepared plan synchronously before the new access choice can be saved.
                preparation.accessChanged()
                useContext = it
            },
            modifier = Modifier.testTag("download-use-context"),
        )
        OutlinedButton(
            onClick = { if (previewAllowed()) preparation.parse(requestDraft) },
            enabled = !submitted && !preparation.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hls-parse-playlist"),
        ) {
            BrowserGlyph(Glyph.LIST, "解析播放清单", Modifier.clearAndSetSemantics {})
            Text("解析播放清单", modifier = Modifier.padding(start = 8.dp))
        }
        if (wifiPreviewBlocked) ResourceStatus(error = true) {
            Text("仅 Wi-Fi 已开启；请连接 Wi-Fi，或关闭“仅 Wi-Fi”后再点击解析/准备。不会自动重试。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("hls-wifi-required"))
        }
        val master = preparation.options?.playlist as? HlsPlaylist.Master
        if (master != null) {
            Text("选择视频档位", style = MaterialTheme.typography.titleSmall)
            Text("默认优先选择不超过 1080p 的受支持档位；仅作为选择建议，不代表内容已通过校验。分辨率或带宽缺失时不作推测。", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                master.variants.forEachIndexed { index, variant ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hls-variant-$index")
                            .selectable(selected = preparation.selected == variant, enabled = variant.supported && !submitted,
                                role = Role.RadioButton, onClick = { preparation.select(variant) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = preparation.selected == variant, onClick = null, enabled = variant.supported && !submitted)
                        Column(Modifier.weight(1f)) {
                            Text(hlsVariantLabel(variant))
                            if (!variant.supported) Text(
                                readableResourceName(variant.unsupportedReason ?: "此档位不在本版支持范围"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (preparation.selected == null) Text("没有受支持的档位，请返回来源网页重新发现资源。", color = MaterialTheme.colorScheme.error)
            OutlinedButton(
                onClick = { if (previewAllowed()) preparation.prepare(requestDraft) },
                enabled = !submitted && !preparation.busy && preparation.selected?.supported == true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hls-prepare-variant"),
            ) {
                BrowserGlyph(Glyph.CHECK, "准备所选档位", Modifier.clearAndSetSemantics {})
                Text("准备所选档位", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (preparation.busy) ResourceStatus {
            Text("正在读取清单…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("hls-preparing"))
            // Network preparation has no meaningful byte or whole-video percentage.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("hls-preparation-progress")
                .semantics { stateDescription = "正在读取清单，尚未创建下载任务" })
        }
        preparation.error?.let { message ->
            ResourceStatus(error = true) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("hls-error"))
            }
        }
        ready?.let { plan ->
            ResourceStatus(modifier = Modifier.testTag("hls-plan-ready")) {
                Text("清单已准备 · ${plan.media.segments.size} 个分片")
                val seconds = plan.media.durationUs / 1_000_000
                Text("清单时长：${seconds / 60} 分 ${seconds % 60} 秒（清单声明）")
                Text("成品大小未知；清单响应大小不是视频大小。下载完成并封装校验后才保存 MP4。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (ready == null && !preparation.busy) Text("清单准备完成前不能保存。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        OutlinedTextField(
            value = fileName, onValueChange = { fileName = it.take(200) }, label = { Text("文件名") },
            singleLine = true, enabled = !submitted,
            isError = fileName.isBlank() || fileName.trim() in setOf(".", ".."),
            supportingText = { Text(when {
                fileName.isBlank() || fileName.trim() in setOf(".", "..") -> "请输入有效的文件名"
                safeName != fileName -> "安全文件名：$safeName"
                else -> "成品统一保存为 MP4。路径、控制字符和特殊字符会过滤。"
            }) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(force = true); keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().testTag("download-file-name"),
        )
        Text("保存至本机公共 Download/PureBrowser 目录，添加唯一前缀避免覆盖。", style = MaterialTheme.typography.bodySmall)
        ResourceOption(
            label = "仅 Wi-Fi",
            description = if (wifiOnly) "无 Wi-Fi 时等待连接" else "允许使用移动网络，可能产生流量费用",
            checked = wifiOnly,
            enabled = !submitted,
            onChange = { wifiOnly = it; wifiPreviewBlocked = false },
        )
        Text("会话不写入任务记录，不跨源转发。重新下载会另建任务并重新读取所选档位，不续传；档位消失时不会偷偷改选其他画质。", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                // Re-read readiness at click time: a queued click must not submit an invalidated plan.
                val plan = preparation.readyPlan(requestDraft)
                if (canConfirm && !submitted && plan != null) {
                    submitted = true
                    preparation.close()
                    focus.clearFocus(force = true)
                    keyboard?.hide()
                    onConfirm(requestDraft, safeName, wifiOnly, plan)
                }
            },
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hls-save"),
        ) {
            BrowserGlyph(Glyph.DOWNLOAD, "保存视频", Modifier.clearAndSetSemantics {})
            Text("保存视频", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = dismissWithKeyboard, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
    }
}

private fun hlsVariantLabel(variant: HlsVariant): String {
    val resolution = if (variant.width != null && variant.height != null) "${variant.width} × ${variant.height}" else "分辨率未知"
    val bandwidth = variant.bandwidth?.let { "$it bit/s（清单声明带宽）" } ?: "带宽未知"
    return "$resolution · $bandwidth"
}

/** Reserve the extension within the repository's UTF-8/character limits, even for edited names. */
private fun hlsFileName(value: String): String {
    var stem = DownloadRules.safeFileName(value.substringBeforeLast('.', value))
    while (stem.length > 96 || stem.toByteArray(Charsets.UTF_8).size > 236) {
        stem = stem.dropLast(Character.charCount(stem.codePointBefore(stem.length)))
    }
    return "$stem.mp4"
}
