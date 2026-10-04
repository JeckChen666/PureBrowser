package com.example.purebrowser.download

import android.content.Context
import android.media.MediaExtractor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.site.DualTrackDownloadPlan
import com.example.purebrowser.media.*
import com.example.purebrowser.media.site.YouTubeResolver
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit real-sites opt-in. Full public/CC-BY Sintel output, not discovery-only success. */
class V015YouTubeProductAudit {
 @Test fun sintelPublicDualTracksProduceActualAudioVideoFile() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("v015RealSites")=="true")
  val context=ApplicationProvider.getApplicationContext<Context>()
  val media=runBlocking{YouTubeResolver(context).resolve("eRsGyueVLvQ")}
  val video=media.videos.minBy{it.height};val audio=media.audios.first()
  val plan=DualTrackDownloadPlan("youtube:${media.videoId}",video.id,audio.id,video.url,audio.url,"avc1","mp4a.40.2",video.length,audio.length,minOf(video.durationMs,audio.durationMs)*1000)
  DualTrackTestSupport.test { repo ->
   val draft=DownloadDraft(MediaCandidate(video.url,MediaKind.FILE,setOf(Evidence.SITE),"video/mp4"),"PureBrowser authorized Sintel audit",sourceUrl="https://www.youtube.com/watch?v=eRsGyueVLvQ",sourceTitle=media.title,useAccessContext=false,dualTrackPlan=plan)
   val id=repo.enqueue(draft,false,"Sintel-CC-BY-3.0.mp4")
   val start=System.currentTimeMillis()
   // Explicit test-only normal-library Range-carrier experiment. Not a production/UI claim.
   // No chunking, token edits, credentials or relaxed length/mux/publication contracts.
   val queryCarrier=InstrumentationRegistry.getArguments().getString("v015FullRangeCarrier")=="query"
   val raw=UrlConnectionTransport()
   val transport=if(!queryCarrier)raw else HttpTransport { url,headers,cancel ->
    val declared=when(url){video.url->video.length;audio.url->audio.length;else->error("unexpected track request")}
    val uri=java.net.URI(url)
    require(uri.host.endsWith(".googlevideo.com") && uri.scheme=="https" && uri.port in setOf(-1,443) && uri.rawFragment==null)
    require(uri.rawQuery!=null && uri.rawQuery.split('&').none{it.substringBefore('=')=="range"})
    require(headers.entries.singleOrNull{it.key.equals("Range",true)}?.value=="bytes=0-${declared-1}")
    require(headers.keys.none{it.equals("Cookie",true)||it.equals("Authorization",true)})
    val target=url+"&range=0-${declared-1}"
    RequestPolicy.validateUrl(target,false)
    raw.open(target,headers.filterKeys{!it.equals("Range",true)},cancel)
   }
   DualTrackTransfer(repo,transport,AccessContextProvider{error("no cookie")}).run(id,TransferCancellation())
   val record=repo.record(id)!!
   val report=JSONObject().put("videoId",media.videoId).put("videoFormat",video.id).put("audioFormat",audio.id).put("version","0.1.5-dev/code14")
    .put("status",record.taskStatus.name).put("safeFailure",record.safeFailure).put("elapsedMs",System.currentTimeMillis()-start)
    .put("fullRangeCarrier",if(queryCarrier)"QUERY_TEST_ONLY" else "HEADER_PRODUCT_DEFAULT")
    .put("currentUiAcceptancePassed",false).put("productDefaultTransport",!queryCarrier).put("productionTransportChanged",false)
    .put("scope",if(queryCarrier)"ONE_FULL_QUERY_CARRIER_POC_NOT_CURRENT_UI_ACCEPTANCE" else "DEFAULT_PRODUCT_TRANSFER")
   val resultFile=File(context.getExternalFilesDir(null),"v015/sintel-result.json").apply{parentFile!!.mkdirs()}
   resultFile.writeText(report.toString(2))
   assertEquals(record.safeFailure,TaskStatus.SUCCEEDED,record.taskStatus)
   val asset=repo.stateSnapshot().assets.single();assertEquals(FormatCheck.PASSED,asset.format)
   val uri=repo.fileUri(id)!!
   val extractor=MediaExtractor()
   try {
    extractor.setDataSource(context,uri,null)
    assertEquals(2,extractor.trackCount)
    val formats=(0 until extractor.trackCount).map{extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)}
    assertTrue("video/avc" in formats);assertTrue("audio/mp4a-latm" in formats)
    report.put("tracks",org.json.JSONArray(formats)).put("durationMs",asset.durationMillis).put("bytes",asset.sizeBytes).put("formatCheck",asset.format.name)
    report.put("actualCrossUidPlayback","not_yet_tested").put("actualAudioListening","not_yet_tested")
    resultFile.writeText(report.toString(2))
   }finally{extractor.release()}
  }
 }
}
