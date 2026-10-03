package com.example.purebrowser.download

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectResumeCheckpointTest {
    private fun test(block: (DirectResumeFixture) -> Unit) = DirectResumeFixture().use(block)

    @Test fun recreationValidatesDurablePrefixAndCountsNoUncommittedTail() = test { f ->
        val id = f.enqueue(); f.seed(id)
        val durable = f.partial.toLong()
        f.stage(id).appendBytes(ByteArray(99) { 17 })
        val length = f.stage(id).length()
        val recreated = DirectCheckpointStore(f.root)
        assertTrue(recreated.hasValid(id, f.stage(id)))
        assertEquals(durable, recreated.cacheBytes(id, f.stage(id)))
        assertEquals(length, f.stage(id).length()) // Validation never truncates a tail.
        val raw = File(f.root, "direct-checkpoints/$id.checkpoint").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("https://")); assertFalse(raw.contains("token=")); assertFalse(raw.contains("Cookie"))
        recreated.delete(id)
        assertFalse(f.checkpoints.hasValid(id, f.stage(id)))
        assertEquals(length, f.stage(id).length()) // Checkpoint deletion does not delete stage bytes.
    }

    @Test fun partialTemporaryMetadataCannotReplaceTheLastAtomicCommit() = test { f ->
        val id = f.enqueue(); f.seed(id)
        File(f.root, "direct-checkpoints/$id.tmp").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(DirectCheckpointStore(f.root).hasValid(id, f.stage(id)))
        assertEquals(f.partial.toLong(), f.checkpoints.cacheBytes(id, f.stage(id)))
        f.checkpoints.delete(id)
        assertFalse(File(f.root, "direct-checkpoints/$id.tmp").exists())
    }

    @Test fun corruptedMetadataOrPrefixAndShortStageNeverValidate() = test { f ->
        val id = f.enqueue(); f.seed(id)
        val metadata = File(f.root, "direct-checkpoints/$id.checkpoint")
        val original = metadata.readBytes()
        val corrupt = original.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        metadata.writeBytes(corrupt)
        assertFalse(f.checkpoints.hasValid(id, f.stage(id))); assertEquals(0L, f.checkpoints.cacheBytes(id, f.stage(id)))
        metadata.writeBytes(original)
        RandomAccessFile(f.stage(id), "rw").use { it.seek(15); it.writeByte(99) }
        assertFalse(f.checkpoints.hasValid(id, f.stage(id)))
        f.seed(id)
        RandomAccessFile(f.stage(id), "rw").use { it.setLength(f.partial - 1L) }
        assertFalse(f.checkpoints.hasValid(id, f.stage(id)))
        assertEquals(f.partial - 1L, f.stage(id).length())
    }

    @Test fun taskPathTraversalAndForeignStageAreRejectedWithoutChangingFiles() = test { f ->
        val id = f.enqueue(); f.seed(id)
        val foreign = File(f.root, "foreign.part").apply { writeBytes(f.stage(id).readBytes()) }
        assertFalse(f.checkpoints.hasValid(id, foreign))
        assertFalse(f.checkpoints.hasValid("../$id", f.stage(id)))
        assertFalse(f.checkpoints.hasValid(id, File(f.root, "transfers/../foreign.part")))
        try { f.checkpoints.delete("../$id"); fail("Unsafe TaskId accepted") } catch (_: IllegalArgumentException) {}
        assertTrue(f.checkpoints.hasValid(id, f.stage(id))); assertTrue(foreign.exists())
        val other = f.enqueue()
        assertFalse(f.checkpoints.hasValid(other, f.stage(id)))
    }

    @Test fun stageSymlinkCannotValidateAndItsTargetIsNeverTouched() = test { f ->
        val id = f.enqueue(); f.seed(id)
        val external = File(f.root.parentFile, "direct-resume-external-${UUID.randomUUID()}")
        val stage = f.stage(id)
        val bytes = stage.readBytes(); external.writeBytes(bytes)
        try {
            assertTrue(stage.delete()); Os.symlink(external.path, stage.path)
            assertFalse(f.checkpoints.hasValid(id, stage))
            f.queue(id)
            ControlledTransfer(f.repo, HttpTransport { _, _, _ -> error("Symlink stage requested source") },
                AccessContextProvider { null }).run(id, TransferCancellation())
            assertEquals(FailureKind.STORAGE, f.repo.record(id)!!.failure)
            assertFalse(f.repo.record(id)!!.resumeAvailable)
            assertArrayEquals(bytes, external.readBytes())
        } finally { stage.delete(); external.delete() }
    }

    @Test fun checkpointAndDirectorySymlinksCannotReadOrOverwriteExternalFiles() = test { f ->
        val id = f.enqueue(); f.seed(id)
        val meta = File(f.root, "direct-checkpoints/$id.checkpoint")
        val outside = File(f.root.parentFile, "direct-resume-meta-${UUID.randomUUID()}")
        val raw = meta.readBytes(); outside.writeBytes(raw)
        try {
            assertTrue(meta.delete()); Os.symlink(outside.path, meta.path)
            assertFalse(f.checkpoints.hasValid(id, f.stage(id)))
            try { f.checkpoints.save(id, f.stage(id), DirectResumeFixture.URL, DirectResumeFixture.URL,
                DirectResumeFixture.ETAG, f.data.size.toLong(), f.partial.toLong()); fail("Symlink checkpoint replaced") }
            catch (_: IllegalArgumentException) {}
            assertArrayEquals(raw, outside.readBytes())
            meta.delete()
            val directory = File(f.root, "direct-checkpoints")
            directory.deleteRecursively()
            val outsideDir = File(f.root.parentFile, "direct-resume-dir-${UUID.randomUUID()}").apply { mkdir() }
            try {
                File(outsideDir, "$id.checkpoint").writeBytes(raw)
                Os.symlink(outsideDir.path, directory.path)
                assertFalse(f.checkpoints.hasValid(id, f.stage(id)))
                try { f.checkpoints.delete(id); fail("Symlink checkpoint directory followed") }
                catch (_: IllegalArgumentException) {}
                assertArrayEquals(raw, File(outsideDir, "$id.checkpoint").readBytes())
            } finally { directory.delete(); outsideDir.deleteRecursively() }
        } finally { meta.delete(); outside.delete() }
    }

    @Test fun corruptionBeyond64KiBInvalidatesTheEntireDurablePrefix() = test { f ->
        val id = f.enqueue()
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        f.data.copyInto(bytes)
        val stage = f.stage(id)
        stage.outputStream().use { it.write(bytes); it.fd.sync() }
        f.checkpoints.save(id, stage, DirectResumeFixture.URL, DirectResumeFixture.URL,
            DirectResumeFixture.ETAG, bytes.size.toLong(), bytes.size.toLong())
        assertTrue(f.checkpoints.hasValid(id, stage))
        val before = bytes.copyOfRange(0, 65536)
        RandomAccessFile(stage, "rw").use { it.seek(130_000); it.writeByte(bytes[130_000].toInt() xor 1); it.fd.sync() }
        assertArrayEquals(before, stage.readBytes().copyOfRange(0, 65536))
        assertFalse(f.checkpoints.hasValid(id, stage))
        assertEquals(0L, f.checkpoints.cacheBytes(id, stage))
        f.repo.change(id) { it.copy(taskStatus = TaskStatus.QUEUED, resumeAvailable = true) }
        ControlledTransfer(f.repo, HttpTransport { _, _, _ -> error("Corrupted durable prefix requested source") },
            AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(TaskStatus.FAILED, f.repo.record(id)!!.taskStatus)
        assertEquals(PauseReason.SOURCE_CHANGED, f.repo.record(id)!!.pauseReason)
        assertFalse(f.repo.record(id)!!.resumeAvailable)
        assertFalse(stage.exists())
    }

    /** Run on API 28 and 37: verifies the actual platform-selected SHA-256 provider supports cloning. */
    @Test fun platformSha256SnapshotsDoNotConsumeTheLiveDigestAndResumeSeedContinuesCorrectly() = test { f ->
        val id = f.enqueue(); f.seed(id)
        val checkpoint = f.checkpoints.load(id, f.stage(id))!!
        val live = checkpoint.verifiedDigest!!
        assertEquals(checkpoint.prefixHash, DirectCheckpointStore.snapshotDigest(live))
        assertEquals(checkpoint.prefixHash, DirectCheckpointStore.snapshotDigest(live))
        val rest = f.data.copyOfRange(f.partial, f.data.size)
        live.update(rest)
        val expected = MessageDigest.getInstance("SHA-256").digest(f.data).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, DirectCheckpointStore.snapshotDigest(live))
        assertEquals(expected, DirectCheckpointStore.snapshotDigest(live))
    }

    @Test fun suppliedIncrementalDigestCheckpointDoesNotReadTheStage() = test { f ->
        val id = f.enqueue(); val stage = f.stage(id)
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        f.data.copyInto(bytes)
        stage.outputStream().use { it.write(bytes); it.fd.sync() }
        val incremental = MessageDigest.getInstance("SHA-256").apply { update(bytes) }
        // The app can stat a write-only file but cannot open it for prefix hashing.
        Os.chmod(stage.path, 128) // 0200
        try {
            f.checkpoints.save(id, stage, DirectResumeFixture.URL, DirectResumeFixture.URL,
                DirectResumeFixture.ETAG, bytes.size.toLong(), bytes.size.toLong(), incremental)
        } finally { Os.chmod(stage.path, 384) } // 0600
        assertTrue(f.checkpoints.hasValid(id, stage))
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, f.checkpoints.load(id, stage)!!.prefixHash)
    }

    @Test fun unsupportedProviderCannotOverwriteAnExistingDurableCheckpoint() = test { f ->
        val id = f.enqueue(); f.seed(id)
        val metadata = File(f.root, "direct-checkpoints/$id.checkpoint")
        val before = metadata.readBytes(); val stageBefore = f.stage(id).readBytes()
        val unsupported = object : MessageDigest("SHA-256") {
            override fun engineUpdate(input: Byte) {}
            override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {}
            override fun engineReset() { error("Live digest was reset") }
            override fun engineDigest(): ByteArray = error("Live digest was consumed")
            override fun clone(): Any = throw CloneNotSupportedException("synthetic unsupported provider")
        }
        try {
            f.checkpoints.save(id, f.stage(id), DirectResumeFixture.URL, DirectResumeFixture.URL,
                DirectResumeFixture.ETAG, f.data.size.toLong(), f.partial.toLong(), unsupported)
            fail("Uncloneable provider committed a checkpoint")
        } catch (_: IOException) {}
        assertArrayEquals(before, metadata.readBytes()); assertArrayEquals(stageBefore, f.stage(id).readBytes())
        assertTrue(f.checkpoints.hasValid(id, f.stage(id)))
    }

    @Test fun metadataWithTrailingGarbageAndImpossibleDurableBytesIsRejected() = test { f ->
        val id = f.enqueue(); f.seed(id)
        val metadata = File(f.root, "direct-checkpoints/$id.checkpoint")
        metadata.appendBytes(byteArrayOf(0))
        assertFalse(f.checkpoints.hasValid(id, f.stage(id)))
        try { f.checkpoints.save(id, f.stage(id), DirectResumeFixture.URL, DirectResumeFixture.URL,
            DirectResumeFixture.ETAG, f.data.size.toLong(), f.data.size + 1L); fail("Impossible count accepted") }
        catch (_: IllegalArgumentException) {}
    }
}
