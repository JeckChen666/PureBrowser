package com.example.purebrowser.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic boundary tests: fake transport, isolated metadata, no service/device lifecycle. */
@RunWith(AndroidJUnit4::class)
class PrivacyBoundaryTest {
    private class Backend : DownloadBackend {
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long = error("No system enqueue")
        override fun query(id: Long): SystemDownloadResult = SystemDownloadResult.Missing
        override fun fileUri(id: Long): String? = null
        override fun access(uri: String) = FileAccess(FileAvailability.MISSING)
        override fun inspect(uri: String) = MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id: Long): Int = error("No system deletion")
    }

    private fun fixture(block: (DownloadRepository) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "privacy-boundary-${UUID.randomUUID()}").apply { mkdirs() }
        val repo = DownloadRepository(DownloadStore(root), Backend(), files = ManagedFileStore(app))
        try { block(repo) } finally { root.deleteRecursively() }
    }

    private fun draft() = DownloadDraft(
        MediaCandidate("https://example.com/video.mp4", MediaKind.FILE, emptySet()),
        "PrivacyBoundaryTest", useAccessContext = false,
    )

    private fun await(latch: CountDownLatch) {
        assertTrue("Boundary was not reached", latch.await(5, TimeUnit.SECONDS))
    }

    @Test fun oldEnqueueEpochRemainsRejectedAfterGateReopens() = fixture { repo ->
        val before = repo.requestGeneration()
        repo.beginPrivacyExclusion()
        assertTrue(runCatching { repo.enqueue(draft(), false, expectedPrivacyGeneration = before) }.isFailure)
        assertTrue(runCatching { repo.requestGeneration() }.isFailure)
        repo.awaitRequestQuiescence()
        repo.transfersAllowed = true
        assertTrue(runCatching { repo.enqueue(draft(), false, expectedPrivacyGeneration = before) }.isFailure)
        assertTrue(repo.records().isEmpty())
        val after = repo.requestGeneration()
        assertNotEquals(before, after)
        val id = repo.enqueue(draft(), false, expectedPrivacyGeneration = after)
        assertEquals(TaskStatus.QUEUED, repo.record(id)!!.taskStatus)
    }

    @Test fun resumeCannotCrossClosedGateButFreshExplicitResumeCanProceed() = fixture { repo ->
        val id = repo.enqueue(draft(), false)
        repo.pause(id)
        repo.beginPrivacyExclusion()
        assertTrue(runCatching { repo.queueResume(id) }.isFailure)
        assertEquals(TaskStatus.PAUSED, repo.record(id)!!.taskStatus)
        repo.awaitRequestQuiescence()
        repo.transfersAllowed = true
        var crossed = false
        repo.transferInFlight = {
            if (!crossed) {
                crossed = true
                // Cross the boundary after queueResume captured its epoch, without changing its record.
                repo.beginPrivacyExclusion()
                repo.awaitRequestQuiescence()
                repo.transfersAllowed = true
            }
            false
        }
        assertTrue(runCatching { repo.queueResume(id) }.isFailure)
        assertEquals(TaskStatus.PAUSED, repo.record(id)!!.taskStatus)
        repo.queueResume(id)
        assertEquals(TaskStatus.QUEUED, repo.record(id)!!.taskStatus)
    }

    @Test fun retryResponseFromOldEpochCannotEnqueueAfterGateReopens() = fixture { repo ->
        val id = UUID.randomUUID().toString()
        val url = "https://example.com/media.m3u8"
        val original = DownloadRecord(recordId = id, name = "${id.take(8)}_retry.mp4", mediaUrl = url,
            transfer = TransferType.CONTROLLED, protocol = DownloadProtocol.HLS,
            taskStatus = TaskStatus.FAILED, failure = FailureKind.NETWORK, hlsPlaylistUrl = url,
            segmentCount = 1, plannedDurationUs = 1_000_000L, useAccessContext = false)
        repo.store.save(DownloadData(records = listOf(original)))
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n"
        var closed = false
        repo.retryTransport = HttpTransport { _, _, _ ->
            object : HttpResponse {
                override val status = 200
                override fun header(name: String): String? = null
                override fun body() = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
                override fun close() {
                    closed = true
                    // Deterministic HTTP-result/operation-commit gap. No wait on our own lease here.
                    repo.beginPrivacyExclusion()
                    repo.transfersAllowed = true
                }
            }
        }
        val failure = runCatching { repo.retry(id, "PrivacyBoundaryTest", false) }.exceptionOrNull()
        assertTrue(closed)
        assertTrue("Retry must fail at the epoch check, not parsing", failure?.message?.contains("网站会话已清理") == true)
        repo.awaitRequestQuiescence()
        assertEquals(listOf(original), repo.records())
    }

    @Test fun pauseInvokesCapturedOldLeaseNotReplacement() = fixture { repo ->
        val id = repo.enqueue(draft(), false)
        repo.change(id) { it.copy(taskStatus = TaskStatus.RUNNING, resumeAvailable = true) }
        val old = TransferCancellation()
        val replacement = TransferCancellation()
        val current = AtomicReference<TransferCancellation?>(old)
        val atStop = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        repo.transferInFlight = { current.get() != null }
        repo.stopTransfer = { error("Pause must not look up the replacement lease") }
        repo.captureStop = {
            assertTrue(Thread.holdsLock(DownloadStore.transactionLock))
            val captured = current.get()
            val stop: () -> Unit = {
                assertFalse(Thread.holdsLock(DownloadStore.transactionLock))
                atStop.countDown()
                await(releaseStop)
                captured?.cancel()
                Unit
            }
            stop
        }
        try {
            val pause = worker.submit { repo.pause(id) }
            await(atStop)
            assertEquals(TaskStatus.PAUSING, repo.record(id)!!.taskStatus)
            // Emulate old-worker settlement, explicit resume, and installation of the next lease.
            current.set(null)
            repo.change(id) { it.copy(taskStatus = TaskStatus.PAUSED, resumeAvailable = false) }
            repo.queueResume(id)
            current.set(replacement)
            repo.change(id) { it.copy(taskStatus = TaskStatus.RUNNING) }
            releaseStop.countDown()
            pause.get(5, TimeUnit.SECONDS)
            assertTrue(runCatching { old.check() }.isFailure)
            replacement.check()
            assertEquals(TaskStatus.RUNNING, repo.record(id)!!.taskStatus)
        } finally {
            releaseStop.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 30_000) fun privacyCancelsRequestAndWaitsUntilResponseCloseActuallyFinishes() = fixture { repo ->
        val token = TransferCancellation()
        val cancelled = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val guarded = repo.guardedTransport(HttpTransport { _, _, cancel ->
            cancel.bind { cancelled.countDown() }
            object : HttpResponse {
                override val status = 200
                override fun header(name: String): String? = null
                override fun body() = ByteArrayInputStream(byteArrayOf(1))
                override fun close() {
                    closing.countDown()
                    // Hold the resource until the test explicitly releases it. The generic
                    // five-second boundary helper could throw here on a busy emulator,
                    // correctly releasing the real lease but creating a false privacy failure.
                    releaseClose.await()
                }
            }
        })
        val response = guarded.open("https://example.com/video.mp4", emptyMap(), token)
        try {
            repo.beginPrivacyExclusion()
            await(cancelled)
            val close = workers.submit { response.close() }
            await(closing)
            val quiescent = workers.submit { waiting.countDown(); repo.awaitRequestQuiescence() }
            await(waiting)
            try {
                quiescent.get(100, TimeUnit.MILLISECONDS)
                fail("Privacy exclusion returned before response.close finished")
            } catch (_: TimeoutException) { /* expected while close is held */ }
            assertTrue(runCatching { guarded.open("https://example.com/other", emptyMap(), TransferCancellation()) }.isFailure)
            releaseClose.countDown()
            close.get(5, TimeUnit.SECONDS)
            quiescent.get(5, TimeUnit.SECONDS)
        } finally {
            releaseClose.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
