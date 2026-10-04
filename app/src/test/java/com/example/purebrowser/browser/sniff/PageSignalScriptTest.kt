package com.example.purebrowser.browser.sniff

import org.junit.Assert.*
import org.junit.Test

class PageSignalScriptTest {
    private val js = PageSignalScript.JS
    @Test fun guardsAgainstDoubleInjectionAndUsesTheBridge() {
        assertTrue(js.contains("__pbSignalGuard"))
        assertTrue(js.contains("PbSniffBridge.signal(JSON.stringify(payload))"))
    }
    @Test fun emitsAllSixReportTypes() {
        listOf("mediaUrl", "mseMime", "blobManifest", "playerConfig", "iframeSrc", "apiPayload").forEach { type ->
            assertTrue(type, js.contains("type: '$type'"))
        }
    }
    @Test fun embedsKotlinKeywordConstantsIntoTheScript() {
        MediaUrlFilter.KEYWORDS.forEach { keyword -> assertTrue(keyword, js.contains("\"$keyword\"")) }
        assertFalse(js.contains("__MEDIA_KEYWORDS__"))
        assertFalse(js.contains("__CAPTURE_ENDPOINTS__"))
    }
    @Test fun wrapsReadOnlyPageApisAndAlwaysDelegates() {
        assertTrue(js.contains("XMLHttpRequest.prototype.open"))
        assertTrue(js.contains("XMLHttpRequest.prototype.send"))
        assertTrue(js.contains("window.fetch"))
        assertTrue(js.contains("MediaSource.prototype.addSourceBuffer"))
        assertTrue(js.contains("new Proxy(NativeBlob"))
        assertTrue(js.contains("Reflect.construct(Target, args)"))
        assertTrue(js.contains("window.__pbSignalGuard = true"))
    }
    @Test fun responseCaptureStaysReadonlyAndBounded() {
        // fetch: only a clone is read, the original promise is returned untouched.
        assertTrue(js.contains("var copy = resp.clone()"))
        assertTrue(js.contains("return promise;"))
        assertFalse(js.contains("resp.text()"))
        // Bounded read: declared content-length gate plus a capped reader with cancel.
        assertTrue(js.contains("if (declared > CAP) return;"))
        assertTrue(js.contains("reader.cancel()"))
        // XHR: property read only, text response types only, same length bound.
        assertTrue(js.contains("this.responseType !== '' && this.responseType !== 'text'"))
        assertTrue(js.contains("content.length <= CAP"))
    }
    @Test fun captureEndpointsAreEmbeddedAsEscapedStringData() {
        val built = PageSignalScript.build(listOf("""a\.b\/c""", """d["e]"""))
        assertTrue(built.contains(""""a\\.b\\/c""""))
        assertTrue(built.contains(""""d[\"e]""""))
        assertTrue(built.contains("new RegExp(patterns[pi])"))
        assertFalse(built.contains("__CAPTURE_ENDPOINTS__"))
        // Default script carries an empty capture list and reports no payloads to match.
        assertTrue(js.contains("var patterns = [];"))
    }
    @Test fun inlineHarvestIsOffByDefaultAndGatedByTheBuildFlag() {
        // Default script: the harvest code is present but inert, and no placeholder leaks.
        assertTrue(js.contains("var INLINE = false;"))
        assertFalse(js.contains("__INLINE_HARVEST__"))
        assertTrue(js.contains("if (!INLINE) return;"))
        // The flag switches the same code path on without changing anything else.
        val built = PageSignalScript.build(emptyList(), inlineHarvest = true)
        assertTrue(built.contains("var INLINE = true;"))
        assertTrue(built.contains("if (!INLINE) return;") && built.contains("harvestInline();"))
    }
    @Test fun inlineHarvestStaysReadonlyAndBounded() {
        listOf(
            "var INLINE_CAP = 262144, INLINE_MAX = 8, INLINE_PAGE_CAP = 1048576;",
            "if (inlineCount >= INLINE_MAX || inlineBudget >= INLINE_PAGE_CAP) return;",
            "if (!content || content.length > INLINE_CAP || inlineBudget + content.length > INLINE_PAGE_CAP) continue;",
            "type: 'inlineData'",
        ).forEach { marker -> assertTrue(marker, js.contains(marker)) }
        // Script-idiom candidates must also look like they can carry an address, and typed scripts
        // outside json/ld+json/javascript are never harvested.
        assertTrue(js.contains("if (lower.indexOf('http') === -1) continue;"))
        // Nothing writes back to the page: only textContent reads and the report bridge.
        assertFalse(js.contains("localStorage"))
        assertFalse(js.contains("document.cookie"))
        assertFalse(js.contains("indexedDB"))
    }
}
