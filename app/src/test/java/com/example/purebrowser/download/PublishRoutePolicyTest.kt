package com.example.purebrowser.download

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure publish-route decision for the v0.1.7 E3 API28 leftover: a targetSdk-30+ app gets no
 * sdcard_rw gid for WRITE_EXTERNAL_STORAGE on Android 9-, so publish() must not attempt the
 * legacy public Download write there (field evidence: createNewFile EACCES with grant present).
 */
class PublishRoutePolicyTest {
    @Test fun android9AndBelowPublishIntoAppSpecificExternalStorage() {
        (26..28).forEach { sdk -> assertEquals("sdk=$sdk", PublishRoute.APP_EXTERNAL, PublishRoutePolicy.forSdk(sdk)) }
    }
    @Test fun android10AndAboveKeepScopedMediaStorePublish() {
        listOf(29, 30, 33, 36, 37).forEach { sdk -> assertEquals("sdk=$sdk", PublishRoute.MEDIASTORE, PublishRoutePolicy.forSdk(sdk)) }
    }
    @Test fun boundaryIsExactlyApi29() {
        assertNotSame(PublishRoutePolicy.forSdk(28), PublishRoutePolicy.forSdk(29))
        assertEquals(PublishRoutePolicy.forSdk(29), PublishRoutePolicy.forSdk(30))
    }
    @Test fun everySupportedSdkLevelGetsARouteAndLegacyPublicIsNeverWritten() {
        // The legacy public file stays readable for old records but is no longer a write target:
        // API29+ owns MediaStore, API28- owns the app-specific fallback, at every level in between.
        assertEquals(setOf(PublishRoute.MEDIASTORE, PublishRoute.APP_EXTERNAL), PublishRoute.entries.toSet())
        (26..37).forEach { sdk -> assertTrue("sdk=$sdk", PublishRoutePolicy.forSdk(sdk) in PublishRoute.entries) }
    }
    @Test fun saveLocationLabelFollowsTheSameDecision() {
        assertEquals("Download/PureBrowser", PublishRoutePolicy.savePathLabel(29))
        assertEquals("Download/PureBrowser", PublishRoutePolicy.savePathLabel(36))
        assertEquals("应用专属外部目录（Download）", PublishRoutePolicy.savePathLabel(26))
        assertEquals("应用专属外部目录（Download）", PublishRoutePolicy.savePathLabel(28))
    }
}
