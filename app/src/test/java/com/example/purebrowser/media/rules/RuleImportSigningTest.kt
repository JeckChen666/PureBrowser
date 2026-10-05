package com.example.purebrowser.media.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * T101 Ed25519 import signing on the JVM: sign→verify, tamper-reject, unsigned-degrade and
 * key-mismatch rejection, all against the SAME canonicalization the SHA-256 digest flow uses.
 * The JVM's builtin java.security provider always carries Ed25519, so these are deterministic;
 * the provider-dependent Android path degrades through [RuleImportSigning.ImportSignatureState.Unavailable].
 */
class RuleImportSigningTest {
    private fun keyPair() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun trusted(key: java.security.KeyPair, keyId: String = "pb-k-test0001") =
        RuleImportSigning.TrustedKey("ed25519", keyId, key.public.encoded)

    /** Signs the canonical form of [text] WITHOUT the envelope key, mirroring the maintainer tool. */
    private fun signDocument(text: String, key: java.security.KeyPair): String {
        val root = RuleSet.parseBoundedJson(text, maxDepth = 16, maxStringChars = 65_536) as Map<String, Any?>
        val bare = root.toMutableMap()
        bare.remove(RuleImportSigning.ENVELOPE_KEY)
        val canonical = ImportIntegrity.canonicalValue(bare)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key.private)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun envelope(keyId: String, value: String): String =
        ""","signature":{"algorithm":"Ed25519","keyid":"$keyId","value":"$value"}}"""

    private val document = """
        {
          "version": 3,
          "rules": [
            {"id": "sample-1", "version": 1,
             "match": {"hosts": "(^|\\.)example\\.org$", "path": "/v/(?<id>[0-9]+)"},
             "actions": [{"type": "jsonExtract", "pointers": ["url"]}],
             "fetch": [{"id": "api", "url": "https://example.org/api/v/{{match.id}}", "maxBytes": 65536}]}
          ]
        }
    """.trimIndent()

    @Test fun signedDocumentVerifiesAndLabelsByKeyid() {
        assumeTrue("runtime lacks Ed25519", RuleImportSigning.ed25519Available())
        val key = keyPair()
        val signed = document.dropLast(1) + envelope("pb-k-test0001", signDocument(document, key))
        val state = RuleImportSigning.verify(signed, listOf(trusted(key)))
        assertEquals(RuleImportSigning.ImportSignatureState.Signed("pb-k-test0001"), state)
        assertTrue(RuleImportSigning.signatureLabel(state).contains("已签名"))
        // The document still parses as rules with the envelope present (unknown keys are ignored).
        assertTrue(RuleSet.parseImported(signed).rules.size == 1)
    }

    @Test fun tamperedDocumentIsRejected() {
        assumeTrue("runtime lacks Ed25519", RuleImportSigning.ed25519Available())
        val key = keyPair()
        val signature = signDocument(document, key)
        val tampered = document
            .replace("\"sample-1\"", "\"evil-1\"") // semantic edit after signing
            .dropLast(1) + envelope("pb-k-test0001", signature)
        assertEquals(RuleImportSigning.ImportSignatureState.Invalid, RuleImportSigning.verify(tampered, listOf(trusted(key))))
    }

    @Test fun unsignedDocumentKeepsTheDigestFlowUnchanged() {
        assumeTrue("runtime lacks Ed25519", RuleImportSigning.ed25519Available())
        assertEquals(RuleImportSigning.ImportSignatureState.Unsigned, RuleImportSigning.verify(document, listOf(trusted(keyPair()))))
        // Byte edits that keep the canonical form identical still verify (whitespace is cosmetic).
        val key = keyPair()
        val signature = signDocument(document, key)
        val reformatted = document.replace("  ", "    ").dropLast(1) + envelope("pb-k-test0001", signature)
        assertEquals(
            RuleImportSigning.ImportSignatureState.Signed("pb-k-test0001"),
            RuleImportSigning.verify(reformatted, listOf(trusted(key))),
        )
    }

    @Test fun wrongKeyOrUnknownKeyidIsRejected() {
        assumeTrue("runtime lacks Ed25519", RuleImportSigning.ed25519Available())
        val signer = keyPair()
        val other = keyPair()
        val signed = document.dropLast(1) + envelope("pb-k-test0001", signDocument(document, signer))
        // Same keyid but a different embedded key (rotation gone wrong).
        assertEquals(RuleImportSigning.ImportSignatureState.Invalid, RuleImportSigning.verify(signed, listOf(trusted(other))))
        // Envelope claiming an id nobody embedded.
        val unknownId = document.dropLast(1) + envelope("pb-k-nobody01", signDocument(document, signer))
        assertEquals(RuleImportSigning.ImportSignatureState.Invalid, RuleImportSigning.verify(unknownId, listOf(trusted(signer))))
        // Non-Ed25519 algorithm claim.
        val badAlgo = document.dropLast(1) + ""","signature":{"algorithm":"RSA","keyid":"pb-k-test0001","value":"AAAA"}}"""
        assertEquals(RuleImportSigning.ImportSignatureState.Invalid, RuleImportSigning.verify(badAlgo, listOf(trusted(signer))))
    }

    @Test fun pubkeyAssetLinesParseAndSampleImportVerifiesAgainstThem() {
        assumeTrue("runtime lacks Ed25519", RuleImportSigning.ed25519Available())
        val asset: File = sequenceOf(
            File("src/main/assets/rules/rules-pubkey.txt"),
            File("app/src/main/assets/rules/rules-pubkey.txt"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("rules-pubkey.txt not found from the test working directory")
        val keys = asset.readLines().mapNotNull(RuleImportSigning::parseKeyLine)
        assertTrue("expected at least one embedded trust anchor", keys.isNotEmpty())
        // The committed sample import file must verify against the committed key: this is the
        // maintainer-channel end-to-end proof (tool signs → app verifies).
        val sample: File = sequenceOf(
            File("tools/rules-transcriber/sample-import.json"),
            File("../tools/rules-transcriber/sample-import.json"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("sample-import.json not found from the test working directory")
        val state = RuleImportSigning.verify(sample.readText(), keys)
        assertTrue(
            "committed sample must verify as Signed (was $state); re-sign with tools/rules-transcriber/sign_rules",
            state is RuleImportSigning.ImportSignatureState.Signed,
        )
    }
}
