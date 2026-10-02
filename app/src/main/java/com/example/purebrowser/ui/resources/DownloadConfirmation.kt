package com.example.purebrowser.ui.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.DownloadRules
import com.example.purebrowser.media.MediaKind

/**
 * Pure confirmation UI. The caller keeps the selected draft for enqueue/permission handling
 * and must revalidate its source tab/generation before submitting. No active-tab lookup occurs
 * here; a deliberately replaced draft resets the edits rather than applying them to a new URL.
 * The repository, not this dialog, owns unique on-disk naming and task creation.
 */
@Composable
fun DownloadConfirmation(
    draft: DownloadDraft,
    defaultWifiOnly: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, wifiOnly: Boolean, useContext: Boolean) -> Unit,
) {
    val frozen = remember(draft) { draft.copy(candidate = draft.candidate.copy(sources = draft.candidate.sources.toSet())) }
    val suggestion = remember(frozen) { suggestedFileName(frozen.candidate) }
    var fileName by rememberSaveable(frozen) { mutableStateOf(suggestion) }
    // Defaults seed a new draft only. A settings refresh must not undo this task's choice.
    var wifiOnly by rememberSaveable(frozen) { mutableStateOf(defaultWifiOnly) }
    val contextAvailable = com.example.purebrowser.download.RequestPolicy.canUseContext(frozen.sourceUrl,frozen.frameUrl,frozen.reliableSource)
    var useContext by rememberSaveable(frozen) { mutableStateOf(contextAvailable && frozen.useAccessContext) }
    var submitted by remember(frozen) { mutableStateOf(false) }
    val safeName = DownloadRules.safeFileName(fileName)
    val canConfirm = fileName.isNotBlank() && fileName.trim() !in setOf(".", "..") &&
        frozen.candidate.canTryDownload() && frozen.candidate.kind != MediaKind.HLS && !submitted
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    ResourceDialog(onDismiss = onDismiss) {
        Text("确认下载直链", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(readableResourceName(frozen.candidate.displayName), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        ResourceMetadata(frozen.candidate)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            frozen.sourceTitle?.takeIf(String::isNotBlank)?.let {
                Text("来源页面：${readableResourceName(it)}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("来源主机：${sourceHost(frozen.sourceUrl) ?: "未记录"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it.take(200) },
            label = { Text("文件名") },
            singleLine = true,
            enabled = !submitted,
            isError = fileName.isBlank() || fileName.trim() in setOf(".", ".."),
            supportingText = {
                Text(when {
                    fileName.isBlank() || fileName.trim() in setOf(".", "..") -> "请输入有效的文件名"
                    safeName != fileName -> "安全文件名：$safeName"
                    else -> "可修改名称，请保留文件扩展名。路径、控制字符和特殊字符会过滤。"
                })
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().testTag("download-file-name"),
        )
        Text("保存至系统 Download/PureBrowser 目录。保存时会添加唯一前缀，避免覆盖同名文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = wifiOnly, enabled = !submitted, role = Role.Checkbox, onValueChange = { wifiOnly = it }),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One accessible toggle target, not a separately clickable checkbox and row.
            Checkbox(checked = wifiOnly, onCheckedChange = null, enabled = !submitted)
            Column(Modifier.weight(1f)) {
                Text("仅 Wi-Fi", style = MaterialTheme.typography.bodyLarge)
                Text(if (wifiOnly) "无 Wi-Fi 时等待连接" else "允许使用移动网络，可能产生流量费用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("download-use-context")
            .toggleable(value=useContext,enabled=contextAvailable && !submitted,role=Role.Checkbox,onValueChange={ useContext=it }),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Checkbox(checked=useContext,onCheckedChange=null,enabled=contextAvailable && !submitted)
            Column(Modifier.weight(1f)) {
                Text("使用当前网站访问条件")
                Text(if(contextAvailable) "只使用适用的同源会话和最小来源；可关闭后尝试公开下载" else "没有可靠页面关联，不使用网站会话",
                    style=MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            if (frozen.candidate.canTryDownload() && frozen.candidate.kind != MediaKind.HLS) "仅支持 MP4/WebM 文件直链；会话不写入任务记录，不跨源转发。签名可能过期，格式初检不等于完整播放保证。"
            else frozen.candidate.unsupportedExplanation(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                if (canConfirm && !submitted) {
                    submitted = true
                    focusManager.clearFocus(force=true)
                    keyboard?.hide()
                    onConfirm(safeName, wifiOnly, useContext)
                }
            },
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("开始下载") }
        TextButton(onClick = { focusManager.clearFocus(force=true);keyboard?.hide();onDismiss() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
    }
}
