package com.example.purebrowser.browser.sniff

import android.webkit.JavascriptInterface

/**
 * Receives reports from the injected page script. signal() runs on WebView's JS bridge thread;
 * the epoch is resolved from the provider at delivery time, so the listener must tolerate
 * cross-thread invocation.
 */
class PageSignalBridge(
    private val epochProvider: () -> Long,
    private val listener: (Long, PageSignal) -> Unit,
) {
    @JavascriptInterface fun signal(json: String) {
        val signal = PageSignalParser.parse(json) ?: return
        runCatching { listener(epochProvider(), signal) }
    }
}
