package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Generic-host fixtures mirroring the four shipped schema-v3 target rules (T89): embed-page regex
 * extraction (archive shape), inline `__NEXT_DATA__`-style regex extraction (TED shape), a
 * videoinfo-API jsonExtract over a formatversion-2 document (Wikimedia shape) and a player-metadata
 * jsonExtract over a qualities map (Dailymotion shape). Every fixture uses `.example` hosts; the
 * real-site evidence lives in the V016 cross-site harnesses, never here.
 */
class SiteRulesV3TargetsTest {
    private fun v3(rules: String) = """{"version":3,"rules":[$rules]}"""

    // --- Archive shape: fetch the item's embed page, regex the direct download addresses. ---
    private val archiveRule = """{"id":"archive-shape","version":1,
        "match":{"hosts":"(^|\\.)archive\\.example$","path":"\\/details\\/([A-Za-z0-9_.-]+)"},
        "actions":[{"type":"regexExtract","pattern":"(https:\\/\\/archive\\.example\\/download\\/[^\"\\s]+\\.mp4)"}],
        "fetch":[{"id":"embed","url":"https://archive.example/embed/{{m1}}","maxBytes":131072}],
        "note":"条目页：受控抓取内嵌播放页，正则提取下载直链"}"""

    // --- TED shape: no fetch, regex over the harvested inline JSON block. ---
    private val tedRule = """{"id":"ted-shape","version":1,
        "match":{"hosts":"(^|\\.)talks\\.example$","path":"\\/talks\\/[A-Za-z0-9_-]+"},
        "actions":[{"type":"regexExtract","pattern":"\"hlsUrl\"\\s*:\\s*\"(https:\\/\\/[^\"]+\\.m3u8[^\"]*)\""}],
        "note":"演讲页：读取内联结构数据中的 HLS 主清单地址"}"""

    // --- Wikimedia shape: videoinfo API jsonExtract with a captured File: title group. ---
    private val commonsRule = """{"id":"commons-shape","version":1,
        "match":{"hosts":"(^|\\.)wikimedia\\.example$","path":"\\/wiki\\/(File:[A-Za-z0-9_%.,()+-]+)"},
        "actions":[{"type":"jsonExtract","pointers":[
            "query.pages[0].videoinfo[0].url",
            "query.pages[0].videoinfo[0].derivatives[0].src",
            "query.pages[0].videoinfo[0].derivatives[1].src"]}],
        "fetch":[{"id":"videoinfo","url":"https://commons.wikimedia.example/w/api.php?action=query&format=json&formatversion=2&prop=videoinfo&viprop=url%7Cderivatives&titles={{m1}}","maxBytes":65536}],
        "note":"文件页：受控抓取 videoinfo 接口，提取原始与转码直链"}"""

    // --- Dailymotion shape: player-metadata API jsonExtract over the qualities map. ---
    private val dmRule = """{"id":"dm-shape","version":1,
        "match":{"hosts":"(^|\\.)dm\\.example$","path":"\\/video\\/([a-z0-9]+)"},
        "actions":[{"type":"jsonExtract","pointers":["qualities.auto[0].url"]}],
        "fetch":[{"id":"metadata","url":"https://www.dm.example/player/metadata/video/{{m1}}","maxBytes":65536}],
        "note":"视频页：受控抓取播放器元数据接口，提取 HLS 主清单"}"""

    @Test fun allFourShapesParseWithFetchAndNoSession() {
        val set = RuleSet.parse(v3("$archiveRule,$tedRule,$commonsRule,$dmRule"))
        assertEquals(setOf("archive-shape", "ted-shape", "commons-shape", "dm-shape"), set.rules.map { it.id }.toSet())
        set.rules.forEach { rule ->
            assertNull("no fixture rule declares a login session", rule.session)
            assertNotNull("fetch-bearing rules keep a hosts face", rule.match.hosts)
        }
        assertEquals(1, set.byId("archive-shape")!!.fetch.size)
        assertEquals(0, set.byId("ted-shape")!!.fetch.size)
        assertEquals(1, set.byId("commons-shape")!!.fetch.size)
        assertEquals(1, set.byId("dm-shape")!!.fetch.size)
        set.rules.flatMap { it.fetch }.forEach { spec ->
            assertEquals("GET", spec.method)
            assertTrue(spec.urlTemplate.startsWith("https://"))
            assertTrue(spec.maxBytes in 1..RuleSet.MAX_FETCH_BYTES)
        }
    }

    @Test fun archiveShapeExtractsDirectFileFormatsFromFetchedEmbedPage() = runBlocking {
        val embedHtml = """
            <script>jwplayer("player").setup({sources:[{file:"https://archive.example/download/item123/video_512kb.mp4"}]});</script>
        """.trimIndent()
        var requestedUrl: String? = null
        val engine = RuleEngine(RuleSet.parse(v3(archiveRule)))
        val findings = engine.evaluate(
            "https://archive.example/details/item123",
            listOf("https://archive.example/details/item123"),
            null,
            fetcher = { _, url ->
                requestedUrl = url.value
                RuleDocument(url.value, 200, embedHtml, "text/html")
            },
        )
        assertEquals("template must render the path group into the embed fetch", "https://archive.example/embed/item123", requestedUrl)
        val finding = findings.first { it.ruleId == "archive-shape" && it.url != null }
        val format = finding.formats.single()
        assertEquals("https://archive.example/download/item123/video_512kb.mp4", format.url)
        assertEquals("mp4", format.ext)
        assertEquals(MediaKind.FILE, format.kind)
    }

    @Test fun tedShapeExtractsHlsMasterFromInlineJsonBlock() = runBlocking {
        val inline = """{"props":{"pageProps":{"videoData":{"hlsUrl":"https://cdn.talks.example/masters/42/manifest.m3u8?src=p"}}}}"""
        val engine = RuleEngine(RuleSet.parse(v3(tedRule)))
        val findings = engine.evaluate(
            "https://www.talks.example/talks/some_talk",
            listOf("https://www.talks.example/talks/some_talk"),
            null,
            inlineData = { listOf(RuleInlineContent(RuleInlineContent.Kind.JSON, inline)) },
        )
        val finding = findings.first { it.ruleId == "ted-shape" && it.url != null }
        val format = finding.formats.single()
        assertEquals("https://cdn.talks.example/masters/42/manifest.m3u8?src=p", format.url)
        assertEquals(MediaKind.HLS, format.kind)
    }

    @Test fun commonsShapeExtractsOriginalAndTranscodedFiles() = runBlocking {
        val apiBody = """
            {"batchcomplete":true,"query":{"pages":[{"title":"File:Clip.webm","videoinfo":[
              {"url":"https://upload.wikimedia.example/7/7c/Clip.webm","derivatives":[
                 {"src":"https://upload.wikimedia.example/transcoded/7/7c/Clip.webm/Clip.webm.240p.webm"},
                 {"src":"https://upload.wikimedia.example/transcoded/7/7c/Clip.webm/Clip.webm.360p.webm"}]}]}]}}
        """.trimIndent()
        var requestedUrl: String? = null
        val engine = RuleEngine(RuleSet.parse(v3(commonsRule)))
        val findings = engine.evaluate(
            "https://commons.wikimedia.example/wiki/File:Clip.webm",
            listOf("https://commons.wikimedia.example/wiki/File:Clip.webm"),
            null,
            fetcher = { _, url ->
                requestedUrl = url.value
                RuleDocument(url.value, 200, apiBody, "application/json")
            },
        )
        assertNotNull(requestedUrl)
        assertTrue("title group lands in the titles parameter", requestedUrl!!.endsWith("titles=File:Clip.webm"))
        val finding = findings.first { it.ruleId == "commons-shape" && it.url != null }
        assertEquals(
            listOf(
                "https://upload.wikimedia.example/7/7c/Clip.webm",
                "https://upload.wikimedia.example/transcoded/7/7c/Clip.webm/Clip.webm.240p.webm",
                "https://upload.wikimedia.example/transcoded/7/7c/Clip.webm/Clip.webm.360p.webm",
            ),
            finding.formats.map { it.url },
        )
        assertTrue(finding.formats.all { it.ext == "webm" && it.kind == MediaKind.FILE })
    }

    @Test fun dmShapeExtractsQualitiesAutoMasterAndToleratesMissingHeights() = runBlocking {
        val meta = """
            {"title":"clip","qualities":{"auto":[{"type":"application/x-mpegURL",
              "url":"https://cdn.dm.example/manifest/video/xyz.m3u8?sec=abc"}]},
             "duration":21}
        """.trimIndent()
        var requestedUrl: String? = null
        val engine = RuleEngine(RuleSet.parse(v3(dmRule)))
        val findings = engine.evaluate(
            "https://www.dm.example/video/xyz",
            listOf("https://www.dm.example/video/xyz", "https://www.dm.example/player/metadata/video/xyz"),
            null,
            fetcher = { _, url ->
                requestedUrl = url.value
                RuleDocument(url.value, 200, meta, "application/json")
            },
        )
        assertEquals("https://www.dm.example/player/metadata/video/xyz", requestedUrl)
        val finding = findings.first { it.ruleId == "dm-shape" && it.url != null }
        val format = finding.formats.single()
        assertEquals("https://cdn.dm.example/manifest/video/xyz.m3u8?sec=abc", format.url)
        assertEquals(MediaKind.HLS, format.kind)
        assertNull("auto pointer carries no height hint", format.height)
    }

    @Test fun digitLeadingQualityKeysAreInvalidPointersAndRejectTheAction() {
        // The Dailymotion API keys qualities by digits (144/380/...); the pointer grammar forbids
        // digit-leading segments, so only letter-leading keys (auto) are expressible. A rule that
        // tries a digit key loses the whole action — and with no actions left, the whole rule.
        val bad = """{"id":"bad","version":1,
            "match":{"hosts":"(^|\\.)dm\\.example$","path":"\\/video\\/([a-z0-9]+)"},
            "actions":[{"type":"jsonExtract","pointers":["qualities.480[0].url"]}],
            "fetch":[{"id":"metadata","url":"https://www.dm.example/player/metadata/video/{{m1}}","maxBytes":1024}]}"""
        assertEquals(0, RuleSet.parse(v3(bad)).rules.size)
    }

    @Test fun unboundPlaceholderRejectsTheFetchRule() {
        // The Wikimedia path face must capture the File: title; without the group the {{m1}}
        // template placeholder has no binding and the loader rejects the rule.
        val unbound = commonsRule.replace("""\\/wiki\\/(File:""", """\\/wiki\\/File:""")
        assertEquals(0, RuleSet.parse(v3(unbound)).rules.size)
    }

    // --- PeerTube shape: instance video API jsonExtract over the files array. ---
    private val peertubeRule = """{"id":"pt-shape","version":1,
        "match":{"hosts":"(^|\\.)video\\.tube\\.example$","path":"\\/w\\/([0-9A-Za-z_-]{22,36})"},
        "actions":[{"type":"jsonExtract","pointers":[
            "files[0].fileDownloadUrl","files[1].fileDownloadUrl","files[2].fileDownloadUrl"]}],
        "fetch":[{"id":"video","url":"https://video.tube.example/api/v1/videos/{{m1}}","maxBytes":65536}],
        "note":"PeerTube 观看页：受控抓取实例视频接口，提取各分辨率直链"}"""

    @Test fun peertubeShapeExtractsPerResolutionDirectFiles() = runBlocking {
        val api = """
            {"name":"clip","files":[
              {"resolution":{"id":1080},"fileDownloadUrl":"https://video.tube.example/download/c-1080.mp4"},
              {"resolution":{"id":480},"fileDownloadUrl":"https://video.tube.example/download/c-480.mp4"},
              {"resolution":{"id":240},"fileDownloadUrl":"https://video.tube.example/download/c-240.mp4"}]}
        """.trimIndent()
        var requestedUrl: String? = null
        val engine = RuleEngine(RuleSet.parse(v3(peertubeRule)))
        val findings = engine.evaluate(
            "https://video.tube.example/w/9BVuB3GdejTFi37jMHuB2k",
            emptyList(),
            null,
            fetcher = { _, url ->
                requestedUrl = url.value
                RuleDocument(url.value, 200, api, "application/json")
            },
        )
        assertEquals("https://video.tube.example/api/v1/videos/9BVuB3GdejTFi37jMHuB2k", requestedUrl)
        val finding = findings.first { it.ruleId == "pt-shape" && it.url != null }
        assertEquals(3, finding.formats.size)
        assertEquals(listOf("https://video.tube.example/download/c-1080.mp4",
            "https://video.tube.example/download/c-480.mp4",
            "https://video.tube.example/download/c-240.mp4"), finding.formats.map { it.url })
        assertTrue(finding.formats.all { it.kind == MediaKind.FILE && it.ext == "mp4" })
    }

    @Test fun mainDocumentAloneBindsThePathFaceEvenWithNoObservedRequests() = runBlocking {
        // The engine appends the page URL to the path face's input by contract: the WebView's
        // main-frame interception races the page-epoch flip, so a page whose detail address is
        // the only path-face match must still render and fetch its templates.
        val engine = RuleEngine(RuleSet.parse(v3(archiveRule)))
        val findings = engine.evaluate(
            "https://archive.example/details/item999",
            emptyList(),
            null,
            fetcher = { _, url -> RuleDocument(url.value, 200, "src=\"https://archive.example/download/item999/v.mp4\"", "text/html") },
        )
        val finding = findings.first { it.ruleId == "archive-shape" && it.url != null }
        assertEquals("https://archive.example/download/item999/v.mp4", finding.formats.single().url)
    }
}
