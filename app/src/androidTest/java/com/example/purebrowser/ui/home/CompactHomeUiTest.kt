package com.example.purebrowser.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.purebrowser.data.browser.BrowserData
import com.example.purebrowser.data.browser.SavedPage
import com.example.purebrowser.data.browser.ThemeMode
import com.example.purebrowser.data.browser.Shortcut
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.VideoAsset
import com.example.purebrowser.theme.PureBrowserTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Standalone content tests: no BrowserScreen, repository, WebView, network or real video files. */
class CompactHomeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun siteTapLongPressAndAllManagementCallbacksArePreserved() {
        val site = Shortcut("site", "Example", "https://example.test")
        val page = SavedPage("visit", "Visited page", "https://example.test/visited", 1)
        val asset = VideoAsset(recordId = "saved", uri = "content://fixture/saved", name = "saved.mp4",
            displayName = "Saved video", indexedAt = 1, availability = FileAvailability.AVAILABLE)
        var opened: String? = null
        var edited: Shortcut? = null
        var played: String? = null
        val actions = mutableListOf<String>()
        compose.setContent {
            PureBrowserTheme {
                HomeScreen(BrowserData(shortcuts = listOf(site), history = listOf(page)), listOf(asset),
                    library = { actions += "library" }, play = { played = it }, open = { opened = it },
                    add = { actions += "add" }, edit = { edited = it }, bookmarks = { actions += "bookmarks" },
                    history = { actions += "history" }, downloads = { actions += "downloads" })
            }
        }
        compose.onNodeWithTag("homeShortcut-site").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(site.url, opened) }
        compose.onNodeWithTag("homeShortcut-site").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(site, edited) }
        clickHome("homeVisit-visit")
        compose.runOnIdle { assertEquals(page.url, opened) }
        clickHome("homeSave-saved")
        compose.runOnIdle { assertEquals(asset.recordId, played) }
        listOf("addShortcutButton", "homeHistoryButton", "homeLibraryButton", "homeBookmarksButton", "homeDownloadsButton")
            .forEach { clickHome(it) }
        compose.runOnIdle { assertEquals(listOf("add", "history", "library", "bookmarks", "downloads"), actions) }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertCountEquals(0)
    }

    @Test fun emptyContentAndNavigationStayReachableAtNarrowWidthAndLargeFont() {
        var added = false
        var downloads = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PureBrowserTheme(mode = ThemeMode.DARK) {
                    Box(Modifier.width(260.dp).height(360.dp)) {
                        HomeScreen(BrowserData(shortcuts = emptyList()), emptyList(), {}, {}, {},
                            { added = true }, {}, {}, {}, { downloads = true })
                    }
                }
            }
        }
        clickHome("addShortcutButton")
        compose.onNodeWithTag("homeScreen").performScrollToNode(hasText("从上方输入网址", substring = true))
        compose.onNodeWithText("从上方输入网址，开始你的第一次浏览。").assertIsDisplayed()
        compose.onNodeWithTag("homeScreen").performScrollToNode(hasText("网页中发现视频后", substring = true))
        compose.onNodeWithText("网页中发现视频后，从资源面板保存到本机。").assertIsDisplayed()
        clickHome("homeDownloadsButton")
        compose.runOnIdle { assertEquals(true, added); assertEquals(true, downloads) }
    }

    private fun clickHome(tag: String) {
        compose.onNodeWithTag("homeScreen").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
}
