package com.example.purebrowser.download

import android.app.DownloadManager
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.library.VideoLibraryRepository
import com.example.purebrowser.media.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DownloadRepositoryTest {
    private data class EnqueueCall(val url: String, val name: String, val userAgent: String, val wifiOnly: Boolean)
    private class FakeBackend : DownloadBackend {
        val rows = mutableMapOf<Long, SystemDownloadResult>()
        val access = mutableMapOf<String, FileAccess>()
        val inspections = mutableMapOf<String, MediaInspection>()
        val removed = mutableListOf<Long>()
        val enqueued = mutableListOf<String>()
        val enqueueCalls = mutableListOf<EnqueueCall>()
        var inspectionCount = 0
        var accessCount = 0
        var onEnqueue: (() -> Unit)? = null
        // Overrides are confined to this fake: row removal never implicitly proves file removal.
        var onRemove: ((Long) -> Int)? = null
        private var nextId = 100L
        fun uri(id: Long) = "content://downloads/all_downloads/$id"
        fun completed(id: Long, check: FormatCheck = FormatCheck.PASSED) {
            rows[id] = SystemDownloadResult.Present(DownloadManager.STATUS_SUCCESSFUL, 1024, 1024, 0,
                "https://example.com/video.mp4?signature=a%2Bb", "video/mp4", 456)
            access[uri(id)] = FileAccess(FileAvailability.AVAILABLE, 1024)
            inspections[uri(id)] = MediaInspection(check, "video/mp4", 2000)
        }
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long {
            enqueued += url
            enqueueCalls += EnqueueCall(url, name, userAgent, wifiOnly)
            onEnqueue?.invoke()
            val id = nextId++
            rows[id] = SystemDownloadResult.Present(DownloadManager.STATUS_PENDING, 0, -1, 0, url)
            return id
        }
        override fun query(id: Long) = rows[id] ?: SystemDownloadResult.Missing
        override fun fileUri(id: Long) = if (rows[id] is SystemDownloadResult.Present) uri(id) else null
        override fun access(uri: String): FileAccess { accessCount++; return access[uri] ?: FileAccess(FileAvailability.MISSING) }
        override fun inspect(uri: String): MediaInspection { inspectionCount++; return inspections[uri] ?: MediaInspection(FormatCheck.UNCONFIRMED) }
        override fun remove(id: Long): Int {
            removed += id
            return onRemove?.invoke(id) ?: if (rows.remove(id) != null) 1 else 0
        }
    }
    private fun withState(action: (File, FakeBackend) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "download-repository-test-${UUID.randomUUID()}").apply { mkdirs() }
        check(directory.isDirectory)
        // The only real IO is this UUID-scoped metadata directory, never AndroidDownloadBackend.
        try { action(directory, FakeBackend()) } finally { directory.deleteRecursively() }
    }
    private fun record() = DownloadRecord("r7", 7, "video.mp4")
    private fun draft(url: String = "https://example.com/video.mp4?token=a%2Bb") =
        DownloadDraft(MediaCandidate(url, MediaKind.FILE, setOf(Evidence.DOM)), "agent", "https://example.com/watch", "Source", "tab1", 8)

    private fun indexedAsset(record: DownloadRecord) = VideoAsset(
        recordId = record.recordId, systemId = record.systemId,
        uri = "content://downloads/all_downloads/${record.systemId}",
        name = record.name, displayName = record.displayName, indexedAt = 100,
        systemUpdatedAt = 456, sizeBytes = 1024, mimeType = "video/mp4",
        format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE, durationMillis = 2000,
    )
    private fun retryableRecord() = record().copy(
        name = "original_physical_file.mp4", displayName = "saved_video.mp4",
        mediaUrl = "https://example.com/video.mp4?signature=a%2Bb&policy=exact%2Fone",
        userAgent = "OriginalAgent/1.0", wifiOnly = false, mimeType = "video/mp4",
        sourceUrl = "https://example.com/watch?id=original", sourceTitle = "Original source",
        sourceTabId = "original-tab", sourceGeneration = 8, createdAt = 123,
    )
    private fun present(status: Int, bytes: Long = 0, total: Long = -1) =
        SystemDownloadResult.Present(status, bytes, total, 0)
    private fun assertRejected(action: () -> Unit) {
        val result = runCatching(action)
        assertTrue("Operation should reject unsafe/unconfirmed state", result.isFailure)
        assertTrue("Expected a validation/state rejection, not an unrelated exception",
            result.exceptionOrNull() is IllegalArgumentException || result.exceptionOrNull() is IllegalStateException || result.exceptionOrNull() is TransferFailure)
    }

    @Test fun migrationNeverCreatesOrDeletesTasksAndRetainsUnknownFields() = withState { dir, backend ->
        val store = DownloadStore(dir) { """[{"id":7,"name":"old.mp4"}]""" }
        val repository = DownloadRepository(store, backend)
        assertEquals("legacy-7", repository.snapshot().single().id)
        val record = store.load().records.single()
        assertEquals("old.mp4", record.name); assertNull(record.createdAt); assertNull(record.sourceUrl)
        assertNull(record.mediaUrl); assertNull(record.userAgent); assertNull(record.wifiOnly)
        assertTrue(backend.enqueued.isEmpty()); assertTrue(backend.removed.isEmpty())
    }
    @Test fun completedFileIsIndexedOnceAndSignedSystemUrlCanBeRecovered() = withState { dir, backend ->
        val store = DownloadStore(dir); store.save(DownloadData(records = listOf(record())))
        backend.completed(7)
        val repository = DownloadRepository(store, backend)
        val first = repository.stateSnapshot(); val second = repository.stateSnapshot()
        assertTrue(first.tasks.single().verified); assertEquals(first.assets, second.assets)
        assertEquals(1, store.load().assets.size); assertEquals(1, backend.inspectionCount)
        assertEquals(2, backend.accessCount)
        assertEquals("https://example.com/video.mp4?signature=a%2Bb", store.load().records.single().mediaUrl)
        assertNull(store.load().records.single().sourceUrl); assertNull(store.load().records.single().createdAt)
        assertEquals(1, VideoLibraryRepository(repository).entries(first).size)
    }
    @Test fun externallyMissingFileCannotRemainVerifiedAndPreviouslyIndexedEntryIsRetained() = withState { dir, backend ->
        val store = DownloadStore(dir); store.save(DownloadData(records = listOf(record()))); backend.completed(7)
        val repository = DownloadRepository(store, backend)
        repository.stateSnapshot()
        backend.access[backend.uri(7)] = FileAccess(FileAvailability.MISSING)
        val state = repository.stateSnapshot()
        assertFalse(state.tasks.single().verified); assertEquals(FileAvailability.MISSING, state.tasks.single().availability)
        assertEquals(FormatCheck.PASSED, state.assets.single().format)
        assertEquals(FileAvailability.MISSING, VideoLibraryRepository(repository).entries(state).single().availability)
        backend.access[backend.uri(7)] = FileAccess(FileAvailability.AVAILABLE, 1024)
        backend.inspections[backend.uri(7)] = MediaInspection(FormatCheck.INVALID)
        assertFalse(repository.stateSnapshot().tasks.single().verified)
        assertEquals(2, backend.inspectionCount)
    }
    @Test fun invalidAndUnreadableFilesDoNotPretendToBeAvailableVideos() = withState { dir, backend ->
        val store = DownloadStore(dir); store.save(DownloadData(records = listOf(record()))); backend.completed(7, FormatCheck.INVALID)
        val repository = DownloadRepository(store, backend)
        val invalid = repository.stateSnapshot()
        assertFalse(invalid.tasks.single().verified); assertTrue(VideoLibraryRepository(repository).entries(invalid).isEmpty())
        backend.access[backend.uri(7)] = FileAccess(FileAvailability.UNREADABLE)
        val denied = repository.stateSnapshot()
        assertEquals(FileAvailability.UNREADABLE, denied.tasks.single().availability)
        assertFalse(denied.tasks.single().detail.contains("丢失"))
    }
    @Test fun forgettingOnlyMetadataCannotDeleteFilesOrResurrectLegacyEntries() = withState { dir, backend ->
        val store = DownloadStore(dir) { """[{"id":7,"name":"old.mp4"}]""" }; backend.completed(7)
        val repository = DownloadRepository(store, backend); repository.stateSnapshot()
        repository.forgetRecord(7)
        assertTrue(backend.removed.isEmpty()); assertTrue(backend.rows.containsKey(7))
        val reloaded = DownloadRepository(DownloadStore(dir) { error("no legacy reimport") }, backend)
        assertTrue(reloaded.stateSnapshot().tasks.isEmpty()); assertTrue(reloaded.stateSnapshot().assets.isEmpty())
    }
    @Test fun separateRepositoryInstancesCannotOverwriteEachOthersRecords() = withState { dir, backend ->
        val a = DownloadRepository(DownloadStore(dir), backend)
        val b = DownloadRepository(DownloadStore(dir), backend)
        a.snapshot(); b.enqueue(draft(), true)
        a.enqueue(draft("https://example.com/second.mp4"), false)
        val records = DownloadStore(dir).load().records
        assertEquals(2, records.size); assertEquals(2, a.snapshot().size); assertEquals(2, b.snapshot().size)
        assertTrue(records.all { it.sourceUrl == "https://example.com/watch" && it.sourceTabId == "tab1" && it.sourceGeneration == 8L })
    }
    @Test fun persistenceFailureCreatesNoTransferAndPreservesExistingSystemTask() = withState { dir, backend ->
        val store = DownloadStore(dir); store.save(DownloadData(records = listOf(record()))); backend.completed(7)
        val repository = DownloadRepository(store, backend)
        File(store.file.path + ".new").apply { mkdir(); File(this,"prevent-delete").writeText("failure fixture") }
        assertTrue(runCatching { repository.enqueue(draft(), true) }.isFailure)
        assertTrue(backend.enqueued.isEmpty());assertTrue(backend.removed.isEmpty());assertTrue(backend.rows.containsKey(7))
        assertEquals(listOf(7L), store.load().records.map { it.systemId })
    }
    @Test fun unavailableSystemStateIsNotReportedAsConfirmedTransferFailure() = withState { dir, backend ->
        val store = DownloadStore(dir); store.save(DownloadData(records = listOf(record())))
        backend.rows[7] = SystemDownloadResult.Unavailable
        val task = DownloadRepository(store, backend).snapshot().single()
        assertEquals(SystemTaskRead.UNAVAILABLE, task.systemRead)
        assertEquals(-1L, task.bytes)
        assertTrue(task.detail.contains("未确认")); assertFalse(task.verified)
        assertEquals(1, store.load().records.size)
    }

    @Test fun retryCreatesNewTransferAndRetainsOldSignedUrlNamePolicyAndUserAgent() = withState { dir, backend ->
        val original = retryableRecord()
        val store = DownloadStore(dir)
        store.save(DownloadData(records = listOf(original)))
        // A saved but invalid file is retryable, yet must not be overwritten or deleted by retry.
        backend.completed(7, FormatCheck.INVALID)
        val repository = DownloadRepository(store, backend)
        val before = repository.stateSnapshot()
        assertTrue(before.tasks.single().canRetry)
        val persistedBefore = store.load()
        val id = repository.retry(7, "FallbackAgent", true)
        val after = store.load()
        val old = after.records.single { it.systemId == 7L }
        val fresh = after.records.single { it.recordId == id }
        assertTrue(id.matches(Regex("[a-f0-9-]{36}")))
        assertNull(fresh.systemId); assertEquals(TransferType.CONTROLLED,fresh.transfer)
        assertNotEquals(old.recordId, fresh.recordId)
        assertEquals(old.recordId, fresh.retryOf)
        assertEquals(persistedBefore.records.single(), old)
        assertEquals(persistedBefore.assets, after.assets)
        assertEquals(original.mediaUrl, fresh.mediaUrl)
        assertEquals(original.displayName, fresh.displayName)
        assertNotEquals(original.name, fresh.name)
        assertTrue(fresh.name.endsWith("_${original.displayName}"))
        assertEquals(original.userAgent, fresh.userAgent)
        assertEquals(original.wifiOnly, fresh.wifiOnly)
        assertEquals(original.mimeType, fresh.mimeType)
        assertEquals(original.sourceUrl, fresh.sourceUrl)
        assertEquals(original.sourceTitle, fresh.sourceTitle)
        assertEquals(original.sourceTabId, fresh.sourceTabId)
        assertEquals(original.sourceGeneration, fresh.sourceGeneration)
        assertFalse(fresh.cancelled)
        assertTrue(backend.enqueueCalls.isEmpty())
        assertTrue(backend.removed.isEmpty())
        assertEquals(FileAvailability.AVAILABLE, backend.access[backend.uri(7)]!!.availability)
        val reloaded = DownloadRepository(DownloadStore(dir), backend)
        assertEquals(original.recordId, reloaded.record(id)!!.retryOf)
        assertEquals(original.name, reloaded.record(7)!!.name)
    }

    @Test fun retryOfLegacyRecordUsesRecoveredExactUrlAndExplicitFallbacks() = withState { dir, backend ->
        val store = DownloadStore(dir)
        store.save(DownloadData(records = listOf(record())))
        val signed = "https://example.com/video.mp4?signature=a%2Bb&expires=123"
        backend.rows[7] = present(DownloadManager.STATUS_FAILED).copy(mediaUrl = signed)
        val repository = DownloadRepository(store, backend)
        val id = repository.retry(7, "FallbackAgent/2.0", true)
        val old = repository.record(7)!!
        val fresh = repository.record(id)!!
        assertEquals(signed, old.mediaUrl)
        assertNull(old.userAgent); assertNull(old.wifiOnly)
        assertEquals(old.recordId, fresh.retryOf)
        assertEquals(signed, fresh.mediaUrl)
        assertEquals("FallbackAgent/2.0", fresh.userAgent)
        assertEquals(true, fresh.wifiOnly)
        assertTrue(backend.enqueueCalls.isEmpty())
        assertTrue(backend.removed.isEmpty())
        assertEquals(2, store.load().records.size)
    }

    @Test fun retryRejectsActiveUnavailableAndUsableCompletedTasksWithoutCreatingTransfers() {
        val states = listOf(
            present(DownloadManager.STATUS_PENDING), present(DownloadManager.STATUS_RUNNING),
            present(DownloadManager.STATUS_PAUSED), SystemDownloadResult.Unavailable,
            present(DownloadManager.STATUS_SUCCESSFUL, 1024, 1024),
        )
        states.forEach { system -> withState { dir, backend ->
            val original = retryableRecord()
            val store = DownloadStore(dir)
            store.save(DownloadData(records = listOf(original)))
            backend.completed(7)
            backend.rows[7] = system
            val repository = DownloadRepository(store, backend)
            repository.stateSnapshot()
            val before = store.load()
            assertRejected { repository.retry(7, "FallbackAgent", true) }
            assertEquals(before, store.load())
            assertTrue(backend.enqueued.isEmpty()); assertTrue(backend.removed.isEmpty())
        } }
    }

    @Test fun cancellationPersistsMarkerAndRetryCreatesNewIdWithoutClearingOldRecord() = withState { dir, backend ->
        val original = retryableRecord()
        val store = DownloadStore(dir)
        store.save(DownloadData(records = listOf(original), assets = listOf(indexedAsset(original))))
        backend.rows[7] = present(DownloadManager.STATUS_RUNNING, 512)
        val repository = DownloadRepository(store, backend)
        repository.cancel(7)
        assertEquals(listOf(7L), backend.removed)
        assertEquals(SystemDownloadResult.Missing, backend.query(7))
        assertEquals(original.copy(cancelled = true), store.load().records.single())
        assertTrue(store.load().assets.isEmpty())
        val reloaded = DownloadRepository(DownloadStore(dir), backend)
        val cancelled = reloaded.stateSnapshot().tasks.single()
        assertTrue(cancelled.cancelled); assertTrue(cancelled.canRetry)
        assertTrue(cancelled.detail.contains("取消")); assertFalse(cancelled.verified)
        val id = reloaded.retry(7, "FallbackAgent", true)
        assertTrue(id.matches(Regex("[a-f0-9-]{36}")))
        assertEquals(original.copy(cancelled = true), reloaded.record(7))
        assertEquals(original.recordId, reloaded.record(id)!!.retryOf)
        assertFalse(reloaded.record(id)!!.cancelled)
        assertEquals(2, store.load().records.size)
        assertEquals(listOf(7L), backend.removed) // Retry must not remove the old ID again.
        assertTrue(backend.enqueueCalls.isEmpty())
        assertTrue(backend.enqueueCalls.isEmpty())
        assertEquals(false,reloaded.record(id)!!.wifiOnly)
    }

    @Test fun cancelAcceptsOnlyOngoingStatesAndDoesNotTouchAnotherTask() {
        listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED).forEach { status ->
            withState { dir, backend ->
                val original = retryableRecord()
                val other = record().copy(recordId = "r8", systemId = 8, name = "other.mp4", displayName = "other.mp4")
                val store = DownloadStore(dir)
                store.save(DownloadData(records = listOf(original, other), assets = listOf(indexedAsset(other))))
                backend.rows[7] = present(status)
                backend.completed(8)
                val otherRow = backend.rows[8]
                val otherAccess = backend.access[backend.uri(8)]
                DownloadRepository(store, backend).cancel(7)
                assertEquals(listOf(7L), backend.removed)
                assertEquals(otherRow, backend.rows[8]); assertEquals(otherAccess, backend.access[backend.uri(8)])
                assertEquals(other, store.load().records.single { it.systemId == 8L })
                assertEquals(listOf(indexedAsset(other)), store.load().assets)
                assertTrue(store.load().records.single { it.systemId == 7L }.cancelled)
                assertTrue(backend.enqueued.isEmpty())
            }
        }
    }

    @Test fun unconfirmedCancellationDoesNotSetMarkerOrLoseMetadata() = withState { dir, backend ->
        val original = retryableRecord()
        val before = DownloadData(records = listOf(original), assets = listOf(indexedAsset(original)))
        val store = DownloadStore(dir); store.save(before)
        backend.rows[7] = present(DownloadManager.STATUS_RUNNING)
        backend.onRemove = { 0 }
        assertRejected { DownloadRepository(store, backend).cancel(7) }
        assertEquals(before, store.load())
        assertFalse(store.load().records.single().cancelled)
        assertEquals(present(DownloadManager.STATUS_RUNNING), backend.rows[7])
        assertEquals(listOf(7L), backend.removed)
        assertTrue(backend.enqueued.isEmpty())
    }

    @Test fun cancelRejectsEndedMissingAndUnavailableTasksBeforeRemoval() {
        listOf(present(DownloadManager.STATUS_FAILED), present(DownloadManager.STATUS_SUCCESSFUL),
            SystemDownloadResult.Missing, SystemDownloadResult.Unavailable).forEach { system ->
            withState { dir, backend ->
                val before = DownloadData(records = listOf(retryableRecord()))
                val store = DownloadStore(dir); store.save(before); backend.rows[7] = system
                assertRejected { DownloadRepository(store, backend).cancel(7) }
                assertEquals(before, store.load())
                assertTrue(backend.removed.isEmpty()); assertTrue(backend.enqueued.isEmpty())
            }
        }
    }

    @Test fun forgetRejectsUnavailableAndEveryActiveStateWithoutOrphaningTasks() {
        listOf(present(DownloadManager.STATUS_PENDING), present(DownloadManager.STATUS_RUNNING),
            present(DownloadManager.STATUS_PAUSED), SystemDownloadResult.Unavailable).forEach { system ->
            withState { dir, backend ->
                val original = retryableRecord()
                val before = DownloadData(records = listOf(original), assets = listOf(indexedAsset(original)))
                val store = DownloadStore(dir); store.save(before); backend.rows[7] = system
                assertRejected { DownloadRepository(store, backend).forgetRecord(7) }
                assertEquals(before, store.load()); assertEquals(system, backend.rows[7])
                assertTrue(backend.removed.isEmpty()); assertTrue(backend.enqueued.isEmpty())
            }
        }
    }

    @Test fun metadataRenameUpdatesBothIndexesButPreservesPhysicalFileAndOtherRecords() = withState { dir, backend ->
        val original = retryableRecord()
        val other = record().copy(recordId = "r8", systemId = 8, name = "other.mp4", displayName = "other.mp4")
        val oldAsset = indexedAsset(original)
        val otherAsset = indexedAsset(other)
        val store = DownloadStore(dir)
        store.save(DownloadData(records = listOf(original, other), assets = listOf(oldAsset, otherAsset)))
        backend.completed(7); backend.completed(8)
        val rowsBefore = backend.rows.toMap(); val filesBefore = backend.access.toMap()
        DownloadRepository(store, backend).rename(7, "  重命名的视频  ")
        val data = DownloadStore(dir).load()
        assertEquals(original.copy(displayName = "重命名的视频"), data.records.single { it.systemId == 7L })
        assertEquals(oldAsset.copy(displayName = "重命名的视频"), data.assets.single { it.systemId == 7L })
        assertEquals(other, data.records.single { it.systemId == 8L })
        assertEquals(otherAsset, data.assets.single { it.systemId == 8L })
        assertEquals(rowsBefore, backend.rows); assertEquals(filesBefore, backend.access)
        assertTrue(backend.removed.isEmpty()); assertTrue(backend.enqueued.isEmpty())
        val repository = DownloadRepository(DownloadStore(dir), backend)
        val state = repository.stateSnapshot()
        assertEquals("重命名的视频", state.tasks.single { it.id == "r7" }.displayName)
        val entry = VideoLibraryRepository(repository).entries(state).single { it.systemId == 7L }
        assertEquals("重命名的视频", entry.displayName)
        assertEquals(original.name, entry.name); assertEquals(oldAsset.uri, entry.uri)
    }

    @Test fun invalidMetadataRenameLeavesBothIndexesUnchanged() = withState { dir, backend ->
        val original = retryableRecord()
        val before = DownloadData(records = listOf(original), assets = listOf(indexedAsset(original)))
        val store = DownloadStore(dir); store.save(before)
        val repository = DownloadRepository(store, backend)
        listOf("", "   ", "x".repeat(181), "bad\u0001name", "bad\nname").forEach { title ->
            assertRejected { repository.rename(7, title) }
            assertEquals(before, store.load())
        }
        assertRejected { repository.rename(999, "不存在的记录") }
        assertEquals(before, store.load())
        assertTrue(backend.removed.isEmpty()); assertTrue(backend.enqueued.isEmpty())
    }

    @Test fun positiveDeleteCountAndMissingSystemRowDoNotProveFileDisappearance() = withState { dir, backend ->
        val original = retryableRecord()
        val before = DownloadData(records = listOf(original), assets = listOf(indexedAsset(original)))
        val store = DownloadStore(dir); store.save(before); backend.completed(7)
        val repository = DownloadRepository(store, backend)
        // Default fake remove returns 1 and removes the row, but deliberately retains the file.
        assertRejected { repository.deleteFile(7) }
        assertEquals(listOf(7L), backend.removed)
        assertEquals(SystemDownloadResult.Missing, backend.query(7))
        assertEquals(FileAvailability.AVAILABLE, backend.access[backend.uri(7)]!!.availability)
        assertEquals(before, store.load())
        val state = DownloadRepository(DownloadStore(dir), backend).stateSnapshot()
        assertEquals(1, state.tasks.size); assertEquals(1, state.assets.size)
        assertEquals(original.name, state.assets.single().name)
        assertFalse(state.tasks.single().verified)
        assertEquals(1, VideoLibraryRepository(repository).entries(state).size)
    }

    @Test fun failedOrDeniedBackendDeleteRetainsMetadataAndReadableFile() {
        listOf<(Long) -> Int>({ 0 }, { throw SecurityException("denied by fake") }).forEach { removal ->
            withState { dir, backend ->
                val original = retryableRecord()
                val before = DownloadData(records = listOf(original), assets = listOf(indexedAsset(original)))
                val store = DownloadStore(dir); store.save(before); backend.completed(7)
                val rowBefore = backend.rows[7]
                backend.onRemove = removal
                val result = runCatching { DownloadRepository(store, backend).deleteFile(7) }
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IllegalStateException || result.exceptionOrNull() is SecurityException)
                assertEquals(before, DownloadStore(dir).load())
                assertEquals(rowBefore, backend.rows[7])
                assertEquals(FileAvailability.AVAILABLE, backend.access[backend.uri(7)]!!.availability)
                assertEquals(listOf(7L), backend.removed)
                assertTrue(backend.enqueued.isEmpty())
            }
        }
    }

    @Test fun unreadableOrUnknownFileAfterPositiveDeleteCountMustRetainRecord() {
        listOf(FileAvailability.UNREADABLE, FileAvailability.UNKNOWN).forEach { availability ->
            withState { dir, backend ->
                val original = retryableRecord()
                val before = DownloadData(records = listOf(original), assets = listOf(indexedAsset(original)))
                val store = DownloadStore(dir); store.save(before); backend.completed(7)
                backend.onRemove = { id ->
                    backend.rows.remove(id)
                    backend.access[backend.uri(id)] = FileAccess(availability)
                    1
                }
                val repository = DownloadRepository(store, backend)
                assertRejected { repository.deleteFile(7) }
                assertEquals(before, store.load())
                val state = DownloadRepository(DownloadStore(dir), backend).stateSnapshot()
                assertEquals(original.recordId, state.tasks.single().recordId)
                assertEquals(availability, state.assets.single().availability)
                assertFalse(state.tasks.single().verified)
                assertEquals(1, VideoLibraryRepository(repository).entries(state).size)
                assertEquals(listOf(7L), backend.removed)
            }
        }
    }

    @Test fun confirmedFileDeletionSynchronizesTaskAndLibraryIndexesAndPreservesOtherFile() = withState { dir, backend ->
        val original = retryableRecord()
        val other = record().copy(recordId = "r8", systemId = 8, name = "other.mp4", displayName = "other.mp4")
        val store = DownloadStore(dir)
        store.save(DownloadData(records = listOf(original, other)))
        backend.completed(7); backend.completed(8)
        val repository = DownloadRepository(store, backend)
        assertEquals(2, repository.stateSnapshot().assets.size)
        val otherRecord = store.load().records.single { it.systemId == 8L }
        val otherAsset = store.load().assets.single { it.systemId == 8L }
        val otherRow = backend.rows[8]; val otherFile = backend.access[backend.uri(8)]
        backend.onRemove = { id ->
            val count = if (backend.rows.remove(id) != null) 1 else 0
            backend.access.remove(backend.uri(id))
            count
        }
        repository.deleteFile(7)
        assertEquals(listOf(7L), backend.removed)
        assertEquals(SystemDownloadResult.Missing, backend.query(7))
        assertEquals(FileAvailability.MISSING, backend.access(backend.uri(7)).availability)
        assertEquals(listOf(otherRecord), store.load().records)
        assertEquals(listOf(otherAsset), store.load().assets)
        assertEquals(otherRow, backend.rows[8]); assertEquals(otherFile, backend.access[backend.uri(8)])
        val reloaded = DownloadRepository(DownloadStore(dir), backend)
        val state = reloaded.stateSnapshot()
        assertEquals(listOf("r8"), state.tasks.map { it.id })
        assertEquals(listOf(8L), VideoLibraryRepository(reloaded).entries(state).map { it.systemId })
        assertNull(reloaded.record(7)); assertNull(reloaded.fileUri(7))
        assertTrue(backend.enqueued.isEmpty())
    }

    @Test fun alreadyMissingKnownFileCanBeDeletedFromBothIndexesDespiteZeroRemoveCount() = withState { dir, backend ->
        val original = retryableRecord()
        val store = DownloadStore(dir)
        store.save(DownloadData(records = listOf(original), assets = listOf(indexedAsset(original))))
        // There is no fake row or file. The owned indexed URI supplies actual absence evidence.
        DownloadRepository(store, backend).deleteFile(7)
        assertEquals(listOf(7L), backend.removed)
        assertTrue(store.load().records.isEmpty()); assertTrue(store.load().assets.isEmpty())
        assertTrue(backend.accessCount > 0)
        val state = DownloadRepository(DownloadStore(dir), backend).stateSnapshot()
        assertTrue(state.tasks.isEmpty()); assertTrue(state.assets.isEmpty())
    }

    @Test fun deleteRejectsUnavailableAndActiveStatesBeforeRemovingAnything() {
        listOf(present(DownloadManager.STATUS_PENDING), present(DownloadManager.STATUS_RUNNING),
            present(DownloadManager.STATUS_PAUSED), SystemDownloadResult.Unavailable).forEach { system ->
            withState { dir, backend ->
                val original = retryableRecord()
                val before = DownloadData(records = listOf(original), assets = listOf(indexedAsset(original)))
                val store = DownloadStore(dir); store.save(before); backend.rows[7] = system
                assertRejected { DownloadRepository(store, backend).deleteFile(7) }
                assertEquals(before, store.load()); assertEquals(system, backend.rows[7])
                assertTrue(backend.removed.isEmpty()); assertTrue(backend.enqueued.isEmpty())
            }
        }
    }

    @Test fun unknownTotalsAndMissingSystemCountersNeverFabricateCompletionOrFileSize() = withState { dir, backend ->
        val store = DownloadStore(dir)
        store.save(DownloadData(records = listOf(retryableRecord())))
        val repository = DownloadRepository(store, backend)
        listOf(-1L, 0L).forEach { total ->
            backend.rows[7] = present(DownloadManager.STATUS_RUNNING, 8192, total)
            val state = repository.stateSnapshot()
            val task = state.tasks.single()
            assertEquals(8192L, task.bytes); assertEquals(total, task.total)
            assertEquals(DownloadManager.STATUS_RUNNING, task.status)
            assertEquals(SystemTaskRead.PRESENT, task.systemRead)
            assertFalse(task.verified); assertFalse(task.canRetry)
            assertTrue(state.assets.isEmpty())
        }
        backend.rows.remove(7)
        val missing = repository.stateSnapshot().tasks.single()
        assertEquals(-1L, missing.total); assertEquals(-1L, missing.bytes)
        assertEquals(SystemTaskRead.MISSING, missing.systemRead)
        assertFalse(missing.verified)
        assertEquals(1, store.load().records.size)
        assertTrue(backend.enqueued.isEmpty()); assertTrue(backend.removed.isEmpty())
    }

    @Test fun httpsEnqueuePreservesExactSignedUrlInBothReleaseAndDebugModes() {
        listOf(false, true).forEach { debug -> withState { dir, backend ->
            val store = DownloadStore(dir)
            val repository = DownloadRepository(store, backend, allowLocalHttp = debug)
            val signed = "https://example.com:443/video.mp4?signature=a%2Bb&policy=exact%2Fone"
            val id = repository.enqueue(draft(signed), true, "signed_video.mp4")
            assertTrue(id.matches(Regex("[a-f0-9-]{36}")))
            val saved = store.load().records.single()
            assertEquals(signed, saved.mediaUrl)
            assertTrue(backend.enqueueCalls.isEmpty())
            assertTrue(saved.name.endsWith("_signed_video.mp4"))
            assertTrue(backend.removed.isEmpty())
        } }
    }

    @Test fun debugHttpGateAllowsOnlyExplicitLoopbackAndEmulatorHosts() {
        listOf("127.0.0.1", "10.0.2.2").forEach { host -> withState { dir, backend ->
            val store = DownloadStore(dir)
            val url = "http://$host:8080/video.mp4?signature=a%2Bb"
            val id = DownloadRepository(store, backend, allowLocalHttp = true).enqueue(draft(url), false)
            assertTrue(id.matches(Regex("[a-f0-9-]{36}")))
            assertEquals(url, store.load().records.single().mediaUrl)
            assertEquals(url, store.load().records.single().mediaUrl)
            assertEquals(false,store.load().records.single().wifiOnly)
            assertTrue(backend.removed.isEmpty())
        } }
    }

    @Test fun releaseHttpAndUnsafeDebugUrlsAreRejectedBeforeBackendEnqueue() {
        val disallowed = listOf(
            false to "http://127.0.0.1:8080/video.mp4",
            false to "http://10.0.2.2:8080/video.mp4",
            false to "http://example.com/video.mp4",
            true to "http://example.com/video.mp4",
            true to "http://localhost/video.mp4",
            true to "http://[::1]/video.mp4",
            true to "http://127.0.0.1.evil.example/video.mp4",
            true to "http://10.0.2.3/video.mp4",
            true to "http://user:secret@127.0.0.1/video.mp4",
            false to "https://user:secret@example.com/video.mp4",
            false to "https://example.com:65536/video.mp4",
            true to "http://127.0.0.1:65536/video.mp4",
            false to "file:///sdcard/video.mp4",
            true to "content://downloads/all_downloads/7",
            true to "ftp://127.0.0.1/video.mp4",
        )
        disallowed.forEach { (debug, url) -> withState { dir, backend ->
            val store = DownloadStore(dir)
            assertRejected { DownloadRepository(store, backend, allowLocalHttp = debug).enqueue(draft(url), true) }
            assertTrue(store.load().records.isEmpty()); assertTrue(store.load().assets.isEmpty())
            assertTrue(backend.enqueued.isEmpty()); assertTrue(backend.enqueueCalls.isEmpty())
            assertTrue(backend.rows.isEmpty()); assertTrue(backend.removed.isEmpty())
        } }
    }

    @Test fun retryReappliesUrlGateRatherThanTrustingOldHttpMetadata() = withState { dir, backend ->
        val original = retryableRecord().copy(mediaUrl = "http://127.0.0.1:8080/video.mp4?signature=a%2Bb")
        val before = DownloadData(records = listOf(original))
        val store = DownloadStore(dir); store.save(before)
        backend.rows[7] = present(DownloadManager.STATUS_FAILED)
        assertRejected { DownloadRepository(store, backend).retry(7, "FallbackAgent", true) }
        assertEquals(before, store.load())
        assertTrue(backend.enqueued.isEmpty()); assertTrue(backend.removed.isEmpty())
        val debug = DownloadRepository(store, backend, allowLocalHttp = true)
        val id = debug.retry(7, "FallbackAgent", true)
        assertTrue(id.matches(Regex("[a-f0-9-]{36}")))
        assertEquals(original.mediaUrl, debug.record(id)!!.mediaUrl)
        assertEquals(original.recordId, debug.record(id)!!.retryOf)
        assertEquals(original, debug.record(7))
        assertTrue(backend.removed.isEmpty())
    }
    @Test fun firstUnreadableUriCannotBeDeclaredLostOrValidatedVideoFromMetadataAlone() = withState { dir, backend ->
        val store=DownloadStore(dir);store.save(DownloadData(records=listOf(record())))
        backend.completed(7)
        backend.access[backend.uri(7)]=FileAccess(FileAvailability.MISSING)
        val state=DownloadRepository(store,backend).stateSnapshot()
        assertEquals(FileAvailability.UNKNOWN,state.tasks.single().availability)
        assertEquals(FormatCheck.UNCONFIRMED,state.tasks.single().format)
        assertFalse(state.tasks.single().verified)
        assertFalse(state.tasks.single().detail.contains("丢失"))
        assertTrue(VideoLibraryRepository(DownloadRepository(store,backend)).entries(state).isEmpty())
    }

}
