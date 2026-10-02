package com.example.purebrowser.download.hls

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Private checkpoint: no URLs, credentials, server diagnostics, or persisted filesystem paths. */
internal data class HlsCheckpoint(
    val planHash: String,
    val started: Boolean = false,
    val mediaSequence: Long? = null,
    val complete: Map<Int, HlsCheckpointPiece> = emptyMap(),
) {
    override fun toString() = "HlsCheckpoint(started=$started, complete=${complete.size})"

    fun encode(): ByteArray {
        val pieces = JSONArray()
        complete.toSortedMap().forEach { (index, piece) ->
            pieces.put(JSONObject().put("index", index).put("size", piece.size).put("sha256", piece.sha256))
        }
        return JSONObject().put("version", 1).put("planSha256", planHash).put("started", started)
            .put("mediaSequence", mediaSequence ?: JSONObject.NULL).put("complete", pieces)
            .toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val hashPattern = Regex("[0-9a-f]{64}")
        fun decode(bytes: ByteArray, segmentCount: Int): HlsCheckpoint {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("version") == 1)
            val hash = json.getString("planSha256"); require(hashPattern.matches(hash))
            val started = json.getBoolean("started")
            val sequence = if (json.isNull("mediaSequence")) null else json.getLong("mediaSequence").also { require(it >= 0) }
            val rows = json.getJSONArray("complete"); require(rows.length() <= segmentCount)
            val pieces = linkedMapOf<Int, HlsCheckpointPiece>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val index = row.getInt("index"); require(index in 0 until segmentCount && index !in pieces)
                val size = row.getLong("size"); require(size in 940..128L * 1024 * 1024 && size % 188 == 0L)
                val sha256 = row.getString("sha256"); require(hashPattern.matches(sha256))
                pieces[index] = HlsCheckpointPiece(size, sha256)
            }
            require(if (started) sequence != null else sequence == null && pieces.isEmpty())
            return HlsCheckpoint(hash, started, sequence, pieces)
        }

        /** Length-prefixed exact octets; order, durations, selected variant and final URL are bound. */
        fun identity(plan: HlsDownloadPlan): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DataOutputStream(DigestOutputStream(object : java.io.OutputStream() { override fun write(value: Int) {}
                override fun write(bytes: ByteArray, offset: Int, length: Int) {} }, digest)).use { out ->
                fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
                text(plan.entryUrl); text(plan.playlistUrl)
                out.writeLong(plan.media.durationUs); out.writeLong(plan.media.targetDurationUs); out.writeLong(plan.media.mediaSequence)
                out.writeInt(plan.media.segments.size)
                plan.media.segments.forEach { out.writeInt(it.index); text(it.url); out.writeLong(it.durationUs) }
                out.writeBoolean(plan.variant != null)
                plan.variant?.let { v ->
                    text(v.url); text(v.bandwidth?.toString() ?: ""); text(v.width?.toString() ?: "")
                    text(v.height?.toString() ?: ""); text(v.codecs ?: "")
                }
            }
            return hex(digest.digest())
        }

        fun hash(file: File, check: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { check(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return hex(digest.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal data class HlsCheckpointPiece(val size: Long, val sha256: String) {
    override fun toString() = "HlsCheckpointPiece(size=$size)"
}
