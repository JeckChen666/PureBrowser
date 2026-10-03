package com.example.purebrowser.ui.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.example.purebrowser.MainActivity
import com.example.purebrowser.data.browser.LocalBrowserRepository
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.UUID

/** Explicit lab-only cleanup: backup first; exact local fixture tabs only, never user-wide reset. */
class FixtureTabCapacityCleanup {
    @Test fun reclaimOnlyKnownFixtureTabsAfterPrivateBackup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cleanupFixtureTabs")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:BrowserViewModel
            scenario.onActivity { model=ViewModelProvider(it)[BrowserViewModel::class.java] }
            val end=System.currentTimeMillis()+10000
            while(!model.ready.value && System.currentTimeMillis()<end)Thread.sleep(50)
            assertTrue(model.ready.value)
            val original=model.data.value
            val backup=File(app.cacheDir,"v013-lab-browser-backup-${UUID.randomUUID()}.json")
            File(app.filesDir,"browser-state.json").copyTo(backup)
            assertTrue(backup.length()>0)
            val owned=original.tabs.filter { tab ->runCatching {
                val u=URI(tab.url)
                u.scheme=="http" && u.host in setOf("127.0.0.1","10.0.2.2") && u.port in setOf(8765,8766) &&
                    u.path in setOf("/","/index.html","/product.html","/second.html","/dynamic.html","/same-frame.html","/frame.html")
            }.getOrDefault(false) }.map { it.id }.toSet()
            scenario.onActivity { owned.forEach { model.tabs.close(it) };model.flush() }
            val deadline=System.currentTimeMillis()+10000
            while(LocalBrowserRepository(app).load().tabs.any { it.id in owned } && System.currentTimeMillis()<deadline)Thread.sleep(50)
            val after=LocalBrowserRepository(app).load()
            assertTrue(after.tabs.none { it.id in owned })
            assertTrue(after.tabs.map { it.id }.containsAll(original.tabs.filterNot { it.id in owned }.map { it.id }))
            assertEquals(original.bookmarks,after.bookmarks)
            assertTrue(original.history.all { old -> after.history.any { it.url==old.url } })
            assertEquals(original.theme,after.theme)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                putInt("fixtureTabsRemoved",owned.size);putInt("remainingTabs",after.tabs.size)
            })
        }
    }
}
