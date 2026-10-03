package com.example.purebrowser.ui.home

import com.example.purebrowser.data.browser.SavedPage
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.VideoAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePresentationTest {
    @Test fun recentVisitsAreNewestFirstAndBoundedWithoutChangingHistory() {
        val history = listOf(visit("old", 1), visit("newest", 4), visit("middle", 2), visit("newer", 3))
        assertEquals(listOf("newest", "newer", "middle"), recentHomeVisits(history).map { it.id })
        assertEquals(listOf("old", "newest", "middle", "newer"), history.map { it.id })
        assertTrue(recentHomeVisits(emptyList()).isEmpty())
    }

    @Test fun savedPreviewNeverShowsMissingUnreadableOrUnconfirmedFiles() {
        val assets = listOf(
            asset("old", 1), asset("newest", 4), asset("middle", 2), asset("newer", 3),
            asset("missing", 100, FileAvailability.MISSING),
            asset("unreadable", 101, FileAvailability.UNREADABLE),
            asset("unknown", 102, FileAvailability.UNKNOWN),
        )
        assertEquals(listOf("newest", "newer", "middle"), recentHomeSaves(assets).map { it.recordId })
        assertEquals(7, assets.size)
        assertTrue(recentHomeSaves(assets.filter { it.availability != FileAvailability.AVAILABLE }).isEmpty())
    }

    @Test fun repeatedVisitsRemainRealHistoryRecordsRatherThanInventedDeduplication() {
        val history = listOf(visit("first", 1), visit("second", 2))
        assertEquals(listOf("second", "first"), recentHomeVisits(history).map { it.id })
    }

    @Test fun siteGridReducesColumnsForNarrowWidthsAndLargeFonts() {
        assertEquals(1, homeSiteColumns(240f, 1f))
        assertEquals(2, homeSiteColumns(288f, 1f))
        assertEquals(1, homeSiteColumns(288f, 2f))
        assertEquals(4, homeSiteColumns(688f, 1f))
        assertEquals(2, homeSiteColumns(688f, 2f))
        assertEquals(4, homeSiteColumns(2000f, 1f))
        assertEquals(1, homeSiteColumns(0f, 1f))
        assertEquals(2, homeSiteColumns(288f, 0.85f))
    }

    private fun visit(id: String, time: Long) = SavedPage(id, "Page $id", "https://example.test", time)
    private fun asset(id: String, time: Long, availability: FileAvailability = FileAvailability.AVAILABLE) =
        VideoAsset(recordId = id, uri = "content://fixture/$id", name = "$id.mp4",
            displayName = id, indexedAt = time, availability = availability)
}
