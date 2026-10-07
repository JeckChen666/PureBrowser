package com.example.purebrowser.ui.resources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.TransferFailure
import com.example.purebrowser.download.dash.DashDownloadPlan
import com.example.purebrowser.download.dash.DashResolver
import com.example.purebrowser.download.dash.MpdPlanParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Dialog-owned DASH preview only. Nothing is fetched until parse/prepare is explicitly invoked. */
internal class DashPreparation(private val resolver: DashResolver, private val scope: CoroutineScope) {
    var options by mutableStateOf<DashResolver.DashOptions?>(null)
        private set
    var selected by mutableStateOf<MpdPlanParser.DashRepresentationOffer?>(null)
        private set
    var plan by mutableStateOf<DashDownloadPlan?>(null)
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

    fun readyPlan(draft: DownloadDraft): DashDownloadPlan? =
        plan?.takeIf { preparedDraft == draft && !busy && !closed }

    fun parse(draft: DownloadDraft) {
        if (closed || busy) return
        invalidate()
        options = null
        optionsDraft = null
        selected = null
        request({ cancel -> resolver.resolveEntry(draft, cancel) }) { parsed ->
            options = parsed
            optionsDraft = draft
            selected = MpdPlanParser.defaultVideoOffer(parsed.document.videoOffers)
        }
    }

    fun select(offer: MpdPlanParser.DashRepresentationOffer) {
        if (closed || !offer.supported || selected === offer ||
            options?.document?.videoOffers?.any { it === offer } != true
        ) return
        invalidate()
        selected = offer
    }

    fun prepare(draft: DownloadDraft) {
        if (closed || busy || optionsDraft != draft) return
        val parsed = options ?: return
        val offer = selected ?: return
        if (!offer.supported) return
        request({ cancel -> resolver.resolvePlan(draft, parsed, offer, cancel) }) { ready ->
            plan = ready
            preparedDraft = draft
        }
    }

    /**
     * T117 one-action save, mirroring [HlsPreparation.save]: the manifest is read only when the
     * user taps 保存; a picked height (the pre-parsed DASH summaries carry no per-variant
     * addresses) is honored or fails honestly, and a multi-offer document without a prior pick
     * stops after the parse for one explicit confirm of the surfaced rows.
     */
    fun save(draft: DownloadDraft, preferredHeight: Int?, onReady: (DashDownloadPlan) -> Unit) {
        if (closed || busy) return
        if (options != null && optionsDraft == draft) {
            prepareSelected(draft, onReady)
            return
        }
        request({ cancel -> resolver.resolveEntry(draft, cancel) }) { parsed ->
            options = parsed
            optionsDraft = draft
            val offers = parsed.document.videoOffers
            val chosen = preferredHeight?.let { height -> offers.firstOrNull { it.height == height } }
            when {
                preferredHeight != null && chosen?.supported != true -> {
                    selected = MpdPlanParser.defaultVideoOffer(offers)
                    error = "所选清晰度已不在清单中，请重新选择后再保存"
                }
                preferredHeight == null && offers.count { it.supported } > 1 ->
                    selected = MpdPlanParser.defaultVideoOffer(offers)
                else -> {
                    selected = chosen ?: MpdPlanParser.defaultVideoOffer(offers) ?: offers.firstOrNull()
                    prepareSelected(draft, onReady)
                }
            }
        }
    }

    private fun prepareSelected(draft: DownloadDraft, onReady: (DashDownloadPlan) -> Unit) {
        val parsed = options ?: return
        val offer = selected?.takeIf { it.supported } ?: run {
            error = "没有受支持的档位，请返回来源网页重新发现资源"
            return
        }
        request({ cancel -> resolver.resolvePlan(draft, parsed, offer, cancel) }) { ready ->
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
                        ?: "DASH 清单未能读取，请检查网络或返回来源网页重新发现资源"
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
