package com.example.purebrowser.library

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** Capture platform handoffs without starting a real player or sending any user file. */
@Suppress("DEPRECATION")
class LocalFileActionsTest {
    private val uri = Uri.parse("content://downloads/all_downloads/7")
    private val forbiddenGrants = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private class RecordingContext(val failure: Throwable? = null) : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        var outgoing: Intent? = null
        override fun startActivity(intent: Intent) {
            outgoing = intent
            failure?.let { throw it }
        }
    }

    private fun launch(
        context: RecordingContext, id: Long = 7, value: Uri = uri,
        type: String = "video/mp4", share: Boolean = false,
    ): String? {
        var result: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = LocalFileActions.launch(context, id.toString(), value, type, share)
        }
        return result
    }

    private fun assertReadOnlyGrant(intent: Intent) {
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and forbiddenGrants)
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
    }

    @Test fun viewUsesActualContentUriMimeAndReadOnlyGrant() {
        val context = RecordingContext()
        assertNull(launch(context))
        val outgoing = context.outgoing!!
        assertEquals(Intent.ACTION_VIEW, outgoing.action)
        assertEquals(uri, outgoing.data)
        assertEquals("video/mp4", outgoing.type)
        assertNull(outgoing.extras)
        assertReadOnlyGrant(outgoing)
        assertTrue(outgoing.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun shareLaunchesChooserForFileStreamNotWebUrlAndDoesNotAssertDelivery() {
        val context = RecordingContext()
        assertNull(launch(context, share = true)) // Only the captured startActivity returned.
        val chooser = context.outgoing!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertReadOnlyGrant(chooser)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals("video/mp4", target.type)
        assertNull(target.data)
        assertEquals(uri, target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(setOf(Intent.EXTRA_STREAM), target.extras!!.keySet())
        assertReadOnlyGrant(target)
        assertNull(target.component)
        assertNull(target.`package`)
    }

    @Test fun foreignMismatchedNonpositiveOrNoncontentUrisNeverLaunch() {
        listOf(
            8L to uri, 0L to uri, -1L to uri,
            7L to Uri.parse("file:///sdcard/video.mp4"),
            7L to Uri.parse("https://example.com/video.mp4?signature=secret"),
            7L to Uri.parse("content://contacts/all_downloads/7"),
        ).forEach { (id, value) ->
            val context = RecordingContext()
            assertNotNull(launch(context, id = id, value = value))
            assertNull(context.outgoing)
        }
    }

    @Test fun missingTargetDeniedMissingFileAndPlatformFailuresHaveActionableErrors() {
        listOf(
            ActivityNotFoundException() to "安装", SecurityException() to "授权",
            FileNotFoundException() to "删除", IOException() to "重试",
            IllegalStateException() to "重试",
        ).forEach { (failure, expected) ->
            listOf(false, true).forEach { share ->
                val context = RecordingContext(failure)
                val result = launch(context, share = share)
                assertNotNull(result)
                assertTrue(result!!.contains(expected))
            }
        }
    }

    @Test fun emptyOrControlContainingMimeDoesNotLaunch() {
        listOf("", "video", "video/mp4\nextra").forEach { type ->
            val context = RecordingContext()
            assertNotNull(launch(context, type = type))
            assertNull(context.outgoing)
        }
    }
}
