package com.example.purebrowser.download

import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in real HTTPS transfer + format/readability audit, including production signed target. */
class HttpsCompatibilityAudit {
    @Test fun tenLicensedVideosAcrossThreeHttpsEnvironments() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("realHttps")=="true")
        val app=instrument.targetContext
        val manifest=JSONArray(instrument.context.assets.open("https-samples.json").bufferedReader().use { it.readText() })
        assertEquals(10,manifest.length())
        val dir=File(app.cacheDir,"https-audit-${UUID.randomUUID()}").apply { mkdirs() }
        val repo=DownloadRepository(DownloadStore(dir),AndroidDownloadBackend(app),false,ManagedFileStore(app))
        val results=JSONArray()
        try {
            for(i in 0 until manifest.length()) {
                val spec=manifest.getJSONObject(i)
                val id=repo.enqueue(DownloadDraft(MediaCandidate(spec.getString("url"),MediaKind.FILE,setOf(Evidence.REQUEST)),"PureBrowser/0.1.1 (https://github.com/JeckChen666/PureBrowser; authorized public video compatibility test)",useAccessContext=false),false,"https-$i.mp4")
                val started=System.currentTimeMillis()
                var httpCode=0
                val realTransport=UrlConnectionTransport()
                val transport=HttpTransport { url,headers,cancel ->realTransport.open(url,headers,cancel).also { httpCode=it.status } }
                ControlledTransfer(repo,transport,AccessContextProvider { error("Public sample must not request cookies") }).run(id,TransferCancellation())
                val r=repo.record(id)!!;val a=repo.stateSnapshot().assets.firstOrNull { it.recordId==id }
                val result=JSONObject().put("title",spec.getString("title")).put("environment",spec.getString("environment"))
                    .put("source",spec.getString("source")).put("license",spec.getString("license"))
                    .put("status",r.taskStatus.name).put("failure",r.failure?.name ?: JSONObject.NULL)
                    .put("httpCode",httpCode).put("bytes",r.received).put("elapsedMs",System.currentTimeMillis()-started)
                    .put("durationMillis",a?.durationMillis ?: JSONObject.NULL).put("readable",repo.fileUri(id)!=null)
                results.put(result)
                instrument.sendStatus(0,android.os.Bundle().apply { putString("httpsSample",result.toString()) })
                if(a!=null)assertTrue(repo.files!!.delete(a))
                repo.forgetRecord(id)
                Thread.sleep(2000)
            }
            File(app.cacheDir,"v011-https-results.json").writeText(results.toString(2))
            assertTrue("All 10 supported public samples must save readable videos: $results",(0 until results.length()).all {
                results.getJSONObject(it).getString("status")=="SUCCEEDED" && results.getJSONObject(it).getBoolean("readable") })
        } finally { dir.deleteRecursively() }
    }
}
