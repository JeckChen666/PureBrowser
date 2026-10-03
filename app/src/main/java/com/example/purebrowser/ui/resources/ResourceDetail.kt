package com.example.purebrowser.ui.resources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.net.URI

@Composable
internal fun ResourceDetail(
    candidate: MediaCandidate,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onSource: () -> Unit,
) {
    ResourceDialog(onDismiss = onDismiss) {
        ResourceDetailContent(candidate, onDismiss, onSelect, onSource)
    }
}

/** Same evidence view in a standalone dialog or replacing the list inside its existing sheet. */
@Composable
internal fun ResourceDetailContent(
    candidate: MediaCandidate,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onSource: () -> Unit,
) {
    ResourceHeader("资源详情", "关闭详情", onDismiss, onSource)
    Text(readableResourceName(candidate.displayName), style = MaterialTheme.typography.titleMedium)
    ResourceMetadata(candidate)
    Text(
        when {
            candidate.kind == MediaKind.HLS -> candidate.unsupportedExplanation()
            candidate.canTryDownload() -> "这是可尝试的文件直链，不代表已验证为完整视频。确认时可选择适用的同源网站会话和最小来源条件，也可关闭后尝试公开下载；会话不跨源转发，不保证跨来源下载成功。"
            else -> candidate.unsupportedExplanation()
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("发现证据", style = MaterialTheme.typography.titleSmall)
        Text(
            Evidence.entries.filter { it in candidate.sources }.joinToString(" / ") { it.label }.ifEmpty { "未记录发现证据" },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("地址协议：${runCatching { URI(candidate.url).scheme }.getOrNull() ?: "未知"}", style = MaterialTheme.typography.bodySmall)
    }
    Text("原始媒体地址", style = MaterialTheme.typography.titleSmall)
    Text("地址可能包含签名或访问参数，请勿随意分享。长按可选择并复制；此处保留原始地址，不解码或删减参数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SelectionContainer {
        // Do not derive this text from URI, displayName, decoded components or a scrubbed URL.
        Text(
            candidate.url,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (candidate.canTryDownload()) {
        Button(onClick = onSelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            BrowserGlyph(Glyph.DOWNLOAD, "尝试下载", Modifier.clearAndSetSemantics {})
            Text("尝试下载", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
