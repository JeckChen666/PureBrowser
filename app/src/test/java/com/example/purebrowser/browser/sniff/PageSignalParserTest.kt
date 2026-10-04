package com.example.purebrowser.browser.sniff

import org.junit.Assert.*
import org.junit.Test

class PageSignalParserTest {
    @Test fun parsesEachReportType() {
        assertEquals(PageSignal.MediaUrl("https://cdn.example/hls/index.m3u8?sig=1"), PageSignalParser.parse("""{"type":"mediaUrl","url":"https://cdn.example/hls/index.m3u8?sig=1"}"""))
        assertEquals(PageSignal.MseMime("video/webm; codecs=\"vp9\""), PageSignalParser.parse("""{"type":"mseMime","mime":"video/webm; codecs=\"vp9\""}"""))
        assertEquals(PageSignal.BlobManifest("#EXTM3U\nline\tone", true), PageSignalParser.parse("""{"type":"blobManifest","content":"#EXTM3U\nline\tone","truncated":true}"""))
        assertEquals(PageSignal.PlayerConfig("flashvars", """{"video_url":"https://cdn.example/v.mp4"}"""), PageSignalParser.parse("""{"type":"playerConfig","family":"flashvars","raw":"{\"video_url\":\"https://cdn.example/v.mp4\"}"}"""))
        assertEquals(PageSignal.IframeSrc("https://embed.example/frame"), PageSignalParser.parse("""{"type":"iframeSrc","url":"https://embed.example/frame"}"""))
    }
    @Test fun decodesStringEscapesIncludingUnicode() {
        assertEquals(PageSignal.MediaUrl("https://a.example/\u00e9 movie.mp4"), PageSignalParser.parse("""{"type":"mediaUrl","url":"https://a.example/\u00e9 movie.mp4"}"""))
        assertEquals(PageSignal.MediaUrl("a/b\\c"), PageSignalParser.parse("""{"type":"mediaUrl","url":"a\/b\\c"}"""))
    }
    @Test fun malformedJsonYieldsNull() {
        listOf("", "   ", "{", "garbage", "{\"type\":\"mediaUrl\"", """{"type":"mediaUrl","url":"u"}extra""", "[1,2]", """{"type":"mediaUrl","url":"u",}""").forEach {
            assertNull(it, PageSignalParser.parse(it))
        }
    }
    @Test fun wrongOrMissingFieldTypesYieldNull() {
        listOf(
            """{}""",
            """{"url":"https://a.example/v.mp4"}""",
            """{"type":"mediaUrl"}""",
            """{"type":"mediaUrl","url":123}""",
            """{"type":"mseMime","mime":true}""",
            """{"type":"blobManifest","content":"x"}""",
            """{"type":"blobManifest","content":"x","truncated":"yes"}""",
            """{"type":"playerConfig","family":"flashvars"}""",
            """{"type":"playerConfig","family":7,"raw":"x"}""",
            """{"type":"iframeSrc","url":null}""",
            """{"type":"unknown","url":"https://a.example/v.mp4"}""",
        ).forEach { assertNull(it, PageSignalParser.parse(it)) }
    }
    @Test fun toleratesWhitespaceNumbersAndDuplicateKeys() {
        assertEquals(PageSignal.IframeSrc("https://a.example/embed"), PageSignalParser.parse("""{ "type" : "iframeSrc" , "url" : "https://a.example/embed" , "seq" : 1.5e3 }"""))
        assertEquals(PageSignal.IframeSrc("https://a.example/embed"), PageSignalParser.parse("""{"type":"mseMime","type":"iframeSrc","url":"https://a.example/embed"}"""))
    }
}
