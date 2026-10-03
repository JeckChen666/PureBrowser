package com.example.purebrowser.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.data.browser.ThemeMode
import com.example.purebrowser.privacy.PrivacyCategory
import com.example.purebrowser.privacy.PrivacyClearResult
import com.example.purebrowser.theme.PureBrowserTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Callback-only settings tests. Nothing actually clears local data or launches a share intent. */
class CompactSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupedSettingsKeepThemeWifiAboutAndReadOnlySaveLocation() {
        var selected: ThemeMode? = null
        var wifi: Boolean? = null
        var aboutCalls = 0
        compose.setContent {
            PureBrowserTheme(mode = ThemeMode.LIGHT) {
                SettingsScreen(ThemeMode.SYSTEM, { selected = it }, false, { wifi = it }, { aboutCalls++ })
            }
        }
        compose.onNodeWithTag("theme-SYSTEM").assertIsSelected()
        compose.onNodeWithTag("theme-DARK").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, selected) }
        compose.onNodeWithTag("defaultWifiOnly").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, wifi) }
        compose.onNodeWithTag("downloadSavePath").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("Download/PureBrowser").assertHasNoClickAction()
        listOf("外观", "下载", "本地数据", "诊断", "关于").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("settingsAboutButton").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, aboutCalls) }
    }

    @Test fun unavailableIntegrationsRemainDisabledInsteadOfClickableNoOps() {
        compose.setContent {
            PureBrowserTheme {
                SettingsScreen(ThemeMode.SYSTEM, {}, false, {}, {})
            }
        }
        PrivacyCategory.entries.forEach {
            compose.onNodeWithTag("privacy-${it.name}").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("diagnostic-generate").performScrollTo().assertIsNotEnabled()
    }

    @Test fun cleanupRequiresFreshConfirmationForEachCategoryAndCancelDoesNothing() {
        val cleared = mutableListOf<PrivacyCategory>()
        val actions = SettingsPrivacyActions(
            clearHistory = { cleared += PrivacyCategory.HISTORY; PrivacyClearResult.COMPLETED },
            clearSiteData = { cleared += PrivacyCategory.SITE_DATA; PrivacyClearResult.REQUESTED },
            clearCache = { cleared += PrivacyCategory.CACHE; PrivacyClearResult.REQUESTED },
            clearDownloadTemp = { cleared += PrivacyCategory.DOWNLOAD_TEMP; PrivacyClearResult.COMPLETED },
        )
        compose.setContent {
            PureBrowserTheme {
                SettingsScreen(ThemeMode.SYSTEM, {}, false, {}, {}, privacyActions = actions)
            }
        }
        compose.onNodeWithTag("privacy-HISTORY").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyList<PrivacyCategory>(), cleared) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(emptyList<PrivacyCategory>(), cleared) }
        PrivacyCategory.entries.forEachIndexed { index, category ->
            compose.onNodeWithTag("privacy-${category.name}").performScrollTo().performClick()
            compose.onNodeWithTag("privacy-confirm").assertIsDisplayed()
            compose.runOnIdle { assertEquals(index, cleared.size) }
            compose.onNodeWithTag("privacy-confirm").performClick()
            compose.onNodeWithTag("privacy-result").assertExists()
            compose.runOnIdle { assertEquals(PrivacyCategory.entries.take(index + 1), cleared) }
        }
    }

    @Test fun diagnosticGenerationOnlyPreviewsAndSharingRequiresExplicitClick() {
        val text = "PureBrowser local diagnostics v1\napp_version=fixture"
        var generated = 0
        val shared = mutableListOf<String>()
        compose.setContent {
            PureBrowserTheme {
                SettingsScreen(ThemeMode.SYSTEM, {}, false, {}, {},
                    privacyActions = SettingsPrivacyActions(
                        diagnosticReport = { generated++; text },
                        shareDiagnosticReport = { shared += it },
                    ))
            }
        }
        compose.onNodeWithTag("diagnostic-generate").performScrollTo().performClick()
        compose.onNodeWithText("本地诊断预览").assertIsDisplayed()
        compose.onNodeWithText(text).assertExists()
        compose.runOnIdle { assertEquals(1, generated); assertEquals(emptyList<String>(), shared) }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), shared) }
        compose.onNodeWithTag("diagnostic-generate").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostic-share").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(text), shared) }
        compose.onNodeWithText("本地诊断预览").assertDoesNotExist()
    }

    @Test fun narrowDarkLargeFontSettingsKeepControlsReachableAndPathNoninteractive() {
        var aboutCalls = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PureBrowserTheme(mode = ThemeMode.DARK) {
                    Box(Modifier.width(260.dp).height(360.dp)) {
                        SettingsScreen(ThemeMode.DARK, {}, true, {}, { aboutCalls++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("theme-DARK").performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("defaultWifiOnly").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("downloadSavePath").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithTag("settingsAboutButton").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, aboutCalls) }
    }

    @Test fun aboutUsesInstalledPackageVersionIncludingBuildSuffix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?.takeIf { it.isNotBlank() } ?: "未知"
        compose.setContent { PureBrowserTheme { AboutScreen() } }
        compose.onNodeWithTag("aboutRuntimeVersion").assertTextEquals("本地视频浏览器 · $version")
        compose.onNodeWithText("应用 ID：${context.packageName}").assertExists()
    }
}
