package com.example.purebrowser.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadRules
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.VideoAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Decorative local thumbnail only: neither a playback control nor proof of video playability. */
@Composable
fun LocalVideoThumbnail(asset: VideoAsset, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext
    val key = LocalThumbnailKey.from(asset)
    val eligible = canReadLocalThumbnail(asset)
    // Replace the state synchronously on eligibility/identity changes: never flash a stale cover.
    var bitmap by remember(key, eligible, asset.systemId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(app, key, eligible, asset.systemId) {
        if (eligible) bitmap = withContext(Dispatchers.IO) { LocalThumbnailCache.load(app, key) }
    }

    Box(
        modifier = modifier.size(96.dp, 54.dp).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val cover = bitmap
        if (eligible && cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = "本地视频缩略图（不代表可播放）",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = when {
                    asset.availability == FileAvailability.MISSING -> "文件已丢失"
                    asset.availability == FileAvailability.UNREADABLE -> "文件暂不可读"
                    asset.availability != FileAvailability.AVAILABLE -> "文件状态未确认"
                    !eligible -> "暂无可用封面"
                    else -> "暂无封面"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

internal fun canReadLocalThumbnail(asset: VideoAsset): Boolean =
    asset.systemId > 0 && asset.format == FormatCheck.PASSED &&
        asset.availability == FileAvailability.AVAILABLE &&
        DownloadRules.isOwnedDownloadUri(asset.uri, asset.systemId)

internal data class LocalThumbnailKey(val uri: String, val sizeBytes: Long?, val updatedAt: Long) {
    companion object {
        fun from(asset: VideoAsset) = LocalThumbnailKey(
            asset.uri, asset.sizeBytes, asset.systemUpdatedAt ?: asset.indexedAt,
        )
    }
}

private object LocalThumbnailCache {
    private const val WIDTH = 320
    private const val HEIGHT = 180
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024
    // API 26 lacks scaled extraction. Refuse unknown/oversized source frames before full decode.
    private const val MAX_LEGACY_PIXELS = 1920L * 1080L
    private const val MAX_LEGACY_DIMENSION = 4096
    private val decodePermit = Semaphore(1)
    private val cache = object : LruCache<LocalThumbnailKey, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: LocalThumbnailKey, value: Bitmap): Int = value.allocationByteCount
        // Never recycle evicted images: a composed row may still hold a reference to them.
    }

    /** Invoked only on Dispatchers.IO; serial decoding also bounds concurrent native work. */
    suspend fun load(context: Context, key: LocalThumbnailKey): Bitmap? = decodePermit.withPermit {
        try {
            currentCoroutineContext().ensureActive()
            // Even a cache hit must recheck readability, so a removed/denied file gets a placeholder.
            context.contentResolver.openFileDescriptor(Uri.parse(key.uri), "r")?.use { descriptor ->
                currentCoroutineContext().ensureActive()
                cache.get(key)?.let { return@withPermit it }
                val retriever = MediaMetadataRetriever()
                val cover = try {
                    // The descriptor is from the validated local Downloads URI, never a network URL.
                    retriever.setDataSource(descriptor.fileDescriptor)
                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT,
                        )
                    } else {
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        if (width == null || height == null || width !in 1..MAX_LEGACY_DIMENSION ||
                            height !in 1..MAX_LEGACY_DIMENSION || width.toLong() * height > MAX_LEGACY_PIXELS
                        ) null else retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    frame?.let { downsize(it) }
                } finally {
                    // release works on API 26 too; close/release may themselves throw on some devices.
                    try { retriever.release() } catch (_: Exception) { /* Keep the placeholder path safe. */ }
                }
                if (cover != null) {
                    try {
                        currentCoroutineContext().ensureActive()
                    } catch (cancelled: CancellationException) {
                        cover.recycle() // Not yet cached or published to Compose.
                        throw cancelled
                    }
                    cache.put(key, cover)
                }
                cover
            } ?: run {
                cache.remove(key)
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Includes missing file, denied grant, invalid media and vendor decoder failures.
            cache.remove(key)
            null
        }
    }

    private fun downsize(frame: Bitmap): Bitmap {
        val scale = min(1f, min(WIDTH.toFloat() / frame.width, HEIGHT.toFloat() / frame.height))
        if (scale >= 1f) return frame
        return try {
            Bitmap.createScaledBitmap(
                frame, (frame.width * scale).roundToInt().coerceAtLeast(1),
                (frame.height * scale).roundToInt().coerceAtLeast(1), true,
            )
        } finally {
            frame.recycle() // The full-size API 26 frame never enters the cache or UI.
        }
    }
}
