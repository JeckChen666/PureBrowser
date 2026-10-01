package com.example.purebrowser.media

import org.junit.Assert.*
import org.junit.Test

class MediaClassifierTest {
    @Test fun signedUppercaseFileUsesPathNotQuery() {
        assertEquals(MediaKind.FILE, MediaClassifier.classify("https://cdn.example/video.MP4?sig=abc&name=.m3u8"))
    }
    @Test fun detectsManifestsAndMimeOnlyResources() {
        assertEquals(MediaKind.HLS, MediaClassifier.classify("https://cdn.example/playlist.m3u8?token=1"))
        assertEquals(MediaKind.DASH, MediaClassifier.classify("https://cdn.example/manifest.mpd"))
        assertEquals(MediaKind.HLS, MediaClassifier.classify("https://cdn.example/api/play", "Application/Vnd.Apple.MpegUrl; charset=UTF-8"))
        assertEquals(MediaKind.FILE, MediaClassifier.classify("https://cdn.example/api/play", "video/webm"))
    }
    @Test fun excludesSegmentsAndNonVideos() {
        listOf("part.ts", "seg.m4s", "captions.vtt", "init.mp4", "chunk-0001.mp4", "segment_12.mp4", "photo.jpg").forEach {
            assertNull("$it should not be a movie", MediaClassifier.classify("https://cdn.example/$it"))
        }
        assertNull(MediaClassifier.classify("https://cdn.example/resource", "video/mp2t"))
    }
    @Test fun localAndExtensionlessDomMediaAreNotDirectFiles() {
        assertEquals(MediaKind.LOCAL, MediaClassifier.classify("blob:https://site.example/123", videoElement = true))
        assertNull(MediaClassifier.classify("blob:https://site.example/123"))
        assertEquals(MediaKind.UNKNOWN, MediaClassifier.classify("https://site.example/stream", videoElement = true))
    }
    @Test fun rejectsUnsafeUrlsAndOversizedInput() {
        listOf("file:///movie.mp4", "javascript:alert(1)", "https://user:secret@site.example/movie.mp4", "not-a-url", "https://site.example/" + "a".repeat(8192)).forEach {
            assertNull(MediaClassifier.classify(it, "video/mp4"))
        }
    }
}
