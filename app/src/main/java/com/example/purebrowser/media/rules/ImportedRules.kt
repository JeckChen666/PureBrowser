package com.example.purebrowser.media.rules

import com.example.purebrowser.download.RequestPolicy
import java.security.MessageDigest

/**
 * T90 user rule-import channel: pure validation, integrity and merge decisions. Everything here is
 * JVM-only data work — the file picker, storage and consent UI live in the settings layer, and the
 * network side stays inside the coordinator's controlled channel.
 *
 * D9 "签名校验" honesty note: v0.1.8 shipped NO signing infrastructure (no key distribution, no
 * update channel, nothing in-app to verify an asymmetric signature against) and surfaced only a
 * deterministic SHA-256 of the CANONICALIZED document for manual verification next to an explicit
 * consent dialog. v0.1.9 (T101) adds real Ed25519 verification — one maintainer public key is
 * embedded as an app asset, and an import document carrying a `signature` envelope is verified
 * against it in-app ("已签名(<keyid>)"); UNSIGNED documents keep the exact v0.1.8 SHA-256 consent
 * flow, and a FAILED verification is labeled as tamper-suspect rather than silently trusted.
 *
 * D5 IMPORTED tier: imported rules run under tighter budgets than built-ins — maxBytes halved,
 * wall clock halved, cross-origin redirects always denied. The decisions are pure functions here
 * ([ImportedTier]); the coordinator applies them when the matched rule's source is IMPORTED.
 */
object ImportedRulePolicy {
    /** Hard read ceiling for one import file; identical to the v3 document-size gate. */
    const val MAX_IMPORT_BYTES = RuleSet.MAX_FILE_BYTES_V3

    /** Merged rule ceiling an import may never push past (built-ins + imported together). */
    fun maxImportedRules(builtInCount: Int): Int =
        (RuleSet.MAX_RULES_V3 - builtInCount).coerceAtLeast(0)
}

/** Outcome of validating one candidate import document. Denials carry a user-facing reason. */
sealed interface ImportValidation {
    /** [rules] are the parsed, source-flagged rules; [digest] is the canonical SHA-256 hex. */
    data class Valid(val rules: List<SiteRule>, val digest: String, val version: Int) : ImportValidation
    data class Denied(val reason: Reason) : ImportValidation {
        enum class Reason { OVERSIZE, MALFORMED, UNSUPPORTED_VERSION, NO_RULES, CAP_EXCEEDED }
    }
}

/**
 * Canonical JSON + SHA-256 over one import document. Canonicalization is deterministic: object
 * keys sorted, insignificant whitespace dropped, whole numbers printed without a fraction. Two
 * byte-different but semantically identical documents therefore hash the same, which is what a
 * user manually comparing digests against a publisher's value needs.
 */
object ImportIntegrity {
    /** Canonical compact form, or null when the text is not parsable bounded JSON. */
    fun canonicalJson(text: String): String? {
        val root = RuleSet.parseBoundedJson(text, maxDepth = 16, maxStringChars = 65_536) ?: return null
        return canonicalValue(root)
    }

    /**
     * Canonical compact form of an ALREADY parsed bounded-JSON value (T101 signing): the signature
     * covers the canonical document WITHOUT its envelope key, so verification needs the same
     * printer over a map copy rather than over re-serialized text.
     */
    fun canonicalValue(root: Any?): String = buildString { print(root) }

    /** SHA-256 hex (lowercase) of [text]'s UTF-8 bytes. */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Canonical SHA-256 hex, or null when the document is not parsable bounded JSON. */
    fun canonicalDigest(text: String): String? = canonicalJson(text)?.let { sha256Hex(it) }

    private fun StringBuilder.print(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> printString(value)
            is Boolean -> append(value.toString())
            is Double -> {
                // Whole numbers print without a fraction so 2048 and 2048.0 agree.
                if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
                    append(value.toLong().toString())
                } else append(value.toString())
            }
            is Map<*, *> -> {
                append('{')
                val entries = value.entries.sortedWith(compareBy { it.key.toString() })
                entries.forEachIndexed { index, (key, v) ->
                    if (index > 0) append(',')
                    printString(key.toString())
                    append(':')
                    print(v)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, v ->
                    if (index > 0) append(',')
                    print(v)
                }
                append(']')
            }
            else -> append("null") // unknown scalars never appear from the bounded reader
        }
    }

    private fun StringBuilder.printString(value: String) {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }
}

/**
 * Pure validation pipeline for one import candidate: size gate, bounded JSON parse, schema-v3
 * version gate, the SAME load-time validation the built-in document passes ([RuleSet.parseImported]
 * shares the exact loader), a "produced at least one rule" floor and the merged-total cap.
 */
object ImportValidator {
    fun validate(text: String, builtInCount: Int): ImportValidation {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > ImportedRulePolicy.MAX_IMPORT_BYTES) {
            return ImportValidation.Denied(ImportValidation.Denied.Reason.OVERSIZE)
        }
        val digest = ImportIntegrity.canonicalDigest(text) ?: return ImportValidation.Denied(ImportValidation.Denied.Reason.MALFORMED)
        val set = RuleSet.parseImported(text)
        if (set.version < RuleSet.SCHEMA_FETCH_VERSION) {
            return ImportValidation.Denied(ImportValidation.Denied.Reason.UNSUPPORTED_VERSION)
        }
        if (set.rules.isEmpty()) return ImportValidation.Denied(ImportValidation.Denied.Reason.NO_RULES)
        if (set.rules.size > ImportedRulePolicy.maxImportedRules(builtInCount)) {
            return ImportValidation.Denied(ImportValidation.Denied.Reason.CAP_EXCEEDED)
        }
        return ImportValidation.Valid(set.rules, digest, set.version)
    }
}

/** D5 IMPORTED tier decisions: halved budgets and same-origin-only redirects. Pure functions. */
object ImportedTier {
    /** An imported rule's fetch reads at most half the declared maxBytes. */
    fun effectiveMaxBytes(maxBytes: Int): Int = (maxBytes / 2).coerceAtLeast(1)

    /** The imported fetch wall clock is half the shared ledger's per-user-action wall. */
    fun wallBudgetMs(baseWallMs: Long): Long = (baseWallMs / 2).coerceAtLeast(1)

    /** Imported rules never follow a cross-origin redirect hop, credentials or not. */
    fun redirectAllowed(current: String, next: String): Boolean = RequestPolicy.sameOrigin(current, next)
}

/**
 * Merge precedence (T90): built-ins ALWAYS win on an id collision — an import extends coverage,
 * it can never shadow or weaken a shipped rule. Imported rules keep their IMPORTED source marker
 * so the coordinator's tier switch and the analyze-button label stay truthful. The merged total
 * respects the v3 rule ceiling by dropping overflow IMPORTED entries; built-ins are never dropped.
 */
object RuleSetMerger {
    fun merge(builtIn: RuleSet, imported: RuleSet): RuleSet {
        if (imported.rules.isEmpty()) return builtIn
        val seen = builtIn.rules.mapTo(hashSetOf()) { it.id }
        val out = ArrayList<SiteRule>(builtIn.rules)
        for (rule in imported.rules) {
            if (out.size >= RuleSet.MAX_RULES_V3) break
            if (!seen.add(rule.id)) continue // built-in precedence on collision
            out += rule
        }
        val version = maxOf(builtIn.version, imported.version)
        return RuleSet(out.toList(), version)
    }
}
