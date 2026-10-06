package com.example.purebrowser.download.hls

import com.example.purebrowser.download.*
import com.example.purebrowser.media.codec.Av1Capability
import com.example.purebrowser.media.codec.Av1CapabilityProvider
import com.example.purebrowser.media.codec.Av1DecodeSupport

class HlsResolver(
    transport:HttpTransport,
    access:AccessContextProvider,
    private val allowLocalHttp:Boolean,
    /** T110 AV1 runtime gate at the offer layer; the default keeps pre-T110 test behavior. */
    private val av1:Av1CapabilityProvider = Av1CapabilityProvider { Av1DecodeSupport.AVAILABLE },
) {
    private val client=HlsHttpClient(transport,access,allowLocalHttp)
    fun resolveEntry(draft:DownloadDraft,cancel:TransferCancellation):HlsOptions {
        val record=previewRecord(draft)
        val (text,finalUrl)=client.text(record,draft.candidate.url,cancel)
        return HlsOptions(draft.candidate.url,finalUrl,gateAv1(parse(text,finalUrl)))
    }
    /**
     * T110 post-parse offer policy (the parser itself stays capability-blind and pure): HIDDEN
     * drops AV1 variants outright — hidden, not greyed — including from default-variant picking;
     * WARNED keeps them selectable (honest attempt) and appends the fixed software-decode note to
     * the parser's codec warning. An unsupported variant's reason stays an exclusion and is never
     * softened into a warning.
     */
    private fun gateAv1(playlist:HlsPlaylist):HlsPlaylist {
        val master=playlist as? HlsPlaylist.Master ?: return playlist
        val support=av1.support()
        val gated=master.variants.mapNotNull { variant ->
            when {
                Av1Capability.hidden(variant.codecs,support) -> null
                support==Av1DecodeSupport.WARNED && variant.supported && Av1Capability.isAv1Codecs(variant.codecs) ->
                    variant.copy(unsupportedReason=Av1Capability.annotated(variant.unsupportedReason,support))
                else -> variant
            }
        }
        if(gated==master.variants) return master
        return HlsPlaylist.Master(gated,master.audioRenditions)
    }
    fun resolvePlan(draft:DownloadDraft,options:HlsOptions,variant:HlsVariant?,cancel:TransferCancellation):HlsDownloadPlan {
        require(options.entryUrl==draft.candidate.url)
        val record=previewRecord(draft)
        val plan=when(val playlist=options.playlist) {
            is HlsPlaylist.Media -> HlsDownloadPlan(options.entryUrl,options.finalUrl,
                transferableMedia(options.finalUrl,playlist,SegmentFormat.Role.SINGLE_TRACK),null)
            is HlsPlaylist.Master -> {
                val chosen=variant?.takeIf { it.supported && playlist.variants.any { v->v==it } }
                    ?: throw TransferFailure(FailureKind.UNSUPPORTED,"请选择受支持的视频档位")
                val (text,finalUrl)=client.text(record,chosen.url,cancel)
                val media=parse(text,finalUrl) as? HlsPlaylist.Media
                    ?: throw TransferFailure(FailureKind.UNSUPPORTED,"不支持嵌套主清单")
                val audio=chosen.audioGroup?.let { group ->
                    // Only renditions that declare their own address form a dual-track plan; a
                    // URI-less group means audio is muxed into the variant stream (single-track).
                    playlist.audioRenditions.filter { it.groupId==group && it.uri!=null }
                        .takeIf { it.isNotEmpty() }?.let { resolveAudioTrack(record,it,cancel) }
                        ?.also { dualTrackGates(chosen,media,it.media) }
                }
                // The variant's container gate depends on the carrying pipeline: only a
                // separate-audio dual-track plan can assemble fMP4 per track (T107); a muxed
                // single-track transfer keeps the TS-only remux path.
                HlsDownloadPlan(options.entryUrl,finalUrl,
                    transferableMedia(finalUrl,media,
                        if(audio!=null) SegmentFormat.Role.DUAL_TRACK_VIDEO else SegmentFormat.Role.SINGLE_TRACK),
                    chosen,audio)
            }
        }
        plan.media.segments.forEach { RequestPolicy.validateUrl(it.url,allowLocalHttp) }
        plan.audio?.media?.segments?.forEach { RequestPolicy.validateUrl(it.url,allowLocalHttp) }
        plan.media.initSegment?.let { RequestPolicy.validateUrl(it.url,allowLocalHttp) }
        plan.audio?.media?.initSegment?.let { RequestPolicy.validateUrl(it.url,allowLocalHttp) }
        return plan
    }
    /** The parser classifies fMP4; this seam decides which pipeline may carry it (T107). */
    private fun transferableMedia(finalUrl:String,playlist:HlsPlaylist,role:SegmentFormat.Role):HlsPlaylist.Media {
        val media=playlist as? HlsPlaylist.Media ?: throw TransferFailure(FailureKind.UNSUPPORTED,"不支持嵌套主清单")
        media.format.requireTransferSupported(role)
        // fMP4 assembly is init-segment driven; a classified fMP4 playlist without EXT-X-MAP
        // has an unknowable init and is refused instead of guessed.
        if(media.format==SegmentFormat.FMP4 && media.initSegment==null)
            throw TransferFailure(FailureKind.UNSUPPORTED,"fMP4 清单缺少初始化段声明，本版无法组装")
        return media
    }
    /** Default-or-single rendition selection; honest ambiguity refusals, never a silent language pick. */
    private fun resolveAudioTrack(record:DownloadRecord,renditions:List<HlsAudioRendition>,cancel:TransferCancellation):HlsAudioTrack {
        val defaults=renditions.filter { it.isDefault }
        val chosen=when {
            renditions.size==1 -> renditions.single()
            defaults.size==1 -> defaults.single()
            defaults.isEmpty() -> throw TransferFailure(FailureKind.UNSUPPORTED,"音轨分组有多个候选且未声明默认音轨，本版不自动挑选")
            else -> throw TransferFailure(FailureKind.UNSUPPORTED,"音轨分组声明了多个默认音轨")
        }
        val (text,finalUrl)=client.text(record,chosen.uri ?: error("音轨缺少地址"),cancel)
        return HlsAudioTrack(finalUrl,transferableMedia(finalUrl,parse(text,finalUrl),SegmentFormat.Role.DUAL_TRACK_AUDIO),chosen)
    }
    /** Declaration-level dual-track gates; segment content is still verified during transfer. */
    private fun dualTrackGates(variant:HlsVariant,video:HlsPlaylist.Media,audio:HlsPlaylist.Media) {
        val names=variant.codecs?.split(',')?.map { it.trim() }
        if(names!=null) {
            if(names.none { it.startsWith("avc1.") || it.startsWith("avc3.") })
                throw TransferFailure(FailureKind.UNSUPPORTED,"此档位视频编码不是 H.264，本版不支持分轨保存")
            val audioTokens=names.filterNot { it.startsWith("avc1.") || it.startsWith("avc3.") }
            if(audioTokens.isNotEmpty() && audioTokens.any { !it.startsWith("mp4a") })
                throw TransferFailure(FailureKind.UNSUPPORTED,"此档位音频编码不是 AAC，本版不支持分轨保存")
        }
        if(video.durationUs>com.example.purebrowser.download.site.DualTrackMetadata.MAX_DURATION_US)
            throw TransferFailure(FailureKind.UNSUPPORTED,"分轨时长超出双轨预算")
        val tolerance=maxOf(2_000_000L,minOf(5_000_000L,video.durationUs/20))
        if(kotlin.math.abs(video.durationUs-audio.durationUs)>tolerance)
            throw TransferFailure(FailureKind.UNSUPPORTED,"音视频轨时长不一致，本版不支持分轨保存")
    }
    private fun parse(text:String,url:String):HlsPlaylist = try { HlsPlaylistParser.parse(text,url) }
        catch(e:HlsParseException) { throw TransferFailure(FailureKind.UNSUPPORTED,e.safeReason) }
    private fun previewRecord(draft:DownloadDraft)=DownloadRecord(recordId="preview",name="preview.mp4",mediaUrl=draft.candidate.url,
        sourceUrl=draft.sourceUrl,userAgent=draft.userAgent,transfer=TransferType.CONTROLLED,
        frameUrl=draft.frameUrl,reliableSource=draft.reliableSource,
        useAccessContext=draft.useAccessContext && RequestPolicy.canUseContext(draft.sourceUrl,draft.frameUrl,draft.reliableSource))
}
