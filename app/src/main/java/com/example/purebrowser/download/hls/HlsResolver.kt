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
            is HlsPlaylist.Media -> HlsDownloadPlan(options.entryUrl,options.finalUrl,playlist,null)
            is HlsPlaylist.Master -> {
                val chosen=variant?.takeIf { it.supported && playlist.variants.any { v->v==it } }
                    ?: throw TransferFailure(FailureKind.UNSUPPORTED,"请选择受支持的视频档位")
                val (text,finalUrl)=client.text(record,chosen.url,cancel)
                val media=parse(text,finalUrl) as? HlsPlaylist.Media ?: throw TransferFailure(FailureKind.UNSUPPORTED,"不支持嵌套主清单")
                HlsDownloadPlan(options.entryUrl,finalUrl,media,chosen)
            }
        }
        plan.media.segments.forEach { RequestPolicy.validateUrl(it.url,allowLocalHttp) }
        return plan
    }
    private fun parse(text:String,url:String):HlsPlaylist = try { HlsPlaylistParser.parse(text,url) }
        catch(e:HlsParseException) { throw TransferFailure(FailureKind.UNSUPPORTED,e.safeReason) }
    private fun previewRecord(draft:DownloadDraft)=DownloadRecord(recordId="preview",name="preview.mp4",mediaUrl=draft.candidate.url,
        sourceUrl=draft.sourceUrl,userAgent=draft.userAgent,transfer=TransferType.CONTROLLED,
        frameUrl=draft.frameUrl,reliableSource=draft.reliableSource,
        useAccessContext=draft.useAccessContext && RequestPolicy.canUseContext(draft.sourceUrl,draft.frameUrl,draft.reliableSource))
}
