package com.example.purebrowser.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Fresh-only, task-owned inputs. Never a checkpoint, and never recursively follows links. */
class DualTrackWorkspace(private val filesRoot: File) {
    private val root get() = File(filesRoot, "dual-track")
    private val leaves = setOf("video.mp4", "audio.mp4")
    // v0.1.9 separate-audio HLS: per-track TS segment parts, remuxed into the mp4 leaves.
    // v0.2.0 T107: fMP4 tracks additionally hold an EXT-X-MAP init part beside their .m4s segments.
    private val segmentLeaf = Regex("(video|audio)-seg-[0-9]{1,4}\\.(ts|m4s)")
    private val initLeaf = Regex("(video|audio)-init\\.mp4")

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

    /** One ordered segment slot of one track; created fresh per task, never a resume target. */
    fun segment(id: TaskId, video: Boolean, index: Int, fmp4: Boolean = false): File {
        require(index in 0..9999)
        val dir = directory(id, true)
        val file = File(dir, "${if (video) "video" else "audio"}-seg-$index.${if (fmp4) "m4s" else "ts"}")
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == dir.canonicalFile)
        require(!file.exists() || file.isFile)
        return file
    }

    /** One fMP4 track's EXT-X-MAP init slot; fresh per task with the same semantics as segments. */
    fun init(id: TaskId, video: Boolean): File {
        val dir = directory(id, true)
        val file = File(dir, "${if (video) "video" else "audio"}-init.mp4")
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == dir.canonicalFile)
        require(!file.exists() || file.isFile)
        return file
    }

    /** Segment/init bytes are dropped as soon as both per-track MP4s exist; the muxer never needs them again. */
    fun deleteSegments(id: TaskId) {
        val dir = directory(id, false)
        dir.listFiles()?.filter { segmentLeaf.matches(it.name) || initLeaf.matches(it.name) }?.forEach { check(it.delete()) }
    }

    fun requireSpace(id: TaskId, bytes: Long) {
        require(bytes >= 0 && bytes <= Long.MAX_VALUE - 256L * 1024 * 1024)
        val dir = directory(id, true)
        if (android.os.StatFs(dir.path).availableBytes < bytes + 256L * 1024 * 1024)
            throw TransferFailure(FailureKind.STORAGE, "可用空间不足，未保存成品")
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
        require(children.all { it.name in leaves || segmentLeaf.matches(it.name) || initLeaf.matches(it.name) }) { "双轨临时目录含有非任务文件" }
        children.forEach {
            if (it.name in leaves) check(it, id) else DirectCheckpointStore.checkLeaf(it)
            if (!it.delete()) throw IOException("双轨临时文件无法清理")
        }
        if (!dir.delete()) throw IOException("双轨临时目录无法清理")
    }
}
