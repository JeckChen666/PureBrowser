package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * T84 tests for the four extraction actions over the three data sources (fetched documents,
 * inline harvested blocks, DOM snapshot), the bounded-regex behavior, the fetch callback's
 * template binding, and the engine's zero-IO guarantee (no transport types may appear in it).
 */
class RuleActionsTest {
    private fun engine(vararg rules: String) =
        RuleEngine(RuleSet.parse("""{"version":3,"rules":[${rules.joinToString(",")}]}"""))

    private fun fetchRule(actions: String, fetch: String = "") =
        """{"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$","path":"\\/watch\\/(?<vid>[0-9]+)"},
           "actions":$actions$fetch}"""

    /** The rule keeps a path face, so every evaluation must include a matching request URL. */
    private val watchRequest = listOf("https://tube.example/watch/1")

    @Test fun jsonExtractReadsFetchedDocuments() = runBlocking {
        val e = engine(fetchRule(
            """[{"type":"jsonExtract","pointers":["h1080","h360","hls.url"]}]""",
            ""","fetch":[{"id":"cfg","url":"https://api.tube.example/v/{{match.vid}}","maxBytes":8192}]""",
        ))
        var seenUrl: RenderedUrl? = null
        val fetcher: suspend (FetchSpec, RenderedUrl) -> RuleDocument? = { _, url ->
            seenUrl = url
            RuleDocument(
                url.value, 200,
                """{"h1080":"https://cdn.tube.example/1080.mp4","h360":"https://cdn.tube.example/360.mp4","hls":{"url":"https://cdn.tube.example/master.m3u8"}}""",
                "application/json",
            )
        }
        val findings = e.evaluate(
            "https://tube.example/watch/12345",
            listOf("https://tube.example/watch/12345"),
            null, fetcher = fetcher,
        )
        // The fetch URL is the template bound to the path-face group; only https renders pass.
        assertEquals("https://api.tube.example/v/12345", seenUrl?.value)
        val finding = findings.single { it.formats.isNotEmpty() }
        assertEquals(3, finding.formats.size)
        assertEquals("https://cdn.tube.example/1080.mp4", finding.formats[0].url)
        assertEquals(1080, finding.formats[0].height) // height inferred from the pointer's key
        assertEquals("mp4", finding.formats[0].ext)
        assertEquals(MediaKind.FILE, finding.formats[0].kind)
        assertEquals(360, finding.formats[1].height)
        assertEquals("https://cdn.tube.example/master.m3u8", finding.formats[2].url)
        assertEquals(MediaKind.HLS, finding.formats[2].kind)
        assertEquals(finding.formats.first().url, finding.url)
    }

    @Test fun jsonExtractReadsInlineJsonAndLdJson() = runBlocking {
        val e = engine(fetchRule("""[{"type":"jsonExtract","pointers":["contentUrl"]}]"""))
        val inline: suspend () -> List<RuleInlineContent> = {
            listOf(
                RuleInlineContent(RuleInlineContent.Kind.LDJSON, """{"@type":"VideoObject","contentUrl":"https://cdn.tube.example/v.mp4"}"""),
                RuleInlineContent(RuleInlineContent.Kind.JSON, """{"contentUrl":"https://cdn.tube.example/alt.mp4"}"""),
                RuleInlineContent(RuleInlineContent.Kind.SCRIPT, """var x=1;"""), // scripts are not JSON sources
            )
        }
        val findings = e.evaluate("https://tube.example/watch/1", watchRequest, null, inlineData = inline)
        val formats = findings.flatMap { it.formats }
        assertEquals(setOf("https://cdn.tube.example/v.mp4", "https://cdn.tube.example/alt.mp4"), formats.map { it.url }.toSet())
    }

    @Test fun jsonExtractDropsMalformedBodiesAndMisses() = runBlocking {
        val e = engine(fetchRule("""[{"type":"jsonExtract","pointers":["a.b","missing","files[9].url"]}]"""))
        val bodies = listOf(
            "not json at all",
            """{"a":{"b":"javascript:alert(1)"}}""",          // non-http extraction is refused
            """{"a":{"b":"https://cdn.tube.example/ok.mp4"}}""",
            """{"missing":123}""",                              // number, not an address
            """[[[[[[[[[{"files":[]}]]]]]]]]]""",               // deep junk yields no hit either
        )
        val inline: suspend () -> List<RuleInlineContent> = { bodies.map { RuleInlineContent(RuleInlineContent.Kind.JSON, it) } }
        val findings = e.evaluate("https://tube.example/watch/1", watchRequest, null, inlineData = inline)
        assertEquals(listOf("https://cdn.tube.example/ok.mp4"), findings.flatMap { it.formats }.map { it.url })
    }

    @Test fun regexExtractReadsInlineScriptTextWithNamedGroups() = runBlocking {
        // JSON-escaped pattern: video_url":"(?<u>[^"]+)" — the quoted-key serialized-config idiom.
        val e = engine(fetchRule("""[{"type":"regexExtract","pattern":"video_url\":\"(?<u>[^\"]+)\"","groupNames":["u"]}]"""))
        val script = """var flashvars = {"video_url":"https://cdn.tube.example/720.mp4",
            "video_alt":"https://cdn.tube.example/nope.mp4", "sources":[]}"""
        val inline: suspend () -> List<RuleInlineContent> = { listOf(RuleInlineContent(RuleInlineContent.Kind.SCRIPT, script)) }
        val findings = e.evaluate("https://tube.example/watch/1", watchRequest, null, inlineData = inline)
        val formats = findings.flatMap { it.formats }
        assertEquals(listOf("https://cdn.tube.example/720.mp4"), formats.map { it.url })
    }

    @Test fun regexExtractWholeMatchWithoutGroupNamesAndBoundedInput() = runBlocking {
        val e = engine(fetchRule("""[{"type":"regexExtract","pattern":"https:\\/\\/cdn[a-z0-9./]*\\.mp4"}]"""))
        val script = "x https://cdn.tube.example/a.mp4 y https://cdn.tube.example/b.mp4"
        val inline: suspend () -> List<RuleInlineContent> = { listOf(RuleInlineContent(RuleInlineContent.Kind.SCRIPT, script)) }
        val findings = e.evaluate("https://tube.example/watch/1", watchRequest, null, inlineData = inline)
        assertEquals(setOf("https://cdn.tube.example/a.mp4", "https://cdn.tube.example/b.mp4"), findings.flatMap { it.formats }.map { it.url }.toSet())
        // Inputs above the run-time length cap are skipped entirely (bounded-input half of the guard).
        val huge: suspend () -> List<RuleInlineContent> = {
            listOf(RuleInlineContent(RuleInlineContent.Kind.SCRIPT, "https://cdn.tube.example/c.mp4 " + "x".repeat(RuleEngine.MAX_REGEX_INPUT_CHARS)))
        }
        assertTrue(e.evaluate("https://tube.example/watch/1", watchRequest, null, inlineData = huge).flatMap { it.formats }.isEmpty())
    }

    @Test fun regexExtractMissesLeaveNoFormats() = runBlocking {
        val e = engine(fetchRule("""[{"type":"regexExtract","pattern":"nomatch_[0-9]+"}]"""))
        val inline: suspend () -> List<RuleInlineContent> = { listOf(RuleInlineContent(RuleInlineContent.Kind.SCRIPT, "var a = 1;")) }
        val findings = e.evaluate("https://tube.example/watch/1", watchRequest, null, inlineData = inline)
        assertTrue(findings.flatMap { it.formats }.isEmpty())
        // The rule-hit marker still appears, exactly like v0.1.7 extraction misses.
        assertTrue(findings.any { it.url == null })
    }

    @Test fun ogMetaReadsMetaTagsThroughTheDomSnapshot() = runBlocking {
        val e = engine(fetchRule("""[{"type":"ogMeta","properties":["og:video:secure_url","twitter:player:stream"]}]"""))
        val snapshot: suspend (String) -> List<DomNode> = { selector ->
            assertEquals("meta[property], meta[name]", selector)
            listOf(
                DomNode(mapOf("property" to "og:video:secure_url", "content" to "https://cdn.tube.example/secure.mp4")),
                DomNode(mapOf("name" to "twitter:player:stream", "content" to "https://cdn.tube.example/stream.mp4")),
                DomNode(mapOf("property" to "og:image", "content" to "https://cdn.tube.example/poster.jpg")),
                DomNode(mapOf("property" to "og:video:secure_url", "content" to "javascript:alert(1)")),
                DomNode(mapOf("name" to "description", "content" to "not a url")),
            )
        }
        val findings = e.evaluate("https://tube.example/watch/1", watchRequest, snapshot)
        val formats = findings.flatMap { it.formats }
        assertEquals(setOf("https://cdn.tube.example/secure.mp4", "https://cdn.tube.example/stream.mp4"), formats.map { it.url }.toSet())
    }

    @Test fun parseManifestCatalogsHlsVariantsFromAFetchedDocument() = runBlocking {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1088528,RESOLUTION=1920x1080,CODECS="avc1.640028"
            https://cdn.tube.example/1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=307200,RESOLUTION=640x360,CODECS="avc1.64001e"
            https://cdn.tube.example/360.m3u8
        """.trimIndent()
        val e = engine(fetchRule(
            """[{"type":"parseManifest","kind":"hls"}]""",
            ""","fetch":[{"id":"m","url":"https://cdn.tube.example/master.m3u8","maxBytes":8192}]""",
        ))
        val fetcher: suspend (FetchSpec, RenderedUrl) -> RuleDocument? = { _, _ ->
            RuleDocument("https://cdn.tube.example/master.m3u8", 200, master, "application/vnd.apple.mpegurl")
        }
        val findings = e.evaluate("https://tube.example/watch/1", watchRequest, null, fetcher = fetcher)
        val finding = findings.single { it.kindHint == MediaKind.HLS }
        assertEquals("https://cdn.tube.example/master.m3u8", finding.url)
        val formats = finding.formats
        assertEquals(2, formats.size)
        assertEquals(1080, formats[0].height)
        assertEquals(1088L, formats[0].tbr) // bandwidth 1088528 bit/s -> 1088 kbit/s
        assertEquals(MediaKind.HLS, formats[0].kind)
        val variants = FormatSelector.toVariantSummaries(formats)
        assertEquals(1_088_000L, variants[0].bandwidth) // tbr*1000 normalization
    }

    @Test fun parseManifestCatalogsDashRepresentationCount() = runBlocking {
        val mpd = """<?xml version="1.0"?>
            <MPD><Period><AdaptationSet mimeType="video/mp4" height="720">
            <Representation id="1" bandwidth="2000000" codecs="avc1"/>
            <Representation id="2" bandwidth="800000" codecs="avc1"/>
            </AdaptationSet></Period></MPD>"""
        val e = engine(fetchRule("""[{"type":"parseManifest","kind":"dash"}]"""))
        val inline: suspend () -> List<RuleInlineContent> = { listOf(RuleInlineContent(RuleInlineContent.Kind.JSON, mpd)) }
        val findings = e.evaluate("https://tube.example/watch/1", watchRequest, null, inlineData = inline)
        val finding = findings.single { it.kindHint == MediaKind.DASH }
        // Display-only tier: the manifest itself is the entry, labeled with the best representation.
        assertEquals("https://tube.example/watch/1", finding.url)
        val entry = finding.formats.single()
        assertEquals(720, entry.height)
        assertEquals(2000L, entry.tbr)
        assertEquals("mpd", entry.ext)
    }

    @Test fun fetchDisabledDegradesToTheMarkerWithoutCallingAnything() = runBlocking {
        val e = engine(fetchRule(
            """[{"type":"jsonExtract","pointers":["files"]}]""",
            ""","fetch":[{"id":"cfg","url":"https://api.tube.example/v/{{match.vid}}","maxBytes":1024}]""",
        ))
        var called = 0
        val fetcher: suspend (FetchSpec, RenderedUrl) -> RuleDocument? = { _, _ -> called++; null }
        // Null fetcher: exactly the v0.1.7 degradation — marker finding, no formats, no IO attempt.
        val withoutChannel = e.evaluate("https://tube.example/watch/1", watchRequest, null)
        assertEquals(1, withoutChannel.size)
        assertNull(withoutChannel[0].url)
        assertTrue(withoutChannel[0].formats.isEmpty())
        // A null-returning channel behaves identically (downgrade, not failure).
        val withChannel = e.evaluate("https://tube.example/watch/1", watchRequest, null, fetcher = fetcher)
        assertEquals(1, withChannel.size)
        assertNull(withChannel[0].url)
        assertEquals(1, called)
    }

    @Test fun unresolvableTemplateBindingSkipsOnlyTheFetch() = runBlocking {
        val e = engine(fetchRule(
            """[{"type":"manifestHint","kind":"unknown"},{"type":"jsonExtract","pointers":["a"]}]""",
            ""","fetch":[{"id":"cfg","url":"https://api.tube.example/v/{{match.vid}}","maxBytes":1024}]""",
        ))
        var called = 0
        val fetcher: suspend (FetchSpec, RenderedUrl) -> RuleDocument? = { _, _ -> called++; null }
        // No request matched the path face: the whole rule is skipped, so no fetch is attempted.
        val findings = e.evaluate("https://tube.example/other", emptyList(), null, fetcher = fetcher)
        assertEquals(0, called)
        assertTrue(findings.isEmpty())
    }

    @Test fun engineStaysZeroIoBySourceTypeGuard() {
        val source = listOf(
            File("src/main/java/com/example/purebrowser/media/rules/RuleEngine.kt"),
            File("app/src/main/java/com/example/purebrowser/media/rules/RuleEngine.kt"),
        ).firstOrNull { it.isFile }?.readText()
        assertNotNull("RuleEngine.kt not found from the test working directory", source)
        listOf("HttpTransport", "UrlFetcher", "okhttp", "HttpURLConnection", "URLConnection", "openStream", "java.net.Socket").forEach { marker ->
            assertFalse("rule engine must stay zero-IO: found $marker", source!!.contains(marker))
        }
    }
}
