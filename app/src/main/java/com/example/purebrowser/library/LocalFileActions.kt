package com.example.purebrowser.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.MainThread
import com.example.purebrowser.download.DownloadRules
import java.io.FileNotFoundException
import java.io.IOException

/** Platform handoff only: no file queries, permissions requests, deletion, or incoming intents. */
object LocalFileActions {
    /**
     * The caller must verify [id] belongs to its stored download record and freshly check
     * file availability through the repository on IO before calling on the main thread. A URI
     * shaped like a Downloads URI is not, by itself, proof that this app owns the download.
     *
     * Returns null only when startActivity returns successfully. This does NOT confirm playback,
     * selection of a sharing target, delivery, or the recipient's ability to read a file later.
     * Files can still disappear after the caller's check; recipient-side failures are not observable.
     */
    @MainThread
    fun launch(
        context: Context,
        id: Long,
        uri: Uri,
        mimeType: String,
        share: Boolean,
    ): String? {
        if (id <= 0 || !DownloadRules.isOwnedDownloadUri(uri.toString(), id)) {
            return "仅支持本应用保存的视频，请返回下载中心核对记录。"
        }
        val type = mimeType.trim()
        if (type.isEmpty() || '/' !in type || type.any { it.isWhitespace() || it.isISOControl() }) {
            return "文件类型未确认，请刷新下载记录后重试。"
        }
        return try {
            // Always build a fresh intent. Never forward a webpage's URI, extras or nested intent.
            val target = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                if (share) {
                    this.type = type
                    putExtra(Intent.EXTRA_STREAM, uri)
                } else {
                    setDataAndType(uri, type)
                }
                // newRawUri does not query a provider on the main thread, unlike newUri.
                clipData = ClipData.newRawUri("本地视频", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val outgoing = if (share) Intent.createChooser(target, "分享视频文件") else target
            if (context !is Activity) outgoing.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(outgoing)
            null
        } catch (_: ActivityNotFoundException) {
            if (share) "未找到可分享文件的应用，请安装支持视频文件的应用后重试。"
            else "未找到可打开此视频的应用，请安装支持此格式的播放器后重试。"
        } catch (_: SecurityException) {
            "文件读取授权被拒绝，请返回下载中心刷新文件状态后重试。"
        } catch (_: FileNotFoundException) {
            "文件可能已被移动或删除，请返回下载中心核对；需要时重新下载。"
        } catch (_: IOException) {
            "暂时无法读取视频文件，请刷新文件状态后重试。"
        } catch (_: RuntimeException) {
            "无法启动文件操作，请返回下载中心刷新后重试，或更换支持此格式的应用。"
        }
    }
}
