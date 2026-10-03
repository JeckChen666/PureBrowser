package com.example.purebrowser.ui.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class BrowserNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun addressSubmissionHidesKeyboardAndNetworkFailureCanBeRetried() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("videoFixture") == "true")
        lateinit var model: BrowserViewModel
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
        compose.activityRule.scenario.onActivity { model.newTab() }
        val unreachable = java.net.ServerSocket(0).use { "http://127.0.0.1:${it.localPort}/" }
        compose.onNodeWithTag("addressInput").performClick().performTextReplacement(unreachable)
        compose.onNodeWithTag("addressInput").performImeAction()
        compose.waitUntil(15000) { model.engine?.page?.value?.error != null }
        compose.waitUntil(10000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(15000) { model.engine?.page?.value?.error != null }
        compose.onNodeWithTag("homeButton").performClick()
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        compose.onNodeWithTag("addressInput").performClick().performTextReplacement("not submitted")
        compose.onNodeWithTag("cancelAddressButton").performClick()
        compose.onNodeWithTag("addressInput").assertTextEquals("搜索或输入网址")
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { model.tabs.close(model.data.value.selectedId) }
    }
}
