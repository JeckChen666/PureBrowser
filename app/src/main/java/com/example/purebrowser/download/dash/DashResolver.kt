package com.example.purebrowser.download.dash

import com.example.purebrowser.download.*
import com.example.purebrowser.media.codec.Av1Capability
import com.example.purebrowser.media.codec.Av1CapabilityProvider

/**
 * T97 DASH resolution, mirroring [com.example.purebrowser.download.hls.HlsResolver]: nothing is
 * fetched until an explicit dialog action calls these; the MPD is read once per resolveEntry and
 * the plan is built from the already-parsed document without a second GET.
 */
class DashResolver(
    transport: HttpTransport,
    access: AccessContextProvider,
    private val allowLocalHttp: Boolean,
    /** T110 AV1 runtime gate at the offer layer; the default keeps pre-T110 test behavior. */
    private val av1: Av1CapabilityProvider = Av1CapabilityProvider { com.example.purebrowser.media.codec.Av1DecodeSupport.AVAILABLE },
) {
    private val client = com.example.purebrowser.download.hls.HlsHttpClient(transport, access, allowLocalHttp)

    data class DashOptions(val entryUrl: String, val finalUrl: String, val document: MpdPlanParser.MpdDocument) {
        override fun toString() = "DashOptions()"
    }

    fun resolveEntry(draft: DownloadDraft, cancel: TransferCancellation): DashOptions {
        val record = previewRecord(draft)
        val (text, finalUrl) = client.text(record, draft.candidate.url, cancel)
        val document = try { MpdPlanParser.parse(text, finalUrl) }
        catch (e: DashPlanException) { throw TransferFailure(FailureKind.UNSUPPORTED, e.safeReason) }
        return DashOptions(draft.candidate.url, finalUrl, gateAv1(document))
    }

    /**
     * T110 post-parse offer policy; MpdPlanParser stays capability-blind and pure. On a
     * no-decoder device AV1 representations are REMOVED from the offer list (hidden, not
     * greyed-out with a reason), so default-variant picking never suggests them either. On
     * decoder-present devices nothing changes here: the fMP4 assembler honestly accepts only
     * H.264/AAC, so an AV1 representation keeps its parser exclusion rather than being offered
     * as a download that is guaranteed to fail late; the software-decode performance note for
     * such devices is carried by the candidate surface (VariantSummary.warning), not by offers.
     */
    private fun gateAv1(document: MpdPlanParser.MpdDocument): MpdPlanParser.MpdDocument {
        val support = av1.support()
        val gated = document.videoOffers.filterNot { Av1Capability.hidden(it.codecs, support) }
        if (gated.size == document.videoOffers.size) return document
        return MpdPlanParser.MpdDocument(document.mpdUrl, document.durationUs, gated, document.audioOffer)
    }

    fun resolvePlan(
        draft: DownloadDraft,
        options: DashOptions,
        video: MpdPlanParser.DashRepresentationOffer,
        cancel: TransferCancellation,
    ): DashDownloadPlan {
        cancel.check()
        require(options.entryUrl == draft.candidate.url)
        if (options.document.videoOffers.none { it === video })
            throw TransferFailure(FailureKind.UNSUPPORTED, "请选择受支持的视频档位")
        val plan = try { MpdPlanParser.buildPlan(options.document, video) }
        catch (e: DashPlanException) { throw TransferFailure(FailureKind.UNSUPPORTED, e.safeReason) }
        return try { plan.validate(allowLocalHttp); plan }
        catch (e: TransferFailure) { throw e }
        catch (_: Exception) { throw TransferFailure(FailureKind.UNSUPPORTED, "DASH 分片方案无法用于下载") }
    }

    private fun previewRecord(draft: DownloadDraft) = DownloadRecord(
        recordId = "preview", name = "preview.mp4", mediaUrl = draft.candidate.url,
        sourceUrl = draft.sourceUrl, userAgent = draft.userAgent, transfer = TransferType.CONTROLLED,
        frameUrl = draft.frameUrl, reliableSource = draft.reliableSource,
        useAccessContext = draft.useAccessContext && RequestPolicy.canUseContext(draft.sourceUrl, draft.frameUrl, draft.reliableSource),
    )
}
