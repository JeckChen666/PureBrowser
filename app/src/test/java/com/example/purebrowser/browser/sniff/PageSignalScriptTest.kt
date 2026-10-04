package com.example.purebrowser.browser.sniff

import org.junit.Assert.*
import org.junit.Test

class PageSignalScriptTest {
    private val js = PageSignalScript.JS
    @Test fun guardsAgainstDoubleInjectionAndUsesTheBridge() {
        assertTrue(js.contains("__pbSignalGuard"))
        assertTrue(js.contains("PbSniffBridge.signal(JSON.stringify(payload))"))
    }
    @Test fun emitsAllFiveReportTypes() {
        listOf("mediaUrl", "mseMime", "blobManifest", "playerConfig", "iframeSrc").forEach { type ->
            assertTrue(type, js.contains("type: '$type'"))
        }
    }
    @Test fun embedsKotlinKeywordConstantsIntoTheScript() {
        MediaUrlFilter.KEYWORDS.forEach { keyword -> assertTrue(keyword, js.contains("\"$keyword\"")) }
        assertFalse(js.contains("__MEDIA_KEYWORDS__"))
    }
    @Test fun wrapsReadOnlyPageApisAndAlwaysDelegates() {
        assertTrue(js.contains("XMLHttpRequest.prototype.open"))
        assertTrue(js.contains("window.fetch"))
        assertTrue(js.contains("MediaSource.prototype.addSourceBuffer"))
        assertTrue(js.contains("new Proxy(NativeBlob"))
        assertTrue(js.contains("Reflect.construct(Target, args)"))
        assertTrue(js.contains("window.__pbSignalGuard = true"))
    }
}
