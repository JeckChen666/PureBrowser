package com.example.purebrowser.ui.resources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import java.net.URI

@Composable
internal fun ResourceDetail(
    candidate: MediaCandidate,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onSource: () -> Unit,
) {
    ResourceDialog(onDismiss = onDismiss) {
        Text("资源详情", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(readableResourceName(candidate.displayName), style = MaterialTheme.typography.titleMedium)
        ResourceMetadata(candidate)
        Text(
            if (candidate.canTryDownload()) "这是可尝试的文件直链，不代表已验证为完整视频。仅支持公开资源，不转发登录凭据。"
            else candidate.unsupportedExplanation(),
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
        OutlinedButton(onClick = onSource, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回来源页") }
        if (candidate.canTryDownload()) {
            Button(onClick = onSelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("尝试下载") }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭详情") }
    }
}
