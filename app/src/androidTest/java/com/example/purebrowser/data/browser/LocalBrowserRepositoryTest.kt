package com.example.purebrowser.data.browser

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LocalBrowserRepositoryTest {
    @Test fun roundtripAllLocalDataAndPreserveSignedUrls() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(app.cacheDir,"repository-test-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(app){override fun getFilesDir()=directory}
        try {
            val repository=LocalBrowserRepository(context)
            val uri="https://example.com/video?signature=a%2Bb"
            val data=BrowserData(tabs=listOf(TabRecord("a",uri,"Video")),selectedId="a",bookmarks=listOf(SavedPage("b","Bookmark",uri,123)),history=listOf(SavedPage("h","History",uri,124)),shortcuts=listOf(Shortcut("s","Site",uri)),theme=ThemeMode.DARK)
            repository.save(data)
            assertEquals(data,LocalBrowserRepository(context).load())
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptionIsBackedUpWithoutTouchingDownloadPreferences() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(app.cacheDir,"repository-test-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(app){override fun getFilesDir()=directory}
        try {
            val file=File(directory,"browser-state.json");val bad="broken-json-but-retain-original";file.writeText(bad)
            val repository=LocalBrowserRepository(context);val data=repository.load()
            assertTrue(repository.recoveredCorruption);assertEquals(HOME_URL,data.tabs.single().url)
            assertEquals(bad,directory.listFiles()!!.first{it.name.contains(".corrupt-")}.readText())
            repository.save(data);assertEquals(data,LocalBrowserRepository(context).load())
        } finally { directory.deleteRecursively() }
    }
}
