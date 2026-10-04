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
}
