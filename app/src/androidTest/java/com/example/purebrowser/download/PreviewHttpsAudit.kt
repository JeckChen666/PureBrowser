package com.example.purebrowser.download

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Two distinct authorized works; the third HLS work is the separate Tears of Steel audit. */
class PreviewHttpsAudit {
    @Test fun signedHttpsMp4AndWebmAreSavedReadableAndDecoded() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("previewHttps")=="true")
        val app=instrument.targetContext
        val faucet=JSONArray(instrument.context.assets.open("https-samples.json").bufferedReader().use { it.readText() }).getJSONObject(0)
        val sintel=JSONObject().put("title","Sintel trailer").put("url","https://media.w3.org/2010/05/sintel/trailer.mp4")
            .put("source","https://durian.blender.org/sharing/").put("license","CC BY 3.0").put("environment","media.w3.org")
        val dir=File(app.cacheDir,"preview-https-${UUID.randomUUID()}").apply { mkdirs() }
        val repo=DownloadRepository(DownloadStore(dir),AndroidDownloadBackend(app),false,ManagedFileStore(app))
        val ids=mutableListOf<String>()
        try {
            for((index,spec) in listOf(sintel,faucet).withIndex()) {
                val id=repo.enqueue(DownloadDraft(MediaCandidate(spec.getString("url"),MediaKind.FILE,setOf(Evidence.REQUEST)),
                    "PureBrowser authorized preview compatibility test",useAccessContext=false),false,"preview-https-$index.mp4")
                ids+=id
                ControlledTransfer(repo,UrlConnectionTransport(),AccessContextProvider { error("public sample must not read session") }).run(id,TransferCancellation())
                val record=repo.record(id)!!
                assertEquals(record.safeFailure,TaskStatus.SUCCEEDED,record.taskStatus)
                val asset=repo.stateSnapshot().assets.single { it.recordId==id }
                assertEquals(FormatCheck.PASSED,asset.format);val uri=repo.fileUri(id) ?: error("unreadable public asset")
                val retriever=android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(app,uri)
                    for(time in listOf(0L,(asset.durationMillis ?: 1)*500)) {
                        val frame=retriever.getFrameAtTime(time,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: error("sample frame not decoded")
                        assertTrue(frame.width>0 && frame.height>0);frame.recycle()
                    }
                } finally { retriever.release() }
                instrument.sendStatus(0,android.os.Bundle().apply { putString("previewHttpsEvidence",JSONObject()
                    .put("title",spec.getString("title")).put("source",spec.getString("source"))
                    .put("license",spec.getString("license")).put("environment",spec.getString("environment"))
                    .put("mime",asset.mimeType).put("bytes",asset.sizeBytes).put("durationMs",asset.durationMillis)
                    .put("readable",true).put("decoded",true).put("api",android.os.Build.VERSION.SDK_INT)
                    .put("package",app.packageName).toString()) })
            }
        } finally {
            ids.forEach { id ->runCatching { if(repo.stateSnapshot().assets.any { it.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id) } }
            dir.deleteRecursively()
        }
    }
}
