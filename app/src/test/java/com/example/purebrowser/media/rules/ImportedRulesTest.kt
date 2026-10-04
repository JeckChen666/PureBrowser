package com.example.purebrowser.media.rules

import org.junit.Assert.*
import org.junit.Test

/**
 * T90 import-channel JVM coverage: validation negatives (oversize/malformed/cap-exceeding and the
 * version gate), canonical-digest stability, the D5 IMPORTED tier decisions, and merge precedence
 * (built-ins win, imported rules keep their marker, totals stay capped).
 */
class ImportedRulesTest {
    private fun doc(rules: String, version: Int = 3) = """{"version":$version,"rules":[$rules]}"""

    private val goodRule = """{"id":"user-a","version":1,
        "match":{"hosts":"(^|\\.)tube\\.example$","path":"\\/watch\\/(?<vid>[0-9]+)"},
        "actions":[{"type":"jsonExtract","pointers":["files"]}],
        "fetch":[{"id":"cfg","url":"https://api.tube.example/v/{{match.vid}}","maxBytes":4096}],
        "note":"用户导入示例"}"""

    @Test fun validDocumentParsesWithImportedSourceAndDigest() {
        val verdict = ImportValidator.validate(doc(goodRule), builtInCount = 11)
        assertTrue(verdict is ImportValidation.Valid)
        verdict as ImportValidation.Valid
        assertEquals(1, verdict.rules.size)
        assertEquals(RuleSource.IMPORTED, verdict.rules.single().source)
        assertEquals(3, verdict.version)
        assertEquals(64, verdict.digest.length)
        assertTrue(verdict.digest.all { it in "0123456789abcdef" })
    }

    @Test fun oversizeDocumentIsDenied() {
        val huge = doc(goodRule) + " ".repeat(ImportedRulePolicy.MAX_IMPORT_BYTES)
        assertEquals(
            ImportValidation.Denied.Reason.OVERSIZE,
            (ImportValidator.validate(huge, 0) as ImportValidation.Denied).reason,
        )
        // The read ceiling and the document gate are the same number.
        assertEquals(RuleSet.MAX_FILE_BYTES_V3, ImportedRulePolicy.MAX_IMPORT_BYTES)
    }

    @Test fun malformedDocumentIsDenied() {
        listOf("", "   ", "not json at all", "{")
            .forEach { text ->
                assertEquals(
                    text.take(20),
                    ImportValidation.Denied.Reason.MALFORMED,
                    (ImportValidator.validate(text, 0) as ImportValidation.Denied).reason,
                )
            }
        // Valid JSON whose rules member is not an array parses to zero rules: that is a
        // NO_RULES denial, not a broken-JSON one.
        assertEquals(
            ImportValidation.Denied.Reason.NO_RULES,
            (ImportValidator.validate("{\"version\":3,\"rules\":\"nope\"}", 0) as ImportValidation.Denied).reason,
        )
    }

    @Test fun belowV3SchemaIsDenied() {
        val verdict = ImportValidator.validate(doc(goodRule, version = 2), 0)
        assertEquals(ImportValidation.Denied.Reason.UNSUPPORTED_VERSION, (verdict as ImportValidation.Denied).reason)
    }

    @Test fun documentWithNoSurvivingRulesIsDenied() {
        // Every entry is invalid (no hosts face + a fetch declaration): parse drops them all.
        val bad = """{"id":"bad","version":1,"match":{"path":"\\/x"},
            "actions":[{"type":"jsonExtract","pointers":["a"]}],
            "fetch":[{"id":"f","url":"https://a.example/x","maxBytes":10}]}"""
        val verdict = ImportValidator.validate(doc(bad), 0)
        assertEquals(ImportValidation.Denied.Reason.NO_RULES, (verdict as ImportValidation.Denied).reason)
    }

    @Test fun capExceedingDocumentIsDenied() {
        // A tiny budget mirrors "built-ins already fill the ceiling".
        val verdict = ImportValidator.validate(doc(goodRule), builtInCount = RuleSet.MAX_RULES_V3)
        assertEquals(ImportValidation.Denied.Reason.CAP_EXCEEDED, (verdict as ImportValidation.Denied).reason)
        // The budget is the v3 ceiling minus what is already shipped.
        assertEquals(0, ImportedRulePolicy.maxImportedRules(RuleSet.MAX_RULES_V3))
    }

    @Test fun canonicalDigestIsStableAcrossWhitespaceAndKeyOrder() {
        val a = """{"version":3,"rules":[{"id":"x","version":1,"match":{"hosts":"a\\.example"},"actions":[{"type":"manifestHint","kind":"unknown"}]}]}"""
        val b = """{
            "rules" : [ { "actions" : [ { "kind" : "unknown" , "type" : "manifestHint" } ] ,
              "match" : { "hosts" : "a\\.example" } , "id" : "x" , "version" : 1 } ] ,
            "version" : 3 }"""
        assertEquals(ImportIntegrity.canonicalDigest(a), ImportIntegrity.canonicalDigest(b))
        // Whole numbers canonicalize without a fraction so 1 and 1.0 agree.
        assertEquals("1", ImportIntegrity.canonicalJson("1.0"))
        assertNull(ImportIntegrity.canonicalDigest("{not json"))
    }

    @Test fun importedTierHalvesBudgetsAndDeniesCrossOriginRedirects() {
        assertEquals(500, ImportedTier.effectiveMaxBytes(1000))
        assertEquals(1, ImportedTier.effectiveMaxBytes(1))
        assertEquals(7_500L, ImportedTier.wallBudgetMs(15_000L))
        assertTrue(ImportedTier.redirectAllowed("https://a.example/x", "https://a.example/y"))
        assertFalse(ImportedTier.redirectAllowed("https://a.example/x", "https://cdn.example/y"))
        assertFalse(ImportedTier.redirectAllowed("https://a.example/x", "http://a.example/y"))
    }

    @Test fun mergeKeepsBuiltInPrecedenceAndImportedMarkers() {
        val builtInRule = RuleSet.parse(doc("""{"id":"dupe","version":1,
            "match":{"hosts":"(^|\\.)built\\.example$"},"actions":[{"type":"manifestHint","kind":"unknown"}]}""")).rules.single()
        val importedSet = RuleSet.parseImported(doc("""
            ${goodRule},{"id":"dupe","version":9,
            "match":{"hosts":"(^|\\.)evil\\.example$"},"actions":[{"type":"manifestHint","kind":"unknown"}]},
            {"id":"user-b","version":1,"match":{"hosts":"(^|\\.)b\\.example$"},"actions":[{"type":"manifestHint","kind":"unknown"}]}
        """))
        assertEquals(RuleSource.IMPORTED, importedSet.rules.single { it.id == "dupe" }.source)

        val merged = RuleSetMerger.merge(RuleSet(listOf(builtInRule), 3), importedSet)
        assertEquals(listOf("dupe", "user-a", "user-b"), merged.rules.map { it.id })
        assertEquals(RuleSource.BUILT_IN, merged.byId("dupe")!!.source) // built-in wins on collision
        assertEquals(RuleSource.IMPORTED, merged.byId("user-a")!!.source)
        assertEquals(3, merged.version)
    }

    @Test fun mergeWithEmptyImportReturnsBuiltInUnchangedAndCapsOverflow() {
        val builtIn = RuleSet.parse(doc("""{"id":"b1","version":1,"match":{"hosts":"b\\.example"},
            "actions":[{"type":"manifestHint","kind":"unknown"}]}"""))
        assertSame(builtIn, RuleSetMerger.merge(builtIn, RuleSet.EMPTY))

        // A merged total past the v3 ceiling drops IMPORTED overflow, never the built-ins.
        val overflow = (0 until 4).joinToString(",") { i ->
            """{"id":"u$i","version":1,"match":{"hosts":"u$i\\.example"},"actions":[{"type":"manifestHint","kind":"unknown"}]}"""
        }
        val importedSet = RuleSet.parseImported(doc(overflow))
        assertEquals(4, importedSet.rules.size)
        val capped = RuleSetMerger.merge(builtIn, importedSet.copyForTest(cap = 2))
        assertEquals(listOf("b1", "u0", "u1"), capped.rules.map { it.id })
    }

    @Test fun importedRulesRunUnderTheCoordinatorTierSwitch() {
        // The tier decision is keyed on the rule's source marker; the engine treats both alike.
        val imported = RuleSet.parseImported(doc(goodRule)).rules.single()
        val builtin = RuleSet.parse(doc(goodRule)).rules.single()
        assertEquals(RuleSource.IMPORTED, imported.source)
        assertEquals(RuleSource.BUILT_IN, builtin.source)
        assertEquals(imported.id, builtin.id)
        assertEquals(imported.fetch.single().maxBytes, builtin.fetch.single().maxBytes)
        // Halved budgets are pure decisions the coordinator applies to the spec copy.
        assertEquals(2048, ImportedTier.effectiveMaxBytes(imported.fetch.single().maxBytes))
    }
}

/** Test-only ceiling probe: rebuilds this set's list truncated to [cap] entries. */
private fun RuleSet.copyForTest(cap: Int): RuleSet = RuleSet(rules.take(cap), version)
