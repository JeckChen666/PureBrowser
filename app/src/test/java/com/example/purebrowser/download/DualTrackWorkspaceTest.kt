package com.example.purebrowser.download

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class DualTrackWorkspaceTest {
    private fun test(block: (File, DualTrackWorkspace) -> Unit) {
        val root = Files.createTempDirectory("dual-workspace-").toFile()
        try { block(root, DualTrackWorkspace(root)) } finally { root.deleteRecursively() }
    }
    @Test fun freshPairNeverReusesBytesAndCleanupIsTaskScoped() = test { _, workspace ->
        val first = workspace.fresh("task-1"); first.first.writeBytes(ByteArray(12)); first.second.writeBytes(ByteArray(5))
        val other = workspace.fresh("task-2"); other.first.writeBytes(ByteArray(20))
        assertEquals(17L, workspace.cacheBytes("task-1"))
        val restarted = workspace.fresh("task-1")
        assertFalse(restarted.first.exists()); assertFalse(restarted.second.exists())
        assertEquals(20L, workspace.cacheBytes("task-2"))
        workspace.delete("task-1"); workspace.delete("task-2")
        assertEquals(0L, workspace.cacheBytes("task-2"))
    }
    @Test fun unsafeIdsSymlinkRootAndForeignChildrenCannotBeDeleted() = test { root, workspace ->
        assertTrue(runCatching { workspace.fresh("../foreign") }.isFailure)
        val pair = workspace.fresh("task-1")
        val foreign = File(pair.first.parentFile, "keep.txt").apply { writeText("valuable") }
        assertTrue(runCatching { workspace.delete("task-1") }.isFailure)
        assertEquals("valuable", foreign.readText()); foreign.delete(); workspace.delete("task-1")
        File(root, "dual-track").delete()
        val target = File(root, "foreign").apply { mkdir() }
        Files.createSymbolicLink(File(root, "dual-track").toPath(), target.toPath())
        assertTrue(runCatching { workspace.fresh("task-3") }.isFailure)
        assertTrue(target.exists())
        Files.delete(File(root, "dual-track").toPath())
    }
    @Test fun symlinkLeafAndTaskDirectoryFailClosed() = test { root, workspace ->
        val pair = workspace.fresh("task-1")
        val foreign = File(root, "keep.mp4").apply { writeText("valuable") }
        Files.createSymbolicLink(pair.first.toPath(), foreign.toPath())
        assertTrue(runCatching { workspace.delete("task-1") }.isFailure)
        assertTrue(runCatching { workspace.cacheBytes("task-1") }.isFailure)
        assertEquals("valuable", foreign.readText())
        Files.delete(pair.first.toPath()); workspace.delete("task-1")
        val dir = File(root, "dual-track/task-2")
        Files.createSymbolicLink(dir.toPath(), root.toPath())
        assertTrue(runCatching { workspace.fresh("task-2") }.isFailure)
        Files.delete(dir.toPath())
    }
}
