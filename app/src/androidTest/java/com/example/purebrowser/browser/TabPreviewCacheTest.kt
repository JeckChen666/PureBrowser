package com.example.purebrowser.browser

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TabPreviewCacheTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun key(id: String = "a", generation: Long = 1L, url: String = "https://example.com/$id", session: Long = 1L) =
        TabPreviewKey(id, generation, url, session)

    @Test fun realBitmapOwnershipIsBoundedByBothCountAndBytes() = onMain {
        val countCache = TabPreviewCache(maxEntries = 2)
        val byteCache = TabPreviewCache(maxEntries = 12, maxBytes = 15_000)
        val allocated = mutableListOf<Bitmap>()
        try {
            repeat(50) { index ->
                val k = key("$index")
                val image = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).also { allocated += it }
                countCache.advance(k); assertTrue(countCache.put(k, image))
                byteCache.advance(k); assertTrue(byteCache.put(k, image))
                assertTrue(countCache.entryCount <= 2); assertTrue(countCache.byteCount <= countCache.maxBytes)
                assertTrue(byteCache.entryCount <= 1); assertTrue(byteCache.byteCount <= byteCache.maxBytes)
            }
            assertNull(countCache.get("0", "https://example.com/0"))
            assertNotNull(countCache.get("49", "https://example.com/49"))
            // No unsafe recycle of a bitmap which an earlier Compose frame could still reference.
            assertFalse(allocated.first().isRecycled)
        } finally { countCache.clear(); byteCache.clear(); allocated.forEach { it.recycle() } }
    }

    @Test fun navigationIncludingSameUrlReloadAndRecreatedSessionInvalidatesOldPixels() = onMain {
        val cache = TabPreviewCache()
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            val original = key()
            cache.advance(original); assertTrue(cache.put(original, image))
            assertSame(image, cache.get("a", original.url))
            assertNull(cache.get("other-tab", original.url))
            assertNull(cache.get("a", "https://other.example"))
            val reload = original.copy(navigation = 2)
            cache.advance(reload)
            assertNull(cache.get("a", original.url)); assertEquals(0L, cache.byteCount)
            assertFalse(cache.put(original, image))
            assertTrue(cache.put(reload, image))
            val recreated = reload.copy(navigation = 0, session = 2)
            cache.advance(recreated)
            assertFalse(cache.put(reload, image)); assertNull(cache.get("a", original.url))
            assertTrue(cache.put(recreated, image))
            cache.advance(recreated.copy(url = "https://example.com/next"))
            assertNull(cache.get("a", original.url))
        } finally { cache.clear(); image.recycle() }
    }

    @Test fun closeAndPrivacyClearDropOwnershipAndRejectPreviouslyIssuedKeys() = onMain {
        val cache = TabPreviewCache()
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            val a = key(); val b = key("b")
            cache.advance(a); cache.advance(b)
            assertTrue(cache.put(a, image)); assertTrue(cache.put(b, image))
            val revision = cache.revision.value
            cache.remove("a")
            assertNull(cache.get("a", a.url)); assertFalse(cache.put(a, image))
            assertSame(image, cache.get("b", b.url))
            cache.clear()
            assertEquals(0, cache.entryCount); assertEquals(0L, cache.byteCount)
            assertNull(cache.get("b", b.url)); assertFalse(cache.put(b, image))
            assertTrue(cache.revision.value > revision)
        } finally { cache.clear(); image.recycle() }
    }

    @Test fun missingHomeUnsafeOversizeAndOverBudgetImagesStayPlaceholders() = onMain {
        val cache = TabPreviewCache(maxBytes = 4096)
        val small = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val large = Bitmap.createBitmap(321, 200, Bitmap.Config.ARGB_8888)
        val budget = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val home = key(url = "about:blank")
            cache.advance(home); assertFalse(cache.put(home, small)); assertNull(cache.get("a", home.url))
            val unsafe = key(url = "file:///private")
            cache.advance(unsafe); assertFalse(cache.put(unsafe, small))
            val valid = key()
            cache.advance(valid)
            assertFalse(cache.put(valid, large)); assertFalse(cache.put(valid, budget))
            assertEquals(0, cache.entryCount)
            assertNull(cache.get("missing", valid.url))
        } finally { cache.clear(); small.recycle(); large.recycle(); budget.recycle() }
    }
}
