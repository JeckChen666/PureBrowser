package com.example.purebrowser.media.codec

import com.example.purebrowser.media.VariantSummary

/**
 * T110 AV1 runtime gating — the pure decision tier (D18). Device capability is reduced to three
 * booleans-plus-API-level inputs; every consumer (candidate variant lists, resolver offer lists,
 * finished-product verification) applies one of the three outcomes below. No Android type is
 * referenced here so the whole table stays JVM-unit-testable; the device read lives in
 * [DeviceAv1CapabilityProvider].
 *
 * Contract (docs/EXECUTION-PLAN-V0.2.0.md §3):
 *  - HIDDEN (API<29, or the device exposes no av01 decoder at all): AV1 variants/candidates are
 *    ABSENT from every surface — hidden, never greyed out.
 *  - WARNED (software decoder only): entries stay listed and selectable (an honest attempt that
 *    may fail or run slowly) and carry the fixed performance warning text.
 *  - AVAILABLE (hardware decoder): unchanged behavior.
 */
enum class Av1DecodeSupport { HIDDEN, WARNED, AVAILABLE }

/** Injectable capability source; pure so pure-JVM holders can take a fixed instance in tests. */
fun interface Av1CapabilityProvider {
    fun support(): Av1DecodeSupport
}

object Av1Capability {
    /** Platform MIME for AV1 video; also the MediaExtractor track MIME for av01 samples. */
    const val AV1_MIME = "video/av01"

    /** Fixed safe text for the WARNED state; never interpolates input. */
    const val SOFTWARE_DECODE_WARNING = "此档位为 AV1，本机仅软件解码，保存与播放可能缓慢"

    /**
     * The one capability table. API<29 hides AV1 outright (no platform decoder story predates
     * Android 10), and so does any API level where no decoder at all is exposed; a software-only
     * device warns; a hardware decoder is the clean path.
     */
    fun evaluate(hasHardwareDecoder: Boolean, hasSoftwareDecoder: Boolean, apiLevel: Int): Av1DecodeSupport = when {
        apiLevel < 29 -> Av1DecodeSupport.HIDDEN
        !hasHardwareDecoder && !hasSoftwareDecoder -> Av1DecodeSupport.HIDDEN
        !hasHardwareDecoder -> Av1DecodeSupport.WARNED
        else -> Av1DecodeSupport.AVAILABLE
    }

    /** True when a codec declaration list contains an av01 token ("av01.0.05M.08" style). */
    fun isAv1Codecs(codecs: String?): Boolean = codecs != null &&
        codecs.split(',').any { it.trim().lowercase().startsWith("av01") }

    /** True for the AV1 track MIME reported by MediaExtractor on an av01 sample. */
    fun isAv1Mime(mime: String?): Boolean = mime?.trim()?.equals(AV1_MIME, ignoreCase = true) == true

    /** HIDDEN policy: the entry must be absent from the list, not rendered disabled. */
    fun hidden(codecs: String?, support: Av1DecodeSupport): Boolean =
        support == Av1DecodeSupport.HIDDEN && isAv1Codecs(codecs)

    /**
     * WARNED policy for a still-listed entry: the fixed AV1 performance note is appended to any
     * existing warning (the parser's codec warning stays — both facts are true), or used alone.
     */
    fun annotated(existingWarning: String?, support: Av1DecodeSupport): String? = when (support) {
        Av1DecodeSupport.WARNED -> when (existingWarning) {
            null -> SOFTWARE_DECODE_WARNING
            else -> "$existingWarning；$SOFTWARE_DECODE_WARNING"
        }
        else -> existingWarning
    }

    /**
     * Candidate-surface policy over [VariantSummary] lists (probe results, page-signal masters,
     * rule findings): HIDDEN drops AV1 entries, WARNED annotates their existing warning field,
     * AVAILABLE is a no-op returning the SAME list instance so equality-based updates still work.
     * Never mutates the input; an all-AV1 list may legitimately gate down to empty.
     */
    fun applyToSummaries(variants: List<VariantSummary>, support: Av1DecodeSupport): List<VariantSummary> {
        if (support == Av1DecodeSupport.AVAILABLE || variants.isEmpty()) return variants
        var changed = false
        val out = ArrayList<VariantSummary>(variants.size)
        variants.forEach { variant ->
            if (hidden(variant.codecs, support)) { changed = true; return@forEach }
            if (support == Av1DecodeSupport.WARNED && isAv1Codecs(variant.codecs)) {
                val annotated = variant.copy(warning = annotated(variant.warning, support))
                if (annotated != variant) changed = true
                out += annotated
            } else out += variant
        }
        return if (changed) out else variants
    }

    /**
     * Finished-product defense in depth (§3 成品校验含解码可用性): an av01 video track in a file
     * this app just saved must be rejected as INVALID when the device cannot decode AV1 at all.
     * On WARNED/AVAILABLE devices the sample stands or fails on its own container merits — slow
     * software decoding is a playback experience, not a verification failure.
     */
    fun rejectsFinishedProduct(videoTrackMime: String?, support: Av1DecodeSupport): Boolean =
        support == Av1DecodeSupport.HIDDEN && isAv1Mime(videoTrackMime)
}
