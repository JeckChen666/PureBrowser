package com.example.purebrowser.download

import com.example.purebrowser.download.site.DualTrackDownloadPlan
import com.example.purebrowser.download.site.DualTrackMetadata
import org.junit.Assert.*
import org.junit.Test

class DualTrackPlanTest {
    private fun plan() = DualTrackDownloadPlan(
        resourceId = "work-1", videoFormatId = "137", audioFormatId = "140",
        videoUrl = "https://cdn.example/video?signature=synthetic-video",
        audioUrl = "https://cdn.example/audio?signature=synthetic-audio",
        videoCodec = "avc1.640028", audioCodec = "mp4a.40.2",
        videoLength = 1200, audioLength = 200, durationUs = 10_000_000,
    )
    @Test fun declarationsAreVersionedBoundedAndRedacted() {
        val p = plan(); p.validate()
        assertEquals(1400L, p.metadata().expectedBytes)
        assertEquals(64, p.metadata().identityHash.length)
        assertFalse(p.metadata().identityHash.contains(p.resourceId))
        assertFalse(p.toString().contains("https")); assertFalse(p.toString().contains("synthetic"))
        assertFalse(p.toString().contains("work-1"))
        assertNull(p.copy(audioLength = null).metadata().expectedBytes)
        assertNotEquals(p.metadata(), p.copy(audioFormatId = "141").metadata())
        assertNotEquals(p.metadata(), p.copy(resourceId = "work-2").metadata())
    }
    @Test fun publicSourceIsOptionalValidatedAndIndependentOfAccessMaterial() {
        assertNull(plan().safeSourceUrl)
        val publicPage = "https://www.youtube.com/watch?v=eRsGyueVLvQ"
        val approved = plan().copy(safeSourceUrl = publicPage)
        approved.validate()
        assertEquals(publicPage, approved.safeSourceUrl)
        assertEquals(plan().metadata(), approved.metadata()) // Navigation is not track identity or authority.
        assertFalse(approved.toString().contains(publicPage))
        val metadata = approved.metadata()
        val record = DownloadRecord(name = "movie.mp4", transfer = TransferType.CONTROLLED,
            protocol = DownloadProtocol.DUAL_TRACK, sourceUrl = publicPage, dualTrackMetadata = metadata,
            expected = metadata.expectedBytes, plannedDurationUs = metadata.durationUs)
        DownloadRules.validate(DownloadData(records = listOf(record)))
        listOf("http://public.example/watch?id=1", "file:///private/page", "javascript:alert(1)",
            "https://user:secret@public.example/watch", "https://public.example/watch#access_token=secret",
            "https://public.example/\nwatch", "https://public.example:0/watch", "https://public.example:65536/watch",
            "https://public.example/" + "x".repeat(8192), plan().videoUrl, plan().audioUrl,
            "https://public.example/watch?token=secret", "https://public.example/watch?%74oken=secret",
            "https://public.example/watch?Access_Token=secret", "https://public.example/watch?signature=secret",
            "https://public.example/watch?session=secret").forEach { source ->
            assertTrue("Unsafe source must fail", runCatching { plan().copy(safeSourceUrl = source) }.isFailure)
        }
        // The generic contract supports public references from other trusted adapters as well.
        plan().copy(safeSourceUrl = "https://public.example/works/public-123?edition=hd").validate()
    }
    @Test fun sixtyMinuteDeclarationIsSupportedWithoutClaimingMuxPressureWasExecuted() {
        val boundary = plan().copy(durationUs = 3_600_000_000L,
            videoLength = DualTrackMetadata.MAX_VIDEO_BYTES, audioLength = DualTrackMetadata.MAX_AUDIO_BYTES)
        boundary.validate()
        assertEquals(640L * 1024 * 1024, boundary.metadata().expectedBytes)
        plan().copy(durationUs = 30L * 60 * 1_000_000 + 1).validate()
    }
    @Test fun unsupportedCodecsIdentitiesLengthsAndDurationsFailClosed() {
        val invalid = listOf<() -> Unit>(
            { plan().copy(resourceId = "https://private.example/?token=secret") },
            { plan().copy(videoFormatId = "140\nCookie") },
            { plan().copy(audioFormatId = "") },
            { plan().copy(videoCodec = "av01.0.08M.08") },
            { plan().copy(audioCodec = "opus") },
            { plan().copy(audioCodec = "mp4a.40.5") },
            { plan().copy(videoLength = 0) }, { plan().copy(audioLength = -1) },
            { plan().copy(videoLength = Long.MAX_VALUE) },
            { plan().copy(audioLength = DualTrackMetadata.MAX_AUDIO_BYTES + 1) },
            { plan().copy(durationUs = 0) },
            { plan().copy(durationUs = DualTrackMetadata.MAX_DURATION_US + 1) },
            { plan().copy(version = 2) },
        )
        invalid.forEach { assertTrue(runCatching(it).isFailure) }
    }
    @Test fun unsafeOrSameTrackUrlsAreRejected() {
        listOf("blob:https://cdn.example/object", "file:///private/video", "https://user:secret@cdn.example/video",
            "https://cdn.example/video#track", "http://cdn.example/video", "https://cdn.example/\nvideo",
            "https://cdn.example/" + "x".repeat(8192)).forEach { url ->
            assertTrue("Rejected $url", runCatching { plan().copy(videoUrl = url) }.isFailure)
        }
        assertTrue(runCatching { plan().copy(audioUrl = plan().videoUrl) }.isFailure)
        val local = plan().copy(videoUrl = "http://127.0.0.1/video")
        assertTrue(runCatching { local.validate() }.isFailure)
        local.validate(allowLocalHttp = true)
    }
    @Test fun dualTasksNeverAdvertisePartialPauseOrResume() {
        val m = plan().metadata()
        TaskStatus.entries.forEach { status ->
            val r = DownloadRecord(name = "movie.mp4", transfer = TransferType.CONTROLLED,
                protocol = DownloadProtocol.DUAL_TRACK, taskStatus = status, dualTrackMetadata = m,
                expected = m.expectedBytes, plannedDurationUs = m.durationUs)
            DownloadRules.validate(DownloadData(records = listOf(r)))
            assertFalse(TaskControlRules.canPause(r)); assertFalse(TaskControlRules.canResume(r))
            assertFalse(TaskControlRules.canResume(r.copy(resumeAvailable = true)))
            assertTrue(runCatching { DownloadRules.validate(DownloadData(records = listOf(r.copy(resumeAvailable = true)))) }.isFailure)
        }
    }
    @Test fun fullRangeMustStartAtZeroAndEndAtTheDeclaredTotal() {
        assertEquals(100L, DualTrackTransfer.fullRange("bytes 0-99/100"))
        assertEquals(1L, DualTrackTransfer.fullRange("bytes 0-0/1"))
        listOf(null, "bytes 10-99/100", "bytes 0-9/100", "bytes */100", "bytes 0-99/*",
            "bytes 0-99/0", "bytes 0-99/9223372036854775808", "bytes 0-99/100\n").forEach {
            assertNull(DualTrackTransfer.fullRange(it))
        }
        assertNull(DualTrackTransfer.length(null)); assertEquals(100L, DualTrackTransfer.length("100"))
        listOf("-1", " 1", "1,1", "9223372036854775808", "1.5").forEach {
            assertTrue(runCatching { DualTrackTransfer.length(it) }.isFailure)
        }
    }
    @Test fun chunkRangeMustBeAnInclusiveSliceInsideTheResource() {
        assertEquals(Triple(0L, 99L, 100L), DualTrackTransfer.chunkRange("bytes 0-99/100"))
        assertEquals(Triple(0L, 0L, 1L), DualTrackTransfer.chunkRange("bytes 0-0/1"))
        assertEquals(Triple(1_048_576L, 2_097_151L, 170_210_373L),
            DualTrackTransfer.chunkRange("bytes 1048576-2097151/170210373"))
        listOf(null, "bytes 0-", "bytes 0-99/*", "bytes */100", "bytes 100-99/100", "bytes 0-100/100",
            "bytes 0-99/99", "bytes 0-99/0", "bytes 0-99/100\n", "bytes -1-99/100",
            "bytes 0-99/9223372036854775808").forEach {
            assertNull("Rejected <$it>", DualTrackTransfer.chunkRange(it))
        }
    }
}
