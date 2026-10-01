package com.example.purebrowser.ui.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.purebrowser.MainActivity
import org.junit.Before
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test

class BrowserScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun startWithOwnHomeTab() {
        lateinit var model: BrowserViewModel
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
        compose.activityRule.scenario.onActivity { model.newTab() }
        compose.waitUntil(5000) { model.engine?.page?.value?.url == "about:blank" }
    }

    @Test fun homeContainsWorkingBrowserControls() {
        compose.onNodeWithText("自在浏览，随手保存。").assertIsDisplayed()
        compose.onNodeWithTag("addressInput").assertIsDisplayed()
        compose.onNodeWithTag("tabsButton").assertIsDisplayed()
        compose.onNodeWithTag("resourcesButton").assertIsDisplayed()
    }
    @Test fun resourcePanelExplainsEmptyState() {
        compose.onNodeWithTag("resourcesButton").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("尚未发现视频").assertIsDisplayed()
    }
    @Test fun downloadCenterExplainsEmptyState() {
        compose.onNodeWithTag("downloadsButton").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("下载中心").assertIsDisplayed()
    }
}
