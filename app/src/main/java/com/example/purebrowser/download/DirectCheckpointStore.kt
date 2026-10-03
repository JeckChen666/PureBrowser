package com.example.purebrowser.download

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Private, task-owned resume metadata. No cookies, request headers or raw URLs are persisted. */
class DirectCheckpointStore(private val root: File) {
    internal class Checkpoint(
        val id: TaskId,
        val sourceHash: String,
        val effectiveHash: String,
        val etag: String,
        val total: Long,
        val bytes: Long,
        val prefixHash: String,
        val verifiedDigest: MessageDigest? = null,
        val stamp: StageStamp? = null,
    )

    internal data class StageStamp(val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long)

    /** Read-only validation: an uncommitted tail is allowed, but is never counted as cached bytes. */
    fun hasValid(id: TaskId, stage: File): Boolean = load(id, stage) != null
    fun cacheBytes(id: TaskId, stage: File): Long = load(id, stage)?.bytes ?: 0L

    fun delete(id: TaskId) = synchronized(lock) {
        checkId(id)
        if (!safeDirectory(root) || !directory().exists()) return@synchronized
        checkedDirectory(false)
        for (file in listOf(metadata(id), temporary(id))) {
            checkLeaf(file)
            if (file.exists() && !file.delete()) throw IOException("续传检查点无法清理")
        }
        syncDirectory(directory())
    }

    internal fun exists(id: TaskId): Boolean = synchronized(lock) {
        checkId(id)
        // A malformed entry is still evidence of an earlier download, not permission to overwrite it.
        Files.exists(metadata(id).toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    internal fun load(id: TaskId, stage: File): Checkpoint? = read(id, stage, verifyPrefix = true)

    /** Recheck metadata and the verified file identity before truncating, without hashing it again. */
    internal fun isCurrent(id: TaskId, stage: File, checkpoint: Checkpoint, fd: FileDescriptor): Boolean = synchronized(lock) {
        val current = read(id, stage, verifyPrefix = false) ?: return@synchronized false
        current.id == checkpoint.id && current.sourceHash == checkpoint.sourceHash &&
            current.effectiveHash == checkpoint.effectiveHash && current.etag == checkpoint.etag &&
            current.total == checkpoint.total && current.bytes == checkpoint.bytes &&
            current.prefixHash == checkpoint.prefixHash && current.stamp == checkpoint.stamp &&
            stamp(Os.fstat(fd)) == checkpoint.stamp
    }

    private fun read(id: TaskId, stage: File, verifyPrefix: Boolean): Checkpoint? = synchronized(lock) {
        runCatching {
            checkStage(id, stage)
            checkedDirectory(false)
            val file = metadata(id)
            checkLeaf(file)
            require(file.length() in 33..MAX_METADATA_BYTES.toLong())
            val raw = noFollowInput(file).use { input ->
                val buffer = ByteArray(MAX_METADATA_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val n = input.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    if (n == 0) continue
                    size += n
                }
                require(size in 33..MAX_METADATA_BYTES)
                buffer.copyOf(size)
            }
            val payload = raw.copyOfRange(0, raw.size - 32)
            require(MessageDigest.isEqual(digest(payload), raw.copyOfRange(raw.size - 32, raw.size)))
            val checkpoint = DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == VERSION)
                Checkpoint(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readLong(), input.readLong(), input.readUTF()).also { require(input.available() == 0) }
            }
            require(checkpoint.id == id && strongEtag(checkpoint.etag) != null)
            require(checkpoint.total >= 12 && checkpoint.bytes in 12..checkpoint.total)
            require(listOf(checkpoint.sourceHash, checkpoint.effectiveHash, checkpoint.prefixHash).all { HASH.matches(it) })
            require(stage.length() >= checkpoint.bytes)
            val before = stamp(Os.lstat(stage.path))
            val verifiedDigest = if (verifyPrefix) prefixDigest(stage, checkpoint.bytes, before) else null
            if (verifiedDigest != null) require(snapshotDigest(verifiedDigest) == checkpoint.prefixHash)
            require(stamp(Os.lstat(stage.path)) == before)
            Checkpoint(checkpoint.id, checkpoint.sourceHash, checkpoint.effectiveHash, checkpoint.etag,
                checkpoint.total, checkpoint.bytes, checkpoint.prefixHash, verifiedDigest, before)
        }.getOrNull()
    }

    /**
     * Caller must force the stage BEFORE committing this count. The live writer supplies its
     * incremental digest: cloning it takes constant work and never reads stage bytes. The null
     * overload is only for one-off checkpoint seeding/tests, and hashes the FULL durable prefix.
     */
    internal fun save(id: TaskId, stage: File, sourceUrl: String, effectiveUrl: String,
                      etag: String, total: Long, bytes: Long, providedDigest: MessageDigest? = null): Checkpoint = synchronized(lock) {
        checkStage(id, stage)
        require(strongEtag(etag) != null && total >= 12 && bytes in 12..total && stage.length() >= bytes)
        val before = stamp(Os.lstat(stage.path))
        val liveDigest = providedDigest ?: prefixDigest(stage, bytes, before)
        val checkpoint = Checkpoint(id, urlHash(sourceUrl), urlHash(effectiveUrl), etag, total, bytes,
            snapshotDigest(liveDigest), stamp = before)
        require(stamp(Os.lstat(stage.path)) == before)
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION)
                out.writeUTF(id); out.writeUTF(checkpoint.sourceHash); out.writeUTF(checkpoint.effectiveHash)
                out.writeUTF(etag); out.writeLong(total); out.writeLong(bytes); out.writeUTF(checkpoint.prefixHash)
            }
        }.toByteArray()
        syncDirectory(stage.parentFile!!)
        checkedDirectory(true)
        val temp = temporary(id); val target = metadata(id)
        checkLeaf(temp); checkLeaf(target)
        val fd = Os.open(temp.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW, 384)
        try { FileOutputStream(fd).use { out -> out.write(payload); out.write(digest(payload)); out.fd.sync() } }
        finally { if (fd.valid()) Os.close(fd) }
        // Same-directory rename is atomic; force the directory entry as well as the metadata contents.
        checkLeaf(target)
        Os.rename(temp.path, target.path)
        syncDirectory(directory())
        checkpoint
    }

    internal fun checkStage(id: TaskId, stage: File) {
        checkId(id)
        require(safeDirectory(root)) { "临时文件根目录不安全" }
        val parent = File(root, "transfers")
        require(safeDirectory(parent) && parent.canonicalFile.parentFile == root.canonicalFile)
        require(stage.absoluteFile == File(parent.absoluteFile, "$id.part"))
        checkLeaf(stage)
        require(stage.isFile && stage.canonicalFile.parentFile == parent.canonicalFile)
    }

    private fun directory() = File(root, "direct-checkpoints")
    private fun metadata(id: TaskId) = File(directory(), "$id.checkpoint")
    private fun temporary(id: TaskId) = File(directory(), "$id.tmp")
    private fun checkedDirectory(create: Boolean) {
        require(safeDirectory(root))
        val dir = directory()
        if (create && !Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!dir.mkdir()) throw IOException("续传检查点目录无法创建")
            syncDirectory(root)
        }
        require(safeDirectory(dir) && dir.canonicalFile.parentFile == root.canonicalFile)
    }

    companion object {
        private val lock = Any() // Includes separate store instances used by the coordinator and writer.
        private val ID = Regex("[a-zA-Z0-9-]{1,100}")
        private val HASH = Regex("[0-9a-f]{64}")
        private const val MAGIC = 0x50424443
        private const val VERSION = 2
        private const val MAX_METADATA_BYTES = 24576
        internal fun checkId(id: TaskId) { require(ID.matches(id)) { "任务标识不安全" } }
        internal fun strongEtag(value: String?): String? = value?.takeIf {
            it.length in 2..4096 && it.first() == '"' && it.last() == '"' &&
                it.substring(1, it.length - 1).all { c -> c == '\u0021' || c in '\u0023'..'\u007e' || c in '\u0080'..'\u00ff' }
        }
        internal fun urlHash(url: String) = hex(digest(url.toByteArray(Charsets.UTF_8)))
        internal fun safeDirectory(file: File) = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        internal fun checkLeaf(file: File) {
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "临时文件路径不安全" }
        }
        internal fun noFollowInput(file: File): FileInputStream {
            val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            return object : FileInputStream(fd) {
                override fun close() { try { super.close() } finally { if (fd.valid()) Os.close(fd) } }
            }
        }
        /** Digest snapshots must never consume/reset the writer's live state. Unsupported providers fail closed. */
        internal fun snapshotDigest(digest: MessageDigest): String {
            require(digest.algorithm.equals("SHA-256", true))
            val copy = try { digest.clone() as MessageDigest }
            catch (_: Exception) { throw IOException("当前设备不支持安全的续传校验快照") }
            require(copy !== digest)
            val bytes = copy.digest()
            require(bytes.size == 32)
            return hex(bytes)
        }
        private fun stamp(stat: StructStat) = StageStamp(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime, stat.st_ctime)
        private fun prefixDigest(stage: File, count: Long, expected: StageStamp): MessageDigest {
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = count
            noFollowInput(stage).use { input ->
                require(stamp(Os.fstat(input.fd)) == expected)
                val buffer = ByteArray(65536)
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n <= 0) throw IOException("续传前缀不完整")
                    digest.update(buffer, 0, n); remaining -= n
                }
                require(stamp(Os.fstat(input.fd)) == expected)
            }
            return digest
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun syncDirectory(dir: File) {
            require(safeDirectory(dir))
            val fd = Os.open(dir.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
}
