package com.example.purebrowser.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real private stage files and MediaStore publishing, with explicit fake GET responses. */
@RunWith(AndroidJUnit4::class)
class DirectResumeTest {
    private fun test(block: (DirectResumeFixture) -> Unit) = DirectResumeFixture().use(block)

    @Test fun strongValidatorPauseAndResumeUsesOriginalTaskAndFreshCookie() = test { f ->
        val id = f.enqueue(context = true)
        var cookies = 0
        val access = AccessContextProvider { "session=${++cookies}" }
        f.pause(id, access = access)
        val bytes = f.checkpoints.cacheBytes(id, f.stage(id))
        assertTrue(bytes in 12 until f.data.size.toLong())
        assertEquals(TaskStatus.PAUSING, f.repo.record(id)!!.taskStatus)
        assertTrue(f.repo.record(id)!!.resumeAvailable)
        f.queue(id)
        var calls = 0
        ControlledTransfer(f.repo, HttpTransport { _, headers, _ ->
            calls++
            assertEquals("bytes=$bytes-", headers["Range"])
            assertEquals(DirectResumeFixture.ETAG, headers["If-Range"])
            assertEquals("identity", headers["Accept-Encoding"])
            assertEquals("session=2", headers["Cookie"])
            f.range(bytes)
        }, access).run(id, TransferCancellation())
        assertEquals(1, calls); assertEquals(2, cookies)
        assertEquals(id, f.repo.records().single().recordId)
        assertEquals(TaskStatus.SUCCEEDED, f.repo.record(id)!!.taskStatus)
        assertFalse(f.repo.record(id)!!.resumeAvailable)
        assertFalse(f.stage(id).exists()); assertFalse(f.checkpoints.hasValid(id, f.stage(id)))
        val asset = f.repo.stateSnapshot().assets.single()
        val saved = f.context.contentResolver.openInputStream(Uri.parse(asset.uri))!!.use { it.readBytes() }
        assertArrayEquals(f.data, saved)
        val raw = f.repo.store.file.readText()
        assertFalse(raw.contains("session="))
    }

    @Test fun everyStoppedStateIsPreservedAndCannotStartAnotherGet() = test { f ->
        val states = listOf(TaskStatus.PAUSING, TaskStatus.PAUSED, TaskStatus.WAITING_WIFI,
            TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED)
        states.forEach { status ->
            val id = f.enqueue()
            f.pause(id, status, abortWithIo = true)
            assertEquals(status, f.repo.record(id)!!.taskStatus)
            assertEquals(PauseReason.USER, f.repo.record(id)!!.pauseReason)
            assertTrue(f.repo.record(id)!!.resumeAvailable)
            assertTrue(f.checkpoints.hasValid(id, f.stage(id)))
            val before = f.stage(id).readBytes()
            ControlledTransfer(f.repo, HttpTransport { _, _, _ -> error("Stopped task opened a GET") },
                AccessContextProvider { error("Stopped task read a cookie") }).run(id, TransferCancellation())
            assertEquals(status, f.repo.record(id)!!.taskStatus)
            assertArrayEquals(before, f.stage(id).readBytes())
        }
    }

    @Test fun resumeRejectsAmbiguousOrChangedResponsesBeforeTouchingOldBytes() = test { f ->
        val cases = listOf<(Long) -> Pair<Int, Map<String, String>>>(
            { 200 to f.fullHeaders() },
            { 416 to mapOf("Content-Range" to "bytes */${f.data.size}", "ETag" to DirectResumeFixture.ETAG) },
            { n -> 206 to (f.rangeHeaders(n) - "ETag") },
            { n -> 206 to (f.rangeHeaders(n) + ("ETag" to "W/\"stable\"")) },
            { n -> 206 to (f.rangeHeaders(n) + ("ETag" to "\"different\"")) },
            { n -> 206 to (f.rangeHeaders(n) + ("Content-Encoding" to "gzip")) },
            { n -> 206 to (f.rangeHeaders(n) + ("Content-Range" to "bytes ${n + 1}-${f.data.lastIndex}/${f.data.size}")) },
            { n -> 206 to (f.rangeHeaders(n) + ("Content-Range" to "bytes $n-${f.data.lastIndex - 1}/${f.data.size}")) },
            { n -> 206 to (f.rangeHeaders(n) + ("Content-Range" to "bytes $n-${f.data.lastIndex}/${f.data.size + 1}")) },
            { n -> 206 to (f.rangeHeaders(n) + ("Content-Range" to "bytes $n-${f.data.lastIndex}/*")) },
            { n -> 206 to (f.rangeHeaders(n) - "Content-Length") },
            { n -> 206 to (f.rangeHeaders(n) + ("Content-Length" to "1, 1")) },
            { n -> 206 to (f.rangeHeaders(n) + ("Transfer-Encoding" to "chunked")) },
            { n -> 206 to (f.rangeHeaders(n) + ("Content-Type" to "multipart/byteranges; boundary=test")) },
        )
        cases.forEach { response ->
            val id = f.enqueue(); f.pause(id)
            val before = f.stage(id).readBytes()
            val offset = f.checkpoints.cacheBytes(id, f.stage(id)); f.queue(id)
            val (status, headers) = response(offset)
            var calls = 0; var closed = false
            ControlledTransfer(f.repo, HttpTransport { _, _, _ ->
                calls++
                directResponse(status, headers, body = { error("Invalid resume body was opened") }, close = {
                    closed = true; assertArrayEquals(before, f.stage(id).readBytes())
                })
            }, AccessContextProvider { null }).run(id, TransferCancellation())
            assertEquals(1, calls); assertTrue(closed)
            assertEquals(TaskStatus.FAILED, f.repo.record(id)!!.taskStatus)
            assertEquals(PauseReason.SOURCE_CHANGED, f.repo.record(id)!!.pauseReason)
            assertFalse(f.repo.record(id)!!.resumeAvailable)
            assertNotNull(f.repo.record(id)!!.safeFailure)
            assertFalse(f.repo.record(id)!!.safeFailure!!.contains("token="))
            assertTrue(f.repo.stateSnapshot().assets.isEmpty())
            assertFalse(f.stage(id).exists())
        }
    }

    @Test fun redirectedResumeSendsOpaqueValidatorOnlyToFrozenEffectiveUrl() = test { f ->
        val final = "https://cdn.example/frozen-video?token=synthetic"
        val intermediate = "https://redirect.example/hop"
        val id = f.enqueue(); f.seed(id, effective = final)
        val offset = f.checkpoints.cacheBytes(id, f.stage(id)); f.queue(id)
        var calls = 0
        ControlledTransfer(f.repo, HttpTransport { url, headers, _ ->
            calls++
            assertNull(headers["Cookie"])
            when (url) {
                DirectResumeFixture.URL -> {
                    assertNull(headers["Range"]); assertNull(headers["If-Range"])
                    directResponse(302, mapOf("Location" to intermediate))
                }
                intermediate -> {
                    assertNull(headers["Range"]); assertNull(headers["If-Range"])
                    directResponse(302, mapOf("Location" to final))
                }
                final -> {
                    assertEquals("bytes=$offset-", headers["Range"])
                    assertEquals(DirectResumeFixture.ETAG, headers["If-Range"])
                    f.range(offset)
                }
                else -> error("Unexpected redirect target")
            }
        }, AccessContextProvider { error("Public resume must not read cookies") }).run(id, TransferCancellation())
        assertEquals(3, calls); assertEquals(TaskStatus.SUCCEEDED, f.repo.record(id)!!.taskStatus)
        assertFalse(f.repo.record(id)!!.resumeAvailable)
    }

    @Test fun changedEffectiveUrlIsRejectedEvenWithIdenticalValidatorAndLength() = test { f ->
        val id = f.enqueue(); f.pause(id); val offset = f.checkpoints.cacheBytes(id, f.stage(id))
        val before = f.stage(id).readBytes(); f.queue(id); var calls = 0
        ControlledTransfer(f.repo, HttpTransport { _, _, _ ->
            if (++calls == 1) directResponse(302, mapOf("Location" to "/other?token=secret"))
            else directResponse(206, f.rangeHeaders(offset), body = { error("Changed URL body read") },
                close = { assertArrayEquals(before, f.stage(id).readBytes()) })
        }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(2, calls); assertEquals(PauseReason.SOURCE_CHANGED, f.repo.record(id)!!.pauseReason)
        assertFalse(f.repo.record(id)!!.resumeAvailable)
    }

    @Test fun changedOriginalUrlIsRejectedWithoutAnyRequest() = test { f ->
        val id = f.enqueue(); f.pause(id); f.queue(id)
        f.repo.change(id) { it.copy(mediaUrl = "https://site.example/other") }
        ControlledTransfer(f.repo, HttpTransport { _, _, _ -> error("Changed original URL requested") },
            AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(PauseReason.SOURCE_CHANGED, f.repo.record(id)!!.pauseReason)
        assertFalse(f.stage(id).exists())
    }

    @Test fun resumedCredentialedRedirectCannotSendCredentialsCrossOrigin() = test { f ->
        val id = f.enqueue(context = true); f.pause(id, access = AccessContextProvider { "session=first" }); f.queue(id)
        var calls = 0
        ControlledTransfer(f.repo, HttpTransport { url, headers, _ ->
            calls++; assertEquals(DirectResumeFixture.URL, url); assertEquals("session=fresh", headers["Cookie"])
            directResponse(302, mapOf("Location" to "https://cdn.example/file"))
        }, AccessContextProvider { "session=fresh" }).run(id, TransferCancellation())
        assertEquals(1, calls); assertEquals(FailureKind.ACCESS_CONDITION, f.repo.record(id)!!.failure)
        assertFalse(f.repo.record(id)!!.resumeAvailable)
    }

    @Test fun weakMissingAndUnreliableValidatorsNeverOfferResume() = test { f ->
        val cases = listOf(
            f.fullHeaders() - "ETag",
            f.fullHeaders() + ("ETag" to "W/\"stable\""),
            f.fullHeaders() + ("ETag" to "\"bad\r\nheader\""),
            f.fullHeaders() - "Content-Length",
            f.fullHeaders() + ("Transfer-Encoding" to "chunked"),
            f.fullHeaders() + ("Content-Length" to "${f.data.size}, ${f.data.size}"),
        )
        cases.forEach { headers ->
            val id = f.enqueue(); f.pause(id, headers = headers)
            assertEquals(TaskStatus.PAUSING, f.repo.record(id)!!.taskStatus)
            assertFalse(f.repo.record(id)!!.resumeAvailable)
            assertFalse(f.stage(id).exists()); assertEquals(0L, f.checkpoints.cacheBytes(id, f.stage(id)))
        }
    }

    @Test fun truncatedNetworkResponsePreservesOnlyDurableCheckpointWithoutRetry() = test { f ->
        val id = f.enqueue(); var calls = 0
        ControlledTransfer(f.repo, HttpTransport { _, _, _ ->
            calls++; directResponse(200, f.fullHeaders(), body = { ByteArrayInputStream(f.data.copyOf(f.partial)) })
        }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(1, calls); assertEquals(TaskStatus.FAILED, f.repo.record(id)!!.taskStatus)
        assertEquals(FailureKind.NETWORK, f.repo.record(id)!!.failure)
        assertTrue(f.repo.record(id)!!.resumeAvailable)
        assertEquals(f.partial.toLong(), f.checkpoints.cacheBytes(id, f.stage(id)))
        f.queue(id)
        ControlledTransfer(f.repo, HttpTransport { _, _, _ -> f.range(f.partial.toLong()) },
            AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(TaskStatus.SUCCEEDED, f.repo.record(id)!!.taskStatus)
    }

    @Test fun storageFailureKeepsAnEarlierTrustworthyCheckpoint() = test { f ->
        val id = f.enqueue(); var injected = false
        ControlledTransfer(f.repo, HttpTransport { _, _, _ -> directResponse(200, f.fullHeaders(), body = {
            object : ByteArrayInputStream(f.data) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!injected && pos > 0) {
                        injected = true
                        // Make the atomic metadata temporary leaf unusable; the previous commit must survive.
                        assertTrue(File(f.root, "direct-checkpoints/$id.tmp").mkdir())
                    }
                    return super.read(b, off, minOf(len, f.partial))
                }
            }
        }) }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertTrue(injected); assertEquals(TaskStatus.FAILED, f.repo.record(id)!!.taskStatus)
        assertEquals(FailureKind.STORAGE, f.repo.record(id)!!.failure)
        assertTrue(f.repo.record(id)!!.resumeAvailable)
        assertEquals(f.partial.toLong(), f.checkpoints.cacheBytes(id, f.stage(id)))
    }

    @Test fun uncommittedTailIsTruncatedOnlyAfterSuccessfulRemoteValidation() = test { f ->
        val id = f.enqueue(); f.pause(id)
        val offset = f.checkpoints.cacheBytes(id, f.stage(id))
        f.stage(id).appendBytes(ByteArray(79) { 90 })
        val oldLength = f.stage(id).length(); f.queue(id)
        ControlledTransfer(f.repo, HttpTransport { _, _, _ ->
            assertEquals(oldLength, f.stage(id).length())
            directResponse(206, f.rangeHeaders(offset), body = {
                assertEquals(oldLength, f.stage(id).length())
                ByteArrayInputStream(f.data.copyOfRange(offset.toInt(), f.data.size))
            })
        }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(TaskStatus.SUCCEEDED, f.repo.record(id)!!.taskStatus)
        val asset = f.repo.stateSnapshot().assets.single()
        assertArrayEquals(f.data, f.context.contentResolver.openInputStream(Uri.parse(asset.uri))!!.use { it.readBytes() })
    }

    @Test fun periodicCheckpointIsVisibleBeforeWriterStopsAndSurvivesUncommittedTail() = test { f ->
        val id = f.enqueue(); val large = ByteArray(2 * 1024 * 1024 + 65536)
        f.data.copyInto(large)
        var seen = 0L; var reads = 0
        val cancellation = TransferCancellation()
        ControlledTransfer(f.repo, HttpTransport { _, _, _ -> directResponse(200,
            f.fullHeaders() + ("Content-Length" to large.size.toString()), body = {
                object : ByteArrayInputStream(large) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        reads++
                        val cached = f.checkpoints.cacheBytes(id, f.stage(id))
                        if (cached > 1024 * 1024) {
                            seen = cached
                            f.repo.change(id) { it.copy(taskStatus = TaskStatus.INTERRUPTED, pauseReason = PauseReason.RECOVERY) }
                            cancellation.cancel(); throw IOException("synthetic process stop")
                        }
                        return super.read(b, off, len)
                    }
                }
            }) }, AccessContextProvider { null }).run(id, cancellation)
        assertTrue(reads > 2); assertTrue(seen >= 1024 * 1024)
        assertEquals(TaskStatus.INTERRUPTED, f.repo.record(id)!!.taskStatus)
        assertTrue(f.checkpoints.hasValid(id, f.stage(id)))
    }

    @Test fun cancellationAfterResumeRequestNeverTruncatesOrAppendsAndRemovesCache() = test { f ->
        val id = f.enqueue(); f.pause(id); val offset = f.checkpoints.cacheBytes(id, f.stage(id))
        val before = f.stage(id).readBytes(); f.queue(id); val cancel = TransferCancellation()
        ControlledTransfer(f.repo, HttpTransport { _, _, _ ->
            f.repo.change(id) { it.copy(taskStatus = TaskStatus.CANCELLED, cancelled = true) }; cancel.cancel()
            directResponse(206, f.rangeHeaders(offset), body = { error("Cancelled body opened") },
                close = { assertArrayEquals(before, f.stage(id).readBytes()) })
        }, AccessContextProvider { null }).run(id, cancel)
        assertEquals(TaskStatus.CANCELLED, f.repo.record(id)!!.taskStatus)
        assertFalse(f.repo.record(id)!!.resumeAvailable); assertFalse(f.stage(id).exists())
        assertFalse(f.checkpoints.exists(id)); assertTrue(f.repo.stateSnapshot().assets.isEmpty())
    }

    @Test fun pauseInsideResumeBodyCannotBeOverwrittenByLateProgressOrPublication() = test { f ->
        val id = f.enqueue(); f.pause(id); val offset = f.checkpoints.cacheBytes(id, f.stage(id)); f.queue(id)
        val before = f.stage(id).readBytes(); val cancel = TransferCancellation()
        ControlledTransfer(f.repo, HttpTransport { _, _, _ -> directResponse(206, f.rangeHeaders(offset), body = {
            object : ByteArrayInputStream(f.data.copyOfRange(offset.toInt(), f.data.size)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    f.repo.change(id) { it.copy(taskStatus = TaskStatus.WAITING_WIFI, pauseReason = PauseReason.WIFI) }
                    cancel.cancel(); return n
                }
            }
        }) }, AccessContextProvider { null }).run(id, cancel)
        assertEquals(TaskStatus.WAITING_WIFI, f.repo.record(id)!!.taskStatus)
        assertEquals(PauseReason.WIFI, f.repo.record(id)!!.pauseReason)
        assertTrue(f.repo.record(id)!!.resumeAvailable)
        assertArrayEquals(before, f.stage(id).readBytes()); assertTrue(f.repo.stateSnapshot().assets.isEmpty())
    }

    @Test fun pauseResumePauseCheckpointsHashAllOldAndNewWrittenBytes() = test { f ->
        val id = f.enqueue(); f.pause(id)
        val offset = f.checkpoints.cacheBytes(id, f.stage(id)); f.queue(id)
        val cancel = TransferCancellation(); var first = true
        val chunk = 2000
        ControlledTransfer(f.repo, HttpTransport { _, _, _ -> directResponse(206, f.rangeHeaders(offset), body = {
            object : ByteArrayInputStream(f.data.copyOfRange(offset.toInt(), f.data.size)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!first) {
                        f.repo.change(id) { it.copy(taskStatus = TaskStatus.PAUSING, pauseReason = PauseReason.USER) }
                        cancel.cancel(); cancel.check()
                    }
                    first = false; return super.read(b, off, minOf(chunk, len))
                }
            }
        }) }, AccessContextProvider { null }).run(id, cancel)
        val checkpoint = f.checkpoints.load(id, f.stage(id))!!
        assertEquals(offset + chunk, checkpoint.bytes)
        val expected = MessageDigest.getInstance("SHA-256").digest(f.data.copyOf(checkpoint.bytes.toInt()))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, checkpoint.prefixHash)
        assertTrue(f.repo.record(id)!!.resumeAvailable)
        assertEquals(TaskStatus.PAUSING, f.repo.record(id)!!.taskStatus)
    }

    @Test fun completeDurableStageUsesStrictOneByteIdentityProbeRatherThan416() = test { f ->
        val id = f.enqueue(); f.seed(id, f.data.size.toLong()); f.queue(id)
        ControlledTransfer(f.repo, HttpTransport { _, headers, _ ->
            assertEquals("bytes=${f.data.lastIndex}-", headers["Range"])
            f.range(f.data.lastIndex.toLong())
        }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(TaskStatus.SUCCEEDED, f.repo.record(id)!!.taskStatus)
        assertFalse(f.repo.record(id)!!.resumeAvailable)
    }
}

internal fun directResponse(status: Int, headers: Map<String, String>, body: () -> InputStream = { ByteArrayInputStream(byteArrayOf()) },
                            close: () -> Unit = {}) = object : HttpResponse {
    override val status = status
    override fun header(name: String) = headers[name]
    override fun body() = body.invoke()
    override fun close() = close.invoke()
}

internal class DirectResumeFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    val root = File(app.cacheDir, "direct-resume-${UUID.randomUUID()}").apply { mkdirs() }
    val context: Context = object : ContextWrapper(app) { override fun getFilesDir() = root }
    val repo = DownloadRepository(DownloadStore(root), NoSystem(), files = ManagedFileStore(context))
    val checkpoints = DirectCheckpointStore(root)
    val data = instrumentation.context.assets.open("test-video.mp4").use { it.readBytes() }
    val partial = data.size / 2
    fun stage(id: TaskId) = repo.files!!.stage(id)
    fun fullHeaders() = mapOf("ETag" to ETAG, "Content-Length" to data.size.toString())
    fun rangeHeaders(offset: Long) = mapOf("ETag" to ETAG, "Content-Length" to (data.size - offset).toString(),
        "Content-Range" to "bytes $offset-${data.lastIndex}/${data.size}")
    fun range(offset: Long) = directResponse(206, rangeHeaders(offset), body = {
        ByteArrayInputStream(data.copyOfRange(offset.toInt(), data.size))
    })
    fun enqueue(context: Boolean = false) = repo.enqueue(DownloadDraft(
        MediaCandidate(URL, MediaKind.UNKNOWN, setOf(Evidence.DOM), frameUrl = "https://site.example/frame", reliableSource = true),
        "TestAgent", "https://site.example/watch?private=secret", useAccessContext = context), false, "test.mp4")
    fun queue(id: TaskId) { repo.change(id) { it.copy(taskStatus = TaskStatus.QUEUED, cancelled = false) } }
    fun seed(id: TaskId, bytes: Long = partial.toLong(), effective: String = URL) {
        stage(id).outputStream().use { it.write(data, 0, bytes.toInt()); it.fd.sync() }
        checkpoints.save(id, stage(id), URL, effective, ETAG, data.size.toLong(), bytes)
        repo.change(id) { it.copy(taskStatus = TaskStatus.PAUSED, resumeAvailable = true, received = bytes, expected = data.size.toLong()) }
    }
    fun pause(id: TaskId, status: TaskStatus = TaskStatus.PAUSING, headers: Map<String, String> = fullHeaders(),
              access: AccessContextProvider = AccessContextProvider { null }, abortWithIo: Boolean = false) {
        val cancel = TransferCancellation()
        var first = true
        ControlledTransfer(repo, HttpTransport { _, _, _ -> directResponse(200, headers, body = {
            object : ByteArrayInputStream(data) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!first) {
                        repo.change(id) { it.copy(taskStatus = status, pauseReason = PauseReason.USER) }
                        cancel.cancel()
                        if (abortWithIo) throw IOException("synthetic aborted read")
                        cancel.check()
                    }
                    first = false
                    return super.read(b, off, minOf(len, partial))
                }
            }
        }) }, access).run(id, cancel)
    }
    override fun close() {
        repo.records().forEach { record ->
            runCatching { repo.stateSnapshot().assets.firstOrNull { it.recordId == record.recordId }?.let { repo.files!!.delete(it) } }
            runCatching { repo.files!!.cleanupPending(record) }
        }
        root.deleteRecursively()
    }
    private class NoSystem : DownloadBackend {
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long = error("Must not enqueue a system task")
        override fun query(id: Long) = SystemDownloadResult.Missing
        override fun fileUri(id: Long): String? = null
        override fun access(uri: String) = FileAccess(FileAvailability.MISSING)
        override fun inspect(uri: String) = MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id: Long) = error("Must not remove a system task")
    }
    companion object {
        const val URL = "https://site.example/media?token=secret%2Bexact"
        const val ETAG = "\"stable-direct-v1\""
    }
}
