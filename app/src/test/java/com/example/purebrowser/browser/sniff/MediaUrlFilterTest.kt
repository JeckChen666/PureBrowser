package com.example.purebrowser.browser.sniff

import org.junit.Assert.*
import org.junit.Test

class MediaUrlFilterTest {
    @Test fun matchesEveryKeywordFamilyCaseInsensitively() {
        listOf(
            "https://a.example/live/playlist.M3U8",
            "https://a.example/clip.Mp4",
            "https://cdn.example/stream.FLV",
            "https://a.example/media/v.WEBM",
            "https://a.example/movie.MKV",
            "https://a.example/api/playlist?id=7",
            "https://a.example/hls/segment-1",
            "https://a.example/dash/manifest.mpd",
            "https://a.example/getplayurl?vid=9",
        ).forEach { assertTrue(it, MediaUrlFilter.isMediaUrl(it)) }
    }
    @Test fun rejectsUrlsWithoutMediaKeywords() {
        listOf("", "about:blank", "https://a.example/page.html", "https://a.example/app.js", "https://a.example/media").forEach {
            assertFalse(it, MediaUrlFilter.isMediaUrl(it))
        }
    }
    @Test fun keywordListIsTheScriptContract() {
        assertEquals(listOf(".m3u8", ".mp4", ".flv", ".webm", ".mkv", "playlist", "/hls/", "manifest", "playurl"), MediaUrlFilter.KEYWORDS)
    }
}
