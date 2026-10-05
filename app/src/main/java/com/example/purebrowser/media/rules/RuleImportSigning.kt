package com.example.purebrowser.media.rules

import android.content.Context
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * T101 Ed25519 import-signing verification. A rule-import document MAY carry a top-level
 * `signature` envelope:
 *
 *   { "version": 3, "rules": [ ... ],
 *     "signature": { "algorithm": "Ed25519", "keyid": "pb-k-XXXXXXXX", "value": "<base64>" } }
 *
 * The signature covers the CANONICAL JSON of the document WITHOUT the envelope key (the exact
 * canonicalization the SHA-256 digest flow uses), so cosmetic reformatting never breaks a valid
 * signature but any semantic edit does. Keys are embedded as an app asset — one per line,
 * `ed25519 <keyid> <base64-X509>` — and nothing about the document is executed during verification.
 *
 * Degradation ladder (honest, never theater):
 *  - [ImportSignatureState.Signed] — verified against an embedded key: the consent dialog labels
 *    the file "已签名(<keyid>)".
 *  - [ImportSignatureState.Unsigned] — no envelope present: the exact v0.1.8 SHA-256 manual
 *    verification consent flow, unchanged.
 *  - [ImportSignatureState.Invalid] — envelope present but the signature/algorithm/keyid does not
 *    verify: labeled tamper-suspect ("签名校验失败"), import stays possible only through the same
 *    explicit consent as an unsigned file — never silently trusted.
 *  - [ImportSignatureState.Unavailable] — this Android build's providers expose no Ed25519
 *    Signature: treated exactly like Unsigned with an honest note, because there is nothing
 *    trustworthy to verify against here.
 */
object RuleImportSigning {
    const val ENVELOPE_KEY = "signature"
    const val ALGORITHM = "Ed25519"
    const val PUBKEY_ASSET_PATH = "rules/rules-pubkey.txt"

    sealed interface ImportSignatureState {
        data class Signed(val keyId: String) : ImportSignatureState
        data object Unsigned : ImportSignatureState
        data object Invalid : ImportSignatureState
        data object Unavailable : ImportSignatureState
    }

    /** One embedded trust anchor parsed from the asset line `ed25519 <keyid> <base64>`. */
    data class TrustedKey(val algorithm: String, val keyId: String, val x509: ByteArray)

    /**
     * Verifies [text] against the embedded maintainer keys. Pure data in, state out; any parse
     * surprise degrades to [ImportSignatureState.Unsigned] (no envelope readable) or
     * [ImportSignatureState.Invalid] (envelope present but not verifiable).
     */
    fun verify(text: String, trustedKeys: List<TrustedKey>): ImportSignatureState {
        if (!ed25519Available()) return ImportSignatureState.Unavailable
        val root = RuleSet.parseBoundedJson(text, maxDepth = 16, maxStringChars = 65_536) as? Map<String, Any?>
            ?: return ImportSignatureState.Unsigned
        val envelope = root[ENVELOPE_KEY] as? Map<String, Any?> ?: return ImportSignatureState.Unsigned
        if ((envelope["algorithm"] as? String) != ALGORITHM) return ImportSignatureState.Invalid
        val keyId = envelope["keyid"] as? String ?: return ImportSignatureState.Invalid
        val value = envelope["value"] as? String ?: return ImportSignatureState.Invalid
        val key = trustedKeys.firstOrNull { it.algorithm.equals(ALGORITHM, ignoreCase = true) && it.keyId == keyId }
            ?: return ImportSignatureState.Invalid
        val signature = runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull()
            ?: return ImportSignatureState.Invalid
        // Canonical form of the document WITHOUT the envelope: same printer as the digest flow.
        val bare = root.toMutableMap()
        bare.remove(ENVELOPE_KEY)
        val canonical = ImportIntegrity.canonicalValue(bare)
        return try {
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(parseKey(key))
            verifier.update(canonical.toByteArray(Charsets.UTF_8))
            if (verifier.verify(signature)) ImportSignatureState.Signed(keyId) else ImportSignatureState.Invalid
        } catch (_: Exception) {
            ImportSignatureState.Invalid
        }
    }

    /** Reads the embedded trust anchors from the app asset; malformed lines are skipped, never fatal. */
    fun trustedKeys(context: Context): List<TrustedKey> = runCatching {
        context.assets.open(PUBKEY_ASSET_PATH).bufferedReader().readLines()
            .mapNotNull(::parseKeyLine)
    }.getOrDefault(emptyList())

    /**
     * User-facing verdict label for the consent dialog (T101): 已签名(keyid) / 签名校验失败 /
     * 未签名 / 本机不支持校验. Pure mapping so the UI stays dumb and testable.
     */
    fun signatureLabel(state: ImportSignatureState): String = when (state) {
        is ImportSignatureState.Signed -> "已签名（${state.keyId}）"
        ImportSignatureState.Unsigned -> "未签名（请人工核对下方 SHA-256 摘要）"
        ImportSignatureState.Invalid -> "签名校验失败（文件可能与发布版本不一致，谨慎导入）"
        ImportSignatureState.Unavailable -> "本机系统不支持 Ed25519 校验，请人工核对下方 SHA-256 摘要"
    }

    /** Convenience for the settings surface: verify against the app asset and render the label. */
    fun signatureLabel(context: Context, text: String): String =
        signatureLabel(verify(text, trustedKeys(context)))

    internal fun parseKeyLine(line: String): TrustedKey? {
        val parts = line.trim().split(" ")
        if (parts.size != 3 || parts[0] != "ed25519") return null
        val bytes = runCatching { Base64.getDecoder().decode(parts[2].trim()) }.getOrNull() ?: return null
        return TrustedKey(parts[0], parts[1], bytes)
    }

    /** Stable short id for a public key: first 8 hex of SHA-256 over its X509 encoding. */
    fun keyIdFor(x509: ByteArray): String =
        "pb-k-" + MessageDigest.getInstance("SHA-256").digest(x509)
            .take(4).joinToString("") { "%02x".format(it) }

    /** True when this runtime can actually verify Ed25519 (JDK builtin on JVM; provider-dependent on Android). */
    fun ed25519Available(): Boolean = runCatching {
        Signature.getInstance(ALGORITHM)
        KeyFactory.getInstance(ALGORITHM)
    }.isSuccess

    private fun parseKey(key: TrustedKey): PublicKey =
        KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(key.x509))
}
