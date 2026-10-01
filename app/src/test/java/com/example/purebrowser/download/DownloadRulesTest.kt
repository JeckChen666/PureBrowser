package com.example.purebrowser.download

import org.junit.Assert.*
import org.junit.Test

class DownloadRulesTest {
    private fun record(id: Long = 1) = DownloadRecord(recordId = "r$id", systemId = id, name = "video$id.mp4")
    private fun asset(id: Long = 1) = VideoAsset("r$id", id, "content://downloads/all_downloads/$id", "video$id.mp4", "video$id.mp4", 123)

    @Test fun safeNamesCannotTraverseDirectoriesOrContainControls() {
        val value = DownloadRules.safeFileName("../My video\\one\n.mp4")
        assertFalse(value.contains('/')); assertFalse(value.contains('\\')); assertFalse(value.contains('\n'))
        assertTrue(value.length <= 100)
        assertEquals("video.mp4", DownloadRules.safeFileName(".."))
    }
    @Test fun signedUrlsAreValidatedWithoutBeingRewritten() {
        val url = "https://example.com/video.mp4?signature=a%2Bb&policy=exact"
        val r = record().copy(mediaUrl = url, sourceUrl = "https://example.com/watch?id=one")
        DownloadRules.validate(DownloadData(records = listOf(r)))
        assertEquals(url, r.mediaUrl)
    }
    @Test fun duplicateRecordOrSystemIdsAreRejected() {
        val r = record()
        assertTrue(runCatching { DownloadRules.validate(DownloadData(records = listOf(r, r.copy(recordId = "other")))) }.isFailure)
        assertTrue(runCatching { DownloadRules.validate(DownloadData(records = listOf(r, record(2).copy(recordId = r.recordId)))) }.isFailure)
    }
    @Test fun assetsMustBelongToAnExistingTaskAndMatchItsName() {
        assertTrue(runCatching { DownloadRules.validate(DownloadData(assets = listOf(asset()))) }.isFailure)
        assertTrue(runCatching { DownloadRules.validate(DownloadData(listOf(record()), listOf(asset().copy(systemId = 2)))) }.isFailure)
        assertTrue(runCatching { DownloadRules.validate(DownloadData(listOf(record()), listOf(asset().copy(name = "wrong.mp4")))) }.isFailure)
    }
    @Test fun fileUrisCannotReferenceForeignProvidersOrDifferentTaskIds() {
        assertTrue(DownloadRules.isOwnedDownloadUri("content://downloads/all_downloads/1", 1))
        listOf("file:///sdcard/video.mp4", "content://contacts/all_downloads/1", "content://downloads/all_downloads/2", "content://downloads/all_downloads/1?other=1").forEach {
            assertFalse(DownloadRules.isOwnedDownloadUri(it, 1))
        }
    }
    @Test fun forgetRemovesOnlyRelatedMetadataWithoutMutatingOriginal() {
        val data = DownloadData(listOf(record(), record(2)), listOf(asset(), asset(2)))
        val next = DownloadRules.forget(data, 1)
        assertEquals(listOf(2L), next.records.map { it.systemId }); assertEquals(listOf(2L), next.assets.map { it.systemId })
        assertEquals(2, data.records.size); assertEquals(2, data.assets.size)
    }
    @Test fun credentialsAndUnsafeOriginsAreRejected() {
        listOf("javascript:alert(1)", "file:///data/private", "https://user:pass@example.com/video.mp4").forEach { url ->
            assertTrue(runCatching { DownloadRules.validate(DownloadData(records = listOf(record().copy(mediaUrl = url)))) }.isFailure)
        }
        assertTrue(runCatching { DownloadRules.validate(DownloadData(records = listOf(record().copy(userAgent = "agent\r\nCookie: secret")))) }.isFailure)
    }
    @Test fun sizeBoundaryDoesNotSilentlyDropOldRecords() {
        val many = (1L..201L).map { record(it) }
        assertTrue(runCatching { DownloadRules.validate(DownloadData(records = many)) }.isFailure)
        assertEquals(201, many.size)
    }
    @Test fun initialHeaderCheckIsLimitedAndRejectsHtml() {
        assertTrue(DownloadRules.supportedHeader(byteArrayOf(0,0,0,24) + "ftypisom".toByteArray()))
        assertTrue(DownloadRules.supportedHeader(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte())))
        assertFalse(DownloadRules.supportedHeader("<html>login</html>".toByteArray()))
        assertFalse(DownloadRules.supportedHeader(byteArrayOf(1,2)))
    }
    @Test fun incidentalLoggingCannotExposeSignedUrlsOrRequestContext() {
        val secret = "https://example.com/video.mp4?signature=private-token"
        val r = record().copy(mediaUrl = secret, sourceUrl = secret, userAgent = "private-agent")
        val item = DownloadItem(1, "video.mp4", 8, 100, 100, "saved", sourceUrl = secret)
        val draft = DownloadDraft(com.example.purebrowser.media.MediaCandidate(secret,
            com.example.purebrowser.media.MediaKind.FILE, emptySet()), "private-agent", secret)
        listOf(r.toString(), item.toString(), draft.toString()).forEach {
            assertFalse(it.contains("private-token")); assertFalse(it.contains("private-agent")); assertFalse(it.contains(secret))
        }
    }

    @Test fun unicodeNamesFitTheFileByteLimitWithoutSplittingCodePoints() {
        val name=DownloadRules.safeFileName("视频".repeat(100)+".mp4")
        assertTrue(name.toByteArray(Charsets.UTF_8).size<=240)
        assertTrue(("12345678_"+name).toByteArray(Charsets.UTF_8).size<255)
        val supplementary=String(Character.toChars(0x10400)).repeat(100)
        val safe=DownloadRules.safeFileName(supplementary)
        assertEquals(safe,safe.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertTrue(safe.length<=100)
    }

}
