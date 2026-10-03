package com.example.purebrowser.download

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Pure policy checks, runnable without an emulator; disk durability is covered by device tests. */
class DirectResumeValidatorTest {
    @Test fun onlySingleStrongEntityTagsCanBecomeIfRangeHeaders() {
        listOf("\"stable\"", "\"\"", "\"a\\b\"", "\"\u00ff\"").forEach {
            assertEquals(it, DirectCheckpointStore.strongEtag(it))
        }
        listOf(null, "", "stable", "W/\"stable\"", "w/\"stable\"", " \"stable\"", "\"stable\" ",
            "\"a b\"", "\"a\tb\"", "\"a\r\nb\"", "\"a\"b\"", "\"a\", \"b\"", "\"\u007f\"",
            "\"\u0100\"", "\"${"a".repeat(4095)}\"").forEach {
            assertNull(DirectCheckpointStore.strongEtag(it))
        }
    }

    @Test fun identityHashIsExactAndDoesNotRetainSignedUrlText() {
        val url = "https://site.example/video?token=secret%2Bexact"
        val hash = DirectCheckpointStore.urlHash(url)
        assertTrue(Regex("[0-9a-f]{64}").matches(hash))
        assertEquals(hash, DirectCheckpointStore.urlHash(url))
        assertNotEquals(hash, DirectCheckpointStore.urlHash(url.replace("%2B", "+")))
        assertNotEquals(hash, DirectCheckpointStore.urlHash(url.replace("site.example", "cdn.example")))
        assertFalse(hash.contains("secret")); assertFalse(hash.contains("https"))
    }

    @Test fun taskIdsCannotEscapeOwnedDirectories() {
        listOf("a", "task-123", "A9-${"a".repeat(96)}").forEach { DirectCheckpointStore.checkId(it) }
        listOf("", "../task", "task/child", "task.part", "task\\child", "\u0000", "a".repeat(101)).forEach {
            try { DirectCheckpointStore.checkId(it); fail("Unsafe task identifier accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun uncloneableSha256ProviderFailsClosedWithoutConsumingItsLiveState() {
        var consumed = false
        val uncloneable = object : MessageDigest("SHA-256") {
            override fun engineUpdate(input: Byte) {}
            override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {}
            override fun engineReset() { consumed = true }
            override fun engineDigest(): ByteArray { consumed = true; return ByteArray(32) }
            override fun clone(): Any = throw CloneNotSupportedException("synthetic unsupported provider")
        }
        try { DirectCheckpointStore.snapshotDigest(uncloneable); fail("Uncloneable provider accepted") }
        catch (_: IOException) {}
        assertFalse(consumed)
    }

    @Test fun cloneSnapshotsMatchFullPrefixHashesWithoutResettingIncrementalDigest() {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val live = MessageDigest.getInstance("SHA-256")
        for (end in listOf(65536, 100000, bytes.size)) {
            val start = when (end) { 65536 -> 0; 100000 -> 65536; else -> 100000 }
            live.update(bytes, start, end - start)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(end))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, DirectCheckpointStore.snapshotDigest(live))
            assertEquals(expected, DirectCheckpointStore.snapshotDigest(live))
        }
    }

    @Test fun absentStageOrUnsafeIdNeverClaimsCachedBytesOrCreatesMetadata() {
        val root = Files.createTempDirectory("direct-resume-validator-").toFile()
        try {
            val parent = File(root, "transfers").apply { mkdir() }
            val stage = File(parent, "task.part")
            val store = DirectCheckpointStore(root)
            assertFalse(store.hasValid("task", stage)); assertEquals(0L, store.cacheBytes("task", stage))
            assertFalse(store.hasValid("../task", stage)); assertEquals(0L, store.cacheBytes("../task", stage))
            store.delete("task")
            assertFalse(File(root, "direct-checkpoints").exists())
            assertFalse(stage.exists())
        } finally { root.deleteRecursively() }
    }
}
