package com.example.purebrowser.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserAddressTest {
    @Test fun resolvesDomainsAndSearches() {
        assertEquals("https://example.com/video", BrowserAddress.resolve(" example.com/video "))
        assertEquals("https://example.com:8443/page", BrowserAddress.resolve("example.com:8443/page"))
        assertEquals("https://example.com/?sig=a%2Bb", BrowserAddress.resolve("https://example.com/?sig=a%2Bb"))
        assertTrue(BrowserAddress.resolve("安卓 视频浏览器").startsWith("https://www.google.com/search?q="))
    }
    @Test fun unsafeSchemesCannotBecomeNavigation() {
        listOf("javascript:alert(1)", "intent://example", "file:///sdcard/a.html", "data:text/html,a", "https://user:secret@example.com/", "").forEach {
            assertTrue("reject $it", runCatching { BrowserAddress.resolve(it) }.isFailure)
        }
    }
    @Test fun validatesWebUrls() {
        assertTrue(BrowserAddress.isWebUrl("https://example.com"))
        assertFalse(BrowserAddress.isWebUrl("about:blank"))
        assertFalse(BrowserAddress.isWebUrl("https://user:secret@example.com"))
    }
}
