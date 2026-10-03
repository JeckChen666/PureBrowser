package com.example.purebrowser.ui.browser

import com.example.purebrowser.data.browser.HOME_URL
import com.example.purebrowser.data.browser.SavedPage
import org.junit.Assert.*
import org.junit.Test

class BrowserChromeRulesTest {
    @Test fun identityNeverDisplaysUserInfoOrPath() {
        assertEquals("example.org:8443",BrowserChromeRules.displayAddress("https://trusted.test@example.org:8443/private?token=secret"))
        assertEquals("xn--bcher-kva.example",BrowserChromeRules.displayAddress("https://xn--bcher-kva.example/path"))
        assertFalse(BrowserChromeRules.displayAddress("https://安全.example/path").contains("安全"))
    }
    @Test fun homeAndMalformedHaveSafeIdentity() {
        assertEquals("搜索或输入网址",BrowserChromeRules.displayAddress(HOME_URL))
        assertEquals("网址（点按查看完整地址）",BrowserChromeRules.displayAddress("not a url"))
    }
    @Test fun suggestionsAreLocalBoundedDeduplicatedAndBookmarkFirst() {
        val book=SavedPage(id="book",title="Book",url="https://example.org/one")
        val history=(0..20).map { SavedPage(id="h$it",title="History $it",url="https://example.org/$it") }+book.copy(id="duplicate",title="Old title")
        val results=BrowserChromeRules.suggestions("EXAMPLE",history,listOf(book))
        assertEquals(8,results.size);assertEquals("书签",results.first().source)
        assertEquals(results.size,results.map { it.url }.distinct().size)
        assertTrue(BrowserChromeRules.suggestions("NO_MATCH",history,listOf(book)).isEmpty())
        assertEquals("Book",BrowserChromeRules.suggestions("book",history,listOf(book)).single().title)
    }
}
