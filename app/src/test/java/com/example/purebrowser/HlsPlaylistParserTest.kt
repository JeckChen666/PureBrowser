package com.example.purebrowser

import com.example.purebrowser.download.hls.HlsParseException
import com.example.purebrowser.download.hls.HlsPlaylist
import com.example.purebrowser.download.hls.HlsPlaylistParser
import com.example.purebrowser.download.hls.HlsSegment
import com.example.purebrowser.download.hls.HlsVariant
import org.junit.Assert.*
import org.junit.Test

class HlsPlaylistParserTest {
    private val base = "https://media.example/path/index.m3u8?parent=secret%2Btoken"
    private fun parse(body: String, url: String = base) = HlsPlaylistParser.parse(body.trimIndent(), url)
    private fun media(entries: String = "#EXTINF:1,\nsegment.ts", extra: String = "", target: Int = 10) =
        "#EXTM3U\n#EXT-X-TARGETDURATION:$target\n$extra\n$entries\n#EXT-X-ENDLIST\n"
    private fun master(attrs: String, uri: String = "video.m3u8", extra: String = "") =
        "#EXTM3U\n$extra\n#EXT-X-STREAM-INF:$attrs\n$uri\n"
    private fun rejected(body: String, url: String = base): HlsParseException {
        try {
            parse(body, url)
            fail("Expected a safe HlsParseException")
        } catch (e: HlsParseException) {
            assertTrue(e.safeReason.isNotBlank())
            assertEquals(e.safeReason, e.message)
            assertNull(e.cause)
            assertFalse(e.toString().contains("https://"))
            assertFalse(e.toString().contains("secret"))
            return e
        }
        throw AssertionError("Expected parser rejection")
    }
    private fun variant(id: String, height: Int? = null, bandwidth: Long? = null, width: Int? = height?.let { it * 16 / 9 },
        supported: Boolean = true) = HlsVariant("https://example.test/$id?secret=token", bandwidth, width, height,
        null, supported, if (supported) null else "不支持")

    @Test fun endedMediaHasExactMicrosecondsAndZeroBasedIndexes() {
        val result = parse(media("#EXTINF:1.234567,first\na.ts\n#EXTINF:2.000001,second\nb.ts")) as HlsPlaylist.Media
        assertEquals(3_234_568L, result.durationUs)
        assertEquals(10_000_000L, result.targetDurationUs)
        assertEquals(listOf(0, 1), result.segments.map { it.index })
        assertEquals(listOf(1_234_567L, 2_000_001L), result.segments.map { it.durationUs })
    }

    @Test fun harmlessMetadataBomCrLfCommentsAndEndedEventAreAccepted() {
        val body = media("#EXTINF:1,\n# a comment\na.ts", """
            #EXT-X-VERSION:6
            #EXT-X-MEDIA-SEQUENCE:1234
            #EXT-X-PLAYLIST-TYPE:EVENT
            #EXT-X-ALLOW-CACHE:NO
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-START:TIME-OFFSET=-1.5,PRECISE=NO
            #EXT-X-PROGRAM-DATE-TIME:2026-10-01T08:00:00.000+08:00
            #EXT-X-DATERANGE:ID="chapter",START-DATE="2026-10-01T08:00:00Z",DURATION=1,X-TITLE="hello, world"
        """.trimIndent())
        val result = parse("\uFEFF" + body.replace("\n", "\r\n")) as HlsPlaylist.Media
        assertEquals(1, result.segments.size)
        assertEquals(0, result.segments.single().index)
    }

    @Test fun relativeUrisUseFinalManifestAndNeverGraftParentQuery() {
        val result = parse(media("#EXTINF:1,\n../a.ts?child=a%2Bb&x=1%2F2\n#EXTINF:1,\nb.ts\n#EXTINF:1,\n/root/c.ts")) as HlsPlaylist.Media
        assertEquals("https://media.example/a.ts?child=a%2Bb&x=1%2F2", result.segments[0].url)
        assertEquals("https://media.example/path/b.ts", result.segments[1].url)
        assertEquals("https://media.example/root/c.ts", result.segments[2].url)
        val redirected = parse(master("BANDWIDTH=500", "child.m3u8?own=secret%2Bvalue"), "https://cdn.example/new/final.m3u8?parent=discard") as HlsPlaylist.Master
        assertEquals("https://cdn.example/new/child.m3u8?own=secret%2Bvalue", redirected.variants.single().url)
    }

    @Test fun absoluteProtocolRelativeAndQueryOnlyReferencesKeepSignedOctets() {
        val result = parse(media("#EXTINF:1,\nhttps://cdn.example/a%2Fb.ts?k=secret%2B%2F&k=2\n#EXTINF:1,\n//other.example/b.ts?q=2\n#EXTINF:1,\n?seg=secret%2B3")) as HlsPlaylist.Media
        assertEquals("https://cdn.example/a%2Fb.ts?k=secret%2B%2F&k=2", result.segments[0].url)
        assertEquals("https://other.example/b.ts?q=2", result.segments[1].url)
        assertEquals("https://media.example/path/index.m3u8?seg=secret%2B3", result.segments[2].url)
    }

    @Test fun extensionlessSegmentsAreTentativeButExplicitOtherContainersAreRejected() {
        assertEquals("https://media.example/path/resource?token=secret", (parse(media("#EXTINF:1,\nresource?token=secret")) as HlsPlaylist.Media).segments.single().url)
        listOf("piece.m4s", "piece.MP4?x=1", "piece.aac", "piece.m4a", "piece.mp3", "piece.vtt", "piece%2Emp4").forEach {
            rejected(media("#EXTINF:1,\n$it"))
        }
    }

    @Test fun headerNonemptySegmentsEndlistAndTargetDurationAreMandatory() {
        listOf("", "# not a manifest", media().replace("#EXTM3U\n", ""), "#EXTM3U\n#EXT-X-ENDLIST",
            media().replace("#EXT-X-ENDLIST", ""), media().replace("#EXT-X-TARGETDURATION:10", ""),
            media().replace("#EXTM3U", "#EXTM3U\n#EXTM3U")).forEach { rejected(it) }
    }

    @Test fun extinfMustPairWithExactlyOneUriAndCannotFollowEndlist() {
        listOf(media("a.ts"), media("#EXTINF:1,\n#EXTINF:1,\na.ts"), media("#EXTINF:1,"),
            media("#EXTINF:1\na.ts"), media("#EXTINF:1,\na.ts\nb.ts"), media() + "#EXTINF:1,\nb.ts\n",
            media() + "#EXT-X-ENDLIST\n").forEach { rejected(it) }
    }

    @Test fun durationsRejectNonfiniteNegativeZeroExponentOverflowAndSubmicrosecondValues() {
        listOf("NaN", "Infinity", "-1", "0", "1e3", "+1", ".5", "0.0000001", "86401",
            "999999999999999999999999999999999999999999999999").forEach {
            rejected(media("#EXTINF:$it,\na.ts"))
        }
        listOf("0", "-1", "NaN", "1.5", "86401", "99999999999999999999999999").forEach {
            rejected(media().replace("TARGETDURATION:10", "TARGETDURATION:$it"))
        }
    }

    @Test fun durationSumIsBoundedAndTargetUsesNearestWholeSecond() {
        val boundary = parse(media("#EXTINF:86400,\na.ts", target = 86400)) as HlsPlaylist.Media
        assertEquals(HlsPlaylistParser.MAX_DURATION_US, boundary.durationUs)
        rejected(media("#EXTINF:43200,\na.ts\n#EXTINF:43200.000001,\nb.ts", target = 86400))
        assertEquals(10_490_000L, (parse(media("#EXTINF:10.49,\na.ts")) as HlsPlaylist.Media).durationUs)
        rejected(media("#EXTINF:10.5,\na.ts"))
        assertEquals(1L, (parse(media("#EXTINF:0.0000005,\na.ts")) as HlsPlaylist.Media).durationUs)
    }

    @Test fun allEncryptionIsRejectedAndOnlyCleanMethodNoneIsAccepted() {
        listOf("AES-128", "SAMPLE-AES", "SAMPLE-AES-CTR", "UNKNOWN").forEach {
            rejected(media(extra = "#EXT-X-KEY:METHOD=$it,URI=\"https://example.test/key?secret=value\""))
            rejected(master("BANDWIDTH=100", extra = "#EXT-X-SESSION-KEY:METHOD=$it,URI=\"key\""))
        }
        assertEquals(1, (parse(media(extra = "#EXT-X-KEY:METHOD=NONE")) as HlsPlaylist.Media).segments.size)
        rejected(media(extra = "#EXT-X-KEY:URI=\"key\""))
        rejected(media(extra = "#EXT-X-KEY:METHOD=NONE,URI=\"key\""))
        rejected(media(extra = "#EXT-X-KEY:METHOD=NONE,METHOD=NONE"))
    }

    @Test fun excludedMediaFeaturesCannotBeIgnored() {
        listOf("#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-BYTERANGE:100@0", "#EXT-X-DISCONTINUITY",
            "#EXT-X-DISCONTINUITY-SEQUENCE:0", "#EXT-X-GAP", "#EXT-X-I-FRAMES-ONLY").forEach {
            rejected(media(extra = it))
        }
    }

    @Test fun lowLatencyVariablesAndUnrecognizedCriticalTagsAreRejected() {
        listOf("#EXT-X-PART:DURATION=1,URI=\"part.ts\"", "#EXT-X-PART-INF:PART-TARGET=1",
            "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"next.ts\"", "#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES",
            "#EXT-X-RENDITION-REPORT:URI=\"other.m3u8\"", "#EXT-X-SKIP:SKIPPED-SEGMENTS=1",
            "#EXT-X-DEFINE:NAME=\"token\",VALUE=\"secret\"", "#EXT-X-UNKNOWN:URI=\"secret\"",
            "#EXT-X-CONTENT-STEERING:SERVER-URI=\"steering\"").forEach { rejected(media(extra = it)) }
        rejected(media("#EXTINF:1,\n{\$token}.ts"))
        rejected(master("BANDWIDTH=100,CODECS=\"{\$codecs}\""))
        rejected(media(extra = "#EXT-X-DATERANGE:ID=\"ad\",START-DATE=\"2026-10-01T00:00:00Z\",CLASS=\"com.apple.hls.interstitial\""))
    }

    @Test fun quotedCommasAndKnownMasterMetadataAreParsedWithoutSplittingValues() {
        val result = parse(master("BANDWIDTH=500000,AVERAGE-BANDWIDTH=450000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\",FRAME-RATE=29.97,CLOSED-CAPTIONS=NONE",
            extra = "#EXT-X-SESSION-DATA:DATA-ID=\"description\",VALUE=\"one,two\"\n#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100,URI=\"preview.m3u8\"")) as HlsPlaylist.Master
        val v = result.variants.single()
        assertEquals(500000L, v.bandwidth)
        assertEquals(1280, v.width)
        assertEquals(720, v.height)
        assertEquals("avc1.4d401f,mp4a.40.2", v.codecs)
        assertTrue(v.supported)
        assertNull(v.unsupportedReason)
    }

    @Test fun missingCodecsAndMissingReliableMetadataRemainTentative() {
        val v = (parse(master("X-HINT=\"unknown\"")) as HlsPlaylist.Master).variants.single()
        assertTrue(v.supported)
        assertNull(v.codecs)
        assertNull(v.bandwidth)
        assertNull(v.width)
        assertNull(v.height)
        assertEquals(123L, (parse(master("AVERAGE-BANDWIDTH=123")) as HlsPlaylist.Master).variants.single().bandwidth)
    }

    @Test fun explicitOtherCodecFamiliesAreMarkedUnsupportedNotSelected() {
        listOf("hvc1.1.6.L93.B0,mp4a.40.2", "hev1.1,mp4a.40.2", "av01.0.08M.08,mp4a.40.2", "avc1.4d401f,ac-3", "vp09.00.10.08,opus").forEach {
            val v = (parse(master("CODECS=\"$it\"")) as HlsPlaylist.Master).variants.single()
            assertFalse(v.supported)
            assertNotNull(v.unsupportedReason)
            assertNull(HlsPlaylistParser.defaultVariant(listOf(v)))
        }
        assertTrue((parse(master("CODECS=\"avc3.4d401f,mp4a.40.5\"")) as HlsPlaylist.Master).variants.single().supported)
    }

    @Test fun attributesRejectDuplicatesUnclosedQuotesTrailingGarbageAndWrongTypes() {
        listOf("BANDWIDTH=100,BANDWIDTH=200", "BANDWIDTH=100,", "BANDWIDTH=100,  ", "BANDWIDTH=",
            "BANDWIDTH=100,,RESOLUTION=1x1", "bandwidth=100", "BANDWIDTH=\"100\"", "CODECS=avc1.4d401f",
            "CODECS=\"avc1.4d401f", "CODECS=\"avc1.4d401f\"garbage", "CODECS=\"\"", "CODECS=\"avc1,,mp4a\"",
            "CODECS=\"avc1..bad\"", "BANDWIDTH=1=2", "AUDIO=\"a\",AUDIO=\"b\"").forEach { rejected(master(it)) }
        rejected(media(extra = "#EXT-X-DATERANGE:ID=\"a\",ID=\"b\",START-DATE=\"2026-10-01T00:00:00Z\""))
    }

    @Test fun resolutionAndBitrateValuesMustBeValidPositiveBoundedIntegers() {
        listOf("RESOLUTION=0x720", "RESOLUTION=1280x0", "RESOLUTION=1280X720", "RESOLUTION=1280x720x2",
            "RESOLUTION=-1x720", "RESOLUTION=2147483648x720", "RESOLUTION=\"1280x720\"",
            "BANDWIDTH=0", "BANDWIDTH=-10", "BANDWIDTH=1.5", "BANDWIDTH=9223372036854775808",
            "AVERAGE-BANDWIDTH=NaN", "FRAME-RATE=Infinity").forEach { rejected(master(it)) }
    }

    @Test fun masterStreamInfMustPairWithUriAndMediaTagsMustNotMix() {
        listOf("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\n#EXT-X-STREAM-INF:BANDWIDTH=200\nv.m3u8",
            master("BANDWIDTH=100") + "#EXT-X-TARGETDURATION:10\n", media(extra = "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"audio\""),
            "#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1,URI=\"preview.m3u8\"").forEach { rejected(it) }
        assertEquals(1, (parse(master("BANDWIDTH=100", "# comment\n\nchild.m3u8")) as HlsPlaylist.Master).variants.size)
    }

    @Test fun externalAudioOnlyDisablesReferencingVariantEvenWhenDeclaredAfterIt() {
        val result = parse("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=100,AUDIO="foreign"
            separate.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=200
            muxed.m3u8
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="foreign",NAME="main",DEFAULT=YES,URI="audio.m3u8?secret=audio"
        """) as HlsPlaylist.Master
        assertFalse(result.variants[0].supported)
        assertTrue(result.variants[1].supported)
        assertSame(result.variants[1], HlsPlaylistParser.defaultVariant(result.variants))
        assertFalse(result.variants[0].unsupportedReason!!.contains("secret"))
    }

    @Test fun uriLessAudioGroupIsMuxedButAnyExternalMemberOrMissingGroupIsUnsupported() {
        val group = "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"muxed\",DEFAULT=YES"
        assertTrue((parse(master("AUDIO=\"a\"", extra = group)) as HlsPlaylist.Master).variants.single().supported)
        assertFalse((parse(master("AUDIO=\"missing\"", extra = group)) as HlsPlaylist.Master).variants.single().supported)
        val mixed = "$group\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"external\",URI=\"audio.m3u8\""
        assertFalse((parse(master("AUDIO=\"a\"", extra = mixed)) as HlsPlaylist.Master).variants.single().supported)
        rejected(master("AUDIO=\"a\"", extra = "$group\n$group"))
    }

    @Test fun subtitleSelectionAndSeparateVideoGroupsAreUnsupportedWithoutBlockingOtherVariants() {
        val subtitles = "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",URI=\"subs.m3u8\""
        assertFalse((parse(master("SUBTITLES=\"subs\"", extra = subtitles)) as HlsPlaylist.Master).variants.single().supported)
        assertTrue((parse(master("BANDWIDTH=100", extra = subtitles)) as HlsPlaylist.Master).variants.single().supported)
        assertFalse((parse(master("CLOSED-CAPTIONS=\"cc\"")) as HlsPlaylist.Master).variants.single().supported)
        assertFalse((parse(master("VIDEO=\"separate\"")) as HlsPlaylist.Master).variants.single().supported)
    }

    @Test fun urlSchemesCredentialsFragmentsMalformedAddressesAndPortsAreRejectedSafely() {
        listOf("file:///tmp/a.ts", "data:video/mp2t,secret", "javascript:secret()", "content://media/secret",
            "http://foreign.example/secret", "https://user:secret@example.test/a.ts", "//user:secret@example.test/a.ts",
            "https://example.test:0/a.ts", "https://example.test:65536/a.ts", "https://example.test/a.ts#secret",
            "https://example.test/a ts", "https://example.test/%secret", "https:///secret", "https:secret").forEach {
            rejected(media("#EXTINF:1,\n$it"))
            rejected(master("BANDWIDTH=100", it))
            rejected(media(), it)
        }
        rejected(media("#EXTINF:1,\na\u0000secret.ts"))
    }

    @Test fun debugNamedLoopbackHttpCanParseButRequestPolicyStillDecidesAuthorization() {
        listOf("127.0.0.1", "10.0.2.2").forEach {
            val url = "http://$it:8765/path/index.m3u8?secret=parent"
            assertEquals("http://$it:8765/path/segment.ts", (parse(media(), url) as HlsPlaylist.Media).segments.single().url)
        }
        val secure = parse(media("#EXTINF:1,\na.ts"), "HTTPS://media.example/path/index.m3u8") as HlsPlaylist.Media
        assertEquals("HTTPS://media.example/path/a.ts", secure.segments.single().url)
    }

    @Test fun exactAndOverUtf8ByteLimitsIncludeMultibyteText() {
        val prefix = "#EXTM3U\n#"; val suffix = "\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n"
        val count = HlsPlaylistParser.MAX_PLAYLIST_BYTES - prefix.toByteArray().size - suffix.toByteArray().size
        val exact = prefix + "x".repeat(count) + suffix
        assertEquals(HlsPlaylistParser.MAX_PLAYLIST_BYTES, exact.toByteArray().size)
        assertEquals(1, (parse(exact) as HlsPlaylist.Media).segments.size)
        rejected(exact + "x")
        rejected(prefix + "界".repeat(count / 3 + 1) + suffix)
    }

    @Test fun maxVariantsAndMaxSegmentsAreInclusiveAndOneMoreIsRejected() {
        fun manyVariants(count: Int) = "#EXTM3U\n" + (0 until count).joinToString("\n") { "#EXT-X-STREAM-INF:BANDWIDTH=${it + 1}\nv$it.m3u8" }
        assertEquals(32, (parse(manyVariants(32)) as HlsPlaylist.Master).variants.size)
        rejected(manyVariants(33))
        fun manySegments(count: Int) = media((0 until count).joinToString("\n") { "#EXTINF:1,\ns$it.ts" }, target = 1)
        val parsed = parse(manySegments(10000)) as HlsPlaylist.Media
        assertEquals(10000, parsed.segments.size)
        assertEquals(9999, parsed.segments.last().index)
        rejected(manySegments(10001))
    }

    @Test fun urlLimitAppliesBothBeforeAndAfterRelativeResolution() {
        val prefix = "https://media.example/"
        val exact = prefix + "x".repeat(HlsPlaylistParser.MAX_URL_LENGTH - prefix.length)
        assertEquals(exact, (parse(media("#EXTINF:1,\n$exact")) as HlsPlaylist.Media).segments.single().url)
        rejected(media("#EXTINF:1,\n${exact}x"))
        rejected(media(), "${exact}x")
        val longRelative = "x".repeat(HlsPlaylistParser.MAX_URL_LENGTH)
        rejected(media("#EXTINF:1,\n$longRelative"))
        rejected(master("BANDWIDTH=100", longRelative))
    }

    @Test fun defaultSelectsHighestResolutionAtMost1080ThenHighestBitrate() {
        val choices = listOf(variant("720fast", 720, 9_000_000), variant("1080slow", 1080, 2_000_000),
            variant("2160", 2160, 50_000_000), variant("1080fast", 1080, 4_000_000), variant("unknown", bandwidth = 99_000_000))
        assertSame(choices[3], HlsPlaylistParser.defaultVariant(choices))
        assertSame(choices[0], HlsPlaylistParser.defaultVariant(listOf(choices[0], choices[1].copy(supported = false))))
    }

    @Test fun defaultForAllOver1080UsesLowestResolutionRegardlessOfBitrate() {
        val choices = listOf(variant("2160", 2160, 1_000), variant("1440", 1440, 20_000), variant("4320", 4320, 10))
        assertSame(choices[1], HlsPlaylistParser.defaultVariant(choices))
        val faster = choices[1].copy(bandwidth = 30_000)
        assertSame(faster, HlsPlaylistParser.defaultVariant(choices + faster))
    }

    @Test fun defaultForUnknownResolutionsUsesMiddleReliableBandwidthOtherwiseFirst() {
        val choices = listOf(variant("high", bandwidth = 900), variant("missing"), variant("low", bandwidth = 100),
            variant("middle", bandwidth = 500))
        assertSame(choices[3], HlsPlaylistParser.defaultVariant(choices))
        assertSame(choices[0], HlsPlaylistParser.defaultVariant(listOf(choices[0], choices[2]))) // upper middle
        val unknown = listOf(variant("first"), variant("second", bandwidth = 0))
        assertSame(unknown[0], HlsPlaylistParser.defaultVariant(unknown))
        val mixed = listOf(variant("unknown"), variant("high", 1440, 100))
        assertSame(mixed[0], HlsPlaylistParser.defaultVariant(mixed))
        assertNull(HlsPlaylistParser.defaultVariant(emptyList()))
        assertNull(HlsPlaylistParser.defaultVariant(listOf(variant("excluded", supported = false))))
    }

    @Test fun exposedPlaylistCollectionsAreDefensiveReadOnlySnapshots() {
        val variants = mutableListOf(variant("one"))
        val master = HlsPlaylist.Master(variants)
        variants.clear()
        assertEquals(1, master.variants.size)
        try { (master.variants as MutableList<HlsVariant>).clear(); fail("Master list is mutable") } catch (_: UnsupportedOperationException) { }
        val segments = mutableListOf(HlsSegment("https://example.test/a?secret=token", 1, 0))
        val media = HlsPlaylist.Media(segments, 1, 1)
        segments.clear()
        assertEquals(1, media.segments.size)
        try { (media.segments as MutableList<HlsSegment>).clear(); fail("Media list is mutable") } catch (_: UnsupportedOperationException) { }
        assertEquals(master, HlsPlaylist.Master(master.variants))
        assertEquals(media, HlsPlaylist.Media(media.segments, 1, 1))
    }

    @Test fun publicDiagnosticsAndEveryUrlModelRedactSignedUrlsAndInputMetadata() {
        val v = variant("secret", 1080, 500).copy(codecs = "secret-codecs", unsupportedReason = "secret-reason")
        val segment = HlsSegment("https://example.test/secret?token=secret", 1, 0)
        listOf(v.toString(), segment.toString(), HlsPlaylist.Master(listOf(v)).toString(),
            HlsPlaylist.Media(listOf(segment), 1, 1).toString()).forEach {
            assertFalse(it.contains("secret"))
            assertFalse(it.contains("https://"))
        }
        val failure = rejected(master("BANDWIDTH=secret"))
        assertFalse(failure.safeReason.contains("secret"))
        assertFalse(failure.stackTraceToString().contains("parent=secret"))
        rejected(media("#EXTINF:1,\nhttps://user:secret@example.test/a.ts"))
    }

    @Test fun malformedKnownMetadataAndRepeatedSingletonTagsFailSafely() {
        listOf("#EXT-X-VERSION:NaN", "#EXT-X-VERSION:3\n#EXT-X-VERSION:4", "#EXT-X-TARGETDURATION:10",
            "#EXT-X-INDEPENDENT-SEGMENTS:secret", "#EXT-X-MEDIA-SEQUENCE:-1", "#EXT-X-PLAYLIST-TYPE:LIVE",
            "#EXT-X-PROGRAM-DATE-TIME:secret", "#EXT-X-DATERANGE:ID=\"a\"", "#EXT-X-START:TIME-OFFSET=NaN",
            "#EXT-X-START:TIME-OFFSET=0,PRECISE=MAYBE").forEach { rejected(media(extra = it)) }
        rejected(master("BANDWIDTH=1", extra = "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"b\",DEFAULT=MAYBE"))
        rejected(master("BANDWIDTH=1", extra = "#EXT-X-SESSION-DATA:DATA-ID=\"a\",VALUE=\"v\",URI=\"metadata\""))
    }
    @org.junit.Test fun explicitAudioOnlyAndVideoOnlyVariantsAreUnsupported() {
        for(codec in listOf("mp4a.40.2","avc1.4d401f")) {
            val m=HlsPlaylistParser.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100,CODECS=\"$codec\"\nv.m3u8\n","https://source.example/master.m3u8") as HlsPlaylist.Master
            org.junit.Assert.assertFalse(m.variants.single().supported)
        }
    }

}
