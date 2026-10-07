package com.example.purebrowser.ui.resources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.TransferFailure
import com.example.purebrowser.download.hls.HlsDownloadPlan
import com.example.purebrowser.download.hls.HlsOptions
import com.example.purebrowser.download.hls.HlsPlaylist
import com.example.purebrowser.download.hls.HlsPlaylistParser
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.download.hls.HlsVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Dialog-owned preview only. Nothing is fetched until parse/prepare is explicitly invoked. */
internal class HlsPreparation(private val resolver: HlsResolver, private val scope: CoroutineScope) {
    var options by mutableStateOf<HlsOptions?>(null)
        private set
    var selected by mutableStateOf<HlsVariant?>(null)
        private set
    var plan by mutableStateOf<HlsDownloadPlan?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var optionsDraft: DownloadDraft? = null
    private var preparedDraft: DownloadDraft? = null
    private var generation = 0L
    private var token: TransferCancellation? = null
    private var job: Job? = null
    private var closed = false

    fun readyPlan(draft: DownloadDraft): HlsDownloadPlan? = plan?.takeIf { preparedDraft == draft && !busy && !closed }

    fun parse(draft: DownloadDraft) {
        if (closed || busy) return
        options = null
        optionsDraft = null
        selected = null
        request({ cancel ->
            val parsed = resolver.resolveEntry(draft, cancel)
            // A media entry is already the child playlist; reuse it without a second GET.
            val ready = if (parsed.playlist is HlsPlaylist.Media) resolver.resolvePlan(draft, parsed, null, cancel) else null
            parsed to ready
        }) { (parsed, ready) ->
            options = parsed
            optionsDraft = draft
            selected = (parsed.playlist as? HlsPlaylist.Master)?.let { HlsPlaylistParser.defaultVariant(it.variants) }
            plan = ready
            preparedDraft = if (ready != null) draft else null
        }
    }

    fun select(variant: HlsVariant) {
        if (closed || !variant.supported || selected == variant ||
            (options?.playlist as? HlsPlaylist.Master)?.variants?.contains(variant) != true) return
        invalidate()
        selected = variant
    }

    fun prepare(draft: DownloadDraft) {
        if (closed || busy || optionsDraft != draft) return
        val parsed = options ?: return
        val variant = selected ?: return
        if (!variant.supported) return
        request({ cancel -> resolver.resolvePlan(draft, parsed, variant, cancel) }) { ready ->
            plan = ready
            preparedDraft = draft
        }
    }

    /**
     * T117 one-action save: reads the entry only when the user taps 保存, honors an already-picked
     * variant address (a vanished or newly-unsupported pick fails honestly — never a silent
     * re-pick), then prepares the plan; [onReady] fires at most once per invocation, on this
     * dialog's scope, only for this chain. A multi-variant master WITHOUT a prior pick stops after
     * the parse so the surfaced rows can be confirmed with the same 保存 action.
     */
    fun save(draft: DownloadDraft, preferredVariantUrl: String?, onReady: (HlsDownloadPlan) -> Unit) {
        if (closed || busy) return
        if (options != null && optionsDraft == draft) {
            prepareSelected(draft, onReady)
            return
        }
        request({ cancel ->
            val parsed = resolver.resolveEntry(draft, cancel)
            // A media entry is already the child playlist; reuse it without a second GET.
            val ready = if (parsed.playlist is HlsPlaylist.Media) resolver.resolvePlan(draft, parsed, null, cancel) else null
            parsed to ready
        }) { (parsed, ready) ->
            options = parsed
            optionsDraft = draft
            val master = parsed.playlist as? HlsPlaylist.Master
            if (master == null) {
                selected = null
                plan = ready
                preparedDraft = if (ready != null) draft else null
                if (ready != null) onReady(ready)
                return@request
            }
            val chosen = preferredVariantUrl?.let { preferred -> master.variants.firstOrNull { it.url == preferred } }
            when {
                // The picked quality vanished or turned unsupported: report and keep the rows.
                preferredVariantUrl != null && chosen?.supported != true -> {
                    selected = HlsPlaylistParser.defaultVariant(master.variants)
                    error = "所选清晰度已不在清单中，请重新选择后再保存"
                }
                // No prior pick and a real ladder: stop after the parse for one explicit confirm.
                preferredVariantUrl == null && master.variants.count { it.supported } > 1 ->
                    selected = HlsPlaylistParser.defaultVariant(master.variants)
                else -> {
                    selected = chosen ?: HlsPlaylistParser.defaultVariant(master.variants) ?: master.variants.firstOrNull()
                    prepareSelected(draft, onReady)
                }
            }
        }
    }

    private fun prepareSelected(draft: DownloadDraft, onReady: (HlsDownloadPlan) -> Unit) {
        val parsed = options ?: return
        val variant = selected?.takeIf { it.supported } ?: run {
            error = "没有受支持的档位，请返回来源网页重新发现资源"
            return
        }
        request({ cancel -> resolver.resolvePlan(draft, parsed, variant, cancel) }) { ready ->
            plan = ready
            preparedDraft = draft
            onReady(ready)
        }
    }

    /** Access changes invalidate entry metadata as well as the prepared plan; never auto-reparse. */
    fun accessChanged() {
        invalidate()
        options = null
        optionsDraft = null
        selected = null
    }

    fun close() {
        closed = true
        invalidate()
    }

    private fun invalidate() {
        generation++
        token?.cancel() // Abort blocking IO too, not just its coroutine continuation.
        token = null
        job?.cancel()
        job = null
        busy = false
        plan = null
        preparedDraft = null
        error = null
    }

    private fun <T> request(read: (TransferCancellation) -> T, accept: (T) -> Unit) {
        invalidate()
        val revision = generation
        val cancellation = TransferCancellation()
        token = cancellation
        busy = true
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { read(cancellation) }
                coroutineContext.ensureActive()
                cancellation.check()
                if (!closed && generation == revision && token === cancellation) accept(result)
            } catch (cancelled: CancellationException) {
                cancellation.cancel()
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && generation == revision && token === cancellation) {
                    // Only the transport's policy-owned diagnostics may appear, never raw URLs/headers.
                    error = (failure as? TransferFailure)?.safeMessage
                        ?.takeIf { it.length <= 180 && !it.contains("://") && it.none(Char::isISOControl) }
                        ?: "清单未能读取，请检查网络或返回来源网页重新发现资源"
                }
            } finally {
                if (generation == revision && token === cancellation) {
                    busy = false
                    token = null
                    job = null
                }
            }
        }
    }
}
