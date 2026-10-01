package com.example.purebrowser.download

import android.app.DownloadManager
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class DownloadRecord(val id: Long, val name: String)
data class DownloadItem(val id: Long, val name: String, val status: Int, val bytes: Long, val total: Long, val detail: String, val verified: Boolean = false)

/** Basic public-file transport; credentials and manifests deliberately stay out of DownloadManager. */
class DownloadRepository(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)
    private val preferences = app.getSharedPreferences("download_records", Context.MODE_PRIVATE)
    private val verification = mutableMapOf<Long, Boolean>()
    private var records: List<DownloadRecord> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { o -> DownloadRecord(o.getLong("id"), o.getString("name")) } }
    }.getOrDefault(emptyList())

    @Synchronized fun enqueue(candidate: MediaCandidate, userAgent: String, wifiOnly: Boolean): Long {
        require(candidate.kind == MediaKind.FILE) { "此格式的下载暂未实现" }
        require(records.size < 200) { "任务记录已满，请先清理旧任务" }
        val guessed = URLUtil.guessFileName(candidate.url, null, candidate.mimeType)
        val safeName = guessed.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(100).ifBlank { "video.mp4" }
        val name = "${UUID.randomUUID().toString().take(8)}_$safeName"
        val request = DownloadManager.Request(Uri.parse(candidate.url))
            .setTitle(name)
            .setDescription("PureBrowser · 公开视频直链")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .addRequestHeader("User-Agent", userAgent)
            .setAllowedOverRoaming(false)
        if (wifiOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        // Do not attach Cookie/Authorization: DM redirects are not origin-filterable here.
        val id = manager.enqueue(request)
        records = listOf(DownloadRecord(id, name)) + records
        persist()
        return id
    }

    @Synchronized fun snapshot(): List<DownloadItem> = records.map { record ->
        manager.query(DownloadManager.Query().setFilterById(record.id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use DownloadItem(record.id, record.name, DownloadManager.STATUS_FAILED, 0, -1, "系统任务不存在")
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val verified = status == DownloadManager.STATUS_SUCCESSFUL && verification.getOrPut(record.id) { verifyContainer(record.id) }
            val detail = when (status) {
                DownloadManager.STATUS_PENDING -> "排队中"
                DownloadManager.STATUS_RUNNING -> "下载中"
                DownloadManager.STATUS_PAUSED -> "等待网络 / 系统重试"
                DownloadManager.STATUS_SUCCESSFUL -> if (verified) "已保存 · 格式初检通过" else "文件已保存，但格式初检失败（可能是登录页）"
                else -> when (reason) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
                    DownloadManager.ERROR_CANNOT_RESUME -> "服务器不支持继续下载"
                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络传输失败"
                    401, 403 -> "无权访问：可能需要登录或链接已过期"
                    else -> "下载失败（$reason）"
                }
            }
            DownloadItem(record.id, record.name, status, bytes, total, detail, verified)
        } ?: DownloadItem(record.id, record.name, DownloadManager.STATUS_FAILED, 0, -1, "无法读取任务")
    }

    @Synchronized fun remove(id: Long) {
        manager.remove(id)
        records = records.filterNot { it.id == id }
        verification.remove(id)
        persist()
    }
    fun fileUri(id: Long): Uri? = manager.getUriForDownloadedFile(id)
    fun mimeType(id: Long): String = manager.getMimeTypeForDownloadedFile(id)?.takeIf { it.startsWith("video/") } ?: "video/*"

    private fun verifyContainer(id: Long): Boolean = runCatching {
        val uri = fileUri(id) ?: return false
        app.contentResolver.openInputStream(uri)?.use { stream ->
            val prefix = ByteArray(12)
            var count = 0
            while (count < prefix.size) {
                val n = stream.read(prefix, count, prefix.size - count)
                if (n < 0) break
                count += n
            }
            val supportedHeader = (count >= 8 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp") ||
                (count >= 4 && prefix.take(4).map { it.toInt() and 0xff } == listOf(0x1a, 0x45, 0xdf, 0xa3))
            if (!supportedHeader) return@use false
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(app, uri, null)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return@use false
                extractor.selectTrack(track)
                extractor.sampleTime >= 0 // Reject empty init segments; this is not a full integrity audit.
            } finally {
                extractor.release()
            }
        } ?: false
    }.getOrDefault(false)

    private fun persist() {
        val array = JSONArray()
        records.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        preferences.edit().putString("items", array.toString()).apply()
    }
}
