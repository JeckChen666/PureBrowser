package com.example.purebrowser.ui.resources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
            candidate.kind == MediaKind.UNKNOWN -> "这是一个待确认的地址：点“分析媒体”检查它是什么视频，检查通过后再保存。"
            candidate.kind == MediaKind.HLS -> candidate.unsupportedExplanation()
            candidate.canTryDownload() -> "这是一个可保存的视频文件地址，保存前会自动检查内容。保存时可以选择是否使用网站的登录状态（仅限同一网站），不用也能尝试公开下载；链接过期或格式问题仍可能导致失败。"
            else -> candidate.unsupportedExplanation()
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("发现方式", style = MaterialTheme.typography.titleSmall)
        Text(
            Evidence.entries.filter { it in candidate.sources }.joinToString(" / ") { it.label }.ifEmpty { "未记录发现方式" },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text("视频地址", style = MaterialTheme.typography.titleSmall)
    Text("地址中可能带有临时有效的访问参数，请勿随意分享。长按可以复制。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SelectionContainer {
        // Do not derive this text from URI, displayName, decoded components or a scrubbed URL.
        Text(
            candidate.url,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    ResourceTechDetail(candidate)
    if (candidate.canTryDownload()) {
        Button(onClick = onSelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            BrowserGlyph(Glyph.DOWNLOAD, if(candidate.kind==MediaKind.UNKNOWN)"分析媒体" else "保存", Modifier.clearAndSetSemantics {})
            Text(if(candidate.kind==MediaKind.UNKNOWN)"分析媒体" else "保存", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * T118: protocol and implementation wording lives behind a collapsed 技术详情 row instead of the
 * primary copy. Expanded state survives recomposition but is deliberately not part of navigation.
 */
@Composable
private fun ResourceTechDetail(candidate: MediaCandidate) {
    var expanded by rememberSaveable(candidate.url) { mutableStateOf(false) }
    val boundary = candidate.technicalBoundary()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("技术详情", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        BrowserGlyph(if (expanded) Glyph.LIST else Glyph.PLUS, if (expanded) "收起技术详情" else "展开技术详情")
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // tech-detail:begin
            Text("原始类型：${candidate.kind.label}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("地址协议：${runCatching { URI(candidate.url).scheme }.getOrNull() ?: "未知"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // tech-detail:end
            boundary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
