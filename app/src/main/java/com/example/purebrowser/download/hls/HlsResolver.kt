package com.example.purebrowser.download.hls

import com.example.purebrowser.download.*

class HlsResolver(transport:HttpTransport,access:AccessContextProvider,private val allowLocalHttp:Boolean) {
    private val client=HlsHttpClient(transport,access,allowLocalHttp)
    fun resolveEntry(draft:DownloadDraft,cancel:TransferCancellation):HlsOptions {
        val record=previewRecord(draft)
        val (text,finalUrl)=client.text(record,draft.candidate.url,cancel)
        return HlsOptions(draft.candidate.url,finalUrl,parse(text,finalUrl))
    }
    fun resolvePlan(draft:DownloadDraft,options:HlsOptions,variant:HlsVariant?,cancel:TransferCancellation):HlsDownloadPlan {
        require(options.entryUrl==draft.candidate.url)
        val record=previewRecord(draft)
        val plan=when(val playlist=options.playlist) {
            is HlsPlaylist.Media -> HlsDownloadPlan(options.entryUrl,options.finalUrl,transferableMedia(options.finalUrl,playlist),null)
            is HlsPlaylist.Master -> {
                val chosen=variant?.takeIf { it.supported && playlist.variants.any { v->v==it } }
                    ?: throw TransferFailure(FailureKind.UNSUPPORTED,"请选择受支持的视频档位")
                val (text,finalUrl)=client.text(record,chosen.url,cancel)
                val media=transferableMedia(finalUrl,parse(text,finalUrl))
                val audio=chosen.audioGroup?.let { group ->
                    // Only renditions that declare their own address form a dual-track plan; a
                    // URI-less group means audio is muxed into the variant stream (single-track).
                    playlist.audioRenditions.filter { it.groupId==group && it.uri!=null }
                        .takeIf { it.isNotEmpty() }?.let { resolveAudioTrack(record,it,cancel) }
                        ?.also { dualTrackGates(chosen,media,it.media) }
                }
                HlsDownloadPlan(options.entryUrl,finalUrl,media,chosen,audio)
            }
        }
        plan.media.segments.forEach { RequestPolicy.validateUrl(it.url,allowLocalHttp) }
        plan.audio?.media?.segments?.forEach { RequestPolicy.validateUrl(it.url,allowLocalHttp) }
        return plan
    }
    /** The parser classifies fMP4; this is the single seam that keeps it out of transfer until T96. */
    private fun transferableMedia(finalUrl:String,playlist:HlsPlaylist):HlsPlaylist.Media {
        val media=playlist as? HlsPlaylist.Media ?: throw TransferFailure(FailureKind.UNSUPPORTED,"不支持嵌套主清单")
        media.format.requireTransferSupported()
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
        return HlsAudioTrack(finalUrl,transferableMedia(finalUrl,parse(text,finalUrl)),chosen)
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
