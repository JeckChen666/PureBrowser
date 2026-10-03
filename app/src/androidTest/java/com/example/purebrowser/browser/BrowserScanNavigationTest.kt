package com.example.purebrowser.browser

import android.content.Context
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.ResourceSniffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The old document may never deliver its evaluateJavascript callback after navigation. */
class BrowserScanNavigationTest {
    private class DeferredView(context: Context) : WebView(context) {
        var currentUrl = "https://fixture.example/old"
        val callbacks = mutableListOf<ValueCallback<String>>()
        override fun getUrl(): String = currentUrl
        override fun loadUrl(url: String) { currentUrl = url }
        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
            callbacks += requireNotNull(callback)
        }
    }

    private fun verify(sameDocument: Boolean) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrument.runOnMainSync {
            try {
            val sniffer = ResourceSniffer()
            val engine = BrowserEngine(sniffer, {}, "https://fixture.example/old")
            val view = DeferredView(instrument.targetContext)
            try {
                engine.attach(view)
                engine.scanMedia()
                assertEquals(1, view.callbacks.size)
                val abandoned = view.callbacks.first()
                view.currentUrl = "https://fixture.example/new"
                if (sameDocument) view.webViewClient.doUpdateVisitedHistory(view, view.currentUrl, false)
                else view.webViewClient.onPageStarted(view, view.currentUrl, null)
                engine.scanMedia()
                assertEquals("An abandoned old-document callback must not block the new generation", 2, view.callbacks.size)
                abandoned.onReceiveValue(JSONObject.quote("""[{"url":"https://fixture.example/old.mp4","video":true,"frame":"https://fixture.example/old"}]"""))
                assertTrue(sniffer.candidates.value.isEmpty())
                engine.scanMedia()
                assertEquals("Old callback must not unlock the newer in-flight scan", 2, view.callbacks.size)
                view.callbacks.last().onReceiveValue(JSONObject.quote("""[{"url":"https://fixture.example/new.mp4","video":true,"frame":"https://fixture.example/new"}]"""))
                assertEquals("https://fixture.example/new.mp4", sniffer.candidates.value.single().url)
                engine.scanMedia()
                assertEquals(3, view.callbacks.size)
            } finally { engine.detach(view) }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    @Test(timeout = 30_000) fun navigationDoesNotWaitForAbandonedJavascriptCallback() = verify(false)
    @Test(timeout = 30_000) fun sameDocumentChangeInvalidatesOldScanWithoutUnlockingNewOne() = verify(true)
}
