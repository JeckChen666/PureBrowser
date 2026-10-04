package com.example.purebrowser.media.verify

import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.ResourceSniffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Parse-on-detection coordinator. Keeps [ResourceSniffer] lean: the sniffer only reports
 * candidates through its auto-verification hook; this class owns the HLS debounce batch, cheap
 * direct submissions for FILE/UNKNOWN candidates, and applying results back onto the sniffer.
 */
class ParseOnDetectionCoordinator(
    private val sniffer: ResourceSniffer,
    private val queue: AutoProbeQueue,
    private val scope: CoroutineScope,
    private val sessionContext: () -> SessionContext?,
    private val debounceMs: Long = ProbePolicy.DEBOUNCE_MS,
) {
    private val guard = Any()
    private var batchEpoch = Long.MIN_VALUE
    private val pending = linkedMapOf<String, SessionContext>()
    private var debounceJob: Job? = null

    fun attach() {
        queue.onResult = { url, epoch, result -> sniffer.applyProbeResult(url, epoch, result) }
        sniffer.autoVerifyHook = ::onCandidate
    }

    fun detach() {
        sniffer.autoVerifyHook = null
        queue.onResult = null
        cancelBatch()
    }

    fun start() = queue.start()

    fun stop() {
        cancelBatch()
        queue.stop()
    }

    /** Invoked under the sniffer's monitor: only enqueue work, never call back into the sniffer. */
    private fun onCandidate(url: String, kind: MediaKind, epoch: Long) {
        val ctx = sessionContext() ?: return
        if (kind != MediaKind.HLS) {
            queue.submit(url, kind, epoch, ctx)
            return
        }
        synchronized(guard) {
            if (epoch != batchEpoch) {
                pending.clear()
                batchEpoch = epoch
            }
            if (pending.containsKey(url)) return
            pending[url] = ctx
            debounceJob?.cancel()
            debounceJob = scope.launch { delay(debounceMs); flush(epoch) }
        }
    }

    /** At most one batch of master-playlist fetches per epoch window. */
    private fun flush(epoch: Long) {
        val batch = synchronized(guard) {
            if (epoch != batchEpoch || pending.isEmpty()) return
            val taken = pending.entries.take(ProbePolicy.MAX_PLAYLIST_FETCHES_PER_EPOCH)
                .map { it.key to it.value }
            pending.clear()
            debounceJob = null
            taken
        }
        batch.forEach { (url, ctx) -> queue.submit(url, MediaKind.HLS, epoch, ctx) }
    }

    private fun cancelBatch() {
        synchronized(guard) {
            debounceJob?.cancel()
            debounceJob = null
            pending.clear()
        }
    }
}
