package com.example.purebrowser.browser

import android.graphics.Bitmap
import android.content.Context
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.data.browser.BrowserData
import com.example.purebrowser.data.browser.HOME_URL
import com.example.purebrowser.data.browser.TabRecord
import org.junit.Assert.*
import org.junit.Test

class TabControllerPreviewTest {
    @Test fun repeatedHostUpdatesForSameBoundViewDoNotPostAgain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val controller = TabController(instrumentation.targetContext, {}, { _, _ -> }, { _, _ -> }, {})
            val view = CountingWebView(instrumentation.targetContext)
            try {
                controller.restore(BrowserData(tabs = listOf(TabRecord("a")), selectedId = "a"))
                view.interceptPosts = true
                controller.bindPreviewSource("a", view)
                assertEquals(1, view.posts)
                repeat(50) { controller.bindPreviewSource("a", view) }
                assertEquals(1, view.posts)
                controller.captureActivePreview(view)
                assertEquals(1, view.posts)
                controller.bindPreviewSource("a", null)
                controller.bindPreviewSource("a", view)
                assertEquals(2, view.posts)
                controller.bindPreviewSource("not-active", view)
                assertEquals(2, view.posts)
            } finally { controller.clear(); view.interceptPosts = false; view.destroy() }
        }
    }

    private class CountingWebView(context: Context) : WebView(context) {
        var interceptPosts = false
        var posts = 0
        override fun post(action: Runnable): Boolean {
            if (!interceptPosts) return super.post(action)
            posts++
            return true
        }
    }

    @Test fun callbacksCapCloseAndPrivacyKeepExistingSessionBoundaries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var records: List<TabRecord> = emptyList()
            var selected = ""
            val notices = mutableListOf<String>()
            val controller = TabController(instrumentation.targetContext, { notices += it }, { tabs, id -> records = tabs; selected = id }, { _, _ -> }, {})
            val ordered = (0 until 50).map { TabRecord("$it", "https://example.com/$it", "Page $it") }
            val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
            try {
                controller.restore(BrowserData(tabs = ordered, selectedId = "25"))
                val originalSession = controller.active.value
                controller.captureActivePreview() // Unmounted: no WebView created for a snapshot.
                assertEquals(0, controller.previewCache.entryCount)
                controller.newTab()
                assertEquals(1, notices.size)
                assertSame(originalSession, controller.active.value)
                controller.close("49")
                assertEquals("25", selected); assertEquals(ordered.dropLast(1), records)
                assertSame(originalSession, controller.active.value)
                val key = TabPreviewKey("25", 0L, ordered[25].url, 1L)
                controller.previewCache.advance(key); assertTrue(controller.previewCache.put(key, image))
                controller.close("25")
                assertEquals("24", selected); assertEquals(48, records.size)
                assertNull(controller.previewCache.get("25", key.url))
                assertNotSame(originalSession, controller.active.value)
                controller.clearPreviews()
                val sessionBeforePrivacy = controller.active.value
                assertEquals(0, controller.previewCache.entryCount)
                assertSame(sessionBeforePrivacy, controller.active.value)
                controller.clear()
                assertNull(controller.active.value); assertEquals(0, controller.previewCache.entryCount)
                controller.restore(BrowserData(tabs = listOf(TabRecord("last")), selectedId = "last"))
                controller.close("last")
                assertEquals(1, records.size); assertEquals(HOME_URL, records.single().url)
                assertEquals(records.single().id, selected); assertNotEquals("last", selected)
            } finally { controller.clear(); image.recycle() }
        }
    }
}
