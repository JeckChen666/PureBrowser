package com.example.purebrowser.media.rules

import android.content.Context
import java.io.File

/**
 * App-private storage for the user's imported rule document (T90). One JSON file under
 * filesDir — never shared, never synced, no remote delivery. Writing is preceded on the caller's
 * side by [ImportedValidator.validate] plus the explicit consent dialog; [save] itself re-checks
 * the size ceiling so a racing larger payload can never land. [RuleSet.invalidateMerged] must be
 * called after [save] or [clear] so the merged view rebuilds.
 *
 * Signing deferral (D9, honest note): there is no key infrastructure in this app — no embedded
 * public key, no update channel — so an asymmetric signature over the document could not be
 * verified against anything trustworthy. The consent surface therefore shows a deterministic
 * SHA-256 of the canonicalized document for the user to compare manually. Asymmetric signing is
 * deferred and recorded in the release ledger, not silently replaced with a decorative check.
 */
object ImportedRuleStore {
    private const val FILE_NAME = "imported-site-rules.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Current import document text, or null when nothing is imported. */
    fun read(context: Context): String? = runCatching {
        val f = file(context)
        if (!f.isFile) return null
        // Same ceiling the reader enforces; a truncated/oversized file is treated as absent.
        val raw = f.readBytes()
        if (raw.isEmpty() || raw.size > ImportedRulePolicy.MAX_IMPORT_BYTES) null else raw.toString(Charsets.UTF_8)
    }.getOrNull()

    /** Persists a validated document; returns false when the payload exceeds the ceiling. */
    fun save(context: Context, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > ImportedRulePolicy.MAX_IMPORT_BYTES) return false
        return runCatching {
            file(context).writeBytes(bytes)
            RuleSet.invalidateMerged()
            true
        }.getOrDefault(false)
    }

    /** Removes the imported document; returns true when a file was actually deleted. */
    fun clear(context: Context): Boolean = runCatching {
        val f = file(context)
        val existed = f.isFile && f.delete()
        RuleSet.invalidateMerged()
        existed
    }.getOrDefault(false)

    /** Convenience for settings surfaces: how many rules the stored document contributes. */
    fun importedCount(context: Context): Int =
        read(context)?.let { RuleSet.parseImported(it).rules.size } ?: 0
}
