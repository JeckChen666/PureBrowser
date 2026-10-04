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
   DualTrackTransfer(repo,UrlConnectionTransport(),AccessContextProvider{error("no cookie")}).run(id,TransferCancellation())
   val record=repo.record(id)!!
   val report=JSONObject().put("videoId",media.videoId).put("videoFormat",video.id).put("audioFormat",audio.id).put("version","0.1.5-dev/code14")
    .put("status",record.taskStatus.name).put("safeFailure",record.safeFailure).put("elapsedMs",System.currentTimeMillis()-start)
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
