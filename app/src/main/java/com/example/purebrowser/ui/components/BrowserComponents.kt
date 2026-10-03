package com.example.purebrowser.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

enum class Glyph { SEARCH, BACK, NEXT, HOME, TABS, MENU, PLUS, CLOSE, BOOKMARK, HISTORY, DOWNLOAD, GLOBE, EDIT, STOP, REFRESH, GRID, LIST, LOCATE, PLAY, PAUSE, SHARE, VIDEO, SETTINGS, CHECK }
@Composable
fun BrowserGlyph(glyph: Glyph, description: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(20.dp).semantics { contentDescription = description }) {
        val unit = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, p(x,y), p(a,b), 1.8f * unit)
        fun path(vararg points: Pair<Float,Float>, close: Boolean = false) {
            val path = Path().apply { moveTo(points[0].first * unit, points[0].second * unit); points.drop(1).forEach { lineTo(it.first * unit,it.second * unit) }; if (close) close() }
            drawPath(path, color, style = Stroke(1.8f * unit))
        }
        when (glyph) {
            Glyph.SEARCH -> { drawCircle(color, 6.5f*unit,p(10f,10f),style=Stroke(1.8f*unit)); line(15f,15f,21f,21f) }
            Glyph.BACK -> path(15f to 5f,8f to 12f,15f to 19f)
            Glyph.NEXT -> path(9f to 5f,16f to 12f,9f to 19f)
            Glyph.HOME -> { path(3f to 11f,12f to 3f,21f to 11f); path(5f to 9f,5f to 21f,19f to 21f,19f to 9f); path(9f to 21f,9f to 14f,15f to 14f,15f to 21f) }
            Glyph.MENU -> { listOf(5f,12f,19f).forEach { drawCircle(color,1.6f*unit,p(12f,it)) } }
            Glyph.PLUS -> { line(12f,4f,12f,20f);line(4f,12f,20f,12f) }
            Glyph.CLOSE -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
            Glyph.TABS -> { drawRoundRect(color,p(5f,5f),Size(15f*unit,16f*unit),CornerRadius(2f*unit),style=Stroke(1.8f*unit));path(2f to 17f,2f to 2f,17f to 2f) }
            Glyph.BOOKMARK -> path(6f to 3f,18f to 3f,18f to 21f,12f to 17f,6f to 21f,close=true)
            Glyph.HISTORY -> { drawCircle(color,9f*unit,p(12f,12f),style=Stroke(1.8f*unit));path(12f to 6f,12f to 12f,17f to 15f) }
            Glyph.DOWNLOAD -> {path(6f to 11f,12f to 17f,18f to 11f);line(12f,3f,12f,17f);path(4f to 17f,4f to 21f,20f to 21f,20f to 17f) }
            Glyph.GLOBE -> {drawCircle(color,9f*unit,p(12f,12f),style=Stroke(1.8f*unit));drawOval(color,p(8f,3f),Size(8f*unit,18f*unit),style=Stroke(1.6f*unit));line(3f,12f,21f,12f)}
            Glyph.EDIT -> {path(4f to 17f,16f to 5f,20f to 9f,8f to 21f,3f to 22f,close=true);line(14f,7f,18f,11f)}
            Glyph.REFRESH -> { drawArc(color, 45f, 290f, false, p(4f,4f), Size(16f*unit,16f*unit), style=Stroke(1.8f*unit));path(20f to 3f,20f to 9f,14f to 9f) }
            Glyph.GRID -> { for(x in listOf(3f,14f)) for(y in listOf(3f,14f)) drawRoundRect(color,p(x,y),Size(7f*unit,7f*unit),CornerRadius(unit),style=Stroke(1.8f*unit)) }
            Glyph.LIST -> { for(y in listOf(5f,12f,19f)) { drawCircle(color,unit,p(4f,y));line(9f,y,21f,y) } }
            Glyph.LOCATE -> { drawCircle(color,6f*unit,p(12f,12f),style=Stroke(1.8f*unit));drawCircle(color,2f*unit,p(12f,12f));line(12f,1f,12f,5f);line(12f,19f,12f,23f);line(1f,12f,5f,12f);line(19f,12f,23f,12f) }
            Glyph.PLAY -> path(7f to 3f,21f to 12f,7f to 21f,close=true)
            Glyph.PAUSE -> { line(8f,4f,8f,20f);line(16f,4f,16f,20f) }
            Glyph.SHARE -> { path(3f to 10f,12f to 5f,21f to 10f);path(3f to 14f,12f to 19f,21f to 14f);for(point in listOf(3f to 12f,21f to 9f,21f to 15f)) drawCircle(color,2f*unit,p(point.first,point.second),style=Stroke(1.8f*unit)) }
            Glyph.VIDEO -> { drawRoundRect(color,p(3f,4f),Size(18f*unit,16f*unit),CornerRadius(2f*unit),style=Stroke(1.8f*unit));path(10f to 8f,16f to 12f,10f to 16f,close=true) }
            Glyph.SETTINGS -> { drawCircle(color,7f*unit,p(12f,12f),style=Stroke(1.8f*unit));drawCircle(color,2.5f*unit,p(12f,12f),style=Stroke(1.8f*unit));for(d in listOf(0,90,180,270)) { val r=Math.toRadians(d.toDouble());line(12f+8f*kotlin.math.cos(r).toFloat(),12f+8f*kotlin.math.sin(r).toFloat(),12f+11f*kotlin.math.cos(r).toFloat(),12f+11f*kotlin.math.sin(r).toFloat()) } }
            Glyph.CHECK -> path(4f to 12f,10f to 18f,21f to 5f)
            Glyph.STOP -> drawRoundRect(color,p(5f,5f),Size(14f*unit,14f*unit),CornerRadius(2f*unit))
        }
    }
}
@Composable
fun ToolButton(glyph: Glyph, label: String, enabled: Boolean = true, tag: String = label, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag(tag)) { BrowserGlyph(glyph, label) }
}
@Composable
fun EmptyContent(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=36.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title, style=MaterialTheme.typography.titleLarge)
        Text(message, style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
