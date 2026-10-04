package com.example.purebrowser.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.*
import com.example.purebrowser.media.resolver.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Actual CC BY4 Coffee Run, standard PeerTube API + shared direct transfer, not claimed new gain. */
class V015PeerTubeProductAudit {
 @Test fun authorizedPublicPeerTubeFileUsesCommonTransferAndProducesDecodedMovie() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("v015RealSites")=="true")
  val context=ApplicationProvider.getApplicationContext<Context>()
  val page="https://video.blender.org/videos/watch/ff8fe61b-026f-4f07-b66b-2a790d6f6ab1"
  val initial=DownloadDraft(MediaCandidate(page,MediaKind.UNKNOWN,setOf(Evidence.SITE)),"PureBrowser authorized Coffee Run audit",sourceUrl=page,useAccessContext=false)
  val options=runBlocking{SiteResolverRegistry(listOf(PeerTubeMediaAdapter(UrlConnectionTransport()))).resolve(initial,TransferCancellation())}
  val selected=options.options.minBy{it.candidate.sizeBytes ?: Long.MAX_VALUE}
  val report=JSONObject().put("work","Coffee Run").put("license","CC BY 4.0").put("formatChoices",options.options.size)
  val resultFile=File(context.getExternalFilesDir(null),"v015/coffee-result.json").apply{parentFile!!.mkdirs()}
  DualTrackTestSupport.test{repo->
   val id=repo.enqueue(initial.copy(candidate=selected.candidate),false,"Coffee-Run-CC-BY-4.0.mp4")
   ControlledTransfer(repo,UrlConnectionTransport(),AccessContextProvider{error("no cookie")}).run(id,TransferCancellation())
   val record=repo.record(id)!!;report.put("status",record.taskStatus.name).put("safeFailure",record.safeFailure)
   resultFile.writeText(report.toString(2));assertEquals(record.safeFailure,TaskStatus.SUCCEEDED,record.taskStatus)
   val asset=repo.stateSnapshot().assets.single();assertEquals(FormatCheck.PASSED,asset.format)
   val uri=repo.fileUri(id)!!;val extractor=MediaExtractor();val retriever=MediaMetadataRetriever()
   try{
    extractor.setDataSource(context,uri,null)
    val formats=(0 until extractor.trackCount).map{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)}
    assertTrue(formats.any{it?.startsWith("video/")==true});assertTrue(formats.any{it?.startsWith("audio/")==true})
    retriever.setDataSource(context,uri)
    for(time in listOf(0L,(asset.durationMillis ?: 1)*500,((asset.durationMillis ?: 1000)-1000).coerceAtLeast(0)*1000)){
     val frame=retriever.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);assertNotNull(frame);assertTrue(frame!!.width>0&&frame.height>0);frame.recycle()
    }
    report.put("tracks",org.json.JSONArray(formats)).put("bytes",asset.sizeBytes).put("durationMs",asset.durationMillis).put("decodedFrames",3)
    report.put("crossUidShare","not_yet_tested").put("listening","not_yet_tested").put("v014AlreadyDiscoveredDirectFile",true)
    resultFile.writeText(report.toString(2))
   }finally{retriever.release();extractor.release()}
  }
 }
}
