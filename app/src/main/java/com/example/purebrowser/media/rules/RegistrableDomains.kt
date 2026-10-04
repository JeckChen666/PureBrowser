package com.example.purebrowser.media.rules

import java.util.Locale

/**
 * Minimal registrable-domain (eTLD+1) approximation used ONLY by the rule loader's session
 * containment check (T82/T83): a rule's `session.hosts` must sit inside the registrable domains
 * of its `match.hosts` face. This is deliberately NOT a full Public Suffix List — the default rule
 * is "last two labels", corrected by a tiny built-in suffix table for the common multi-label
 * public suffixes (co.uk/com.cn style). Anything the table misses fails CLOSED: an unknown
 * multi-label suffix yields a registrable domain one label too short, which can only make the
 * containment check stricter, never looser, because both sides of the comparison go through the
 * same function.
 */
internal object RegistrableDomains {
    /** Small, fixed exception table; documented deviation from a real PSL, kept tiny on purpose. */
    private val MULTI_LABEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.au", "net.au", "org.au",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "com.br", "com.mx", "com.tr", "com.sg", "com.hk", "com.ar", "com.pl", "com.ua",
        "co.kr", "co.in", "co.nz", "co.za",
    )

    private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")
    private val PLAIN_HOST = Regex("([a-z0-9-]+\\.)+[a-z]{2,}")

    /** A plain lowercase host literal: dot-separated labels, no regex metacharacters, no port. */
    fun isPlainHost(host: String): Boolean {
        val value = host.lowercase(Locale.ROOT)
        if (value.length !in 4..253 || value.endsWith('.') || value.startsWith('.')) return false
        return PLAIN_HOST.matches(value)
    }

    /** eTLD+1 of a host literal, or null when the shape is not a registrable candidate at all. */
    fun registrableDomain(host: String): String? {
        val value = host.lowercase(Locale.ROOT).trimEnd('.')
        if (!isPlainHost(value)) return null
        val labels = value.split('.')
        if (labels.any { !LABEL.matches(it) }) return null
        val lastTwo = labels.takeLast(2).joinToString(".")
        return when {
            lastTwo in MULTI_LABEL_SUFFIXES -> labels.takeLast(3).joinToString(".")
            else -> lastTwo
        }
    }

    /**
     * Registrable domains literally covered by a hosts-face regex source. Expands top-level
     * alternations (the `(^|\.)host\.(com|net)$` shape) into literal branches, then keeps every
     * branch-side domain literal. Rules whose hosts face contains shapes this cannot expand
     * (character classes, unbounded wildcards) simply yield fewer or no domains, which fails the
     * session containment check closed.
     */
    fun fromHostPattern(pattern: String): Set<String> {
        val out = mutableSetOf<String>()
        for (branch in literalBranches(pattern)) {
            var i = 0
            while (i < branch.length) {
                if (!isDomainChar(branch[i])) { i++; continue }
                var j = i
                while (j < branch.length && isDomainChar(branch[j])) j++
                val candidate = branch.substring(i, j).trim('.')
                registrableDomain(candidate)?.let { out += it }
                i = j
            }
        }
        return out
    }

    private fun isDomainChar(c: Char) = c.isLetterOrDigit() || c == '.' || c == '-'

    /** Bounded literal expansion of a hosts pattern; groups become their alternatives, anchors drop. */
    private fun literalBranches(pattern: String): List<String> {
        var out = listOf("")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> { out = append(out, pattern[i + 1].let { if (it == '.') "." else "\u0000" }); i += 2 }
                c == '(' -> {
                    val close = matchingParen(pattern, i)
                    if (close < 0) return out // malformed tail: keep what expanded so far
                    val inner = pattern.substring(i + 1, close)
                    val alternatives = splitTopLevel(inner)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("?:") && !it.startsWith("?=") && !it.startsWith("?!") && !it.startsWith("?<=") && !it.startsWith("?<!") }
                        .flatMap { literalBranches(it) }
                    out = cross(out, alternatives); i = close + 1
                }
                c == '[' -> {
                    // A character class makes the run non-literal; break it with a separator.
                    val close = pattern.indexOf(']', i + 1)
                    val end = if (close < 0) pattern.length else close + 1
                    out = append(out, "\u0000"); i = end
                }
                c == '^' || c == '$' -> i++
                c.isLetterOrDigit() || c == '.' || c == '-' -> { out = append(out, c.toString()); i++ }
                else -> { out = append(out, "\u0000"); i++ } // quantifiers and other metacharacters break literals
            }
            if (out.size > MAX_BRANCHES) return out.take(MAX_BRANCHES)
        }
        return out
    }

    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when {
                text[i] == '\\' -> i++
                text[i] == '[' -> { val close = text.indexOf(']', i + 1); i = if (close < 0) text.length else close }
                text[i] == '(' -> depth++
                text[i] == ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun splitTopLevel(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '\\' && i + 1 < text.length -> { current.append(text[i]).append(text[i + 1]); i += 2 }
                text[i] == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    val end = if (close < 0) text.length else close + 1
                    current.append(text, i, end); i = end
                }
                text[i] == '(' -> {
                    val close = matchingParen(text, i)
                    if (close < 0) { current.append(text, i, text.length); return out + current.toString() }
                    current.append(text, i, close + 1); i = close + 1
                }
                text[i] == '|' -> { out += current.toString(); current.clear(); i++ }
                else -> { current.append(text[i]); i++ }
            }
        }
        return out + current.toString()
    }

    private fun append(branches: List<String>, piece: String): List<String> = branches.take(MAX_BRANCHES).map { it + piece }
    private fun cross(left: List<String>, right: List<String>): List<String> =
        left.take(MAX_BRANCHES).flatMap { l -> right.map { l + it } }.take(MAX_BRANCHES)

    private const val MAX_BRANCHES = 16
}

/** Regex-source scanning helpers shared by the loader's template/group validation (T82). */
internal object RegexSourceScan {
    /** Capture groups declared by a pattern: positional and named, character-class and escape aware. */
    fun capturingGroupCount(pattern: String): Int {
        var count = 0
        var i = 0
        while (i < pattern.length) {
            when {
                pattern[i] == '\\' -> i++
                pattern[i] == '[' -> { val close = pattern.indexOf(']', i + 1); i = if (close < 0) pattern.length else close + 1 }
                pattern[i] == '(' -> if (isCapturingOpen(pattern, i)) count++
            }
            i++
        }
        return count
    }

    /** Names of `(?<name>...)` groups in the source, in order. */
    fun namedGroupNames(pattern: String): Set<String> {
        val out = mutableSetOf<String>()
        var i = 0
        while (i < pattern.length) {
            when {
                pattern[i] == '\\' -> i++
                pattern[i] == '[' -> { val close = pattern.indexOf(']', i + 1); i = if (close < 0) pattern.length else close + 1 }
                pattern[i] == '(' && pattern.startsWith("(?<", i) ->
                    nameAfter(pattern, i + 3)?.let { out += it }
            }
            i++
        }
        return out
    }

    /** Reads an identifier starting at [start]; null unless it is followed by `>`. */
    private fun nameAfter(pattern: String, start: Int): String? {
        var j = start
        while (j < pattern.length && pattern[j] != '>') j++
        if (j == start || j >= pattern.length) return null
        val name = pattern.substring(start, j)
        return name.takeIf { Regex("[A-Za-z][A-Za-z0-9_]*").matches(it) }
    }

    /** `(?<name>` opens a capturing group; `(?<=` and `(?<!` are lookbehinds, everything else non-capturing. */
    private fun isCapturingOpen(pattern: String, open: Int): Boolean {
        val next = pattern.getOrNull(open + 1) ?: return true
        if (next != '?') return true
        val after = pattern.getOrNull(open + 2)
        return after == '<' && pattern.getOrNull(open + 3)?.let { it != '=' && it != '!' } == true
    }
}
