package com.example.purebrowser.data.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserRulesTest {
    @Test fun newTabSelectsItWithoutChangingOtherTabs() {
        val original=BrowserData(tabs=listOf(TabRecord("a","https://example.com","A")),selectedId="a")
        val next=BrowserRules.addTab(original,"https://example.org")
        assertEquals(2,next.tabs.size);assertEquals(original.tabs.first(),next.tabs.first());assertEquals(next.tabs.last().id,next.selectedId)
    }
    @Test fun closingSelectedTabSelectsNeighbor() {
        val data=BrowserData(tabs=listOf(TabRecord("a"),TabRecord("b"),TabRecord("c")),selectedId="b")
        val closed=BrowserRules.closeTab(data,"b")
        assertEquals(listOf("a","c"),closed.tabs.map{it.id});assertEquals("a",closed.selectedId)
    }
    @Test fun closingBackgroundTabPreservesSelection() {
        val data=BrowserData(tabs=listOf(TabRecord("a"),TabRecord("b")),selectedId="b")
        assertEquals("b",BrowserRules.closeTab(data,"a").selectedId)
    }
    @Test fun closingFinalTabCreatesHome() {
        val original=BrowserData()
        val data=BrowserRules.closeTab(original,original.selectedId)
        assertEquals(1,data.tabs.size);assertEquals(HOME_URL,data.tabs.single().url);assertEquals(data.tabs.single().id,data.selectedId)
    }
    @Test fun normalizeRejectsUnsafeUrlsAndRepairsSelection() {
        val data=BrowserData(tabs=listOf(TabRecord("bad","javascript:alert(1)"),TabRecord("good","https://example.com")),selectedId="missing",bookmarks=listOf(SavedPage(title="bad",url="file:///test")))
        val repaired=BrowserRules.normalize(data)
        assertEquals("good",repaired.selectedId);assertEquals(1,repaired.tabs.size);assertTrue(repaired.bookmarks.isEmpty())
    }
    @Test fun historyDeduplicatesConsecutiveCallbacksAndPreservesSignedUrl() {
        val url="https://example.com/?signature=a%2Bb"
        val one=BrowserRules.visit(BrowserData(),url,"First",1)
        val two=BrowserRules.visit(one,url,"Updated",2)
        assertEquals(1,two.history.size);assertEquals(one.history.first().id,two.history.first().id);assertEquals(url,two.history.first().url);assertEquals("Updated",two.history.first().title)
    }
    @Test fun historyIsBoundedAndRejectsHome() {
        var data=BrowserData()
        repeat(1100) { data=BrowserRules.visit(data,"https://example.com/$it","Page $it",it.toLong()) }
        assertEquals(1000,data.history.size);assertEquals(data.history,BrowserRules.visit(data,HOME_URL,"Home").history)
    }
    @Test fun addingTooManyTabsDoesNotSilentlyDropPages() {
        val tabs=(0..49).map{TabRecord(it.toString())}
        assertTrue(runCatching{BrowserRules.addTab(BrowserData(tabs=tabs,selectedId="0"))}.isFailure)
    }
}
