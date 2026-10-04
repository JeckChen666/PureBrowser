package com.example.purebrowser.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Fresh-only, task-owned inputs. Never a checkpoint, and never recursively follows links. */
class DualTrackWorkspace(private val filesRoot: File) {
    private val root get() = File(filesRoot, "dual-track")
    private val leaves = setOf("video.mp4", "audio.mp4")

    private fun directory(id: TaskId, create: Boolean): File {
        DirectCheckpointStore.checkId(id)
        require(DirectCheckpointStore.safeDirectory(filesRoot))
        if (create && !Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) check(root.mkdir())
        if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(DirectCheckpointStore.safeDirectory(root) && root.canonicalFile.parentFile == filesRoot.canonicalFile)
        }
        val dir = File(root, id)
        if (create && !Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) check(dir.mkdir())
        if (Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(DirectCheckpointStore.safeDirectory(dir) && dir.canonicalFile.parentFile == root.canonicalFile)
        }
        return dir
    }

    fun fresh(id: TaskId): Pair<File, File> {
        delete(id) // No byte reuse, even when the stable identity matches.
        val dir = directory(id, true)
        return File(dir, "video.mp4") to File(dir, "audio.mp4")
    }

    fun check(file: File, id: TaskId) {
        val dir = directory(id, false)
        require(file.parentFile == dir && file.name in leaves)
        DirectCheckpointStore.checkLeaf(file)
        require(file.canonicalFile.parentFile == dir.canonicalFile)
    }

    fun cacheBytes(id: TaskId): Long {
        val dir = directory(id, false)
        return leaves.sumOf { name -> File(dir, name).let { check(it, id); if (it.exists()) it.length() else 0L } }
    }

    fun delete(id: TaskId) {
        val dir = directory(id, false)
        if (!dir.exists()) return
        val children = dir.listFiles() ?: throw IOException("双轨临时目录无法读取")
        require(children.all { it.name in leaves }) { "双轨临时目录含有非任务文件" }
        children.forEach { check(it, id); if (!it.delete()) throw IOException("双轨临时文件无法清理") }
        if (!dir.delete()) throw IOException("双轨临时目录无法清理")
    }
}
