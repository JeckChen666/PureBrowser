package com.example.purebrowser.download

import com.example.purebrowser.download.mux.DualTrackMuxer
import com.example.purebrowser.download.mux.DualTrackMuxException
import com.example.purebrowser.download.mux.MuxedTracks
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException

/** Authored fixtures + fake HTTP only. Does not launch a browser/service or contact a site. */
class DualTrackTransferTest {
    private fun response(data: ByteArray, status: Int = 200,
        headers: Map<String, String> = mapOf("Content-Length" to data.size.toString()),
        body: () -> InputStream = { ByteArrayInputStream(data) }, onClose: () -> Unit = {}) = object : HttpResponse {
        override val status = status
        override fun header(name: String) = headers[name]
        override fun body() = body()
        override fun close() = onClose()
    }
    // One closed slice of the whole resource, honouring the requested Range like a real CDN.
    private fun ranged(data: ByteArray, range: String): HttpResponse {
        val match = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(range)
            ?: return response(byteArrayOf(), 403, headers = emptyMap())
        val start = match.groupValues[1].toLong().toInt()
        val end = minOf(match.groupValues[2].toLong(), data.size - 1L).toInt()
        val slice = data.copyOfRange(start, end + 1)
        return response(slice, 206, mapOf("Content-Length" to slice.size.toString(),
            "Content-Range" to "bytes $start-$end/${data.size}"))
    }
    // A track smaller than one chunk arrives as a single exact closed slice.
    private fun whole(data: ByteArray) = ranged(data, "bytes=0-${data.size - 1}")
    private fun assertNoPartialAsset(repo: DownloadRepository, id: TaskId) {
        assertTrue(repo.stateSnapshot().assets.isEmpty())
        assertFalse(repo.record(id)!!.resumeAvailable)
        assertFalse(repo.snapshot().single().canResume); assertFalse(repo.snapshot().single().canRetry)
        assertFalse(repo.files!!.stage(id).exists()); assertEquals(0L, repo.files!!.dualTrackWorkspace.cacheBytes(id))
        assertNull(repo.record(id)!!.pendingUri)
    }
    // Print only fixed stage labels, counts and bounded public failure text, never URL/context/headers.
    private fun outcome(repo: DownloadRepository, id: TaskId, muxStage: String): String {
        val record = repo.record(id)!!
        val space = repo.files!!.stage(id).parentFile!!.usableSpace
        return "muxStage=$muxStage status=${record.taskStatus} failure=${record.failure} " +
            "safeFailure=${record.safeFailure} received=${record.received} expected=${record.expected} " +
            "availableBytes=$space"
    }
    @Test fun complete206TracksUseOneSequentialAttemptAndPublishOneVerifiedMp4() = DualTrackTestSupport.test { repo ->
        val draft = DualTrackTestSupport.draft(context = true)
        val id = repo.enqueue(draft, false, "fixture.mp4")
        val visited = mutableListOf<String>(); var active = 0; var peak = 0
        var muxStage = "not_entered"
        val transport = HttpTransport { url, headers, _ ->
            visited.add(url); active++; peak = maxOf(peak, active)
            assertNull(headers["Cookie"]); assertNull(headers["Authorization"]); assertNull(headers["If-Range"])
            assertEquals("https://site.example/", headers["Referer"])
            val bytes = if (visited.size == 1) DualTrackTestSupport.video else DualTrackTestSupport.audio
            assertEquals("bytes=0-${bytes.size - 1}", headers["Range"])
            response(bytes, 206, mapOf("Content-Length" to bytes.size.toString(),
                "Content-Range" to "bytes 0-${bytes.size - 1}/${bytes.size}"), onClose = { active-- })
        }
        DualTrackTransfer(repo, transport, AccessContextProvider { error("Anonymous CDN must not receive cookies") },
            mux = { video, audio, output, cancel ->
                assertEquals(TaskStatus.MUXING, repo.record(id)!!.taskStatus)
                assertArrayEquals(DualTrackTestSupport.video, video.readBytes())
                assertArrayEquals(DualTrackTestSupport.audio, audio.readBytes())
                assertFalse(output.exists()); assertEquals(0, active)
                muxStage = "entered"
                DualTrackMuxer().mux(video, audio, output, cancel).also {
                    muxStage = "returned_duration_us_${it.durationUs}"
                }
            }).run(id, TransferCancellation())
        assertEquals(listOf(draft.dualTrackPlan!!.videoUrl, draft.dualTrackPlan.audioUrl), visited)
        assertEquals(1, peak)
        assertEquals(outcome(repo, id, muxStage), TaskStatus.SUCCEEDED, repo.record(id)!!.taskStatus)
        val asset = repo.stateSnapshot().assets.single()
        assertEquals(FormatCheck.PASSED, asset.format); assertEquals("video/mp4", asset.mimeType)
        assertTrue(repo.snapshot().single().verified); assertNotNull(repo.fileUri(id))
        assertFalse(repo.files!!.stage(id).exists()); assertEquals(0L, repo.files!!.cacheBytes(id))
        assertFalse(repo.store.file.readText().contains("secret"))
    }
    @Test fun unknownTotalsRemainUnknownEvenAfterBothTracksComplete() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(DualTrackTestSupport.plan(false)), false)
        var calls = 0; var muxStage = "not_entered"; val ranges = mutableListOf<String>()
        DualTrackTransfer(repo, HttpTransport { _, headers, _ ->
            // Even without a declared length the request stays a closed interval, never bytes=0-.
            val range = headers["Range"]!!
            ranges.add(range); assertTrue(range, Regex("bytes=[0-9]{1,19}-[0-9]{1,19}").matches(range))
            ranged(if (calls++ == 0) DualTrackTestSupport.video else DualTrackTestSupport.audio, range)
        }, AccessContextProvider { null }, mux = { video, audio, output, cancel ->
            muxStage = "entered"
            DualTrackMuxer().mux(video, audio, output, cancel).also {
                muxStage = "returned_duration_us_${it.durationUs}"
            }
        }).run(id, TransferCancellation())
        assertEquals(outcome(repo, id, muxStage), TaskStatus.SUCCEEDED, repo.record(id)!!.taskStatus)
        assertEquals(2, ranges.size)
        assertNull(repo.record(id)!!.expected); assertEquals(-1L, repo.snapshot().single().total)
    }
    @Test fun audio403NeverRetriesMuxesOrPublishesVideoAlone() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            if (calls++ == 0) whole(DualTrackTestSupport.video) else response(byteArrayOf(), 403)
        }, AccessContextProvider { null }, mux = { _, _, _, _ -> error("No mux after audio failure") })
            .run(id, TransferCancellation())
        assertEquals(2, calls); assertEquals(FailureKind.ACCESS_CONDITION, repo.record(id)!!.failure)
        assertTrue(repo.record(id)!!.safeFailure!!.contains("重新解析")); assertNoPartialAsset(repo, id)
        assertTrue(runCatching { repo.queueResume(id) }.isFailure)
        assertTrue(runCatching { repo.retry(id, "agent", false) }.isFailure)
    }
    @Test fun partial206NeverTurnsIntoConcatenatedContinuation() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            calls++; response(DualTrackTestSupport.video.copyOf(12), 206,
                mapOf("Content-Range" to "bytes 0-11/${DualTrackTestSupport.video.size}", "Content-Length" to "12"))
        }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(1, calls); assertEquals(FailureKind.HTTP_REJECTED, repo.record(id)!!.failure)
        assertNoPartialAsset(repo, id)
    }
    @Test fun truncatedSecondTrackAndHtmlFirstTrackAreNeverHalfSuccess() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            if (calls++ == 0) whole(DualTrackTestSupport.video) else response(DualTrackTestSupport.audio.copyOf(20),
                206, mapOf("Content-Length" to DualTrackTestSupport.audio.size.toString(),
                    "Content-Range" to "bytes 0-${DualTrackTestSupport.audio.size - 1}/${DualTrackTestSupport.audio.size}"))
        }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(FailureKind.NETWORK, repo.record(id)!!.failure); assertNoPartialAsset(repo, id)
        repo.forgetRecord(id)
        val next = repo.enqueue(DualTrackTestSupport.draft(DualTrackTestSupport.plan(false)), false)
        DualTrackTransfer(repo, HttpTransport { _, _, _ -> response("<html>not media</html>".toByteArray()) },
            AccessContextProvider { null }).run(next, TransferCancellation())
        // A closed-range request answered by a full non-empty body violates the slice contract.
        assertEquals(FailureKind.HTTP_REJECTED, repo.record(next)!!.failure); assertNoPartialAsset(repo, next)
    }
    @Test fun cancellationDuringReadCleansEverythingAndSkipsAudio() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        val token = TransferCancellation()
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            calls++; response(DualTrackTestSupport.video, 206, mapOf(
                "Content-Length" to DualTrackTestSupport.video.size.toString(),
                "Content-Range" to "bytes 0-${DualTrackTestSupport.video.size - 1}/${DualTrackTestSupport.video.size}"),
                body = {
                    object : ByteArrayInputStream(DualTrackTestSupport.video) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            repo.cancel(id); token.cancel(); return n
                        }
                    }
                })
        }, AccessContextProvider { null }).run(id, token)
        assertEquals(1, calls); assertEquals(TaskStatus.CANCELLED, repo.record(id)!!.taskStatus)
        assertNoPartialAsset(repo, id)
    }
    @Test fun cancellationDuringMuxRemovesUnpublishedOutput() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0; val token = TransferCancellation()
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            whole(if (calls++ == 0) DualTrackTestSupport.video else DualTrackTestSupport.audio)
        }, AccessContextProvider { null }, mux = { _, _, output, cancel ->
            output.writeText("private incomplete output")
            repo.cancel(id); cancel.cancel(); throw CancellationException()
        }).run(id, token)
        assertEquals(TaskStatus.CANCELLED, repo.record(id)!!.taskStatus); assertNoPartialAsset(repo, id)
    }
    @Test fun coldRepositoryNeverUsesStaleInputsOrSignedUrls() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false)
        val pair = repo.files!!.dualTrackWorkspace.fresh(id)
        pair.first.writeBytes(DualTrackTestSupport.video); pair.second.writeBytes(DualTrackTestSupport.audio.copyOf(12))
        repo.files!!.stage(id).writeText("old unfinished output")
        val cold = DownloadRepository(DownloadStore(repo.store.file.parentFile!!), DualTrackTestSupport.NoSystem(), files = repo.files)
        DualTrackTransfer(cold, HttpTransport { _, _, _ -> error("Cold task must not issue requests") },
            AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(TaskStatus.INTERRUPTED, cold.record(id)!!.taskStatus)
        assertEquals(PauseReason.SOURCE_CHANGED, cold.record(id)!!.pauseReason)
        assertNoPartialAsset(cold, id)
    }
    @Test fun mismatchedPlanIdentityAndActualTrackRoleAreRejected() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false)
        repo.change(id) { it.copy(dualTrackMetadata = DualTrackTestSupport.plan().copy(resourceId = "different-work").metadata()) }
        DualTrackTransfer(repo, HttpTransport { _, _, _ -> error("Identity mismatch must not contact the CDN") },
            AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(FailureKind.HTTP_REJECTED, repo.record(id)!!.failure); assertNoPartialAsset(repo, id)
        repo.forgetRecord(id)
        val wrong = DualTrackTestSupport.plan().copy(videoLength = DualTrackTestSupport.audio.size.toLong())
        val next = repo.enqueue(DualTrackTestSupport.draft(wrong), false)
        DualTrackTransfer(repo, HttpTransport { _, _, _ -> whole(DualTrackTestSupport.audio) },
            AccessContextProvider { null }).run(next, TransferCancellation())
        assertEquals(FailureKind.NOT_VIDEO, repo.record(next)!!.failure); assertNoPartialAsset(repo, next)
    }
    @Test fun fakeMuxSuccessStillRequiresVerifiedTwoTrackMp4AndDeclaredDuration() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            whole(if (calls++ == 0) DualTrackTestSupport.video else DualTrackTestSupport.audio)
        }, AccessContextProvider { null }, mux = { video, _, output, _ ->
            video.copyTo(output); MuxedTracks(2_000_000)
        }).run(id, TransferCancellation())
        assertEquals(FailureKind.NOT_VIDEO, repo.record(id)!!.failure); assertNoPartialAsset(repo, id)
    }
    @Test fun typedMuxIOExceptionIsMediaFailureAndNeverLeaksNativeDetails() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        val privateMessage = "/data/user/0/private/native-path?token=synthetic-secret"
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            whole(if(calls++==0)DualTrackTestSupport.video else DualTrackTestSupport.audio)
        }, AccessContextProvider { null }, mux = { _, _, _, _ ->
            throw DualTrackMuxException(privateMessage)
        }).run(id, TransferCancellation())
        assertEquals(2, calls)
        assertEquals(FailureKind.NOT_VIDEO, repo.record(id)!!.failure)
        assertEquals(DualTrackTransfer.MUX_FAILURE_MESSAGE, repo.record(id)!!.safeFailure)
        assertFalse(repo.store.file.readText().contains("native-path"))
        assertFalse(repo.store.file.readText().contains("synthetic-secret"))
        assertNoPartialAsset(repo, id)
    }
    @Test fun psshVersionOneOutputCannotBePublishedEvenAfterFakeMuxSuccess() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            whole(if(calls++==0)DualTrackTestSupport.video else DualTrackTestSupport.audio)
        }, AccessContextProvider { null }, mux = { video, audio, output, cancel ->
            val result = DualTrackMuxer().mux(video, audio, output, cancel)
            val payload = ByteArray(28).apply { this[0] = 1 }
            val pssh = java.nio.ByteBuffer.allocate(36).putInt(36).put("pssh".toByteArray(Charsets.US_ASCII)).put(payload).array()
            output.appendBytes(pssh)
            assertEquals(FormatCheck.INVALID, repo.files!!.inspectDualTrack(output, 2_000_000, cancel).format)
            result
        }).run(id, TransferCancellation())
        assertEquals(FailureKind.NOT_VIDEO, repo.record(id)!!.failure)
        assertNoPartialAsset(repo, id)
    }
    @Test fun credentialedCrossOriginRedirectStillUsesExistingPolicy() = DualTrackTestSupport.test { repo ->
        val plan = DualTrackTestSupport.plan().copy(videoUrl = "https://site.example/video?signature=ephemeral",
            audioUrl = "https://site.example/audio?signature=ephemeral")
        val id = repo.enqueue(DualTrackTestSupport.draft(plan, context = true), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, headers, _ ->
            calls++; assertEquals("session=synthetic", headers["Cookie"])
            response(byteArrayOf(), 302, mapOf("Location" to "https://foreign.example/video"))
        }, AccessContextProvider { "session=synthetic" }).run(id, TransferCancellation())
        assertEquals(1, calls); assertEquals(FailureKind.ACCESS_CONDITION, repo.record(id)!!.failure)
        assertNoPartialAsset(repo, id); assertFalse(repo.store.file.readText().contains("synthetic"))
    }
    private fun expectedChunks(size: Int, chunk: Long) = generateSequence(0L) { it + chunk }
        .takeWhile { it < size }.map { "bytes=$it-${minOf(it + chunk, size.toLong()) - 1}" }.toList()
    @Test fun closedRangeChunksAreSequentialExactAndReassembledIntact() = DualTrackTestSupport.test { repo ->
        val draft = DualTrackTestSupport.draft(); val id = repo.enqueue(draft, false)
        val chunk = 512L; val asked = mutableListOf<Pair<String, String>>(); var muxStage = "not_entered"
        val transport = HttpTransport { url, headers, _ ->
            assertNull(headers["Cookie"]); assertNull(headers["If-Range"])
            val range = headers["Range"]!!
            asked.add(url to range)
            ranged(if (url == draft.dualTrackPlan!!.videoUrl) DualTrackTestSupport.video else DualTrackTestSupport.audio, range)
        }
        DualTrackTransfer(repo, transport, AccessContextProvider { error("Anonymous CDN must not receive cookies") },
            chunkBytes = chunk, mux = { video, audio, output, cancel ->
                assertEquals(TaskStatus.MUXING, repo.record(id)!!.taskStatus)
                assertArrayEquals(DualTrackTestSupport.video, video.readBytes())
                assertArrayEquals(DualTrackTestSupport.audio, audio.readBytes())
                assertFalse(output.exists()); muxStage = "entered"
                DualTrackMuxer().mux(video, audio, output, cancel).also {
                    muxStage = "returned_duration_us_${it.durationUs}"
                }
            }).run(id, TransferCancellation())
        assertEquals(outcome(repo, id, muxStage), TaskStatus.SUCCEEDED, repo.record(id)!!.taskStatus)
        val videoChunks = expectedChunks(DualTrackTestSupport.video.size, chunk)
        val audioChunks = expectedChunks(DualTrackTestSupport.audio.size, chunk)
        assertEquals(videoChunks.map { draft.dualTrackPlan!!.videoUrl to it } +
            audioChunks.map { draft.dualTrackPlan!!.audioUrl to it }, asked)
        // Every slice is a closed interval and the final slice of each track is exact.
        (videoChunks + audioChunks).forEach { range ->
            assertTrue(range, Regex("bytes=[0-9]{1,19}-[0-9]{1,19}").matches(range))
        }
        fun lastSlice(size: Int) = "bytes=${size - (size % chunk)}-${size - 1}"
        assertEquals(lastSlice(DualTrackTestSupport.video.size), videoChunks.last())
        assertEquals(lastSlice(DualTrackTestSupport.audio.size), audioChunks.last())
        assertTrue(repo.snapshot().single().verified); assertNotNull(repo.fileUri(id))
        assertFalse(repo.files!!.stage(id).exists()); assertEquals(0L, repo.files!!.cacheBytes(id))
    }
    @Test fun unknownTotalsChunkFromTheLengthTheFirstClosedSliceReports() = DualTrackTestSupport.test { repo ->
        val draft = DualTrackTestSupport.draft(DualTrackTestSupport.plan(false))
        val id = repo.enqueue(draft, false); val chunk = 512L; val asked = mutableListOf<String>()
        DualTrackTransfer(repo, HttpTransport { url, headers, _ ->
            asked.add(headers["Range"]!!)
            ranged(if (url == draft.dualTrackPlan!!.videoUrl) DualTrackTestSupport.video else DualTrackTestSupport.audio,
                headers["Range"]!!)
        }, AccessContextProvider { null }, chunkBytes = chunk).run(id, TransferCancellation())
        // The first slice discovers the length from Content-Range; the rest stay exact and closed.
        assertEquals(expectedChunks(DualTrackTestSupport.video.size, chunk) +
            expectedChunks(DualTrackTestSupport.audio.size, chunk), asked)
        assertEquals(TaskStatus.SUCCEEDED, repo.record(id)!!.taskStatus)
        assertNull(repo.record(id)!!.expected)
    }
    @Test fun chunk403MidTrackFailsHonestlyWithoutRetryOrMux() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, headers, _ ->
            if (calls++ == 0) ranged(DualTrackTestSupport.video, headers["Range"]!!)
            else response(byteArrayOf(), 403)
        }, AccessContextProvider { null }, chunkBytes = 512, mux = { _, _, _, _ -> error("No mux after chunk failure") })
            .run(id, TransferCancellation())
        assertEquals(2, calls); assertEquals(FailureKind.ACCESS_CONDITION, repo.record(id)!!.failure)
        assertTrue(repo.record(id)!!.safeFailure!!.contains("重新解析")); assertNoPartialAsset(repo, id)
    }
    @Test fun chunkRequestBudgetOverrunFailsClosed() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false)
        val chunk = 64L; val chunks = (DualTrackTestSupport.video.size + chunk - 1) / chunk; var calls = 0
        DualTrackTransfer(repo, HttpTransport { url, headers, _ ->
            calls++
            // Every slice costs one redirect hop plus the ranged answer.
            if (url.contains("h=1")) ranged(DualTrackTestSupport.video, headers["Range"]!!)
            else response(byteArrayOf(), 302, mapOf("Location" to "$url&h=1"))
        }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(chunks + DualTrackTransfer.CHUNK_OPEN_ALLOWANCE, calls.toLong())
        assertEquals(FailureKind.HTTP_REJECTED, repo.record(id)!!.failure)
        assertEquals(DualTrackTransfer.CHUNK_BUDGET_MESSAGE, repo.record(id)!!.safeFailure)
        assertNoPartialAsset(repo, id)
    }
}
