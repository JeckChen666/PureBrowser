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
    private class FakeBackend : DownloadBackend {
        val rows = mutableMapOf<Long, SystemDownloadResult>()
        val access = mutableMapOf<String, FileAccess>()
        val inspections = mutableMapOf<String, MediaInspection>()
        val removed = mutableListOf<Long>()
        val enqueued = mutableListOf<String>()
        var inspectionCount = 0
        var accessCount = 0
        var onEnqueue: (() -> Unit)? = null
        private var nextId = 100L
        fun uri(id: Long) = "content://downloads/all_downloads/$id"
        fun completed(id: Long, check: FormatCheck = FormatCheck.PASSED) {
            rows[id] = SystemDownloadResult.Present(DownloadManager.STATUS_SUCCESSFUL, 1024, 1024, 0,
                "https://example.com/video.mp4?signature=a%2Bb", "video/mp4", 456)
            access[uri(id)] = FileAccess(FileAvailability.AVAILABLE, 1024)
            inspections[uri(id)] = MediaInspection(check, "video/mp4", 2000)
        }
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long {
            enqueued += url; onEnqueue?.invoke()
            val id = nextId++
            rows[id] = SystemDownloadResult.Present(DownloadManager.STATUS_PENDING, 0, -1, 0, url)
            return id
        }
        override fun query(id: Long) = rows[id] ?: SystemDownloadResult.Missing
        override fun fileUri(id: Long) = if (rows[id] is SystemDownloadResult.Present) uri(id) else null
        override fun access(uri: String): FileAccess { accessCount++; return access[uri] ?: FileAccess(FileAvailability.MISSING) }
        override fun inspect(uri: String): MediaInspection { inspectionCount++; return inspections[uri] ?: MediaInspection(FormatCheck.UNCONFIRMED) }
        override fun remove(id: Long): Int { removed += id; return if (rows.remove(id) != null) 1 else 0 }
    }
    private fun withState(action: (File, FakeBackend) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "download-repository-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { action(directory, FakeBackend()) } finally { directory.deleteRecursively() }
    }
    private fun record() = DownloadRecord("r7", 7, "video.mp4")
    private fun draft(url: String = "https://example.com/video.mp4?token=a%2Bb") =
        DownloadDraft(MediaCandidate(url, MediaKind.FILE, setOf(Evidence.DOM)), "agent", "https://example.com/watch", "Source", "tab1", 8)

    @Test fun migrationNeverCreatesOrDeletesTasksAndRetainsUnknownFields() = withState { dir, backend ->
        val store = DownloadStore(dir) { """[{"id":7,"name":"old.mp4"}]""" }
        val repository = DownloadRepository(store, backend)
        assertEquals(7L, repository.snapshot().single().id)
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
    @Test fun persistenceFailureRollsBackOnlyThisNewSystemTask() = withState { dir, backend ->
        val store = DownloadStore(dir); store.save(DownloadData(records = listOf(record()))); backend.completed(7)
        val repository = DownloadRepository(store, backend)
        backend.onEnqueue = { File(store.file.path + ".new").mkdir() }
        assertTrue(runCatching { repository.enqueue(draft(), true) }.isFailure)
        assertEquals(listOf(100L), backend.removed); assertTrue(backend.rows.containsKey(7)); assertFalse(backend.rows.containsKey(100L))
        assertEquals(listOf(7L), store.load().records.map { it.systemId })
    }
    @Test fun unavailableSystemStateIsNotReportedAsConfirmedTransferFailure() = withState { dir, backend ->
        val store = DownloadStore(dir); store.save(DownloadData(records = listOf(record())))
        backend.rows[7] = SystemDownloadResult.Unavailable
        val task = DownloadRepository(store, backend).snapshot().single()
        assertEquals(SystemTaskRead.UNAVAILABLE, task.systemRead)
        assertTrue(task.detail.contains("未确认")); assertFalse(task.verified)
        assertEquals(1, store.load().records.size)
    }
}
