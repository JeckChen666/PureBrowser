package com.example.purebrowser.ui.library

import com.example.purebrowser.data.browser.SavedPage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/** Matches the grid's 12dp side padding and 8dp gaps. Font scaling is never disabled. */
internal fun videoLibraryColumnCount(widthDp: Float, fontScale: Float): Int {
    val scale = if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f
    if (!widthDp.isFinite()) return 1
    val minimumTileWidth = 100f * scale
    return floor((widthDp - 24f + 8f) / (minimumTileWidth + 8f)).toInt().coerceIn(1, 6)
}

internal data class HistoryDateGroup(val date: LocalDate?, val pages: List<SavedPage>)

/** Calendar dates, not rolling 24-hour buckets; handles local midnight and DST. */
internal fun groupHistoryByLocalDate(pages: List<SavedPage>, zone: ZoneId): List<HistoryDateGroup> =
    pages.sortedWith(compareByDescending<SavedPage> { it.time }.thenBy { it.id })
        .groupBy { page ->
            if (page.time < 0) null else Instant.ofEpochMilli(page.time).atZone(zone).toLocalDate()
        }
        .map { (date, entries) -> HistoryDateGroup(date, entries) }

internal fun savedPageMatches(page: SavedPage, query: String): Boolean {
    val search = query.trim()
    return page.title.contains(search, ignoreCase = true) || page.url.contains(search, ignoreCase = true)
}

internal fun historyRelativeDateLabel(date: LocalDate?, today: LocalDate): String? = when (date) {
    null -> "日期未知"
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> null // The screen supplies a locale-formatted, absolute date (including future dates).
}
