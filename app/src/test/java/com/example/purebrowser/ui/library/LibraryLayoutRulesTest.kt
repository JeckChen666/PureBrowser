package com.example.purebrowser.ui.library

import com.example.purebrowser.data.browser.SavedPage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LibraryLayoutRulesTest {
    @Test fun normalPhoneHasThreeColumns() {
        assertEquals(3, videoLibraryColumnCount(360f, 1f))
        assertEquals(3, videoLibraryColumnCount(393f, 1f))
        assertEquals(3, videoLibraryColumnCount(400f, 1f))
    }

    @Test fun narrowAndLargeFontWindowsReduceColumnsWithoutDisablingScaling() {
        assertEquals(2, videoLibraryColumnCount(320f, 1f))
        assertEquals(2, videoLibraryColumnCount(260f, 1f))
        assertEquals(2, videoLibraryColumnCount(360f, 1.3f))
        assertEquals(1, videoLibraryColumnCount(360f, 2f))
        assertEquals(1, videoLibraryColumnCount(260f, 2f))
    }

    @Test fun expandedWindowsAreBoundedAndWidthsRemainValid() {
        assertEquals(6, videoLibraryColumnCount(840f, 1f))
        assertEquals(3, videoLibraryColumnCount(840f, 2f))
        assertEquals(1, videoLibraryColumnCount(0f, 1f))
        assertEquals(1, videoLibraryColumnCount(-10f, 1f))
        assertEquals(1, videoLibraryColumnCount(Float.POSITIVE_INFINITY, 1f))
        assertEquals(3, videoLibraryColumnCount(360f, Float.NaN))
        assertEquals(3, videoLibraryColumnCount(360f, 0.8f))
    }

    @Test fun historyUsesLocalMidnightAndDoesNotMutateCallerOrder() {
        val before = page("before", "2026-10-03T15:59:59Z")
        val after = page("after", "2026-10-03T16:00:00Z")
        val pages = listOf(before, after)
        val groups = groupHistoryByLocalDate(pages, ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 3)), groups.map { it.date })
        assertEquals(listOf("after", "before"), groups.flatMap { it.pages }.map { it.id })
        assertEquals(listOf("before", "after"), pages.map { it.id })
        assertEquals(1, groupHistoryByLocalDate(pages, ZoneId.of("UTC")).size)
    }

    @Test fun springAndFallDstDaysAreCalendarDatesNot24HourBuckets() {
        val zone = ZoneId.of("America/New_York")
        val fall = groupHistoryByLocalDate(listOf(
            page("first-0130", "2026-11-01T05:30:00Z"),
            page("second-0130", "2026-11-01T06:30:00Z"),
            page("evening", "2026-11-02T04:30:00Z"),
            page("next-midnight", "2026-11-02T05:00:00Z"),
        ), zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)), fall.map { it.date })
        assertEquals(listOf("evening", "second-0130", "first-0130"), fall[1].pages.map { it.id })
        val spring = groupHistoryByLocalDate(listOf(
            page("before-gap", "2026-03-08T06:30:00Z"),
            page("after-gap", "2026-03-08T07:30:00Z"),
        ), zone)
        assertEquals(1, spring.size)
        assertEquals(LocalDate.of(2026, 3, 8), spring.single().date)
    }

    @Test fun unknownDatesAreLastAndEqualTimesHaveStableIdOrder() {
        val a = page("a", "2026-10-03T01:00:00Z")
        val b = a.copy(id = "b")
        val groups = groupHistoryByLocalDate(listOf(b, a.copy(id = "unknown", time = -1), a), ZoneId.of("UTC"))
        assertEquals(listOf("a", "b"), groups.first().pages.map { it.id })
        assertNull(groups.last().date)
        assertEquals("unknown", groups.last().pages.single().id)
        assertTrue(groupHistoryByLocalDate(emptyList(), ZoneId.of("UTC")).isEmpty())
    }

    @Test fun labelsOnlyUseTodayAndYesterdayForExactDates() {
        val today = LocalDate.of(2026, 10, 3)
        assertEquals("今天", historyRelativeDateLabel(today, today))
        assertEquals("昨天", historyRelativeDateLabel(today.minusDays(1), today))
        assertEquals("日期未知", historyRelativeDateLabel(null, today))
        assertNull(historyRelativeDateLabel(today.minusDays(2), today))
        assertNull(historyRelativeDateLabel(today.plusDays(1), today))
    }

    @Test fun searchKeepsTitleAndUrlMatchesAndTrimsQuery() {
        val page = page("a", "2026-10-03T01:00:00Z").copy(title = "Field Notes", url = "https://example.org/path")
        assertTrue(savedPageMatches(page, " NOTES "))
        assertTrue(savedPageMatches(page, "EXAMPLE.ORG"))
        assertTrue(savedPageMatches(page, " "))
        assertFalse(savedPageMatches(page, "unrelated"))
    }

    private fun page(id: String, time: String) = SavedPage(
        id = id, title = "Page $id", url = "https://example.org/$id", time = Instant.parse(time).toEpochMilli(),
    )
}
