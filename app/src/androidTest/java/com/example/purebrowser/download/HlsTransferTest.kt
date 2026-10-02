package com.example.purebrowser.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.hls.HlsPlaylist
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.download.hls.HlsTransfer
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** CORE-only evidence: all requests are exact-URL in-memory GETs, never sockets or HEADs. */
@RunWith(AndroidJUnit4::class)
class HlsTransferTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testApp get() = InstrumentationRegistry.getInstrumentation().context
    private val entry = "https://source.example/hls/video.m3u8?entry=private%2Bexact"
    private val source = "https://source.example/watch?private=source-secret#fragment"
    private val noCookies = AccessContextProvider { null }
    private val segmentUrls get() = (0..3).map { "https://source.example/hls/segment-%03d.ts".format(Locale.US, it) }

    private class NoSystem : DownloadBackend {
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long = error("No DownloadManager enqueue")
        override fun query(id: Long) = SystemDownloadResult.Missing
        override fun fileUri(id: Long): String? = null
        override fun access(uri: String) = FileAccess(FileAvailability.MISSING)
        override fun inspect(uri: String) = MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id: Long) = error("No DownloadManager removal")
    }

    private data class Request(val url: String, val headers: Map<String, String>)
    private class FakeTransport : HttpTransport {
        private val routes = ConcurrentHashMap<String, (TransferCancellation) -> HttpResponse>()
        private val requests = Collections.synchronizedList(mutableListOf<Request>())
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        fun requests(): List<Request> = synchronized(requests) { requests.toList() }
        fun calls(url: String) = requests().count { it.url == url }
        fun route(url: String, reply: (TransferCancellation) -> HttpResponse) { routes[url] = reply }
        override fun open(url: String, headers: Map<String, String>, cancel: TransferCancellation): HttpResponse {
            cancel.check()
            requests.add(Request(url, headers.toMap()))
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            try {
                // An unmapped URL cannot accidentally reach a real network transport.
                val response = routes[url]?.invoke(cancel) ?: throw AssertionError("Unexpected fake request")
                return object : HttpResponse {
                    private val closed = AtomicBoolean()
                    override val status = response.status
                    override fun header(name: String) = response.header(name)
                    override fun body() = response.body()
                    override fun close() {
                        if (closed.compareAndSet(false, true)) try { response.close() } finally { active.decrementAndGet() }
                    }
                }
            } catch (failure: Throwable) {
                active.decrementAndGet()
                throw failure
            }
        }
    }

    private fun response(
        bytes: ByteArray = byteArrayOf(), status: Int = 200,
        headers: Map<String, String> = mapOf("Content-Length" to bytes.size.toString()),
        body: (() -> InputStream)? = null,
    ) = object : HttpResponse {
        override val status = status
        override fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun body() = body?.invoke() ?: ByteArrayInputStream(bytes)
        override fun close() {}
    }

    private fun asset(name: String) = testApp.assets.open("hls/$name").use { it.readBytes() }
    private fun segment(index: Int) = asset("segment-%03d.ts".format(Locale.US, index))
    private fun playlist() = asset("video.m3u8").toString(Charsets.UTF_8)
    private fun draft(url: String = entry, context: Boolean = false) = DownloadDraft(
        MediaCandidate(url, MediaKind.HLS, setOf(Evidence.DOM), frameUrl = "https://source.example/frame", reliableSource = true),
        "HlsCoreTestAgent", source, "Self-owned AVC B-frames + AAC", "test-tab", 1, useAccessContext = context,
    )

    private fun fixture(
        mediaUrl: String = entry, text: String = playlist(), urls: List<String> = segmentUrls,
        unknownLength: Boolean = false,
    ) = FakeTransport().also { fake ->
        fake.route(mediaUrl) { response(text.toByteArray(Charsets.UTF_8)) }
        urls.forEachIndexed { index, url ->
            val bytes = segment(index)
            fake.route(url) { response(bytes, headers = if (unknownLength) emptyMap() else mapOf("Content-Length" to bytes.size.toString())) }
        }
    }

    private inner class Harness(val repo: DownloadRepository) {
        private val ownedIds = mutableListOf<TaskId>()
        fun enqueue(fake: FakeTransport, draft: DownloadDraft = this@HlsTransferTest.draft(), access: AccessContextProvider = noCookies): TaskId {
            val resolver = HlsResolver(fake, access, repo.allowLocalHttp)
            val token = TransferCancellation()
            val options = resolver.resolveEntry(draft, token)
            val variant = (options.playlist as? HlsPlaylist.Master)?.variants?.single()
            val plan = resolver.resolvePlan(draft, options, variant, token)
            return repo.enqueue(draft, false, "hls-core-test.mp4", hlsPlan = plan).also { ownedIds.add(it) }
        }
        fun run(id: TaskId, fake: FakeTransport, access: AccessContextProvider = noCookies, cancel: TransferCancellation = TransferCancellation()) {
            HlsTransfer(repo, fake, access).run(id, cancel)
            assertEquals("Responses must close on every terminal path", 0, fake.active.get())
        }
        fun assertClean(id: TaskId) {
            assertFalse(repo.files!!.stage(id).exists())
            assertFalse(File(app.filesDir, "hls/$id").exists())
            assertNull(repo.record(id)!!.pendingUri)
            assertFalse(repo.record(id)!!.resumeAvailable)
        }
        fun assertRecoverableWorkspace(id: TaskId) {
            val record = repo.record(id)!!
            val workspace = repo.files!!.hlsWorkspace
            val directory = File(app.filesDir, "hls/$id")
            assertTrue(File(directory, "plan.json").isFile)
            assertTrue(File(directory, "checkpoint.json").isFile)
            val plan = workspace.load(id)
            assertEquals(record.mediaUrl, plan.entryUrl)
            assertEquals(record.hlsPlaylistUrl, plan.variant?.url ?: plan.playlistUrl)
            assertEquals(record.segmentCount, plan.media.segments.size)
            assertEquals(record.plannedDurationUs, plan.media.durationUs)
            assertTrue(record.resumeAvailable)
            assertTrue(workspace.hasResumeData(id)) // Valid known plan also permits resuming from zero.
            assertEquals(if (record.failure == FailureKind.STORAGE) PauseReason.STORAGE else PauseReason.NETWORK, record.pauseReason)
            assertFalse(repo.files!!.stage(id).exists())
            assertNull(record.pendingUri)
            assertTrue("All partial writers must join and leave no .part files", directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
            val complete = workspace.verifiedPieces(id, plan)
            val segments = directory.listFiles().orEmpty().filter { Regex("segment-[0-9]+\\.ts").matches(it.name) }
            assertEquals(complete.keys.map { "segment-$it.ts" }.toSet(), segments.map { it.name }.toSet())
            assertEquals(complete.size, record.completedSegments)
            val cacheBytes = complete.values.sumOf { it.size }
            assertEquals(cacheBytes, record.received)
            assertEquals(cacheBytes, workspace.cacheBytes(id))
        }
        fun assertFailed(id: TaskId, kind: FailureKind) {
            val record = repo.record(id)!!
            assertEquals(TaskStatus.FAILED, record.taskStatus)
            assertEquals(kind, record.failure)
            assertNotNull(record.safeFailure)
            assertFalse(record.safeFailure!!.contains("://"))
            assertTrue(repo.stateSnapshot().assets.none { it.recordId == id })
            assertNull(repo.fileUri(id))
            assertFalse(repo.snapshot().single { it.id == id }.verified)
            if (kind in setOf(FailureKind.NETWORK, FailureKind.STORAGE)) assertRecoverableWorkspace(id)
            else assertClean(id)
        }
        fun assertSucceeded(id: TaskId) {
            val record = repo.record(id)!!
            assertEquals(TaskStatus.SUCCEEDED, record.taskStatus)
            assertEquals(DownloadProtocol.HLS, record.protocol)
            assertEquals(4, record.completedSegments)
            assertEquals(8_000_000L, record.plannedDurationUs!!)
            assertTrue(record.name.endsWith(".mp4"))
            assertEquals("video/mp4", repo.mimeType(id))
            assertTrue(repo.snapshot().single { it.id == id }.verified)
            val asset = repo.stateSnapshot().assets.single { it.recordId == id }
            assertEquals(FormatCheck.PASSED, asset.format)
            assertEquals(FileAvailability.AVAILABLE, asset.availability)
            assertEquals(if (Build.VERSION.SDK_INT >= 29) AssetLocation.MEDIASTORE_DOWNLOAD else AssetLocation.LEGACY_PUBLIC_FILE, asset.location)
            val uri = repo.fileUri(id)!!
            assertEquals(if (Build.VERSION.SDK_INT >= 29) "media" else "${app.packageName}.files", uri.authority)
            // Read the published file, not a private stage or remuxer-only result.
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(app, uri, null)
                assertEquals(2, extractor.trackCount)
                val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                assertEquals(setOf("video/avc", "audio/mp4a-latm"), tracks.map { it.getString(MediaFormat.KEY_MIME) }.toSet())
                tracks.forEachIndexed { index, format ->
                    assertTrue(kotlin.math.abs(format.getLong(MediaFormat.KEY_DURATION) - 8_000_000L) < 2_000_000L)
                    extractor.selectTrack(index)
                    extractor.seekTo(4_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    assertTrue(extractor.sampleTime >= 0)
                    assertTrue(extractor.readSampleData(java.nio.ByteBuffer.allocate(1024 * 1024), 0) > 0)
                    extractor.unselectTrack(index)
                }
            } finally { extractor.release() }
            assertClean(id)
        }
        fun cleanup() {
            // Only IDs minted by this test. Never sweep the application's/public Downloads directories.
            ownedIds.forEach { id ->
                val record = repo.record(id) ?: return@forEach
                if (record.taskStatus in DownloadRepository.activeStatuses) repo.cancel(id)
                if (repo.stateSnapshot().assets.any { it.recordId == id }) repo.deleteFile(id) else repo.forgetRecord(id)
            }
        }
    }

    private fun withRepo(allowLocalHttp: Boolean = false, block: (Harness) -> Unit) {
        val directory = File(app.cacheDir, "hls-core-${UUID.randomUUID()}").apply { mkdirs() }
        val harness = Harness(DownloadRepository(DownloadStore(directory), NoSystem(), allowLocalHttp, ManagedFileStore(app)))
        try { block(harness) } finally { try { harness.cleanup() } finally { directory.deleteRecursively() } }
    }

    private fun resolverFailure(kind: FailureKind, block: () -> Unit) {
        try { block(); fail("Resolver must throw TransferFailure") }
        catch (failure: TransferFailure) {
            assertEquals(kind, failure.kind)
            assertFalse(failure.safeMessage.contains("://"))
            assertFalse(failure.safeMessage.contains("private"))
        }
    }

    @Test fun actualResolveEnqueueAndTransferPublishesVerifiedPublicMp4() = withRepo { h ->
        val fake = fixture()
        val id = h.enqueue(fake)
        assertTrue(File(app.filesDir, "hls/$id/plan.json").isFile)
        h.run(id, fake)
        h.assertSucceeded(id)
        assertEquals(5, fake.requests().size)
        assertEquals(1, fake.calls(entry))
        segmentUrls.forEach { assertEquals(1, fake.calls(it)) }
    }

    @Test fun masterRelativeVariantAndSegmentQueriesKeepExactOctets() = withRepo { h ->
        val masterUrl = "https://source.example/root/master.m3u8?master=private%2Bexact"
        val mediaUrl = "https://source.example/root/variants/video.m3u8?variant=private%2Bexact"
        val urls = (0..3).map { "https://source.example/chunks/segment-%03d.ts?part=%d%%2Bexact".format(Locale.US, it, it) }
        var text = playlist()
        (0..3).forEach { i -> text = text.replace("segment-%03d.ts".format(Locale.US, i), "../../chunks/segment-%03d.ts?part=%d%%2Bexact".format(Locale.US, i, i)) }
        val fake = fixture(mediaUrl, text, urls)
        fake.route(masterUrl) { response(("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x180,CODECS=\"avc1.4d401e,mp4a.40.2\"\nvariants/video.m3u8?variant=private%2Bexact\n").toByteArray()) }
        val id = h.enqueue(fake, draft(masterUrl))
        val record = h.repo.record(id)!!
        assertEquals(mediaUrl, record.hlsPlaylistUrl)
        assertEquals(320, record.hlsWidth!!)
        assertEquals(180, record.hlsHeight!!)
        assertEquals(500_000L, record.hlsBandwidth!!)
        h.run(id, fake)
        h.assertSucceeded(id)
        assertEquals(listOf(masterUrl, mediaUrl), fake.requests().take(2).map { it.url })
        assertEquals(urls.toSet(), fake.requests().drop(2).map { it.url }.toSet())
        assertEquals(6, fake.requests().size)
    }

    /** A cancellation-aware bounded barrier; assertions run on the test thread, never get swallowed by CORE. */
    private class ReadGate {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        fun body(bytes: ByteArray, cancel: TransferCancellation): InputStream {
            cancel.bind { release.countDown() }
            return object : ByteArrayInputStream(bytes) {
                private var reads = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    cancel.check()
                    if (reads++ == 1) {
                        entered.countDown()
                        if (!release.await(15, TimeUnit.SECONDS)) throw IOException("Test read barrier expired")
                        cancel.check()
                    }
                    return super.read(buffer, offset, minOf(length, 188 * 5))
                }
            }
        }
    }

    private fun gatedTransfer(h: Harness, fake: FakeTransport, id: TaskId, gate: ReadGate, block: (TransferCancellation) -> Unit) {
        val cancel = TransferCancellation()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit { HlsTransfer(h.repo, fake, noCookies).run(id, cancel) }
        try {
            assertTrue("Both segment workers must reach the bounded barrier", gate.entered.await(12, TimeUnit.SECONDS))
            block(cancel)
            gate.release.countDown()
            future.get(45, TimeUnit.SECONDS)
            assertEquals(0, fake.active.get())
        } finally {
            cancel.cancel(); gate.release.countDown(); future.cancel(true); executor.shutdownNow()
            assertTrue("Transfer test must not leave background writers", executor.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 90_000) fun unknownLengthPublishesByteAndSegmentProgressWithoutInventingTotal() = withRepo { h ->
        val fake = fixture(unknownLength = true)
        val gate = ReadGate()
        (0..1).forEach { i -> val bytes = segment(i); fake.route(segmentUrls[i]) { cancel -> response(bytes, headers = emptyMap(), body = { gate.body(bytes, cancel) }) } }
        val id = h.enqueue(fake)
        gatedTransfer(h, fake, id, gate) {
            val running = h.repo.record(id)!!
            assertEquals(TaskStatus.RUNNING, running.taskStatus)
            assertTrue(running.received > 0)
            assertNull(running.expected)
            assertEquals(0, running.completedSegments)
            assertEquals(-1L, h.repo.snapshot().single().total)
        }
        h.assertSucceeded(id)
        assertNull(h.repo.record(id)!!.expected)
        assertEquals(-1L, h.repo.snapshot().single().total)
        assertEquals((0..3).sumOf { segment(it).size.toLong() }, h.repo.record(id)!!.received)
    }

    @Test(timeout = 90_000) fun twoActiveSegmentWorkersNeverHaveMoreThanTwoRequestsInFlight() = withRepo { h ->
        val fake = fixture()
        val gate = ReadGate()
        (0..1).forEach { i -> val bytes = segment(i); fake.route(segmentUrls[i]) { cancel -> response(bytes, body = { gate.body(bytes, cancel) }) } }
        val id = h.enqueue(fake)
        gatedTransfer(h, fake, id, gate) {
            assertEquals(2, fake.active.get())
            assertEquals(2, fake.requests().count { it.url in segmentUrls })
            assertEquals(0, fake.calls(segmentUrls[2]))
            assertEquals(0, fake.calls(segmentUrls[3]))
        }
        assertEquals(2, fake.maximum.get())
        segmentUrls.forEach { assertEquals(1, fake.calls(it)) }
        h.assertSucceeded(id)
    }

    private fun retryCase(transient: (Int) -> HttpResponse, succeeds: Boolean, kind: FailureKind = FailureKind.NETWORK) = withRepo { h ->
        val fake = fixture()
        val attempts = AtomicInteger()
        val bytes = segment(0)
        fake.route(segmentUrls[0]) { val attempt = attempts.incrementAndGet(); if (succeeds && attempt == 3) response(bytes) else transient(attempt) }
        val id = h.enqueue(fake)
        h.run(id, fake)
        assertEquals("At most the initial GET and two retries", 3, attempts.get())
        assertEquals(1, fake.calls(entry))
        if (succeeds) h.assertSucceeded(id) else h.assertFailed(id, kind)
    }

    @Test(timeout = 90_000) fun two503RetriesCanRecover() = retryCase({ response(status = 503) }, true)
    @Test(timeout = 90_000) fun exhausted503RetriesRecordNetworkFailure() = retryCase({ response(status = 503) }, false)
    @Test(timeout = 90_000) fun twoNetworkRetriesCanRecover() = retryCase({ throw IOException("Synthetic offline") }, true)
    @Test(timeout = 90_000) fun exhaustedNetworkRetriesRecordFailure() = retryCase({ throw IOException("Synthetic offline") }, false)

    private fun noRetryCase(kind: FailureKind, reply: () -> HttpResponse) = withRepo { h ->
        val fake = fixture()
        fake.route(segmentUrls[0]) { reply() }
        val id = h.enqueue(fake)
        h.run(id, fake)
        assertEquals(1, fake.calls(segmentUrls[0]))
        h.assertFailed(id, kind)
    }

    @Test fun http403HasZeroAutomaticRetries() = noRetryCase(FailureKind.ACCESS_CONDITION) { response(status = 403) }
    @Test fun missing404SegmentHasZeroAutomaticRetriesAndNoAsset() = noRetryCase(FailureKind.HTTP_REJECTED) { response(status = 404) }
    @Test fun tlsIdentityFailureHasZeroAutomaticRetries() = noRetryCase(FailureKind.NETWORK) { throw SSLHandshakeException("Synthetic TLS identity failure") }
    @Test fun htmlEvenWithVideoMimeAndPacketSizedLengthCannotSucceed() = noRetryCase(FailureKind.NOT_VIDEO) {
        val html = "<html>not video</html>".toByteArray().copyOf(188 * 5)
        response(html, headers = mapOf("Content-Type" to "video/mp2t", "Content-Length" to html.size.toString()))
    }
    @Test fun unknownLengthTruncatedPacketCannotSucceed() = noRetryCase(FailureKind.NOT_VIDEO) { response(segment(0).dropLast(1).toByteArray(), headers = emptyMap()) }
    @Test(timeout = 90_000) fun advertisedLengthTruncationRetriesAreBoundedAndNeverPublish() = retryCase({
        val bytes = segment(0)
        response(bytes.dropLast(1).toByteArray(), headers = mapOf("Content-Length" to bytes.size.toString()))
    }, false)

    @Test(timeout = 90_000) fun cancellationAfterPartialWritesLeavesNoAssetPlanOrSegments() = withRepo { h ->
        val fake = fixture()
        val gate = ReadGate()
        (0..1).forEach { i -> val bytes = segment(i); fake.route(segmentUrls[i]) { cancel -> response(bytes, body = { gate.body(bytes, cancel) }) } }
        val id = h.enqueue(fake)
        gatedTransfer(h, fake, id, gate) { cancel ->
            assertTrue(h.repo.record(id)!!.received > 0)
            assertTrue(h.repo.files!!.hlsWorkspace.segmentPart(id, 0).length() > 0)
            assertFalse("A partial response must not be marked COMPLETE", h.repo.files!!.hlsWorkspace.segment(id, 0).exists())
            h.repo.cancel(id)
            cancel.cancel()
        }
        assertEquals(TaskStatus.CANCELLED, h.repo.record(id)!!.taskStatus)
        assertTrue(h.repo.stateSnapshot().assets.isEmpty())
        assertNull(h.repo.fileUri(id))
        assertEquals(0, fake.calls(segmentUrls[2]))
        assertEquals(0, fake.calls(segmentUrls[3]))
        h.assertClean(id)
    }

    @Test fun unsupportedPlaylistsFailInResolverBeforeEnqueue() = withRepo { h ->
        val unsupported = listOf(
            playlist().replace("#EXT-X-ENDLIST", ""),
            playlist().replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-KEY:METHOD=AES-128,URI=\"secret.key\""),
            playlist().replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-MAP:URI=\"init.mp4\""),
            playlist().replace("#EXTINF:2.000000,", "#EXT-X-BYTERANGE:1880@0\n#EXTINF:2.000000,"),
            playlist().replace("#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-DISCONTINUITY"),
            playlist().replace("#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PART:DURATION=0.5,URI=\"part.ts\""),
        )
        unsupported.forEach { text ->
            val fake = fixture(text = text)
            resolverFailure(FailureKind.UNSUPPORTED) { HlsResolver(fake, noCookies, false).resolveEntry(draft(), TransferCancellation()) }
            assertEquals(1, fake.requests().size)
            assertEquals(0, fake.active.get())
            assertTrue(h.repo.records().isEmpty())
            assertTrue(h.repo.stateSnapshot().assets.isEmpty())
        }
    }

    @Test fun cookiesAreFetchedPerExactUrlReferrersAreMinimizedAndSetCookieIsIgnored() = withRepo { h ->
        val cross = "https://cdn.example/segment-001.ts?part=private%2Bexact"
        val urls = segmentUrls.toMutableList().also { it[1] = cross }
        val text = playlist().replace("segment-001.ts", cross)
        val fake = fixture(text = text, urls = urls)
        val playlistBytes = text.toByteArray()
        fake.route(entry) { response(playlistBytes, headers = mapOf("Content-Length" to playlistBytes.size.toString(), "Set-Cookie" to "server-poison=never-replay")) }
        val lookups = Collections.synchronizedList(mutableListOf<String>())
        val cookies = AccessContextProvider { url -> lookups.add(url); "path-cookie=${URI(url).path.substringAfterLast('/')}" }
        val id = h.enqueue(fake, draft(context = true), cookies)
        h.run(id, fake, cookies)
        h.assertSucceeded(id)
        val requests = fake.requests()
        requests.forEach { request ->
            assertEquals("HlsCoreTestAgent", request.headers["User-Agent"])
            assertEquals("identity", request.headers["Accept-Encoding"])
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Set-Cookie"])
            if (request.url == cross) {
                assertNull(request.headers["Cookie"])
                assertEquals("https://source.example/", request.headers["Referer"])
            } else {
                assertEquals("path-cookie=${URI(request.url).path.substringAfterLast('/')}", request.headers["Cookie"])
                assertEquals("https://source.example/watch", request.headers["Referer"])
            }
        }
        assertEquals(requests.filter { it.url != cross }.map { it.url }.toSet(), synchronized(lookups) { lookups.toSet() })
        assertEquals(4, lookups.size)
        val raw = h.repo.store.file.readText()
        assertFalse(raw.contains("path-cookie="))
        assertFalse(raw.contains("server-poison"))
    }

    @Test fun disabledAccessContextDoesNotLookupCookiesOrSendReferrers() = withRepo { h ->
        val fake = fixture()
        val access = AccessContextProvider { throw AssertionError("Cookie lookup forbidden") }
        val id = h.enqueue(fake, draft(context = false), access)
        h.run(id, fake, access)
        h.assertSucceeded(id)
        fake.requests().forEach { assertNull(it.headers["Cookie"]); assertNull(it.headers["Referer"]) }
    }

    @Test fun originallyPublicEntryRedirectingToSourceCannotAcquireSourceCookies() = withRepo { h ->
        val publicEntry = "https://cdn.example/public.m3u8?public=private%2Bexact"
        val fake = fixture()
        fake.route(publicEntry) { response(status = 302, headers = mapOf("Location" to entry)) }
        val lookups = AtomicInteger()
        val access = AccessContextProvider { lookups.incrementAndGet(); "source-cookie=must-not-leak" }
        val id = h.enqueue(fake, draft(publicEntry, context = true), access)
        h.run(id, fake, access)
        h.assertSucceeded(id)
        assertEquals(0, lookups.get())
        fake.requests().forEach { assertNull(it.headers["Cookie"]) }
        assertEquals(6, fake.requests().size)
    }

    @Test fun publicSegmentRedirectChainBackToSourceCannotAcquireCookies() = withRepo { h ->
        val publicSegment = "https://cdn.example/public.ts?public=private%2Bexact"
        val returned = "https://source.example/returned.ts?returned=private%2Bexact"
        val urls = segmentUrls.toMutableList().also { it[0] = publicSegment }
        val fake = fixture(text = playlist().replace("segment-000.ts", publicSegment), urls = urls)
        fake.route(publicSegment) { response(status = 302, headers = mapOf("Location" to returned)) }
        fake.route(returned) { response(segment(0)) }
        val lookups = Collections.synchronizedList(mutableListOf<String>())
        val access = AccessContextProvider { url -> lookups.add(url); "source-cookie=synthetic" }
        val id = h.enqueue(fake, draft(context = true), access)
        h.run(id, fake, access)
        h.assertSucceeded(id)
        fake.requests().filter { it.url == publicSegment || it.url == returned }.forEach { assertNull(it.headers["Cookie"]) }
        assertFalse(lookups.contains(publicSegment))
        assertFalse(lookups.contains(returned))
        assertEquals(1, fake.calls(returned))
    }

    @Test fun credentialedCrossOriginSegmentRedirectIsRejectedWithoutContactingTarget() = withRepo { h ->
        val foreign = "https://foreign.example/steal.ts"
        val fake = fixture()
        fake.route(segmentUrls[0]) { response(status = 302, headers = mapOf("Location" to foreign)) }
        val access = AccessContextProvider { "session=synthetic" }
        val id = h.enqueue(fake, draft(context = true), access)
        h.run(id, fake, access)
        h.assertFailed(id, FailureKind.ACCESS_CONDITION)
        assertEquals(1, fake.calls(segmentUrls[0]))
        assertEquals(0, fake.calls(foreign))
    }

    @Test fun credentialedCrossOriginPlaylistRedirectThrowsTransferFailure() {
        val foreign = "https://foreign.example/playlist.m3u8"
        val fake = fixture()
        fake.route(entry) { response(status = 302, headers = mapOf("Location" to foreign)) }
        resolverFailure(FailureKind.ACCESS_CONDITION) {
            HlsResolver(fake, AccessContextProvider { "session=synthetic" }, false).resolveEntry(draft(context = true), TransferCancellation())
        }
        assertEquals(0, fake.calls(foreign))
        assertEquals(0, fake.active.get())
    }

    @Test fun httpsToPublicHttpSegmentDowngradeIsDenied() = withRepo { h ->
        val insecure = "http://source.example/insecure.ts"
        val fake = fixture()
        fake.route(segmentUrls[0]) { response(status = 302, headers = mapOf("Location" to insecure)) }
        val id = h.enqueue(fake)
        h.run(id, fake)
        h.assertFailed(id, FailureKind.UNSUPPORTED)
        assertEquals(0, fake.calls(insecure))
    }

    @Test fun httpsPlaylistDowngradeIsDeniedEvenWhenDebugLoopbackHttpIsAllowed() {
        val insecure = "http://127.0.0.1/insecure.m3u8"
        val fake = fixture()
        fake.route(entry) { response(status = 302, headers = mapOf("Location" to insecure)) }
        fake.route(insecure) { response(playlist().toByteArray()) }
        resolverFailure(FailureKind.UNSUPPORTED) {
            HlsResolver(fake, noCookies, true).resolveEntry(draft(), TransferCancellation())
        }
        assertEquals(0, fake.calls(insecure))
        assertEquals(0, fake.active.get())
    }
}
