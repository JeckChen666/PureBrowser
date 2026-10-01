package com.example.purebrowser.library

import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.VideoAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVideoThumbnailRulesTest {
    private fun asset() = VideoAsset(
        recordId = "local-7", systemId = 7,
        uri = "content://downloads/all_downloads/7",
        name = "video.mp4", displayName = "视频", indexedAt = 100,
        sizeBytes = 1024, systemUpdatedAt = 200,
        format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE,
    )

    @Test fun onlyAvailablePassedOwnedAssetsMayReadFiles() {
        val video = asset()
        assertTrue(canReadLocalThumbnail(video))
        FileAvailability.entries.filter { it != FileAvailability.AVAILABLE }.forEach {
            assertFalse(canReadLocalThumbnail(video.copy(availability = it)))
        }
        FormatCheck.entries.filter { it != FormatCheck.PASSED }.forEach {
            assertFalse(canReadLocalThumbnail(video.copy(format = it)))
        }
        assertFalse(canReadLocalThumbnail(video.copy(systemId = 8)))
        assertFalse(canReadLocalThumbnail(video.copy(systemId = 0)))
        listOf(
            "file:///sdcard/video.mp4", "https://example.com/video.mp4",
            "content://contacts/all_downloads/7", "content://downloads/all_downloads/7?other=1",
        ).forEach { assertFalse(canReadLocalThumbnail(video.copy(uri = it))) }
    }

    @Test fun cacheIdentityChangesWithUriSizeOrUpdateButNotDisplayName() {
        val video = asset()
        val key = LocalThumbnailKey.from(video)
        assertNotEquals(key, LocalThumbnailKey.from(video.copy(uri = "content://downloads/my_downloads/7")))
        assertNotEquals(key, LocalThumbnailKey.from(video.copy(sizeBytes = 2048)))
        assertNotEquals(key, LocalThumbnailKey.from(video.copy(systemUpdatedAt = 201)))
        assertEquals(key, LocalThumbnailKey.from(video.copy(displayName = "新名称")))
    }

    @Test fun unknownUpdateTimeFallsBackToIndexTimeAndUnknownSizeRemainsDistinct() {
        val video = asset().copy(systemUpdatedAt = null, sizeBytes = null)
        assertEquals(100L, LocalThumbnailKey.from(video).updatedAt)
        assertNotEquals(LocalThumbnailKey.from(video), LocalThumbnailKey.from(video.copy(indexedAt = 101)))
        assertNotEquals(LocalThumbnailKey.from(video), LocalThumbnailKey.from(video.copy(sizeBytes = 0)))
    }
}
