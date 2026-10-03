package com.example.purebrowser.download.hls

import android.util.AtomicFile
import com.example.purebrowser.download.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.file.Files

/** Bounded atomic plans/checkpoints. All local paths are derived, never read from persisted data. */
class HlsWorkspace(private val root: File) {
    private fun directory(id: TaskId): File {
        require(Regex("[a-zA-Z0-9-]{1,100}").matches(id))
        require(!Files.isSymbolicLink(root.toPath()))
        check(root.isDirectory || root.mkdirs())
        val dir = File(root, id)
        require(!Files.isSymbolicLink(dir.toPath()))
        require(dir.canonicalFile.parentFile == root.canonicalFile)
        return dir
    }
    private fun local(id: TaskId, name: String, create: Boolean = false): File {
        val dir = directory(id)
        if (create) check(dir.isDirectory || dir.mkdirs())
        val file = File(dir, name)
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile == File(dir.canonicalFile, name))
        require(!file.exists() || file.isFile)
        return file
    }
    private fun atomic(id: TaskId, name: String, create: Boolean = false): AtomicFile {
        // AtomicFile can read/write these siblings too; do not allow a symlink through recovery.
        local(id, "$name.bak", create); local(id, "$name.new", create)
        return AtomicFile(local(id, name, create))
    }
    fun segment(id: TaskId, index: Int): File {
        require(index in 0..9999); return local(id, "segment-$index.ts", true)
    }
    internal fun segmentPart(id: TaskId, index: Int): File {
        require(index in 0..9999); return local(id, "segment-$index.ts.part", true)
    }
    fun requireSpace(id: TaskId, bytes: Long = 65536) {
        require(bytes >= 0 && bytes <= Long.MAX_VALUE - 256L * 1024 * 1024)
        val dir = directory(id).apply { check(isDirectory || mkdirs()) }
        if (android.os.StatFs(dir.path).availableBytes < bytes + 256L * 1024 * 1024)
            throw TransferFailure(FailureKind.STORAGE, "可用空间不足，未保存成品")
    }
    @Synchronized fun save(id: TaskId, plan: HlsDownloadPlan) {
        validate(plan)
        val rows = JSONArray(); plan.media.segments.forEach { s ->
            rows.put(JSONObject().put("url", s.url).put("durationUs", s.durationUs))
        }
        val json = JSONObject().put("version", 2).put("entryUrl", plan.entryUrl).put("playlistUrl", plan.playlistUrl)
            .put("durationUs", plan.media.durationUs).put("targetDurationUs", plan.media.targetDurationUs)
            .put("mediaSequence", plan.media.mediaSequence).put("segments", rows)
        plan.variant?.let { v -> json.put("variant", JSONObject().put("url", v.url).put("width", v.width ?: JSONObject.NULL)
            .put("height", v.height ?: JSONObject.NULL).put("bandwidth", v.bandwidth ?: JSONObject.NULL).put("codecs", v.codecs ?: JSONObject.NULL)) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= PLAN_LIMIT)
        // Saving a new plan can never inherit COMPLETE entries from an older plan at the same ID.
        cleanSegments(id)
        write(atomic(id, "plan.json", true), bytes)
        writeCheckpoint(id, HlsCheckpoint(HlsCheckpoint.identity(plan)))
    }
    @Synchronized fun load(id: TaskId): HlsDownloadPlan {
        val o = JSONObject(read(atomic(id, "plan.json"), PLAN_LIMIT).toString(Charsets.UTF_8)); require(o.getInt("version") in 1..2)
        require(o.getInt("version") == 1 || o.has("mediaSequence"))
        val a = o.getJSONArray("segments"); require(a.length() in 1..10000)
        val segments = (0 until a.length()).map { i -> a.getJSONObject(i).let { HlsSegment(it.getString("url"), it.getLong("durationUs"), i) } }
        val variant = if (o.has("variant")) o.getJSONObject("variant").let { v -> HlsVariant(v.getString("url"),
            if (v.isNull("bandwidth")) null else v.getLong("bandwidth"), if (v.isNull("width")) null else v.getInt("width"),
            if (v.isNull("height")) null else v.getInt("height"), if (v.isNull("codecs")) null else v.getString("codecs"), true, null) } else null
        val plan = HlsDownloadPlan(o.getString("entryUrl"), o.getString("playlistUrl"),
            HlsPlaylist.Media(segments, o.getLong("durationUs"), o.getLong("targetDurationUs"),
                if (o.has("mediaSequence")) o.getLong("mediaSequence") else 0), variant)
        validate(plan)
        checkpoint(id, plan) // Reject changed plans before any COMPLETE file can be consumed.
        return plan
    }
    private fun validate(plan: HlsDownloadPlan) {
        require(plan.media.segments.size in 1..10000)
        require(plan.media.mediaSequence >= 0)
        require(plan.media.durationUs in 1..HlsPlaylistParser.MAX_DURATION_US)
        require(plan.media.targetDurationUs in 1..HlsPlaylistParser.MAX_DURATION_US)
        plan.media.segments.forEachIndexed { i, s ->
            require(i == s.index && s.durationUs in 1..HlsPlaylistParser.MAX_DURATION_US)
            RequestPolicy.validateUrl(s.url, true)
        }
        require(plan.media.segments.sumOf { it.durationUs } == plan.media.durationUs)
        RequestPolicy.validateUrl(plan.entryUrl, true); RequestPolicy.validateUrl(plan.playlistUrl, true)
        plan.variant?.let { RequestPolicy.validateUrl(it.url, true) }
    }
    private fun checkpoint(id: TaskId, plan: HlsDownloadPlan): HlsCheckpoint {
        val file = local(id, "checkpoint.json")
        val backup = local(id, "checkpoint.json.bak")
        local(id, "checkpoint.json.new")
        if (!file.exists() && !backup.exists()) {
            // Legacy v0.1.2 plans can be unstarted; their uncheckpointed files are never reusable.
            return HlsCheckpoint(HlsCheckpoint.identity(plan))
        }
        val checkpoint = HlsCheckpoint.decode(read(atomic(id, "checkpoint.json"), CHECKPOINT_LIMIT), plan.media.segments.size)
        require(checkpoint.planHash == HlsCheckpoint.identity(plan))
        if (mediaSequenceKnown(id) && checkpoint.started) require(checkpoint.mediaSequence == plan.media.mediaSequence)
        return checkpoint
    }
    private fun writeCheckpoint(id: TaskId, checkpoint: HlsCheckpoint) {
        accounting.remove(id)
        write(atomic(id, "checkpoint.json", true), checkpoint.encode())
    }

    /** A valid sequence-known frozen plan can resume from zero; this does not claim cached bytes.
     * Legacy sequence-unknown plans are resumable only while original/unstarted. Piece validation
     * still runs at explicit resume/cold recovery; invalid pieces are discarded before any reuse.
     */
    @Synchronized fun hasResumeData(id: TaskId): Boolean = runCatching {
        val plan = load(id); val cp = checkpoint(id, plan)
        if (!cp.started) true
        else if (!mediaSequenceKnown(id)) false
        else {
            verified(id, cp)
            true // Even an empty verified set can safely restart against the frozen identity.
        }
    }.getOrDefault(false)

    /** Cheap snapshot accounting: checkpointed COMPLETE lengths only, no plan/piece hashing.
     * Same-size corruption is detected by hasResumeData/discardIncomplete at recovery/resume,
     * not by the frequently-polled accounting API. Partial TS/MP4 files are never counted.
     */
    @Synchronized fun cacheBytes(id: TaskId): Long = runCatching {
        if (!local(id, "plan.json").isFile && !local(id, "plan.json.bak").isFile) return@runCatching 0L
        val file = local(id, "checkpoint.json"); val backup = local(id, "checkpoint.json.bak")
        val pending = local(id, "checkpoint.json.new")
        if (!file.isFile && !backup.isFile) return@runCatching 0L
        val stamp = CacheStamp(file.lastModified(), file.length(), backup.lastModified(), backup.length(), pending.lastModified())
        val cached = accounting[id]
        val cp = if (cached != null && cached.first == stamp) cached.second else {
            HlsCheckpoint.decode(read(atomic(id, "checkpoint.json"), CHECKPOINT_LIMIT), 10000).also {
                accounting[id] = stamp to it
            }
        }
        cp.complete.entries.sumOf { (index, piece) ->
            runCatching {
                val segment = segment(id, index)
                if (segment.isFile && segment.length() == piece.size) piece.size else 0L
            }.getOrDefault(0L)
        }
    }.getOrDefault(0L)
    private data class CacheStamp(val modified: Long, val size: Long, val backupModified: Long, val backupSize: Long, val pendingModified: Long)
    private val accounting = mutableMapOf<TaskId, Pair<CacheStamp, HlsCheckpoint>>()

    /** Call only after all old workers join (also safe at cold recovery). Invalid pieces are removed. */
    @Synchronized fun discardIncomplete(id: TaskId) {
        if (!directory(id).exists()) { accounting.remove(id); return }
        val plan = runCatching { load(id) }.getOrNull()
        if (plan == null) { accounting.remove(id); cleanSegments(id); return }
        val cp = checkpoint(id, plan)
        val complete = if (mediaSequenceKnown(id)) verified(id, cp) else emptyMap()
        val keep = complete.keys.map { "segment-$it.ts" }.toSet()
        segmentFiles(id).filter { it.name !in keep }.forEach { check(it.delete()) }
        writeCheckpoint(id, cp.copy(complete = complete))
    }
    @Synchronized internal fun verifiedPieces(id: TaskId, plan: HlsDownloadPlan): Map<Int, HlsCheckpointPiece> {
        require(HlsCheckpoint.identity(load(id)) == HlsCheckpoint.identity(plan))
        return if (mediaSequenceKnown(id)) verified(id, checkpoint(id, plan)) else emptyMap()
    }
    /** v0.1.2 plans did not freeze sequence: never infer that a missing field means sequence zero. */
    @Synchronized internal fun mediaSequenceKnown(id: TaskId): Boolean =
        JSONObject(read(atomic(id, "plan.json"), PLAN_LIMIT).toString(Charsets.UTF_8)).has("mediaSequence")
    @Synchronized internal fun bindLegacySequence(id: TaskId, plan: HlsDownloadPlan, sequence: Long): HlsDownloadPlan {
        require(!mediaSequenceKnown(id) && checkpoint(id, plan).complete.isEmpty())
        val bound = plan.copy(media = HlsPlaylist.Media(plan.media.segments, plan.media.durationUs, plan.media.targetDurationUs, sequence))
        // Remote proof may fill this single previously-unknown field, never replace URLs/variant/order/durations.
        save(id, bound)
        return bound
    }
    @Synchronized internal fun started(id: TaskId, plan: HlsDownloadPlan): Boolean = checkpoint(id, plan).started
    @Synchronized internal fun start(id: TaskId, plan: HlsDownloadPlan, sequence: Long): Boolean {
        val cp = checkpoint(id, plan)
        if (!mediaSequenceKnown(id) || plan.media.mediaSequence != sequence ||
            cp.mediaSequence != null && cp.mediaSequence != sequence) return false
        writeCheckpoint(id, cp.copy(started = true, mediaSequence = sequence))
        return true
    }
    @Synchronized internal fun completeSegment(id: TaskId, plan: HlsDownloadPlan, index: Int, checkCancelled: () -> Unit) {
        val cp = checkpoint(id, plan); require(cp.started)
        val part = segmentPart(id, index)
        val size = part.length(); require(size in 940..128L * 1024 * 1024 && size % 188 == 0L)
        val piece = HlsCheckpointPiece(size, HlsCheckpoint.hash(part, checkCancelled))
        checkCancelled()
        val file = segment(id, index)
        check(!file.exists() || file.delete())
        check(part.renameTo(file))
        // A crash after rename but before the atomic checkpoint leaves an untrusted file to discard.
        writeCheckpoint(id, cp.copy(complete = cp.complete + (index to piece)))
    }
    private fun verified(id: TaskId, cp: HlsCheckpoint): Map<Int, HlsCheckpointPiece> = cp.complete.filter { (index, piece) ->
        runCatching {
            val file = segment(id, index)
            file.isFile && file.length() == piece.size && HlsCheckpoint.hash(file) == piece.sha256
        }.getOrDefault(false)
    }
    private fun segmentFiles(id: TaskId) = directory(id).listFiles()?.filter {
        Regex("segment-[0-9]+\\.ts(?:\\.part)?").matches(it.name)
    }.orEmpty()
    @Synchronized fun cleanSegments(id: TaskId) { segmentFiles(id).forEach { check(it.delete()) } }
    @Synchronized fun delete(id: TaskId) {
        accounting.remove(id)
        val dir = directory(id)
        // walkFileTree does not follow symbolic links, even when an unexpected subdirectory exists.
        if (dir.exists()) Files.walkFileTree(dir.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
    private fun read(atomic: AtomicFile, limit: Int): ByteArray = atomic.openRead().use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= limit); out.write(buffer, 0, n) }
        out.toByteArray()
    }
    private fun write(atomic: AtomicFile, bytes: ByteArray) {
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    private companion object { const val PLAN_LIMIT = 4 * 1024 * 1024; const val CHECKPOINT_LIMIT = 2 * 1024 * 1024 }
}
