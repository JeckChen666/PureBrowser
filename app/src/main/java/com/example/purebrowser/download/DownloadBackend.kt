package com.example.purebrowser.download

import android.app.DownloadManager
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException

sealed interface SystemDownloadResult {
    data class Present(
        val status: Int,
        val bytes: Long,
        val total: Long,
        val reason: Int,
        val mediaUrl: String? = null,
        val mimeType: String? = null,
        val updatedAt: Long? = null,
    ) : SystemDownloadResult
    data object Missing : SystemDownloadResult
    data object Unavailable : SystemDownloadResult
}

data class FileAccess(val availability: FileAvailability, val sizeBytes: Long? = null)
data class MediaInspection(val format: FormatCheck, val mimeType: String? = null, val durationMillis: Long? = null)

/** Small platform boundary: tests never need to touch unrelated system downloads. */
interface DownloadBackend {
    fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long
    fun query(id: Long): SystemDownloadResult
    fun fileUri(id: Long): String?
    fun access(uri: String): FileAccess
    fun inspect(uri: String): MediaInspection
    fun remove(id: Long): Int
    /** A positive row count alone is not evidence that a known file disappeared. */
    fun removeFileAndConfirm(id: Long, uri: String?): Boolean {
        val count = remove(id)
        return if(uri != null) access(uri).availability == FileAvailability.MISSING
            else count > 0 && query(id) == SystemDownloadResult.Missing
    }
}

class AndroidDownloadBackend(context: Context) : DownloadBackend {
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)

    override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long {
        val request = DownloadManager.Request(url.toUri())
            .setTitle(name).setDescription("PureBrowser · 公开视频直链")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .addRequestHeader("User-Agent", userAgent).setAllowedOverRoaming(false)
        if (wifiOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        // No Cookie/Authorization or arbitrary headers; redirects are not origin-filterable here.
        return manager.enqueue(request)
    }

    override fun query(id: Long): SystemDownloadResult = try {
        manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return@use SystemDownloadResult.Missing
            fun text(key: String) = c.getColumnIndex(key).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getString(it) }
            val updated = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP).takeIf { it >= 0 }
                ?.let { c.getLong(it).takeIf { stamp -> stamp >= 0 } }
            SystemDownloadResult.Present(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                text(DownloadManager.COLUMN_URI), text(DownloadManager.COLUMN_MEDIA_TYPE), updated,
            )
        } ?: SystemDownloadResult.Unavailable
    } catch (_: Exception) { SystemDownloadResult.Unavailable }

    override fun fileUri(id: Long): String? = runCatching { manager.getUriForDownloadedFile(id)?.toString() }.getOrNull()

    override fun access(uri: String): FileAccess = try {
        app.contentResolver.openFileDescriptor(uri.toUri(), "r")?.use {
            FileAccess(FileAvailability.AVAILABLE, it.statSize.takeIf { size -> size >= 0 })
        } ?: FileAccess(FileAvailability.UNKNOWN)
    } catch (failure: FileNotFoundException) {
        FileAccess(if (failure.message.orEmpty().contains("permission", ignoreCase = true)) FileAvailability.UNREADABLE else FileAvailability.MISSING)
    } catch (_: SecurityException) { FileAccess(FileAvailability.UNREADABLE) }
    catch (_: Exception) { FileAccess(FileAvailability.UNKNOWN) }

    override fun inspect(uri: String): MediaInspection {
        val parsed = uri.toUri()
        val prefix = try {
            app.contentResolver.openInputStream(parsed)?.use { input ->
                val bytes = ByteArray(12)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                bytes.copyOf(size)
            } ?: return MediaInspection(FormatCheck.UNCONFIRMED)
        } catch (_: Exception) { return MediaInspection(FormatCheck.UNCONFIRMED) }
        if (!DownloadRules.supportedHeader(prefix)) return MediaInspection(FormatCheck.INVALID)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(app, parsed, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return MediaInspection(FormatCheck.INVALID)
            extractor.selectTrack(index)
            if (extractor.sampleTime < 0) return MediaInspection(FormatCheck.INVALID)
            val track = extractor.getTrackFormat(index)
            val duration = if (track.containsKey(MediaFormat.KEY_DURATION)) track.getLong(MediaFormat.KEY_DURATION).takeIf { it >= 0 }?.div(1000) else null
            // Header recognition is only a first check, not proof of a specific container subtype.
            MediaInspection(FormatCheck.PASSED, durationMillis = duration)
        } catch (_: Exception) { MediaInspection(FormatCheck.UNCONFIRMED) }
        finally { extractor.release() }
    }

    override fun remove(id: Long): Int = manager.remove(id)

    override fun removeFileAndConfirm(id: Long, uri: String?): Boolean {
        if(uri == null) return super.removeFileAndConfirm(id, null)
        if(!DownloadRules.isOwnedDownloadUri(uri,id)) return false
        if(access(uri).availability == FileAvailability.MISSING) return super.removeFileAndConfirm(id,uri)
        // Removing a system row revokes its URI permission. Hold the actual owned file's
        // descriptor so revoked access cannot be confused with physical deletion evidence.
        return try {
            app.contentResolver.openFileDescriptor(uri.toUri(), "r")?.use { descriptor ->
                val before = Os.fstat(descriptor.fileDescriptor)
                if(!OsConstants.S_ISREG(before.st_mode)) return@use false
                val originalLink = runCatching { Os.readlink("/proc/self/fd/${descriptor.fd}") }.getOrNull()
                if(manager.remove(id) <= 0) return@use access(uri).availability == FileAvailability.MISSING
                repeat(6) {
                    val unlinked = runCatching { Os.fstat(descriptor.fileDescriptor).st_nlink == 0L }.getOrDefault(false)
                    val deletedLink = originalLink != null && runCatching {
                        Os.readlink("/proc/self/fd/${descriptor.fd}") == "$originalLink (deleted)"
                    }.getOrDefault(false)
                    if(unlinked || deletedLink || access(uri).availability == FileAvailability.MISSING) return@use true
                    Thread.sleep(50) // Bounded IO-thread reconciliation, not a foreground service.
                }
                false
            } ?: false
        } catch (_: Exception) { false }
    }
}
