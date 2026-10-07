package com.example.purebrowser.ui.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.ui.components.ToolButton

/** Resource-only presentation primitives; colors always come from the parent's Material theme. */
@Composable
internal fun ResourceHeading(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

@Composable
internal fun ResourceHeader(title: String, closeLabel: String, onClose: () -> Unit, onSource: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ResourceHeading(title, Modifier.weight(1f))
        if (onSource != null) ToolButton(Glyph.LOCATE, "返回来源页", action = onSource)
        ToolButton(Glyph.CLOSE, closeLabel, action = onClose)
    }
}

/** One toggle target, including its explanation; never a second independently clickable checkbox. */
@Composable
internal fun ResourceOption(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Only frozen, recorded source information; the media host never substitutes for the page host. */
@Composable
internal fun ResourceSource(draft: DownloadDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        draft.sourceTitle?.takeIf(String::isNotBlank)?.let {
            Text("来自页面：${readableResourceName(it)}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("来自网站：${sourceHost(draft.sourceUrl) ?: "未记录"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * T86 login-session reuse toggle, mirroring the "download-use-context" row interaction. Rendering
 * this row is the user-visible half of D6 ("规则声明 ≠ 生效"): it appears only when the matched rule
 * declares a session inside the page's registrable domain and the user has not opted out, and it
 * defaults OFF unless an explicit OPT_IN was persisted. Toggling persists immediately through the
 * caller; the download transport itself is unaffected (no cookie rides the download request).
 */
@Composable
internal fun ResourceSessionOption(
    offer: com.example.purebrowser.media.rules.SessionToggleOffer,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ResourceOption(
        label = "使用网站登录状态（${offer.domain}）",
        description = if (offer.checkedByDefault)
            "已为该站点开启：仅 ${offer.domain} 同一网站集团的站点规则会带上登录状态；不会导出或另存，下载本身不受影响"
        else
            "默认关闭：开启后仅 ${offer.domain} 同一网站集团的站点规则可能带上登录状态；不会导出或另存，下载本身不受影响",
        checked = checked,
        enabled = enabled,
        onChange = onChange,
        modifier = Modifier.testTag("rule-session-use"),
    )
}

@Composable
internal fun ResourceStatus(
    modifier: Modifier = Modifier,
    error: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
