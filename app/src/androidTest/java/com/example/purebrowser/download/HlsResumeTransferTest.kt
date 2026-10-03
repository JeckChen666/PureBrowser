package com.example.purebrowser.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.hls.*
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same bounded, exact-URL fake transport + self-owned TS fixtures as HlsTransferTest. No sockets. */
@RunWith(AndroidJUnit4::class)
class HlsResumeTransferTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testApp get() = InstrumentationRegistry.getInstrumentation().context
    private val entry = "https://source.example/hls/video.m3u8?private=entry%2Bexact"
    private val urls get() = (0..3).map { "https://source.example/hls/segment-%03d.ts".format(Locale.US, it) }
    private val noCookies = AccessContextProvider { null }
    private fun bytes(i: Int) = testApp.assets.open("hls/segment-%03d.ts".format(Locale.US, i)).use { it.readBytes() }
    private fun playlist() = testApp.assets.open("hls/video.m3u8").use { it.readBytes().toString(Charsets.UTF_8) }
    private class NoSystem : DownloadBackend {
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long = error("No DownloadManager")
        override fun query(id: Long) = SystemDownloadResult.Missing
        override fun fileUri(id: Long): String? = null
        override fun access(uri: String) = FileAccess(FileAvailability.MISSING)
        override fun inspect(uri: String) = MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id: Long) = error("No DownloadManager")
    }
    private data class Request(val url: String, val headers: Map<String, String>)
    private class Fake : HttpTransport {
        private val requests = Collections.synchronizedList(mutableListOf<Request>())
        val routes = ConcurrentHashMap<String, (TransferCancellation) -> HttpResponse>()
        val active = AtomicInteger(); val maximum = AtomicInteger()
        fun requests(): List<Request> = synchronized(requests) { requests.toList() }
        fun calls(url: String) = requests().count { it.url == url }
        override fun open(url: String, headers: Map<String, String>, cancel: TransferCancellation): HttpResponse {
            cancel.check(); requests.add(Request(url, headers.toMap()))
            val now = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, now) }
            try {
                val response = routes[url]?.invoke(cancel) ?: throw AssertionError("Unmapped fake URL")
                return object : HttpResponse {
                    private val closed = AtomicBoolean()
                    override val status = response.status
                    override fun header(name: String) = response.header(name)
                    override fun body() = response.body()
                    override fun close() { if (closed.compareAndSet(false, true)) try { response.close() } finally { active.decrementAndGet() } }
                }
            } catch (failure: Throwable) { active.decrementAndGet(); throw failure }
        }
    }
    private fun response(bytes: ByteArray, body: (() -> InputStream)? = null) = object : HttpResponse {
        override val status = 200
        override fun header(name: String) = if (name.equals("Content-Length", true)) bytes.size.toString() else null
        override fun body() = body?.invoke() ?: ByteArrayInputStream(bytes)
        override fun close() {}
    }
    private inner class Harness(val repo: DownloadRepository, val fake: Fake, val id: TaskId) {
        val ws get() = repo.files!!.hlsWorkspace
        fun run(cancel: TransferCancellation = TransferCancellation()) {
            HlsTransfer(repo, fake, noCookies).run(id, cancel)
            assertEquals(0, fake.active.get())
            assertTrue(fake.requests().all { r -> r.headers.keys.none { it.equals("Range", true) } })
            assertTrue(fake.maximum.get() <= 2)
        }
        fun resume() {
            // Coordinator contract: explicitly queue only AFTER the previous run/workers finish.
            repo.change(id) { it.copy(taskStatus = TaskStatus.QUEUED, failure = null, pauseReason = null) }
            run()
        }
        fun assertSucceeded() {
            val record = repo.record(id)!!
            assertEquals(TaskStatus.SUCCEEDED, record.taskStatus); assertEquals(4, record.completedSegments)
            assertFalse(record.resumeAvailable); assertNull(record.pendingUri)
            assertEquals(FormatCheck.PASSED, repo.stateSnapshot().assets.single { it.recordId == id }.format)
            assertFalse(repo.files!!.stage(id).exists()); assertFalse(File(app.filesDir, "hls/$id").exists())
        }
        fun seedAll() {
            val plan = ws.load(id); assertTrue(ws.start(id, plan, 0))
            (0..3).forEach { i -> ws.segmentPart(id, i).writeBytes(bytes(i)); ws.completeSegment(id, plan, i) {} }
            repo.change(id) { it.copy(taskStatus = TaskStatus.INTERRUPTED, failure = FailureKind.INTERRUPTED,
                pauseReason = PauseReason.RECOVERY, resumeAvailable = true, completedSegments = 4) }
        }
    }
    private fun withRepo(block: (Harness) -> Unit) {
        val directory = File(app.cacheDir, "hls-resume-store-${UUID.randomUUID()}").apply { mkdirs() }
        val repo = DownloadRepository(DownloadStore(directory), NoSystem(), false, ManagedFileStore(app))
        val fake = Fake()
        fake.routes[entry] = { response(playlist().toByteArray()) }
        urls.forEachIndexed { i, url -> fake.routes[url] = { response(bytes(i)) } }
        val draft = DownloadDraft(MediaCandidate(entry, MediaKind.HLS, setOf(Evidence.DOM)), "HlsResumeTestAgent",
            sourceUrl = "https://source.example/watch", useAccessContext = false)
        val resolver = HlsResolver(fake, noCookies, false)
        val token = TransferCancellation()
        val options = resolver.resolveEntry(draft, token)
        val plan = resolver.resolvePlan(draft, options, null, token)
        val id = repo.enqueue(draft, false, "hls-resume-test.mp4", hlsPlan = plan)
        try { block(Harness(repo, fake, id)) } finally {
            // Only this test's minted task ID/public output, not any shared/public directory sweep.
            if (repo.record(id) != null) {
                repo.change(id) { it.copy(taskStatus = TaskStatus.INTERRUPTED) }
                if (repo.stateSnapshot().assets.any { it.recordId == id }) repo.deleteFile(id) else repo.forgetRecord(id)
            }
            directory.deleteRecursively()
        }
    }
    private class Gate {
        val entered = CountDownLatch(2); private val release = CountDownLatch(1)
        fun body(bytes: ByteArray, cancel: TransferCancellation): InputStream {
            cancel.bind { release.countDown() }
            return object : ByteArrayInputStream(bytes) {
                private var reads = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    cancel.check()
                    if (reads++ == 1) {
                        entered.countDown()
                        if (!release.await(15, TimeUnit.SECONDS)) throw IOException("Read barrier expired")
                        cancel.check()
                    }
                    return super.read(buffer, offset, minOf(length, 188 * 5))
                }
            }
        }
    }
    private fun pauseAfterTwo(h: Harness, state: TaskStatus = TaskStatus.PAUSING) {
        val gate = Gate()
        (2..3).forEach { i -> h.fake.routes[urls[i]] = { token -> response(bytes(i)) { gate.body(bytes(i), token) } } }
        val token = TransferCancellation(); val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit { HlsTransfer(h.repo, h.fake, noCookies).run(h.id, token) }
        try {
            assertTrue("Two COMPLETE pieces, two partial writers", gate.entered.await(15, TimeUnit.SECONDS))
            assertEquals(2, h.repo.record(h.id)!!.completedSegments)
            // Stop state must precede cancellation, including the asynchronous response-close callbacks.
            h.repo.change(h.id) { it.copy(taskStatus = state, pauseReason = PauseReason.USER) }
            token.cancel(); future.get(20, TimeUnit.SECONDS)
            assertEquals(state, h.repo.record(h.id)!!.taskStatus)
            assertEquals(0, h.fake.active.get())
        } finally { token.cancel(); pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)) }
        (2..3).forEach { i -> h.fake.routes[urls[i]] = { response(bytes(i)) } }
    }

    @Test(timeout = 30_000) fun pauseBeforeFirstPieceCompletesCanExplicitlyResumeFromZero() = withRepo { h ->
        val gate = Gate()
        (0..1).forEach { i -> h.fake.routes[urls[i]] = { token -> response(bytes(i)) { gate.body(bytes(i), token) } } }
        val token = TransferCancellation(); val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit { HlsTransfer(h.repo, h.fake, noCookies).run(h.id, token) }
        try {
            assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
            assertEquals(0, h.repo.record(h.id)!!.completedSegments)
            h.repo.change(h.id) { it.copy(taskStatus = TaskStatus.PAUSING, pauseReason = PauseReason.USER) }
            token.cancel(); future.get(20, TimeUnit.SECONDS)
            // Parent settles PAUSING only after this run and every piece writer have ended.
            h.repo.change(h.id) { it.copy(taskStatus = TaskStatus.PAUSED) }
            val stopped = h.repo.record(h.id)!!
            assertTrue(stopped.resumeAvailable); assertTrue(h.ws.hasResumeData(h.id))
            assertEquals(0L, stopped.received); assertEquals(0L, h.ws.cacheBytes(h.id))
            assertEquals(0, stopped.completedSegments)
            assertTrue(h.ws.verifiedPieces(h.id, h.ws.load(h.id)).isEmpty())
            (0..1).forEach { assertFalse(h.ws.segmentPart(h.id, it).exists()) }
            assertEquals(0, h.fake.active.get())
        } finally { token.cancel(); pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)) }
        (0..1).forEach { i -> h.fake.routes[urls[i]] = { response(bytes(i)) } }
        h.resume(); h.assertSucceeded()
        assertEquals(2, h.fake.calls(entry)) // Resolve once, then resume identity validation.
        (0..1).forEach { assertEquals(2, h.fake.calls(urls[it])) }
        (2..3).forEach { assertEquals(1, h.fake.calls(urls[it])) }
    }

    @Test(timeout = 30_000) fun freshFrozenPlanStartsSegmentsWithoutAnotherManifestGet() = withRepo { h ->
        assertEquals(1, h.fake.calls(entry))
        h.fake.routes.remove(entry) // One-use signed manifest; only TS routes exist for first run.
        h.run(); h.assertSucceeded()
        assertEquals(1, h.fake.calls(entry)); urls.forEach { assertEquals(1, h.fake.calls(it)) }
        assertEquals(5, h.fake.requests().size)
    }

    @Test(timeout = 30_000) fun legacyUnknownSequenceRequiresProofAndNeverReusesUncheckpointedPieces() = withRepo { h ->
        val file = File(app.filesDir, "hls/${h.id}/plan.json")
        val legacy = org.json.JSONObject(file.readText()).put("version", 1).apply { remove("mediaSequence") }
        file.writeText(legacy.toString())
        assertTrue(File(app.filesDir, "hls/${h.id}/checkpoint.json").delete())
        h.ws.segment(h.id, 0).writeBytes(bytes(0)) // Old unverified workspace: never reuse this file.
        assertTrue(h.ws.hasResumeData(h.id))
        h.fake.routes[entry] = { response(playlist().replace("MEDIA-SEQUENCE:0", "MEDIA-SEQUENCE:7").toByteArray()) }
        h.run(); h.assertSucceeded()
        assertEquals(2, h.fake.calls(entry)); urls.forEach { assertEquals(1, h.fake.calls(it)) }
    }

    @Test(timeout = 90_000) fun allStoppedStatesKeepVerifiedPiecesAndExplicitResumeSkipsThem() {
        listOf(TaskStatus.PAUSING, TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED).forEach { state ->
            withRepo { h ->
                pauseAfterTwo(h, state)
                assertTrue(h.repo.record(h.id)!!.resumeAvailable); assertTrue(h.ws.hasResumeData(h.id))
                assertEquals(bytes(0).size.toLong() + bytes(1).size, h.ws.cacheBytes(h.id))
                assertFalse(h.ws.segmentPart(h.id, 2).exists()); assertFalse(h.ws.segmentPart(h.id, 3).exists())
                assertTrue(h.ws.segment(h.id, 0).isFile); assertEquals(1, h.fake.calls(entry))
                // Calling the transfer while stopped must neither GET nor overwrite the stop state.
                h.run(); assertEquals(state, h.repo.record(h.id)!!.taskStatus); assertEquals(1, h.fake.calls(entry))
                h.resume(); h.assertSucceeded()
                assertEquals(2, h.fake.calls(entry))
                (0..1).forEach { assertEquals(1, h.fake.calls(urls[it])) }
                (2..3).forEach { assertEquals(2, h.fake.calls(urls[it])) }
            }
        }
    }

    @Test(timeout = 90_000) fun changedSequenceOrderDurationSignatureOrEndMarkerCannotReusePieces() {
        val changes = listOf<(String) -> String>(
            { it.replace("MEDIA-SEQUENCE:0", "MEDIA-SEQUENCE:1") },
            { it.replace("segment-000.ts", "swap.ts").replace("segment-001.ts", "segment-000.ts").replace("swap.ts", "segment-001.ts") },
            { it.replaceFirst("2.000000", "1.500000") },
            { it.replace("segment-000.ts", "segment-000.ts?signature=replaced") },
            { it.replace("#EXT-X-ENDLIST", "") },
        )
        changes.forEach { change -> withRepo { h ->
            pauseAfterTwo(h)
            val before = urls.map { h.fake.calls(it) }
            h.fake.routes[entry] = { response(change(playlist()).toByteArray()) }
            h.resume()
            val record = h.repo.record(h.id)!!
            assertEquals(TaskStatus.FAILED, record.taskStatus); assertEquals(PauseReason.SOURCE_CHANGED, record.pauseReason)
            assertFalse(record.resumeAvailable); assertFalse(h.ws.hasResumeData(h.id))
            assertEquals(before, urls.map { h.fake.calls(it) })
            assertTrue(h.repo.stateSnapshot().assets.none { it.recordId == h.id })
        } }
    }

    @Test(timeout = 30_000) fun completePiecesColdRecoverAndRemuxRatherThanContinuePartialMp4() = withRepo { h ->
        h.seedAll()
        h.repo.files!!.stage(h.id).writeText("partial MP4 must not be appended")
        val recovered = HlsWorkspace(File(app.filesDir, "hls"))
        recovered.discardIncomplete(h.id)
        assertTrue(recovered.hasResumeData(h.id))
        h.resume(); h.assertSucceeded()
        assertEquals(2, h.fake.calls(entry)); urls.forEach { assertEquals(0, h.fake.calls(it)) }
    }

    @Test(timeout = 30_000) fun sameSizeCorruptPieceIsRefetchedInFullWhileOtherPiecesAreReused() = withRepo { h ->
        h.seedAll()
        val file = h.ws.segment(h.id, 1)
        val corrupt = file.readBytes().apply { this[100] = (this[100].toInt() xor 1).toByte() }
        file.writeBytes(corrupt)
        h.resume(); h.assertSucceeded()
        urls.forEachIndexed { i, url -> assertEquals(if (i == 1) 1 else 0, h.fake.calls(url)) }
    }

    @Test(timeout = 30_000) fun recoverableNetworkFailurePreservesPiecesButCancellationDeletesWorkspace() = withRepo { h ->
        h.seedAll()
        h.fake.routes[entry] = { throw IOException("Synthetic offline") }
        h.resume()
        assertEquals(TaskStatus.FAILED, h.repo.record(h.id)!!.taskStatus)
        assertEquals(FailureKind.NETWORK, h.repo.record(h.id)!!.failure)
        assertTrue(h.repo.record(h.id)!!.resumeAvailable); assertTrue(h.ws.hasResumeData(h.id))
        assertEquals(4, h.fake.calls(entry)) // Resolve + bounded three validation GET attempts.
        h.repo.change(h.id) { it.copy(taskStatus = TaskStatus.CANCELLED, cancelled = true) }
        h.run()
        assertFalse(h.ws.hasResumeData(h.id)); assertFalse(File(app.filesDir, "hls/${h.id}").exists())
        assertFalse(h.repo.record(h.id)!!.resumeAvailable)
    }

    @Test(timeout = 30_000) fun recoverableStorageFailureKeepsOtherCompletePiecesWithoutRetryingTs() = withRepo { h ->
        h.seedAll()
        assertTrue(h.ws.segment(h.id, 2).delete())
        h.fake.routes[urls[2]] = { throw TransferFailure(FailureKind.STORAGE, "合成存储失败") }
        h.resume()
        val record = h.repo.record(h.id)!!
        assertEquals(TaskStatus.FAILED, record.taskStatus); assertEquals(FailureKind.STORAGE, record.failure)
        assertEquals(PauseReason.STORAGE, record.pauseReason); assertTrue(record.resumeAvailable)
        assertEquals(3, record.completedSegments); assertEquals(1, h.fake.calls(urls[2]))
        h.fake.routes[urls[2]] = { response(bytes(2)) }
        h.resume(); h.assertSucceeded()
        urls.forEachIndexed { i, url -> assertEquals(if (i == 2) 2 else 0, h.fake.calls(url)) }
    }

    @Test(timeout = 30_000) fun cancelActivePartialWritersJoinsThemBeforeRemovingWorkspace() = withRepo { h ->
        pauseAfterTwo(h, TaskStatus.CANCELLED)
        assertFalse(File(app.filesDir, "hls/${h.id}").exists())
        assertFalse(h.repo.record(h.id)!!.resumeAvailable); assertEquals(0, h.fake.active.get())
    }

    @Test(timeout = 30_000) fun alteredLocalPlanIsRejectedBeforeAnyResumeRequest() = withRepo { h ->
        h.seedAll()
        val file = File(app.filesDir, "hls/${h.id}/plan.json")
        file.writeText(file.readText().replace("segment-000.ts", "attacker.ts"))
        h.resume()
        assertEquals(TaskStatus.FAILED, h.repo.record(h.id)!!.taskStatus)
        assertFalse(h.repo.record(h.id)!!.resumeAvailable)
        assertEquals(1, h.fake.calls(entry)); urls.forEach { assertEquals(0, h.fake.calls(it)) }
    }
}
