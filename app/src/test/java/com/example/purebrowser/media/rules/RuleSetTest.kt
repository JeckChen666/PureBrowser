package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.fingerprint.PlayerFamily
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests for the versioned rule loader: caps, regex compilation and the action whitelist. */
class RuleSetTest {
    private fun json(rules: String) = """{"version":1,"rules":[$rules]}"""
    private fun hint(id: String, hosts: String? = null, path: String? = null, kind: String = "unknown") =
        """{"id":"$id","version":1,"match":{${if (hosts != null) """"hosts":"$hosts",""" else ""}${if (path != null) """"path":"$path",""" else ""}},"actions":[{"type":"manifestHint","kind":"$kind"}]}"""

    @Test fun malformedJsonYieldsEmptySetWithoutThrowing() {
        assertEquals(0, RuleSet.parse("{not json").rules.size)
        assertEquals(0, RuleSet.parse("").rules.size)
        assertEquals(0, RuleSet.parse("""{"rules":"nope"}""").rules.size)
        assertEquals(0, RuleSet.parse("""[1,2,3]""").rules.size)
    }

    @Test fun invalidRegexRejectsOnlyThatRule() {
        val set = RuleSet.parse(json("""
            {"id":"bad","version":1,"match":{"hosts":"(["},"actions":[{"type":"manifestHint","kind":"hls"}]},
            ${hint("good", hosts = "media.example$")}
        """.trimIndent()))
        assertEquals(listOf("good"), set.rules.map { it.id })
        assertEquals(1, set.version)
    }

    @Test fun overlongPatternIsRejectedAtLoad() {
        val long = "a".repeat(513)
        val set = RuleSet.parse(json(hint("cap", path = long)))
        assertTrue(set.rules.isEmpty())
        val bounded = RuleSet.parse(json(hint("ok", path = "a".repeat(512))))
        assertEquals(listOf("ok"), bounded.rules.map { it.id })
    }

    @Test fun ruleCountIsCappedAtSixtyFour() {
        val rules = (1..70).joinToString(",") { hint("r$it", hosts = "h$it.example") }
        assertEquals(64, RuleSet.parse(json(rules)).rules.size)
    }

    @Test fun matchRequiresAtLeastOneFace() {
        assertTrue(RuleSet.parse(json("""{"id":"faceless","version":1,"match":{},"actions":[{"type":"manifestHint","kind":"hls"}]}""")).rules.isEmpty())
        assertTrue(RuleSet.parse(json("""{"id":"blankfaces","version":1,"match":{"hosts":"  ","path":""},"actions":[{"type":"manifestHint","kind":"hls"}]}""")).rules.isEmpty())
    }

    @Test fun unknownActionTypeIsSkippedButValidOnesSurvive() {
        val set = RuleSet.parse(json("""
            {"id":"mixed","version":1,"match":{"hosts":"a\\.example"},
             "actions":[{"type":"downloadPage"},{"type":"manifestHint","kind":"hls"},{"type":"captureHeaders"}]}
        """.trimIndent()))
        assertEquals(1, set.rules.size)
        assertEquals(listOf<RuleAction>(RuleAction.ManifestHint(MediaKind.HLS)), set.rules[0].actions)
    }

    @Test fun ruleWithOnlyUnknownActionsIsDropped() {
        val set = RuleSet.parse(json("""{"id":"empty","version":1,"match":{"hosts":"a\\.example"},"actions":[{"type":"nope"}]}"""))
        assertTrue(set.rules.isEmpty())
    }

    @Test fun unknownFamilyOrKindValuesSkipTheAction() {
        val set = RuleSet.parse(json("""
            {"id":"values","version":1,"match":{"hosts":"a\\.example"},
             "actions":[{"type":"playerConfig","family":"brandnew"},{"type":"manifestHint","kind":"mkv"},{"type":"manifestHint","kind":"dash"}]}
        """.trimIndent()))
        assertEquals(listOf(RuleAction.ManifestHint(MediaKind.DASH)), set.rules.single().actions)
    }

    @Test fun actionsParseIntoWhitelistedShapes() {
        val set = RuleSet.parse(json("""
            {"id":"full","version":2,"match":{"hosts":"v\\.example$","path":"\\/watch\\/[0-9]+"},
             "actions":[
               {"type":"domExtract","selector":".player video","attribute":"src","titleSelector":"h1"},
               {"type":"playerConfig","family":"kvs"},
               {"type":"manifestHint","kind":"hls"}],
             "note":"one line note"}
        """.trimIndent()))
        val rule = set.rules.single()
        assertEquals("full", rule.id)
        assertEquals(2, rule.version)
        assertEquals("one line note", rule.note)
        assertEquals(
            listOf(
                RuleAction.DomExtract(".player video", "src", "h1"),
                RuleAction.PlayerConfigExtract(PlayerFamily.KVS),
                RuleAction.ManifestHint(MediaKind.HLS),
            ),
            rule.actions,
        )
        assertNotNull(rule.match.hostsPattern)
        assertNotNull(rule.match.pathPattern)
        assertEquals("v\\.example$", rule.match.hosts)
        assertEquals("\\/watch\\/[0-9]+", rule.match.path)
    }

    @Test fun invalidSelectorRejectsItsActionAndThenTheRule() {
        val set = RuleSet.parse(json("""
            {"id":"injection","version":1,"match":{"hosts":"a\\.example"},
             "actions":[{"type":"domExtract","selector":"video)('; DROP","attribute":"src"}]}
        """.trimIndent()))
        assertTrue(set.rules.isEmpty())
    }

    @Test fun duplicateIdsKeepTheFirstEntry() {
        val set = RuleSet.parse(json(hint("dup", hosts = "first.example") + "," + hint("dup", hosts = "second.example")))
        assertEquals(1, set.rules.size)
        assertEquals("first.example", set.rules[0].match.hosts)
    }

    @Test fun oversizedDocumentIsRejectedEntirely() {
        val text = """{"note":"${"x".repeat(70_000)}","rules":[]}"""
        assertEquals(RuleSet.EMPTY, RuleSet.parse(text))
    }

    @Test fun byIdLooksUpCompiledRules() {
        val set = RuleSet.parse(json(hint("one", hosts = "a.example")))
        assertEquals("one", set.byId("one")?.id)
        assertNull(set.byId("missing"))
    }
}
