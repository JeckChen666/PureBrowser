package com.example.purebrowser.download

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.hls.*
import com.example.purebrowser.media.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** One distinct CC BY 3.0 Blender film, not six samples or three independent environments. */
class HlsRealHttpsAudit {
    @Test fun licenseBackedTearsOfSteelOnRealHttpsSavesAnIndependentMp4() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hlsRealHttps")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val url="https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
        val draft=DownloadDraft(MediaCandidate(url,MediaKind.HLS,setOf(Evidence.REQUEST)),"PureBrowser/0.1.2 (authorized compatibility test)",useAccessContext=false)
        ActivityScenario.launch(MainActivity::class.java).use {
            val runtime=DownloadRuntime.get(app);val repo=runtime.repository
            val resolver=HlsResolver(UrlConnectionTransport(),WebsiteAccessContext(),repo.allowLocalHttp)
            val options=resolver.resolveEntry(draft,TransferCancellation())
            val master=options.playlist as HlsPlaylist.Master
            assertTrue(master.variants.any { !it.supported }) // explicit audio-only variants cannot be downloaded as video
            val chosen=master.variants.filter { it.supported }.minBy { it.bandwidth ?: Long.MAX_VALUE }
            val plan=resolver.resolvePlan(draft,options,chosen,TransferCancellation())
            val id=repo.enqueue(draft,false,"Tears-of-Steel-Blender-CC-BY-3.0.mp4",hlsPlan=plan)
            try {
                runtime.kick();val deadline=System.currentTimeMillis()+900000
                while(repo.record(id)!!.taskStatus in DownloadRepository.activeStatuses && System.currentTimeMillis()<deadline)Thread.sleep(500)
                val record=repo.record(id)!!;assertEquals(record.safeFailure,TaskStatus.SUCCEEDED,record.taskStatus)
                val asset=repo.stateSnapshot().assets.single { a->a.recordId==id }
                assertEquals("video/mp4",asset.mimeType);assertNotNull(repo.fileUri(id))
                val evidence=JSONObject().put("film","Tears of Steel").put("publisher","Blender Foundation")
                    .put("license","CC BY 3.0").put("licenseEvidence","https://media.xiph.org/tearsofsteel/README.txt")
                    .put("environment","demo.unified-streaming.com").put("date",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()).put("durationMs",asset.durationMillis)
                    .put("segments",record.segmentCount).put("received",record.received).put("package",app.packageName).toString()
                File(app.cacheDir,"v012-real-https-result.json").writeText(evidence)
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("hlsEvidence",evidence) })
            } finally { runCatching {
                if(repo.record(id)?.taskStatus in DownloadRepository.activeStatuses)repo.cancel(id)
                val until=System.currentTimeMillis()+10000
                while(repo.transferInFlight(id) && System.currentTimeMillis()<until)Thread.sleep(50)
                if(!repo.transferInFlight(id)) {
                    if(repo.stateSnapshot().assets.any { a->a.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id)
                }
            } }
        }
    }
}
