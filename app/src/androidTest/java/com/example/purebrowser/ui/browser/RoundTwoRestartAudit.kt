package com.example.purebrowser.ui.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.DownloadStore
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Host force-stops the process before invoking this separate instrumentation session. */
class RoundTwoRestartAudit {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun restartReconcilesFilesKeepsForgottenEntryAbsentAndRestoresWifiPolicy() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restartAudit")=="true")
        lateinit var model:BrowserViewModel
        compose.activityRule.scenario.onActivity { model=ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
        val id=File(compose.activity.cacheDir,"round2-restart-id.txt").readText().trim().toLong()
        val forgotten=File(compose.activity.cacheDir,"round2-forgotten-id.txt").readText().trim().toLong()
        compose.waitUntil(10000) { model.downloads.value.any { it.id==id && it.verified } }
        assertFalse(model.defaultWifiOnly.value)
        assertNotNull(model.repository.fileUri(id))
        assertTrue(model.videoLibrary.value.any { it.systemId==id })
        assertTrue(model.downloads.value.none { it.id==forgotten });assertTrue(model.videoLibrary.value.none { it.systemId==forgotten })
        assertEquals(model.data.value,com.example.purebrowser.data.browser.LocalBrowserRepository(compose.activity).load())
        assertTrue(DownloadStore(compose.activity).load().records.any { it.systemId==id })
    }
    @Test fun externallyDeletedIndexedFileIsRetainedWithoutPlaybackOrShare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("externalMissingAudit")=="true")
        lateinit var model:BrowserViewModel
        compose.activityRule.scenario.onActivity { model=ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
        val id=File(compose.activity.cacheDir,"round2-restart-id.txt").readText().trim().toLong()
        try { compose.waitUntil(10000) { model.downloads.value.any { it.id==id && !it.verified && it.availability==com.example.purebrowser.download.FileAvailability.MISSING } } }
        catch(error:Throwable) {
            val manager=compose.activity.getSystemService(android.app.DownloadManager::class.java)
            val local=manager.query(android.app.DownloadManager.Query().setFilterById(id))?.use { c -> if(c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_LOCAL_URI)) else "missing row" }
            throw AssertionError("Own deleted fixture ID=$id: ${model.downloads.value.firstOrNull { it.id==id }}; asset=${model.videoLibrary.value.firstOrNull { it.systemId==id }?.availability}; system-local=$local",error)
        }
        assertNull(model.repository.fileUri(id))
        assertTrue(model.videoLibrary.value.any { it.systemId==id && it.availability==com.example.purebrowser.download.FileAvailability.MISSING })
        compose.onNodeWithTag("downloadsButton").performClick()
        compose.onNodeWithTag("downloadsLibrary").performClick()
        compose.onNodeWithTag("videoLibraryList").performScrollToNode(hasTestTag("video-$id"))
        compose.onNodeWithTag("video-$id").assertExists()
        compose.onNodeWithTag("video-open-$id").assertDoesNotExist()
        compose.onNodeWithTag("video-share-$id").assertDoesNotExist()
    }

}
