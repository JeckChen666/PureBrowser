package com.example.purebrowser.download.hls

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HlsResumeWorkspaceTest {
    private val id = "resume-owned-test"
    private fun plan() = HlsDownloadPlan("https://example.test/media.m3u8?private=entry", "https://example.test/media.m3u8?private=entry",
        HlsPlaylist.Media(listOf(HlsSegment("https://example.test/0.ts?private=segment", 2_000_000, 0),
            HlsSegment("https://example.test/1.ts", 2_000_000, 1)), 4_000_000, 2_000_000, 17))
    private val bytes = ByteArray(188 * 5) { if (it % 188 == 0) 0x47 else 0x11 }
    private fun withWorkspace(block: (HlsWorkspace, File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "hls-resume-${UUID.randomUUID()}")
        try { block(HlsWorkspace(root), root) } finally {
            // Tests unlink their own symlinks first; never traverse a linked outside directory.
            File(root, id).listFiles()?.filter { Files.isSymbolicLink(it.toPath()) }?.forEach { it.delete() }
            root.deleteRecursively()
        }
    }
    private fun seed(workspace: HlsWorkspace) {
        val plan = plan(); workspace.save(id, plan)
        assertTrue(workspace.start(id, plan, 17))
        workspace.segmentPart(id, 0).writeBytes(bytes)
        workspace.completeSegment(id, plan, 0) {}
    }

    @Test fun noPlanIsNotResumableButOriginalUnstartedPlanIs() = withWorkspace { ws, root ->
        assertFalse(ws.hasResumeData(id)); assertEquals(0L, ws.cacheBytes(id))
        ws.discardIncomplete(id) // Missing old/cancelled workspace must be a harmless cold-recovery call.
        assertFalse(File(root, id).exists())
        ws.save(id, plan())
        assertTrue(File(root, "$id/checkpoint.json").delete()) // Legacy unstarted plan has no checkpoint.
        assertTrue(ws.hasResumeData(id)); assertEquals(0L, ws.cacheBytes(id))
        ws.start(id, plan(), 17)
        assertTrue(ws.hasResumeData(id)); assertEquals(0L, ws.cacheBytes(id))
        File(root, "$id/plan.json").delete()
        assertFalse(ws.hasResumeData(id))
    }

    @Test fun startedKnownPlanWithNoCompletePiecesSurvivesColdRecoveryWithoutClaimingBytes() = withWorkspace { ws, root ->
        ws.save(id, plan()); assertTrue(ws.start(id, plan(), 17))
        ws.segmentPart(id, 0).writeBytes(bytes)
        ws.segmentPart(id, 1).writeBytes(bytes)
        val recovered = HlsWorkspace(root)
        recovered.discardIncomplete(id)
        assertTrue(recovered.hasResumeData(id)); assertEquals(0L, recovered.cacheBytes(id))
        assertTrue(recovered.verifiedPieces(id, recovered.load(id)).isEmpty())
        assertEquals(17L, recovered.load(id).media.mediaSequence)
        assertFalse(recovered.segmentPart(id, 0).exists()); assertFalse(recovered.segmentPart(id, 1).exists())
    }

    @Test fun checkpointIsRedactedAndColdRecoveryKeepsOnlyVerifiedCompletePieces() = withWorkspace { ws, root ->
        seed(ws)
        ws.segmentPart(id, 1).writeBytes(bytes)
        ws.segment(id, 1).writeBytes(bytes) // Fully sized but never checkpointed: not COMPLETE.
        val cp = File(root, "$id/checkpoint.json").readText()
        assertFalse(cp.contains("https")); assertFalse(cp.contains("private")); assertFalse(cp.contains(".ts"))
        assertFalse(cp.contains("Cookie")); assertFalse(cp.contains("Authorization")); assertFalse(cp.contains(root.path))
        val recovered = HlsWorkspace(root)
        recovered.discardIncomplete(id)
        assertTrue(recovered.hasResumeData(id)); assertEquals(bytes.size.toLong(), recovered.cacheBytes(id))
        assertFalse(recovered.segmentPart(id, 1).exists()); assertFalse(recovered.segment(id, 1).exists())
        assertArrayEquals(bytes, recovered.segment(id, 0).readBytes())
        assertFalse(recovered.start(id, plan(), 18))
    }

    @Test fun sizeAndSameSizeHashCorruptionAreNeverReusable() = withWorkspace { ws, _ ->
        seed(ws)
        val altered = bytes.clone().apply { this[100] = 0x55 }
        ws.segment(id, 0).writeBytes(altered)
        assertTrue(ws.hasResumeData(id)) // Valid known plan remains resumable, not this corrupt piece.
        // Frequent cache snapshots stat lengths only; discard/resume performs the hash sweep.
        assertEquals(bytes.size.toLong(), ws.cacheBytes(id))
        ws.discardIncomplete(id); assertEquals(0L, ws.cacheBytes(id)); assertFalse(ws.segment(id, 0).exists())
        seed(ws); ws.segment(id, 0).appendBytes(byteArrayOf(1))
        assertTrue(ws.hasResumeData(id)); ws.discardIncomplete(id)
        assertFalse(ws.segment(id, 0).exists())
        assertTrue(ws.hasResumeData(id)); assertEquals(0L, ws.cacheBytes(id))
    }

    @Test fun alteredPlanAndMalformedCheckpointCannotReusePieces() = withWorkspace { ws, root ->
        seed(ws)
        val file = File(root, "$id/plan.json")
        val json = JSONObject(file.readText())
        json.getJSONArray("segments").getJSONObject(0).put("url", "https://example.test/replaced.ts")
        file.writeText(json.toString())
        assertFalse(ws.hasResumeData(id))
        ws.discardIncomplete(id); assertEquals(0L, ws.cacheBytes(id)); assertFalse(ws.segment(id, 0).exists())
        seed(ws); File(root, "$id/checkpoint.json").writeText("{broken")
        assertFalse(ws.hasResumeData(id)); ws.discardIncomplete(id)
        assertFalse(ws.segment(id, 0).exists())
    }

    @Test fun symlinkedPieceAndAtomicBackupCannotEscapeTaskDirectory() = withWorkspace { ws, root ->
        seed(ws)
        val outside = File(root, "outside.ts").apply { writeBytes(bytes) }
        val piece = ws.segment(id, 0); assertTrue(piece.delete())
        Files.createSymbolicLink(piece.toPath(), outside.toPath())
        assertTrue(ws.hasResumeData(id)); assertEquals(0L, ws.cacheBytes(id)); ws.discardIncomplete(id)
        assertArrayEquals(bytes, outside.readBytes()); assertFalse(Files.isSymbolicLink(piece.toPath()))
        seed(ws)
        Files.createSymbolicLink(File(root, "$id/checkpoint.json.bak").toPath(), outside.toPath())
        assertFalse(ws.hasResumeData(id)); assertEquals(0L, ws.cacheBytes(id))
        ws.discardIncomplete(id); assertArrayEquals(bytes, outside.readBytes())
    }

    @Test fun checkpointAtomicBackupIsRecoveredAndDeletionDoesNotFollowLinks() = withWorkspace { ws, root ->
        seed(ws)
        val cp = File(root, "$id/checkpoint.json")
        assertTrue(cp.renameTo(File(root, "$id/checkpoint.json.bak")))
        assertTrue(HlsWorkspace(root).hasResumeData(id))
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "keep").writeText("owned outside task")
        Files.createSymbolicLink(File(root, "$id/untrusted-directory").toPath(), outside.toPath())
        ws.delete(id)
        assertTrue(File(outside, "keep").exists()); assertFalse(File(root, id).exists())
    }
}
