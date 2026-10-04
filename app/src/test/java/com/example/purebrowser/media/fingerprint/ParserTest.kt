package com.example.purebrowser.media.fingerprint

import com.example.purebrowser.media.MediaKind
import org.junit.Assert.*
import org.junit.Test

class ParserTest {
    @Test fun emptyInputYieldsNothing() {
        assertTrue(PlayerConfigParser.parse("", PlayerFamily.FLASHVARS).isEmpty())
        assertTrue(PlayerConfigParser.parse("   ", PlayerFamily.KVS).isEmpty())
        assertTrue(PlayerConfigParser.parseScript(" \n ").isEmpty())
    }

    @Test fun flashvarsMediaDefinitionsYieldOneSourcePerQuality() {
        val json = """
            {"flashvars_1":{"videoId":"42","mediaDefinitions":[
              {"videoUrl":"https://cdn.example.com/hls/master.m3u8","quality":"hls","format":"hls"},
              {"videoUrl":"https://cdn.example.com/dash/manifest.mpd","quality":"dash","format":"mpd"},
              {"videoUrl":"https://cdn.example.com/720.mp4","quality":"720","format":"mp4"},
              {"videoUrl":"https://cdn.example.com/360.mp4","quality":360,"format":"mp4"}]}}
        """.trimIndent()
        val sources = PlayerConfigParser.parse(json, PlayerFamily.FLASHVARS, origin = "embed-1")
        assertEquals(listOf("hls", "dash", "720", "360"), sources.map { it.qualityLabel })
        assertEquals(listOf(MediaKind.HLS, MediaKind.UNKNOWN, MediaKind.FILE, MediaKind.FILE), sources.map { it.kindHint })
        assertTrue(sources.all { it.family == PlayerFamily.FLASHVARS && it.origin == "embed-1" })
    }

    @Test fun flashvarsItemsWithoutQualityKeepNullLabel() {
        val json = """{"mediaDefinitions":[{"videoUrl":"https://cdn.example.com/a.mp4","format":"mp4"},{"videoUrl":"https://cdn.example.com/b.m3u8","format":"hls"}]}"""
        val sources = PlayerConfigParser.parse(json, PlayerFamily.FLASHVARS)
        assertEquals(listOf(null, null), sources.map { it.qualityLabel })
        assertEquals(listOf(MediaKind.FILE, MediaKind.HLS), sources.map { it.kindHint })
    }

    @Test fun kvsQualityMapsHarvestEveryPairUnderVideoUrlKeys() {
        val json = """
            {"config":{"player":{
              "video_url":{"240":"https://media.example.com/240.mp4","360":"https://media.example.com/360.mp4","720":"https://media.example.com/720.mp4","1080":"https://media.example.com/1080.mp4"},
              "video_alt_url0":{"low":"https://media.example.com/low.mp4","hd":"https://media.example.com/hd.flv"},
              "video_url_text":"1080","poster":"https://media.example.com/thumb.jpg"}}}
        """.trimIndent()
        val sources = PlayerConfigParser.parse(json, PlayerFamily.KVS, origin = "frame")
        assertEquals(listOf("240", "360", "720", "1080", "low", "hd"), sources.map { it.qualityLabel })
        assertTrue(sources.all { it.kindHint == MediaKind.FILE && it.family == PlayerFamily.KVS && it.origin == "frame" })
    }

    @Test fun kvsPlainStringVideoUrlIsHarvestedWithoutLabel() {
        val sources = PlayerConfigParser.parse("""{"video_url":"https://media.example.com/prime.mp4"}""", PlayerFamily.KVS)
        assertEquals(1, sources.size)
        assertNull(sources[0].qualityLabel)
        assertEquals(MediaKind.FILE, sources[0].kindHint)
    }

    @Test fun wrongFamilyGuessStillFindsSourcesViaGenericWalk() {
        val json = """{"video_url":{"240":"https://media.example.com/240.mp4","720":"https://media.example.com/720.mp4"}}"""
        val sources = PlayerConfigParser.parse(json, PlayerFamily.FLASHVARS)
        assertEquals(listOf("240", "720"), sources.map { it.qualityLabel })
    }

    @Test fun html5playerScriptCoversEverySetterForm() {
        val script = """
            html5player.setVideoUrlHigh('https://cdn.example.com/hd.mp4');
            html5player.setVideoUrlLow("https://cdn.example.com/sd.mp4");
            html5player.setVideoHLS('https://cdn.example.com/master.m3u8');
            setVideoUrl('https://cdn.example.com/prime.mp4');
        """.trimIndent()
        val sources = PlayerConfigParser.parseScript(script, origin = "watch")
        assertEquals(listOf("high", "low", null, null), sources.map { it.qualityLabel })
        assertEquals(listOf(MediaKind.FILE, MediaKind.FILE, MediaKind.HLS, MediaKind.FILE), sources.map { it.kindHint })
        assertTrue(sources.all { it.family == PlayerFamily.HTML5PLAYER && it.origin == "watch" })
    }

    @Test fun setVideoHlsForcesHlsKindEvenWithoutManifestExtension() {
        val sources = PlayerConfigParser.parseScript("setVideoHLS('https://cdn.example.com/playlist?fmt=m3u8')")
        assertEquals(1, sources.size)
        assertEquals(MediaKind.HLS, sources[0].kindHint)
    }

    @Test fun flvUrlParameterIsDecodedToDirectFile() {
        val script = """var fv = "flv_url=https%3A%2F%2Fcdn.example.com%2Fclip.flv%3Ftoken%3D1&poster=https://cdn.example.com/thumb.jpg";"""
        val sources = PlayerConfigParser.parseScript(script)
        assertEquals(listOf("https://cdn.example.com/clip.flv?token=1"), sources.map { it.url })
        assertNull(sources[0].qualityLabel)
        assertEquals(MediaKind.FILE, sources[0].kindHint)
        assertEquals(PlayerFamily.FLASHVARS, sources[0].family)
    }

    @Test fun genericJsonCollectsMediaUrlsWithSiblingAndOwnKeyLabels() {
        val json = """
            {"sources":[
              {"url":"https://cdn.example.com/a.mp4","quality":"1080"},
              {"url":"https://cdn.example.com/b.mp4","height":720},
              {"url":"https://cdn.example.com/c.m3u8"},
              {"file":"https://cdn.example.com/d.webm","resolution":"540p"}],
             "qualityMap":{"240":"https://cdn.example.com/e.mp4","720":"https://cdn.example.com/f.mp4"}}
        """.trimIndent()
        val sources = PlayerConfigParser.parse(json, PlayerFamily.STREAM_DATA)
        assertEquals(listOf("1080", "720", null, "540p", "240", "720"), sources.map { it.qualityLabel })
        assertEquals(listOf(MediaKind.FILE, MediaKind.FILE, MediaKind.HLS, MediaKind.FILE, MediaKind.FILE, MediaKind.FILE), sources.map { it.kindHint })
    }

    @Test fun truncatedJsonKeepsCompletedEntries() {
        val truncated = """{"mediaDefinitions":[{"videoUrl":"https://cdn.example.com/hd.mp4","quality":"720""""
        val sources = PlayerConfigParser.parse(truncated, PlayerFamily.FLASHVARS)
        assertEquals(1, sources.size)
        assertEquals("720", sources[0].qualityLabel)
    }

    @Test fun unparseableTextIsSalvagedByRegexScan() {
        val garbage = """boot failed at 'https://cdn.example.com/a.mp4' then "720":"https://cdn.example.com/b.mp4" escaped"""
        val sources = PlayerConfigParser.parse(garbage, PlayerFamily.UNKNOWN)
        assertEquals(
            listOf("https://cdn.example.com/b.mp4" to "720", "https://cdn.example.com/a.mp4" to null),
            sources.map { it.url to it.qualityLabel },
        )
    }

    @Test fun walkIsDepthBoundedAndHostileNestingStaysBounded() {
        val shallow = "[".repeat(4) + "\"https://cdn.example.com/deep.mp4\"" + "]".repeat(4)
        assertEquals(1, PlayerConfigParser.parse(shallow, PlayerFamily.UNKNOWN).size)
        val beyondBound = "[".repeat(10) + "\"https://cdn.example.com/deep.mp4\"" + "]".repeat(10)
        assertTrue(PlayerConfigParser.parse(beyondBound, PlayerFamily.UNKNOWN).isEmpty())
        val hostile = "[".repeat(500) + "\"https://cdn.example.com/deep.mp4\"" + "]".repeat(500)
        assertEquals(1, PlayerConfigParser.parse(hostile, PlayerFamily.UNKNOWN).size)
    }

    @Test fun relativeUrlsResolveAgainstBaseUrlOnlyForSupportedForms() {
        val script = """
            html5player.setVideoUrlHigh('//cdn.example.com/hd.mp4');
            html5player.setVideoUrlLow('/files/sd.mp4');
            setVideoUrl('media/clip.mp4');
        """.trimIndent()
        val resolved = PlayerConfigParser.parseScript(script, baseUrl = "https://page.example:8443/watch/1")
        assertEquals(listOf("https://cdn.example.com/hd.mp4", "https://page.example:8443/files/sd.mp4"), resolved.map { it.url })
        assertTrue(PlayerConfigParser.parseScript(script).isEmpty())
    }

    @Test fun duplicateUrlsCollapseToFirstDiscoveredLabel() {
        val json = """{"a":{"url":"https://cdn.example.com/dup.mp4","quality":"1080"},"b":{"url":"https://cdn.example.com/dup.mp4","quality":"720"}}"""
        val sources = PlayerConfigParser.parse(json, PlayerFamily.UNKNOWN)
        assertEquals(1, sources.size)
        assertEquals("1080", sources[0].qualityLabel)
    }

    @Test fun resultsAreCappedAtThirtyTwoEntries() {
        val json = (1..40).joinToString(",", "[", "]") { """{"url":"https://cdn.example.com/v$it.mp4"}""" }
        assertEquals(32, PlayerConfigParser.parse(json, PlayerFamily.UNKNOWN).size)
    }

    @Test fun unsafeAndMalformedUrlsAreRejected() {
        val json = """{"a":"javascript:alert(1)","b":"data:video/mp4;base64,QUJD","c":"   ","d":"https://user:pw@cdn.example.com/x.mp4","e":"https://cdn.example.com/ha s.mp4","f":"https://cdn.example.com/ok.mp4"}"""
        assertEquals(listOf("https://cdn.example.com/ok.mp4"), PlayerConfigParser.parse(json, PlayerFamily.UNKNOWN).map { it.url })
        assertTrue(PlayerConfigParser.parseScript("html5player.setVideoUrlHigh('javascript:alert(1)');setVideoUrl('data:text/html,xx')").isEmpty())
    }
}
