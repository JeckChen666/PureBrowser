package com.example.purebrowser.download

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class DualTrackStoreTest {
    @Test fun enqueueUsesDraftWithoutNewSignatureAndPersistsOnlyNonSecretDescription() = DualTrackTestSupport.test { repo ->
        val draft = DualTrackTestSupport.draft(context = true)
        val id = repo.enqueue(draft, wifiOnly = false, fileName = "fixture.webm")
        val r = repo.record(id)!!
        assertEquals(DownloadProtocol.DUAL_TRACK, r.protocol); assertEquals(TransferType.CONTROLLED, r.transfer)
        assertTrue(r.name.endsWith(".mp4")); assertNull(r.mediaUrl); assertNull(r.frameUrl)
        assertNull(r.sourceUrl)
        assertEquals(draft.dualTrackPlan!!.metadata(), r.dualTrackMetadata)
        val raw = repo.store.file.readText()
        listOf("video-secret", "audio-secret", "page-secret", "signature=", "session=", "Cookie", "Authorization",
            "fixture-work", "fixture-video", "fixture-audio", "videoUrl", "audioUrl").forEach { assertFalse(raw.contains(it)) }
        val reopened = DownloadStore(repo.store.file.parentFile!!).load().records.single()
        assertEquals(r, reopened); assertFalse(reopened.resumeAvailable)
        assertFalse(repo.snapshot().single().canPause); assertFalse(repo.snapshot().single().canResume)
        assertTrue(runCatching { repo.queueResume(id) }.isFailure)
        val lease = repo.takeDualTrackRequest(id)!!
        assertEquals(draft.dualTrackPlan, lease.plan)
        assertEquals(draft.sourceUrl, lease.requestRecord.sourceUrl)
        assertNull(repo.takeDualTrackRequest(id)) // Exactly one full attempt, not durable credentials.
    }
    @Test fun approvedPublicSourceRoundTripsAndEnablesExistingReturnToSourceAction() = DualTrackTestSupport.test { repo ->
        val publicPage = "https://www.youtube.com/watch?v=eRsGyueVLvQ"
        val draft = DualTrackTestSupport.draft(DualTrackTestSupport.plan().copy(safeSourceUrl = publicPage))
        val id = repo.enqueue(draft, false)
        assertEquals(publicPage, repo.record(id)!!.sourceUrl)
        val lease = repo.takeDualTrackRequest(id)!!
        assertEquals(draft.sourceUrl, lease.requestRecord.sourceUrl) // Navigation never replaces request context.
        repo.change(id) { it.copy(taskStatus = TaskStatus.FAILED, failure = FailureKind.ACCESS_CONDITION) }
        assertEquals(publicPage, repo.snapshot().single().sourceUrl)
        val cold = DownloadRepository(DownloadStore(repo.store.file.parentFile!!), DualTrackTestSupport.NoSystem(), files = repo.files)
        assertEquals(publicPage, cold.record(id)!!.sourceUrl)
        assertEquals(publicPage, cold.snapshot().single().sourceUrl)
        assertNull(cold.record(id)!!.mediaUrl); assertFalse(cold.snapshot().single().canResume)
        val raw = repo.store.file.readText()
        assertEquals(publicPage, JSONObject(raw).getJSONArray("records").getJSONObject(0).getString("sourceUrl"))
        listOf("video-secret", "audio-secret", "page-secret", "signature=", "session=").forEach { assertFalse(raw.contains(it)) }
    }
    @Test fun v6MigrationProtectsOriginalClearsUnapprovedDualOriginsAndPreservesOtherSources() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false)
        val dual = repo.record(id)!!
        val direct = DownloadRecord(recordId = "direct-v6", name = "direct.mp4", transfer = TransferType.CONTROLLED,
            sourceUrl = "https://example.org/watch?old=signed%2Bquery", mediaUrl = "https://example.org/video?signature=old",
            taskStatus = TaskStatus.PAUSED, received = 12, resumeAvailable = true)
        val json = JSONObject(DownloadStore.encode(DownloadData(records = listOf(dual, direct)))).put("schemaVersion", 6)
        json.getJSONArray("records").getJSONObject(0).put("sourceUrl", "https://site.example:443/")
        val raw = " \n$json\n"
        repo.store.file.writeText(raw)
        val migrated = repo.store.load()
        assertEquals(listOf(dual, direct), migrated.records)
        assertNull(migrated.records.first().sourceUrl)
        assertEquals(direct.sourceUrl, migrated.records.last().sourceUrl)
        assertEquals(7, JSONObject(repo.store.file.readText()).getInt("schemaVersion"))
        assertEquals(raw, repo.store.file.parentFile!!.listFiles()!!.single { it.name.startsWith("download-v6-") }.readText())
        assertEquals(migrated, DownloadStore(repo.store.file.parentFile!!).load())
    }
    @Test fun v5MigrationPreservesOldDirectHlsSystemTasksAndOriginalBytes() = DualTrackTestSupport.test { repo ->
        val direct = DownloadRecord(recordId = "direct-old", name = "direct.mp4", transfer = TransferType.CONTROLLED,
            taskStatus = TaskStatus.PAUSED, mediaUrl = "https://example.org/video?exact=old%2Bsignature",
            received = 4096, expected = 8192, resumeAvailable = true, pauseReason = PauseReason.USER)
        val hls = DownloadRecord(recordId = "hls-old", name = "hls.mp4", transfer = TransferType.CONTROLLED,
            protocol = DownloadProtocol.HLS, taskStatus = TaskStatus.INTERRUPTED, hlsPlaylistUrl = "https://example.org/media.m3u8",
            plannedDurationUs = 4_000_000, segmentCount = 2, completedSegments = 1, resumeAvailable = true)
        val system = DownloadRecord(recordId = "system-old", systemId = 77, name = "legacy.webm")
        val data = DownloadData(records = listOf(direct, hls, system))
        val json = JSONObject(DownloadStore.encode(data)).put("schemaVersion", 5)
        for (i in 0 until 3) json.getJSONArray("records").getJSONObject(i).remove("dualTrackMetadata")
        val raw = " \n$json\n"
        repo.store.file.writeText(raw)
        assertEquals(data, repo.store.load())
        val backup = repo.store.file.parentFile!!.listFiles()!!.single { it.name.startsWith("download-v5-") }
        assertEquals(raw, backup.readText())
        assertEquals(DownloadStore.SCHEMA_VERSION, JSONObject(repo.store.file.readText()).getInt("schemaVersion"))
        assertEquals(data, DownloadStore(repo.store.file.parentFile!!).load())
        assertEquals(raw, backup.readText())
    }
    @Test fun failedOriginalProtectionKeepsV5ByteExactAndReadOnly() = DualTrackTestSupport.test { repo ->
        val raw = JSONObject(DownloadStore.encode(DownloadData())).put("schemaVersion", 5).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        val victim = File(repo.store.file.parentFile, "valuable.txt").apply { writeText("do not overwrite") }
        val backup = File(repo.store.file.parentFile, "download-v5-$hash.json")
        Files.createSymbolicLink(backup.toPath(), victim.toPath())
        repo.store.file.writeText(raw)
        repo.store.load(); assertFalse(repo.store.writable)
        assertEquals(raw, repo.store.file.readText()); assertEquals("do not overwrite", victim.readText())
        assertTrue(runCatching { repo.store.save(DownloadData()) }.isFailure)
        Files.delete(backup.toPath())
    }
    @Test fun malformedNewMetadataAndInventedResumeCannotDecode() = DualTrackTestSupport.test { repo ->
        repo.enqueue(DualTrackTestSupport.draft(), false)
        val raw = repo.store.file.readText()
        fun invalid(block: (JSONObject) -> Unit) {
            val json = JSONObject(raw); block(json.getJSONArray("records").getJSONObject(0))
            assertTrue(runCatching { DownloadStore.decode(json.toString()) }.isFailure)
        }
        invalid { it.put("resumeAvailable", true) }
        invalid { it.put("mediaUrl", "https://cdn.example/video?signature=secret") }
        invalid { it.put("sourceUrl", "https://site.example/watch?token=secret") }
        invalid { it.getJSONObject("dualTrackMetadata").put("videoUrl", "https://cdn.example/video") }
        invalid { it.getJSONObject("dualTrackMetadata").put("videoLength", 1.25) }
        invalid { it.getJSONObject("dualTrackMetadata").put("durationUs", Long.MAX_VALUE) }
        invalid { it.getJSONObject("dualTrackMetadata").put("version", 4_294_967_297L) }
        invalid { it.put("dualTrackMetadata", JSONObject.NULL) }
        val old = JSONObject(raw).put("schemaVersion", 5)
        assertTrue(runCatching { DownloadStore.decode(old.toString()) }.isFailure)
    }
    @Test fun privacyBoundaryDestroysUnusedPlanLeaseAndDoesNotPersistUrls() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false)
        repo.beginPrivacyExclusion()
        assertNull(repo.takeDualTrackRequest(id))
        repo.transfersAllowed = true
        assertNull(repo.takeDualTrackRequest(id))
        assertFalse(repo.store.file.readText().contains("secret"))
    }
    @Test fun waitingNeverStartedTaskMayWakeOnlyWithLivePlanLease() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), true)
        repo.change(id) { it.copy(taskStatus = TaskStatus.WAITING_WIFI, pauseReason = PauseReason.WIFI) }
        assertTrue(repo.wakeFreshDualTrack(id)); assertEquals(TaskStatus.QUEUED, repo.record(id)!!.taskStatus)
        repo.takeDualTrackRequest(id)
        repo.change(id) { it.copy(taskStatus = TaskStatus.WAITING_WIFI, pauseReason = PauseReason.WIFI) }
        assertFalse(repo.wakeFreshDualTrack(id)); assertFalse(TaskControlRules.canResume(repo.record(id)!!))
    }
}
