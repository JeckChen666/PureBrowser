package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests for the rule evaluator: faces, whitelisted actions, budget bail-out, DOM snapshot. */
class RuleEngineTest {
    private fun engine(vararg rules: String, now: () -> Long = System::nanoTime): RuleEngine =
        RuleEngine(RuleSet.parse("""{"version":1,"rules":[${rules.joinToString(",")}]}"""), now)

    @Test fun hostFaceMatchesSubdomainsAndRejectsOtherHosts() = runBlocking {
        val e = engine("""{"id":"h","version":1,"match":{"hosts":"(^|\\.)media\\.example$"},"actions":[{"type":"playerConfig","family":"kvs"}]}""")
        assertEquals(1, e.evaluate("https://www.media.example/watch/1", emptyList(), null).size)
        assertEquals(1, e.evaluate("https://media.example/watch/1", emptyList(), null).size)
        assertEquals(0, e.evaluate("https://other.example/watch/1", emptyList(), null).size)
        assertEquals(0, e.evaluate("about:blank", emptyList(), null).size)
    }

    @Test fun pathFaceMatchesAnySeenRequestIncludingThePageDocument() = runBlocking {
        val e = engine("""{"id":"p","version":1,"match":{"path":"\\/api\\/video\\.get"},"actions":[{"type":"manifestHint","kind":"unknown"}]}""")
        val findings = e.evaluate(
            "https://social.example/watch/1",
            listOf("https://social.example/api/video.get?v=1", "https://cdn.example/img.jpg"),
            null,
        )
        assertEquals(listOf("https://social.example/api/video.get?v=1"), findings.map { it.url })
        assertEquals(listOf(MediaKind.UNKNOWN), findings.map { it.kindHint })
        assertEquals(listOf("p"), findings.map { it.ruleId })
        assertEquals(0, e.evaluate("https://social.example/watch/1", listOf("https://cdn.example/img.jpg"), null).size)
    }

    @Test fun bothFacesMustPassWhenBothPresent() = runBlocking {
        val e = engine("""{"id":"both","version":1,"match":{"hosts":"(^|\\.)tube\\.example$","path":"viewkey=[A-Za-z0-9]+"},"actions":[{"type":"playerConfig","family":"flashvars"}]}""")
        assertEquals(1, e.evaluate("https://tube.example/x", listOf("https://tube.example/w?viewkey=ab12"), null).size)
        assertEquals(0, e.evaluate("https://tube.example/x", listOf("https://tube.example/other"), null).size)
        assertEquals(0, e.evaluate("https://elsewhere.example/w?viewkey=ab12", listOf("https://tube.example/w?viewkey=ab12"), null).size)
    }

    @Test fun manifestHintLabelsEachMatchedRequestWithTheHintedKind() = runBlocking {
        val e = engine("""{"id":"m","version":1,"match":{"path":"\\.m3u8"},"actions":[{"type":"manifestHint","kind":"hls"}]}""")
        val findings = e.evaluate("https://a.example", (1..12).map { "https://cdn.example/p$it.m3u8" }, null)
        assertEquals(8, findings.size) // per-rule URL cap
        assertTrue(findings.all { it.kindHint == MediaKind.HLS && it.url!!.endsWith(".m3u8") })
    }

    @Test fun domExtractReadsAttributesAndTitleSelectorFromTheSnapshot() = runBlocking {
        val e = engine("""{"id":"d","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},"actions":[{"type":"domExtract","selector":".player video","attribute":"src","titleSelector":"h1"}]}""")
        val snapshot: suspend (String) -> List<DomNode> = { selector ->
            when (selector) {
                ".player video" -> listOf(
                    DomNode(mapOf("src" to "https://cdn.example/clip.mp4", "poster" to "https://cdn.example/p.jpg")),
                    DomNode(mapOf("poster" to "https://cdn.example/only-poster.jpg")),
                )
                "h1" -> listOf(DomNode(emptyMap(), "  Clip One  "))
                else -> emptyList()
            }
        }
        val findings = e.evaluate("https://tube.example/watch/1", emptyList(), snapshot)
        assertEquals(listOf("https://cdn.example/clip.mp4"), findings.map { it.url })
        assertEquals(listOf("Clip One"), findings.map { it.title })
        assertEquals(listOf(MediaKind.FILE), findings.map { it.kindHint })
        assertTrue(findings.single().evidenceNote.contains("站点规则 d"))
    }

    @Test fun domExtractDropsNonHttpAttributeValues() = runBlocking {
        val e = engine("""{"id":"d","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},"actions":[{"type":"domExtract","selector":"video","attribute":"src"}]}""")
        val snapshot: suspend (String) -> List<DomNode> = { _ ->
            listOf(
                DomNode(mapOf("src" to "blob:https://tube.example/uuid")),
                DomNode(mapOf("src" to "javascript:alert(1)")),
                DomNode(mapOf("src" to "https://user:pass@evil.example/x.mp4")),
                DomNode(emptyMap()),
            )
        }
        val findings = e.evaluate("https://tube.example/watch/1", emptyList(), snapshot)
        // Nothing addressable: exactly the rule-hit marker, never an executed or invented URL.
        assertEquals(1, findings.size)
        assertNull(findings[0].url)
    }

    @Test fun matchedRuleWithoutExtractionLeavesOneMarkerFinding() = runBlocking {
        val e = engine("""{"id":"m","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},"actions":[{"type":"domExtract","selector":"video","attribute":"src"}]}""")
        val findings = e.evaluate("https://tube.example/watch/1", emptyList(), { _ -> emptyList() })
        assertEquals(1, findings.size)
        assertNull(findings[0].url)
        assertEquals("m", findings[0].ruleId)
    }

    @Test fun playerConfigExtractEmitsUrlLessFamilyMarker() = runBlocking {
        val e = engine("""{"id":"f","version":1,"match":{"path":"[?&]video_id=[0-9]+"},"actions":[{"type":"playerConfig","family":"flashvars"}]}""")
        val findings = e.evaluate("https://a.example/w?video_id=7", listOf("https://a.example/w?video_id=7"), null)
        assertEquals(1, findings.size)
        assertNull(findings[0].url)
        assertEquals(MediaKind.UNKNOWN, findings[0].kindHint)
        assertTrue(findings[0].evidenceNote.contains("flashvars"))
    }

    @Test fun budgetExhaustionBailsOutWithPartialResults() = runBlocking {
        var ticks = 0L
        val e = engine(
            """{"id":"one","version":1,"match":{"hosts":"a\\.example"},"actions":[{"type":"playerConfig","family":"kvs"}]}""",
            """{"id":"two","version":1,"match":{"hosts":"a\\.example"},"actions":[{"type":"playerConfig","family":"kvs"}]}""",
            now = { ticks += 10_000_000_000L; ticks },
        )
        // The first now() sets the deadline; the second already exceeds a 50 ms budget.
        assertEquals(0, e.evaluate("https://a.example/watch", emptyList(), null).size)
    }

    @Test fun generousBudgetStillEvaluatesEveryRule() = runBlocking {
        val e = engine(
            """{"id":"one","version":1,"match":{"hosts":"a\\.example"},"actions":[{"type":"playerConfig","family":"kvs"}]}""",
            """{"id":"two","version":1,"match":{"hosts":"a\\.example"},"actions":[{"type":"playerConfig","family":"kvs"}]}""",
        )
        assertEquals(2, e.evaluate("https://a.example/watch", emptyList(), null).size)
    }

    @Test fun totalFindingsAreCapped() = runBlocking {
        val rules = (1..40).map { index ->
            """{"id":"r$index","version":1,"match":{"hosts":"a\\.example"},"actions":[{"type":"manifestHint","kind":"unknown"}]}"""
        }
        val findings = engine(*rules.toTypedArray())
            .evaluate("https://a.example/watch", listOf("https://a.example/doc"), null)
        assertEquals(32, findings.size)
    }

    @Test fun requestsAreDeduplicatedAndCappedBeforeMatching() = runBlocking {
        val e = engine("""{"id":"p","version":1,"match":{"path":"\\.m3u8"},"actions":[{"type":"manifestHint","kind":"hls"}]}""")
        val many = (1..300).map { if (it % 2 == 0) "https://cdn.example/x.m3u8" else "https://cdn.example/other$it" }
        val findings = e.evaluate("https://a.example", many, null)
        assertEquals(1, findings.size)
    }
}
