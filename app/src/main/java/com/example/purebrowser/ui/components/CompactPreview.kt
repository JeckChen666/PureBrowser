package com.example.purebrowser.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.purebrowser.data.browser.ThemeMode
import com.example.purebrowser.theme.PureBrowserTheme

@Preview(name="Compact / Light",showBackground=true,widthDp=360)
@Composable
private fun CompactLightPreview() = CompactPreview(ThemeMode.LIGHT)
@Preview(name="Compact / Dark",showBackground=true,widthDp=360)
@Composable
private fun CompactDarkPreview() = CompactPreview(ThemeMode.DARK)
@Composable
private fun CompactPreview(mode:ThemeMode) {
    PureBrowserTheme(mode) {
        Surface {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("紧凑浏览器",style=MaterialTheme.typography.titleLarge)
                Text("内容优先，工具按需展开。",style=MaterialTheme.typography.bodyMedium)
                Row {
                    ToolButton(Glyph.BACK,"后退",false){}
                    ToolButton(Glyph.HOME,"首页"){}
                    ToolButton(Glyph.TABS,"标签页"){}
                    ToolButton(Glyph.MENU,"菜单"){}
                }
                HorizontalDivider()
                Text("等待网络 · 尚未完成保存",style=MaterialTheme.typography.bodyMedium)
                Text("关键状态保留文字，视觉紧凑不缩小点击区域。",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
