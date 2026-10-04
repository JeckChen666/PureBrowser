package com.example.purebrowser.download

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.purebrowser.download.mux.AuthoredMuxFixtures
import com.example.purebrowser.download.site.DualTrackDownloadPlan
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.io.File
import java.util.UUID

internal object DualTrackTestSupport {
    val video: ByteArray get() = Base64.decode(AuthoredMuxFixtures.VIDEO, Base64.DEFAULT)
    val audio: ByteArray get() = Base64.decode(AuthoredMuxFixtures.AUDIO, Base64.DEFAULT)
    fun plan(knownLength: Boolean = true) = DualTrackDownloadPlan(
        resourceId = "fixture-work", videoFormatId = "fixture-video", audioFormatId = "fixture-audio",
        videoUrl = "https://cdn.example/video?signature=video-secret",
        audioUrl = "https://cdn.example/audio?signature=audio-secret",
        videoCodec = "avc1.640028", audioCodec = "mp4a.40.2",
        videoLength = if (knownLength) video.size.toLong() else null,
        audioLength = if (knownLength) audio.size.toLong() else null, durationUs = 2_000_000,
    )
    fun draft(plan: DualTrackDownloadPlan = plan(), context: Boolean = false) = DownloadDraft(
        MediaCandidate(plan.videoUrl, MediaKind.FILE, emptySet(), "video/mp4", reliableSource = true),
        "DualFixture", sourceUrl = "https://site.example/watch?session=page-secret", sourceTitle = "Authored fixture",
        useAccessContext = context, dualTrackPlan = plan,
    )
    class NoSystem : DownloadBackend {
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long = error("No system enqueue")
        override fun query(id: Long) = SystemDownloadResult.Missing
        override fun fileUri(id: Long): String? = null
        override fun access(uri: String) = FileAccess(FileAvailability.MISSING)
        override fun inspect(uri: String) = MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id: Long) = error("No system remove")
    }
    fun test(block: (DownloadRepository) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(app.cacheDir, "dual-test-${UUID.randomUUID()}").apply { mkdirs() }
        val repo = DownloadRepository(DownloadStore(dir), NoSystem(), files = ManagedFileStore(app))
        try { block(repo) } finally {
            repo.records().forEach { r ->
                runCatching { repo.stateSnapshot().assets.firstOrNull { it.recordId == r.recordId }?.let { repo.files!!.delete(it) } }
                runCatching { repo.files!!.cleanupPending(r) }
                runCatching { repo.files!!.clearPrivate(r.recordId) }
            }
            dir.deleteRecursively()
        }
    }
}
