package com.example.purebrowser.browser.sniff

import org.junit.Assert.*
import org.junit.Test

class PageSignalBridgeTest {
    @Test fun forwardsParsedSignalWithCurrentEpoch() {
        var delivered: Pair<Long, PageSignal>? = null
        val bridge = PageSignalBridge({ 7L }) { epoch, signal -> delivered = epoch to signal }
        bridge.signal("""{"type":"mediaUrl","url":"https://cdn.example/v.m3u8"}""")
        assertEquals(7L to PageSignal.MediaUrl("https://cdn.example/v.m3u8"), delivered)
    }
    @Test fun malformedPayloadNeverReachesTheListener() {
        var called = false
        val bridge = PageSignalBridge({ 1L }) { _, _ -> called = true }
        bridge.signal("not-json")
        bridge.signal("""{"type":"unknown"}""")
        assertFalse(called)
    }
    @Test fun throwingListenerIsContained() {
        val bridge = PageSignalBridge({ 2L }) { _, _ -> error("listener crashed") }
        bridge.signal("""{"type":"iframeSrc","url":"https://a.example/embed"}""")
    }
}
