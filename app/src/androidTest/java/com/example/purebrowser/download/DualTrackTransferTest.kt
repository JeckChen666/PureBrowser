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
        var calls = 0; var muxStage = "not_entered"
        DualTrackTransfer(repo, HttpTransport { _, headers, _ ->
            assertEquals("bytes=0-", headers["Range"])
            response(if (calls++ == 0) DualTrackTestSupport.video else DualTrackTestSupport.audio, headers = emptyMap())
        }, AccessContextProvider { null }, mux = { video, audio, output, cancel ->
            muxStage = "entered"
            DualTrackMuxer().mux(video, audio, output, cancel).also {
                muxStage = "returned_duration_us_${it.durationUs}"
            }
        }).run(id, TransferCancellation())
        assertEquals(outcome(repo, id, muxStage), TaskStatus.SUCCEEDED, repo.record(id)!!.taskStatus)
        assertNull(repo.record(id)!!.expected); assertEquals(-1L, repo.snapshot().single().total)
    }
    @Test fun audio403NeverRetriesMuxesOrPublishesVideoAlone() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            if (calls++ == 0) response(DualTrackTestSupport.video) else response(byteArrayOf(), 403)
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
            if (calls++ == 0) response(DualTrackTestSupport.video) else response(DualTrackTestSupport.audio.copyOf(20),
                headers = mapOf("Content-Length" to DualTrackTestSupport.audio.size.toString()))
        }, AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(FailureKind.NETWORK, repo.record(id)!!.failure); assertNoPartialAsset(repo, id)
        repo.forgetRecord(id)
        val next = repo.enqueue(DualTrackTestSupport.draft(DualTrackTestSupport.plan(false)), false)
        DualTrackTransfer(repo, HttpTransport { _, _, _ -> response("<html>not media</html>".toByteArray()) },
            AccessContextProvider { null }).run(next, TransferCancellation())
        assertEquals(FailureKind.NOT_VIDEO, repo.record(next)!!.failure); assertNoPartialAsset(repo, next)
    }
    @Test fun cancellationDuringReadCleansEverythingAndSkipsAudio() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        val token = TransferCancellation()
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            calls++; response(DualTrackTestSupport.video, body = {
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
            response(if (calls++ == 0) DualTrackTestSupport.video else DualTrackTestSupport.audio)
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
        DualTrackTransfer(repo, HttpTransport { _, _, _ -> response(DualTrackTestSupport.audio) },
            AccessContextProvider { null }).run(next, TransferCancellation())
        assertEquals(FailureKind.NOT_VIDEO, repo.record(next)!!.failure); assertNoPartialAsset(repo, next)
    }
    @Test fun fakeMuxSuccessStillRequiresVerifiedTwoTrackMp4AndDeclaredDuration() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            response(if (calls++ == 0) DualTrackTestSupport.video else DualTrackTestSupport.audio)
        }, AccessContextProvider { null }, mux = { video, _, output, _ ->
            video.copyTo(output); MuxedTracks(2_000_000)
        }).run(id, TransferCancellation())
        assertEquals(FailureKind.NOT_VIDEO, repo.record(id)!!.failure); assertNoPartialAsset(repo, id)
    }
    @Test fun typedMuxIOExceptionIsMediaFailureAndNeverLeaksNativeDetails() = DualTrackTestSupport.test { repo ->
        val id = repo.enqueue(DualTrackTestSupport.draft(), false); var calls = 0
        val privateMessage = "/data/user/0/private/native-path?token=synthetic-secret"
        DualTrackTransfer(repo, HttpTransport { _, _, _ ->
            response(if(calls++ == 0)DualTrackTestSupport.video else DualTrackTestSupport.audio)
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
            response(if(calls++ == 0)DualTrackTestSupport.video else DualTrackTestSupport.audio)
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
}
