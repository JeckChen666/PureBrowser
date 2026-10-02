package com.example.purebrowser.ui.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.data.browser.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ProductWorkflowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun tabsBookmarksShortcutsHistoryThemeAndDiskRestore() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("videoFixture")=="true")
        val base=args.getString("fixtureBaseUrl")?:"http://127.0.0.1:8765"
        lateinit var model:BrowserViewModel
        compose.activityRule.scenario.onActivity{model=ViewModelProvider(it)[BrowserViewModel::class.java]}
        compose.waitUntil(10000){model.ready.value}
        fun onMain(action:()->Unit){compose.activityRule.scenario.onActivity{action()}}
        fun menu(text:String){compose.onNodeWithTag("menuButton").performClick();val key=when(text){"添加书签"->"bookmarkToggle";"书签"->"bookmarks";"历史"->"history";else->"settings"};compose.onNodeWithTag("menu-$key").performClick()}
        // Work in newly created owned tabs, not whatever pages existed before the test.
        val aUrl="$base/?product=${UUID.randomUUID()}"
        onMain{model.newTab(aUrl)}
        fun hasExpectedSources():Boolean {
            val urls=model.sniffer?.candidates?.value?.map { java.net.URI(it.url).path }?.toSet().orEmpty()
            return setOf("/sample.mp4","/sample.m3u8","/manifest.mpd").all { it in urls }
        }
        compose.waitUntil(15000){hasExpectedSources()}
        val a=model.data.value.selectedId
        menu("添加书签")
        compose.waitUntil(5000){model.data.value.bookmarks.any{it.url==aUrl}}
        onMain{model.navigate("$base/second.html")}
        compose.waitUntil(15000){model.engine?.page?.value?.canGoBack==true && model.sniffer?.candidates?.value?.singleOrNull()?.url=="$base/second.mp4"}
        onMain{model.newTab("$base/second.html")}
        compose.waitUntil(15000){model.sniffer?.candidates?.value?.singleOrNull()?.url=="$base/second.mp4"}
        val b=model.data.value.selectedId
        onMain{model.newTab()}
        val c=model.data.value.selectedId
        compose.waitUntil(5000){model.sniffer?.candidates?.value?.isEmpty()==true}
        // UI tab switching, not just state rules.
        compose.onNodeWithTag("tabsButton").performClick()
        compose.onNodeWithTag("tabList").performScrollToNode(hasTestTag("tab-$a"))
        compose.onNodeWithTag("tab-$a").performClick()
        compose.waitUntil(5000){model.data.value.selectedId==a && model.engine?.page?.value?.canGoBack==true}
        compose.onNodeWithTag("backButton").performClick()
        compose.waitUntil(15000){hasExpectedSources() && model.engine?.page?.value?.url==aUrl}
        compose.onNodeWithTag("tabsButton").performClick()
        compose.onNodeWithTag("tabList").performScrollToNode(hasTestTag("tab-$b"))
        compose.onNodeWithTag("close-$b").performClick()
        compose.waitUntil(5000){model.data.value.tabs.none{it.id==b}}
        compose.onNodeWithTag("tabList").performScrollToNode(hasTestTag("tab-$c"))
        compose.onNodeWithTag("tab-$c").performClick()
        compose.onNodeWithTag("homeScreen").performScrollToNode(hasTestTag("addShortcutButton"))
        compose.onNodeWithTag("addShortcutButton").performClick()
        val title="回归站点-${UUID.randomUUID().toString().take(4)}"
        compose.onNodeWithTag("editorName").performTextReplacement(title)
        compose.onNodeWithTag("editorUrl").performTextReplacement("$base/second.html")
        compose.onNodeWithTag("editorSave").performClick()
        compose.waitUntil(5000){model.data.value.shortcuts.any{it.title==title}}
        val shortcut=model.data.value.shortcuts.first{it.title==title}
        Thread.sleep(600) // Platform IME close animation after native editor confirmation.
        compose.onNodeWithTag("homeScreen").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed().performTouchInput{longClick()}
        compose.onNodeWithTag("editorName").performTextReplacement("$title-编辑")
        compose.onNodeWithTag("editorSave").performClick()
        compose.waitUntil(5000){model.data.value.shortcuts.any{it.id==shortcut.id && it.title.endsWith("编辑")}}
        menu("书签")
        val bookmark=model.data.value.bookmarks.first{it.url==aUrl}
        compose.onNodeWithTag("edit-${bookmark.id}").performClick()
        val bookmarkName="本地收藏-${UUID.randomUUID().toString().take(4)}"
        compose.onNodeWithTag("editorName").performTextReplacement(bookmarkName)
        compose.onNodeWithTag("editorSave").performClick()
        compose.waitUntil(5000){model.data.value.bookmarks.any{it.id==bookmark.id && it.title==bookmarkName}}
        compose.onNodeWithTag("savedPageSearch").performTextReplacement(bookmarkName)
        compose.onNodeWithTag("saved-${bookmark.id}").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        menu("设置")
        compose.onNodeWithTag("theme-DARK").performClick()
        compose.waitUntil(5000){model.data.value.theme==ThemeMode.DARK}
        compose.onNodeWithContentDescription("返回").performClick()
        menu("历史")
        compose.onNodeWithTag("clearHistoryButton").performClick()
        compose.onNodeWithTag("confirmClearHistory").performClick()
        compose.waitUntil(5000){model.data.value.history.isEmpty()}
        assertTrue(model.data.value.bookmarks.any{it.id==bookmark.id})
        onMain{model.flush()}
        val storage=LocalBrowserRepository(compose.activity)
        compose.waitUntil(5000){runCatching{storage.load().let{it.theme==ThemeMode.DARK && it.selectedId==c && it.bookmarks.any{p->p.id==bookmark.id} && it.shortcuts.any{p->p.id==shortcut.id && p.title.endsWith("编辑")}}}.getOrDefault(false)}
        val restored=storage.load()
        assertEquals(model.data.value.tabs,restored.tabs)
        assertTrue(restored.history.isEmpty())
        // Closing a background tab and the final test tab does not touch downloads.
        onMain{model.tabs.close(a)}
        assertFalse(model.data.value.tabs.any{it.id==a})
    }
}
