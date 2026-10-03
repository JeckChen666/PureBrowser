package com.example.purebrowser.ui.tabs

import com.example.purebrowser.data.browser.BrowserData
import com.example.purebrowser.data.browser.BrowserRules
import com.example.purebrowser.data.browser.HOME_URL
import com.example.purebrowser.data.browser.TabRecord
import org.junit.Assert.*
import org.junit.Test

class TabSwitcherRulesTest {
    private val tabs = listOf(
        TabRecord("a", "https://alpha.example/Path?Token=Secret", "First ALPHA"),
        TabRecord("b", "https://beta.example", "Second beta"),
        TabRecord("c", "https://alpha.example/other", "最后一个"),
    )

    @Test fun searchIsLocalCaseInsensitiveAndStable() {
        assertEquals(listOf("a", "c"), TabSwitcherRules.search(tabs, "  AlPhA  ").map { it.id })
        assertEquals(listOf("a"), TabSwitcherRules.search(tabs, "token=secret").map { it.id })
        assertEquals(listOf("b"), TabSwitcherRules.search(tabs, "SECOND").map { it.id })
        assertEquals(listOf("c"), TabSwitcherRules.search(tabs, "最后").map { it.id })
        assertTrue(TabSwitcherRules.search(tabs, "not-open-in-these-tabs").isEmpty())
        assertEquals(listOf("a", "b", "c"), tabs.map { it.id })
    }

    @Test fun emptyOrWhitespaceRestoresExactOriginalOrder() {
        assertSame(tabs, TabSwitcherRules.search(tabs, ""))
        assertSame(tabs, TabSwitcherRules.search(tabs, " \n\t "))
    }

    @Test fun locateClearsHiddenFilterAndUsesUnfilteredPositionAtAllTargetCounts() {
        listOf(1, 24, 50).forEach { count ->
            val many = (0 until count).map { TabRecord("$it", "https://example.com/$it", "Page $it") }
            assertTrue(TabSwitcherRules.search(many, "missing").isEmpty())
            assertEquals(TabLocation("", count - 1), TabSwitcherRules.locate(many, many.last().id))
            assertEquals(TabLocation("", null), TabSwitcherRules.locate(many, "no-longer-open"))
        }
    }

    @Test fun viewPreferenceDefaultsSafelyWithoutAutomaticCountBasedSwitches() {
        assertEquals(TabViewMode.GRID, TabViewMode.fromStored(null))
        assertEquals(TabViewMode.GRID, TabViewMode.fromStored("unknown-future-value"))
        assertEquals(TabViewMode.GRID, TabViewMode.fromStored("grid"))
        assertEquals(TabViewMode.GRID, TabViewMode.fromStored("GRID"))
        assertEquals(TabViewMode.LIST, TabViewMode.fromStored("LIST"))
    }

    @Test fun subtitleDoesNotShowCredentialsQueryOrFragments() {
        val tab = TabRecord("safe", "https://user:secret@example.com/path?token=secret#private", "Page")
        assertEquals("example.com", TabSwitcherRules.subtitle(tab))
        assertEquals("首页", TabSwitcherRules.subtitle(TabRecord()))
        assertEquals("网页", TabSwitcherRules.subtitle(TabRecord(url = "broken URL")))
    }

    @Test fun closeBoundariesPreserveExistingRulesAtOneTwentyFourAndFiftyTabs() {
        listOf(1, 24, 50).forEach { count ->
            val ordered = (0 until count).map { TabRecord("$it", "https://example.com/$it", "Page $it") }
            ordered.indices.forEach { selectedIndex ->
                val data = BrowserData(tabs = ordered, selectedId = ordered[selectedIndex].id)
                ordered.indices.forEach { closeIndex ->
                    val closed = BrowserRules.closeTab(data, ordered[closeIndex].id)
                    if (count == 1) {
                        assertEquals(1, closed.tabs.size)
                        assertEquals(HOME_URL, closed.tabs.single().url)
                        assertNotEquals(ordered.single().id, closed.selectedId)
                        assertEquals(closed.tabs.single().id, closed.selectedId)
                    } else {
                        val remaining = ordered.filterIndexed { index, _ -> index != closeIndex }
                        assertEquals(remaining, closed.tabs)
                        val expected = if (selectedIndex == closeIndex)
                            remaining[(closeIndex - 1).coerceIn(0, remaining.lastIndex)].id else data.selectedId
                        assertEquals(expected, closed.selectedId)
                    }
                }
                assertSame(data, BrowserRules.closeTab(data, "missing"))
            }
        }
    }

    @Test fun capDoesNotDropTabsAndSelectingIsNotAReorder() {
        val ordered = (0 until 50).map { TabRecord("$it") }
        val data = BrowserData(tabs = ordered, selectedId = "49")
        assertTrue(runCatching { BrowserRules.addTab(data) }.isFailure)
        assertEquals(ordered, data.tabs)
        assertEquals(ordered, data.copy(selectedId = "0").tabs)
    }
}
