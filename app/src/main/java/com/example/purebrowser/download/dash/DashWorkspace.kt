package com.example.purebrowser.download.dash

import com.example.purebrowser.download.DirectCheckpointStore
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.TaskId
import com.example.purebrowser.download.TransferFailure
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * T97 per-task DASH scratch space (init + segments + assembled single-track MP4s). Like the
 * dual-track workspace there is no durable resume: fresh(id) wipes any previous bytes and every
 * derived path is task-owned. All local paths are derived, never read from persisted data.
 */
class DashWorkspace(private val filesRoot: File) {
    private val root get() = File(filesRoot, "dash")

    class DashTaskWorkspace internal constructor(val directory: File) {
        val videoDir: File get() = File(directory, "video")
        val audioDir: File get() = File(directory, "audio")
        fun videoInit(): File = File(videoDir, "init.mp4")
        fun videoSegment(index: Int): File = File(videoDir, "segment-$index.m4s")
        fun audioInit(): File = File(audioDir, "init.mp4")
        fun audioSegment(index: Int): File = File(audioDir, "segment-$index.m4s")
        fun assembledVideo(): File = File(directory, "video-track.mp4")
        fun assembledAudio(): File = File(directory, "audio-track.mp4")
    }

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

    /** No byte reuse, even when a stable identity would match; callers re-fetch from the plan. */
    fun fresh(id: TaskId): DashTaskWorkspace {
        delete(id)
        val dir = directory(id, true)
        check(File(dir, "video").mkdir()) { "DASH 临时目录无法创建" }
        check(File(dir, "audio").mkdir()) { "DASH 临时目录无法创建" }
        return DashTaskWorkspace(dir)
    }

    fun cacheBytes(id: TaskId): Long = runCatching {
        val dir = directory(id, false)
        if (!dir.isDirectory) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.take(10000).sumOf { it.length() }
    }.getOrDefault(0L)

    fun delete(id: TaskId) {
        val dir = directory(id, false)
        if (!dir.exists()) return
        // walkFileTree does not follow symbolic links, even when an unexpected subdirectory exists.
        Files.walkFileTree(dir.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    fun requireSpace(id: TaskId, bytes: Long = 65536L) {
        require(bytes >= 0 && bytes <= Long.MAX_VALUE - 256L * 1024 * 1024)
        val dir = directory(id, true).apply { check(isDirectory || mkdirs()) }
        if (android.os.StatFs(dir.path).availableBytes < bytes + 256L * 1024 * 1024)
            throw TransferFailure(FailureKind.STORAGE, "可用空间不足，未保存成品")
    }
}
