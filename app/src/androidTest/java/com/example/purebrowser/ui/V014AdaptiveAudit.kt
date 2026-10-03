package com.example.purebrowser.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.data.browser.HOME_URL
import com.example.purebrowser.ui.browser.BrowserViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * Two actual MainActivity/BrowserViewModel journeys complement the 60 synthetic visual cases.
 * No screenshots, network requests, theme writes, download/file actions or global data clearing.
 * Requires v014DisposableProfile=true BEFORE the activity is launched: MainActivity otherwise reads
 * the real profile. Run ONLY on a disposable test installation. A temporary blank tab is created and
 * closed in finally; original selection is restored. Does not claim real webpage scroll/back-stack,
 * IME, rotation/font, release upgrade or download E2E coverage; the parent owns those final journeys.
 *
 * Parent command (not executed here):
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.example.purebrowser.ui.V014AdaptiveAudit \
 *   -Pandroid.testInstrumentationRunnerArguments.v014DisposableProfile=true
 * Uses current exclusive BrowserTool and menu-only resources/download entry; no obsolete direct
 * toolbar resources/download assumptions and no fragile localized text matching.
 */
class V014AdaptiveAudit {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val disposableProfile = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Disposable installation required before MainActivity launch",
                    InstrumentationRegistry.getArguments().getString("v014DisposableProfile") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(disposableProfile).around(compose)

    @Test fun menuManagementRoutes_preserveActiveSessionAndReturnToSingleChrome() = withBlankTab { model, id ->
        val session = model.tabs.active.value
        val generation = session?.engine?.generation
        val recordIds = model.data.value.tabs.map { it.id }
        listOf(
            Triple("menu-settings", "settingsScreen", "routeBackButton"),
            Triple("menu-bookmarks", "savedPagesList", "routeBackButton"),
            Triple("menu-history", "savedPagesList", "routeBackButton"),
            Triple("downloadsButton", "downloadsScreen", "downloadsBack"),
            Triple("menu-library", "videoLibraryList", "videoLibraryBack"),
        ).forEach { (entry, screen, back) ->
            compose.onNodeWithTag("menuButton").performClick()
            compose.onNodeWithTag(entry).performScrollTo().performClick()
            compose.onNodeWithTag(screen).assertIsDisplayed()
            compose.onNodeWithTag("dismissMenuButton").assertDoesNotExist()
            compose.onNodeWithTag("addressInput").assertDoesNotExist()
            compose.onNodeWithTag("menuButton").assertDoesNotExist()
            compose.onNodeWithTag(back).performClick()
            compose.onNodeWithTag("homeScreen").assertIsDisplayed()
            compose.onAllNodesWithTag("addressInput").assertCountEquals(1)
            compose.onNodeWithTag("menuButton").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(id, model.data.value.selectedId)
                assertEquals(recordIds, model.data.value.tabs.map { it.id })
                assertSame("Management routes must not replace the active session", session, model.tabs.active.value)
                assertEquals("Management routes must not reload even the blank page", generation, model.engine?.generation)
            }
        }
        // Re-entry retains the manager's local search while leaving no keyboard on the browser.
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("menu-history").performScrollTo().performClick()
        compose.onNodeWithTag("savedPageSearch").performTextReplacement("owned reentry query")
        compose.onNodeWithTag("routeBackButton").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("menuButton").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("menu-history").performScrollTo().performClick()
        compose.onNodeWithTag("savedPageSearch").assertTextEquals("owned reentry query")
        compose.onNodeWithTag("savedPageSearch").performTextReplacement("")
        compose.onNodeWithTag("routeBackButton").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("menuButton").assertIsDisplayed() }.isSuccess }
        // Resource access also comes through the exclusive page menu, not an old chrome button.
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("resourcesButton").performClick()
        compose.onNodeWithTag("resource-sheet").assertIsDisplayed()
        compose.onNodeWithTag("dismissMenuButton").assertDoesNotExist()
        compose.onNodeWithTag("menuButton").assertDoesNotExist()
        // Real system-back dispatch, not a localized close-label lookup.
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(5000) { compose.onAllNodesWithTag("resource-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("resource-sheet").assertDoesNotExist()
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        compose.runOnIdle { assertSame(session, model.tabs.active.value); assertEquals(generation, model.engine?.generation) }
    }

    @Test fun tabsThenCancelledAddressEdit_preserveSelectionAndDoNotSubmit() = withBlankTab { model, id ->
        val session = model.tabs.active.value
        val generation = session?.engine?.generation
        val recordIds = model.data.value.tabs.map { it.id }
        compose.onNodeWithTag("tabsButton").performClick()
        compose.onNodeWithTag("tabSwitcher").assertIsDisplayed()
        compose.onNodeWithTag("tab-$id").assertIsSelected()
        compose.onNodeWithTag("menuButton").assertDoesNotExist()
        compose.onNodeWithTag("dismissTabsButton").performClick()
        compose.onNodeWithTag("addressInput").performClick().performTextReplacement("https://v014.example.invalid/not-submitted")
        compose.onAllNodesWithTag("addressInput").assertCountEquals(1)
        compose.onNodeWithTag("cancelAddressButton").assertIsDisplayed()
        compose.onNodeWithTag("menuButton").assertDoesNotExist()
        compose.onNodeWithTag("cancelAddressButton").performClick()
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        compose.onNodeWithTag("cancelAddressButton").assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("menuButton").fetchSemanticsNodes().size == 1 }
        compose.runOnIdle {
            assertEquals(id, model.data.value.selectedId)
            assertEquals(recordIds, model.data.value.tabs.map { it.id })
            assertSame(session, model.tabs.active.value)
            assertEquals(generation, model.engine?.generation)
            assertEquals(HOME_URL, model.engine?.page?.value?.url)
        }
    }

    private fun withBlankTab(journey: (BrowserViewModel, String) -> Unit) {
        lateinit var model: BrowserViewModel
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(15_000) { model.ready.value }
        assumeTrue("Leave room for one bounded fixture tab", model.data.value.tabs.size < 50)
        val original = model.data.value.selectedId
        var fixture: String? = null
        try {
            compose.activityRule.scenario.onActivity {
                model.newTab()
                fixture = model.data.value.selectedId
            }
            val id = checkNotNull(fixture)
            assertNotEquals(original, id)
            compose.waitUntil(10_000) {
                model.tabs.active.value?.recordId == id && model.engine?.page?.value?.let { it.url == HOME_URL && it.progress == 100 } == true
            }
            compose.onNodeWithTag("homeScreen").assertIsDisplayed()
            // about:blank does not emit onPageStarted on every WebView provider. Use a renderer
            // round trip after mount, not a guessed positive generation or fixed sleep.
            val documentReady=java.util.concurrent.atomic.AtomicBoolean(false)
            compose.activityRule.scenario.onActivity {
                checkNotNull(model.tabs.active.value?.mountedPreviewView()).evaluateJavascript("document.readyState") { value ->
                    documentReady.set(value == "\"complete\"")
                }
            }
            compose.waitUntil(10000) { documentReady.get() && model.engine?.page?.value?.let { it.url==HOME_URL && it.progress==100 }==true }
            journey(model, id)
        } finally {
            compose.activityRule.scenario.onActivity {
                fixture?.let { id -> if (id != original) model.tabs.close(id) }
                if (model.data.value.tabs.any { it.id == original }) model.tabs.select(original)
                model.flush()
            }
        }
    }
}
