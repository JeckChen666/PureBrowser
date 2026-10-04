package com.example.purebrowser.ui.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.rules.FormatChoice
import com.example.purebrowser.media.rules.FormatChoiceRow
import com.example.purebrowser.media.rules.FormatPreference
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.Glyph

/**
 * T87 format chooser for rules-sourced candidates whose rule reported a structured format set
 * (folded into `MediaCandidate.variants`). Pure selection surface: the preference controls and the
 * row list both resolve through the pure [FormatChoice] layer, nothing is fetched here, and the
 * chosen row is handed back to the caller which routes it through the EXISTING paths — manifest
 * rows continue into the HLS preparation flow, direct rows into the direct-download confirmation.
 */
@Composable
fun RuleFormatConfirmation(
    draft: DownloadDraft,
    onDismiss: () -> Unit,
    onSelected: (row: FormatChoiceRow) -> Unit,
) {
    val frozen = remember(draft) { draft.copy(candidate = draft.candidate.copy(sources = draft.candidate.sources.toSet())) }
    val rows = remember(frozen) { FormatChoice.rows(frozen.candidate.variants.orEmpty()) }
    val heights = remember(rows) { FormatChoice.heights(rows) }
    val containers = remember(rows) { FormatChoice.containers(rows) }
    var submitted by remember(frozen) { mutableStateOf(false) }
    // Preference mode: 0 = 最佳, 1 = 指定清晰度, 2 = 指定容器; -1 seeds "nothing picked yet".
    var mode by rememberSaveable(frozen) { mutableStateOf(0) }
    var wantedHeight by rememberSaveable(frozen) { mutableStateOf(-1) }
    var wantedExt by rememberSaveable(frozen) { mutableStateOf("") }
    var manualUrl by rememberSaveable(frozen) { mutableStateOf<String?>(null) }

    val preference: FormatPreference? = when (mode) {
        1 -> heights.firstOrNull { it == wantedHeight }?.let { FormatPreference.FixedHeight(it) }
        2 -> containers.firstOrNull { it == wantedExt }?.let { FormatPreference.FixedExt(it) }
        else -> FormatPreference.Best
    }
    val computed = preference?.let { FormatChoice.select(rows, it) }
    val selected = rows.firstOrNull { it.url == manualUrl } ?: computed

    ResourceDialog(onDismiss = onDismiss) {
        ResourceHeading("选择清晰度")
        Text(readableResourceName(frozen.candidate.displayName), style = MaterialTheme.typography.titleMedium)
        ResourceMetadata(frozen.candidate)
        ResourceSource(frozen)
        Text(
            "站点规则报告了 ${rows.size} 个可选格式。可按偏好自动选择，或直接点选下方列表；选择仅决定下载地址，不发起任何请求。清单类地址继续走既有 HLS 解析流程，直链地址走直链下载确认。",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text("画质偏好", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            preferenceRow("最佳", "按分辨率、码率与容器的既定排序自动选择", mode == 0) { mode = 0; manualUrl = null }
            preferenceRow("指定清晰度", "从可用高度中选择；无精确档时选择不超过该高度的次优档", mode == 1) {
                mode = 1; manualUrl = null
                if (wantedHeight <= 0 && heights.isNotEmpty()) wantedHeight = heights.first()
            }
            preferenceRow("指定容器", "限定 mp4 / webm / ts 容器后按既定排序选择", mode == 2) {
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
                            "清单地址，保存时将继续按既有${row.kind.label}流程解析",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (selected != null) {
            ResourceStatus(modifier = Modifier.testTag("rule-format-picked")) {
                Text("将选择：${FormatChoice.label(selected)}")
                Text("选择后进入保存确认；若该格式失效会如实报告，不会悄悄改选其他档位。", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            ResourceStatus(error = true) {
                Text("该偏好下没有可用格式；请改选其他偏好或直接点选列表。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = {
                val row = selected
                if (row != null && !submitted) {
                    submitted = true
                    onSelected(row)
                }
            },
            enabled = selected != null && !submitted,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rule-format-continue"),
        ) {
            BrowserGlyph(Glyph.CHECK, "继续", Modifier.clearAndSetSemantics {})
            Text("继续", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
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
