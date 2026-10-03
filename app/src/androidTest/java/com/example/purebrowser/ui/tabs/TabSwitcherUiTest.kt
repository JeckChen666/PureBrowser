package com.example.purebrowser.ui.tabs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.data.browser.BrowserData
import com.example.purebrowser.data.browser.BrowserRules
import com.example.purebrowser.data.browser.HOME_URL
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.theme.PureBrowserTheme
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Isolated overview tests; no MainActivity, networking, services or live browsing sessions. */
class TabSwitcherUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceFile = "t44-ui-${UUID.randomUUID()}"
    private lateinit var preferences: TabViewPreferences

    @Before fun prepare() {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(preferenceFile, mode)
        }
        preferences = TabViewPreferences(context)
    }
    @After fun cleanup() { app.deleteSharedPreferences(preferenceFile) }

    @Test fun searchBothFieldsAndLocateHiddenCurrentInFiftyTabs() {
        val tabs = (0 until 50).map { TabRecord("$it", "https://example.com/Path/$it", "Page $it") }
        compose.setContent { PureBrowserTheme {
            TabSwitcher(tabs, "49", {}, {}, {}, {}, viewPreferences = preferences)
        } }
        compose.onNodeWithTag("tab-49").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("newTabButton").assertIsNotEnabled()
        compose.onNodeWithTag("tabSearchField").performTextReplacement("pAtH/17")
        compose.onNodeWithTag("tab-17").assertIsDisplayed()
        compose.onNodeWithTag("tab-49").assertDoesNotExist()
        compose.onNodeWithTag("tabSearchField").performTextReplacement("pAgE 7")
        compose.onNodeWithTag("tab-7").assertIsDisplayed()
        compose.onNodeWithTag("locateTabButton").performClick()
        compose.onNodeWithTag("tabSearchField").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("tab-49").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("tabListButton").performClick()
        compose.onNodeWithTag("tabList").assertExists()
        compose.onNodeWithTag("tab-49").assertIsDisplayed()
        compose.runOnIdle { assertEquals(TabViewMode.LIST, preferences.read()) }
    }

    @Test fun viewChangesNeverRecaptureAndReopeningUsesPersistedExplicitChoice() {
        val showing = mutableStateOf(true)
        val tabs = (0 until 24).map { TabRecord("$it", "https://example.com/$it", "Page $it") }
        var captures = 0
        compose.setContent { PureBrowserTheme {
            if (showing.value) TabSwitcher(tabs, "0", {}, {}, {}, { showing.value = false }, requestPreview = { captures++ }, viewPreferences = preferences)
        } }
        compose.onNodeWithTag("previewPlaceholder-0", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tabListButton").performClick()
        compose.onNodeWithTag("tabGridButton").performClick()
        compose.onNodeWithTag("tabListButton").performClick()
        compose.runOnIdle { assertEquals(1, captures) }
        compose.onNodeWithTag("dismissTabsButton").performClick()
        compose.runOnIdle { showing.value = true }
        compose.onNodeWithTag("tabList").assertExists()
        compose.runOnIdle { assertEquals(2, captures); assertEquals(TabViewMode.LIST, preferences.read()) }
    }

    @Test fun emptyResultHasExplicitClearAndClearingRestoresStableOrder() {
        val tabs = listOf(TabRecord("a", "https://alpha.example", "Alpha"), TabRecord("b", "https://beta.example", "Beta"))
        compose.setContent { PureBrowserTheme {
            TabSwitcher(tabs, "a", {}, {}, {}, {}, viewPreferences = preferences)
        } }
        compose.onNodeWithTag("tabSearchField").performTextReplacement("not-an-open-tab")
        compose.onNodeWithTag("tabSearchEmpty").assertIsDisplayed()
        compose.onNodeWithTag("clearEmptyTabSearchButton").performClick()
        compose.onNodeWithTag("tab-a").assertExists()
        compose.onNodeWithTag("tab-b").assertExists()
        compose.onNodeWithTag("tabSearchField").performTextReplacement("BETA")
        compose.onNodeWithTag("tab-a").assertDoesNotExist()
        compose.onNodeWithTag("clearTabSearchButton").performClick()
        compose.onNodeWithTag("tab-a").assertExists()
        compose.onNodeWithTag("tab-b").assertExists()
    }

    @Test fun closeIsIndependentFromSelectionAndPreservesLastHomeBoundary() {
        val state = mutableStateOf(BrowserData(tabs = listOf(TabRecord("a"), TabRecord("b")), selectedId = "a"))
        val selections = mutableListOf<String>()
        val closes = mutableListOf<String>()
        compose.setContent { PureBrowserTheme {
            TabSwitcher(state.value.tabs, state.value.selectedId, { selections += it }, { id ->
                closes += id; state.value = BrowserRules.closeTab(state.value, id)
            }, {}, {}, viewPreferences = preferences)
        } }
        compose.onNodeWithTag("close-b").performClick()
        compose.runOnIdle { assertTrue(selections.isEmpty()); assertEquals("a", state.value.selectedId) }
        compose.onNodeWithTag("close-a").performClick()
        compose.runOnIdle {
            assertTrue(selections.isEmpty()); assertEquals(listOf("b", "a"), closes)
            assertEquals(HOME_URL, state.value.tabs.single().url)
            assertEquals(state.value.tabs.single().id, state.value.selectedId)
        }
        compose.onNodeWithTag("tab-${state.value.selectedId}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(state.value.selectedId), selections) }
    }

    @Test fun everyToolbarAndCloseTargetIsAtLeastFortyEightDp() {
        compose.setContent { PureBrowserTheme {
            TabSwitcher(listOf(TabRecord("a")), "a", {}, {}, {}, {}, viewPreferences = preferences)
        } }
        listOf("tabGridButton", "tabListButton", "locateTabButton", "newTabButton", "dismissTabsButton", "close-a").forEach { tag ->
            compose.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }
}
