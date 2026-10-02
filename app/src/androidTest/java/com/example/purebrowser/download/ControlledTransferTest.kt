package com.example.purebrowser.download

import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.*
import java.io.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ControlledTransferTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val testApp get()=InstrumentationRegistry.getInstrumentation().context
    private class NoSystem : DownloadBackend {
        override fun enqueue(url:String,name:String,userAgent:String,wifiOnly:Boolean):Long=error("New tasks must not reach DownloadManager")
        override fun query(id:Long)=SystemDownloadResult.Missing
        override fun fileUri(id:Long):String?=null
        override fun access(uri:String)=FileAccess(FileAvailability.MISSING)
        override fun inspect(uri:String)=MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id:Long)=error("Managed tasks must not remove a system task")
    }
    private fun sample(name:String="test-video.mp4")=testApp.assets.open(name).use { it.readBytes() }
    private fun test(block:(DownloadRepository)->Unit) {
        val dir=File(app.cacheDir,"v011-transfer-${UUID.randomUUID()}").apply { mkdirs() }
        val repo=DownloadRepository(DownloadStore(dir),NoSystem(),files=ManagedFileStore(app))
        try { block(repo) } finally {
            repo.records().forEach { r ->
                runCatching { val a=repo.stateSnapshot().assets.firstOrNull { it.recordId==r.recordId };if(a!=null)repo.files!!.delete(a) }
                runCatching { repo.files!!.cleanupPending(r);repo.files!!.removeStage(r.recordId) }
            };dir.deleteRecursively()
        }
    }
    private fun enqueue(repo:DownloadRepository,url:String="https://site.example/media?token=demo%2Bexact",context:Boolean=true):TaskId = repo.enqueue(
        DownloadDraft(MediaCandidate(url,MediaKind.UNKNOWN,setOf(Evidence.DOM),frameUrl="https://site.example/frame",reliableSource=true),
            "TestAgent","https://site.example/watch?private=demo","Demo","tab",1,useAccessContext=context),false,"test.mp4")
    private fun response(data:ByteArray,status:Int=200,headers:Map<String,String> = mapOf("Content-Length" to data.size.toString()),body:(()->InputStream)?=null)=object:HttpResponse {
        override val status=status
        override fun header(name:String)=headers[name]
        override fun body()=body?.invoke() ?: ByteArrayInputStream(data)
        override fun close() {}
    }
    @Test fun extensionlessMp4SingleGetPublishesPlayableMediaStoreAsset()=test { repo ->
        val id=enqueue(repo);var calls=0
        ControlledTransfer(repo,HttpTransport { url,headers,_ ->
            calls++;assertEquals("https://site.example/media?token=demo%2Bexact",url)
            assertEquals("session=synthetic",headers["Cookie"]);assertEquals("https://site.example/watch",headers["Referer"])
            response(sample()) },AccessContextProvider { "session=synthetic" }).run(id,TransferCancellation())
        assertEquals(1,calls);assertEquals(TaskStatus.SUCCEEDED,repo.record(id)!!.taskStatus)
        val item=repo.snapshot().single();assertTrue(item.verified);assertNotNull(repo.fileUri(id));assertEquals(id,item.id)
        assertEquals(if(android.os.Build.VERSION.SDK_INT>=29) "media" else "${app.packageName}.files",repo.fileUri(id)!!.authority)
        val raw=repo.store.file.readText();assertFalse(raw.contains("session=synthetic"));assertFalse(raw.contains("Authorization"))
        assertFalse(repo.files!!.stage(id).exists())
    }
    @Test fun webmMimeAndContainerDetermineExtension()=test { repo ->
        val id=enqueue(repo)
        ControlledTransfer(repo,HttpTransport { _,_,_->response(sample("test-video.webm")) },AccessContextProvider { null }).run(id,TransferCancellation())
        assertEquals(TaskStatus.SUCCEEDED,repo.record(id)!!.taskStatus)
        assertTrue(repo.record(id)!!.name.endsWith(".webm"));assertEquals("video/webm",repo.mimeType(id))
    }
    @Test fun unknownLengthRemainsUnknownEvenAfterSuccessfulSave()=test { repo ->
        val id=enqueue(repo)
        ControlledTransfer(repo,HttpTransport { _,_,_->response(sample(),headers=emptyMap()) },AccessContextProvider { null }).run(id,TransferCancellation())
        assertTrue(repo.snapshot().single().verified);assertEquals(-1L,repo.snapshot().single().total)
    }
    @Test fun htmlWithVideoMimeCannotEnterLibrary()=test { repo ->
        val id=enqueue(repo)
        ControlledTransfer(repo,HttpTransport { _,_,_->response("<html>login page</html>".toByteArray(),headers=mapOf("Content-Type" to "video/mp4")) },AccessContextProvider { null }).run(id,TransferCancellation())
        assertEquals(FailureKind.NOT_VIDEO,repo.record(id)!!.failure);assertTrue(repo.stateSnapshot().assets.isEmpty());assertFalse(repo.files!!.stage(id).exists())
    }
    @Test fun truncatedResponseCannotPublishFile()=test { repo ->
        val id=enqueue(repo)
        ControlledTransfer(repo,HttpTransport { _,_,_->response(sample(),headers=mapOf("Content-Length" to (sample().size+1).toString())) },AccessContextProvider { null }).run(id,TransferCancellation())
        assertEquals(FailureKind.NETWORK,repo.record(id)!!.failure);assertTrue(repo.stateSnapshot().assets.isEmpty())
    }
    @Test fun credentialedRedirectNeverContactsForeignOrigin()=test { repo ->
        val id=enqueue(repo);var calls=0
        ControlledTransfer(repo,HttpTransport { _,_,_->calls++;response(byteArrayOf(),302,mapOf("Location" to "https://foreign.example/movie")) },AccessContextProvider { "session=synthetic" }).run(id,TransferCancellation())
        assertEquals(1,calls);assertEquals(FailureKind.ACCESS_CONDITION,repo.record(id)!!.failure)
    }
    @Test fun publicRedirectHasOnlyOriginRefererAndNoCookie()=test { repo ->
        val id=enqueue(repo);var calls=0
        ControlledTransfer(repo,HttpTransport { _,headers,_->
            calls++;if(calls==1)response(byteArrayOf(),302,mapOf("Location" to "https://cdn.example/movie"))
            else { assertNull(headers["Cookie"]);assertEquals("https://site.example/",headers["Referer"]);response(sample()) }
        },AccessContextProvider { null }).run(id,TransferCancellation())
        assertEquals(2,calls);assertTrue(repo.snapshot().single().verified)
    }
    @Test fun sameOriginRedirectReadsCurrentCookieAgain()=test { repo ->
        val id=enqueue(repo);var calls=0;var cookies=0
        ControlledTransfer(repo,HttpTransport { _,headers,_->
            calls++;assertEquals("session=$calls",headers["Cookie"])
            if(calls==1)response(byteArrayOf(),302,mapOf("Location" to "/second")) else response(sample())
        },AccessContextProvider { "session=${++cookies}" }).run(id,TransferCancellation())
        assertEquals(2,cookies);assertTrue(repo.snapshot().single().verified)
    }
    @Test fun contextDisabledNeverReadsCookieOrSendsReferer()=test { repo ->
        val id=enqueue(repo,context=false)
        ControlledTransfer(repo,HttpTransport { _,h,_->assertNull(h["Cookie"]);assertNull(h["Referer"]);response(sample()) },AccessContextProvider { error("No cookie lookup expected") }).run(id,TransferCancellation())
        assertTrue(repo.snapshot().single().verified)
    }
    @Test fun http403DoesNotInventReasonOrAutoRetry()=test { repo ->
        val id=enqueue(repo);var calls=0
        ControlledTransfer(repo,HttpTransport { _,_,_->calls++;response(byteArrayOf(),403) },AccessContextProvider { null }).run(id,TransferCancellation())
        assertEquals(1,calls);assertEquals(FailureKind.ACCESS_CONDITION,repo.record(id)!!.failure);assertTrue(repo.snapshot().single().canRetry)
    }
    @Test fun redirectLoopAndCompressedPayloadAreRefused()=test { repo ->
        val id=enqueue(repo);var calls=0
        ControlledTransfer(repo,HttpTransport { _,_,_->calls++;response(byteArrayOf(),302,mapOf("Location" to "/again")) },AccessContextProvider { null }).run(id,TransferCancellation())
        assertEquals(6,calls);assertEquals(FailureKind.HTTP_REJECTED,repo.record(id)!!.failure)
        val next=enqueue(repo)
        ControlledTransfer(repo,HttpTransport { _,_,_->response(sample(),headers=mapOf("Content-Encoding" to "gzip")) },AccessContextProvider { null }).run(next,TransferCancellation())
        assertEquals(FailureKind.UNSUPPORTED,repo.record(next)!!.failure)
    }
    @Test fun cancelCannotBeResurrectedByLateProgressOrCompletion()=test { repo ->
        val id=enqueue(repo);val cancel=TransferCancellation();var read=false
        ControlledTransfer(repo,HttpTransport { _,_,_->response(sample(),body={ object:ByteArrayInputStream(sample()) {
            override fun read(b:ByteArray,off:Int,len:Int):Int { val n=super.read(b,off,len);if(!read) { read=true;repo.cancel(id);cancel.cancel() };return n }
        } }) },AccessContextProvider { null }).run(id,cancel)
        assertEquals(TaskStatus.CANCELLED,repo.record(id)!!.taskStatus);assertTrue(repo.stateSnapshot().assets.isEmpty());assertFalse(repo.files!!.stage(id).exists())
    }
    @Test fun forgetKeepsPublicFileButPhysicalDeleteRemovesIt()=test { repo ->
        val id=enqueue(repo)
        ControlledTransfer(repo,HttpTransport { _,_,_->response(sample()) },AccessContextProvider { null }).run(id,TransferCancellation())
        val asset=repo.stateSnapshot().assets.single();repo.forgetRecord(id)
        assertEquals(FileAvailability.AVAILABLE,repo.files!!.access(asset).availability)
        assertTrue(repo.files!!.delete(asset));assertEquals(FileAvailability.MISSING,repo.files!!.access(asset).availability)
    }
    @Test fun v2MigrationRetainsOriginalBackupAndUnknownFields()=test { repo ->
        val v2="""{"schemaVersion":2,"legacyMigrationDone":true,"records":[{"recordId":"legacy-7","systemId":7,"name":"old.mp4","displayName":"old.mp4","mediaUrl":null,"sourceUrl":null,"sourceTitle":null,"createdAt":null,"userAgent":null,"wifiOnly":null,"mimeType":null,"retryOf":null,"sourceTabId":null,"sourceGeneration":null}],"assets":[]}"""
        repo.store.file.writeText(v2)
        val state=repo.store.load();assertNull(state.records.single().sourceUrl);assertNull(state.records.single().wifiOnly)
        assertEquals(TransferType.SYSTEM,state.records.single().transfer)
        assertTrue(repo.store.file.readText().contains("\"schemaVersion\":3"))
        assertTrue(repo.store.file.parentFile!!.listFiles()!!.any { it.name.startsWith("download-v2-") && it.readText()==v2 })
    }
    @Test fun futureSchemaRemainsReadOnlyAndUnchanged()=test { repo ->
        val raw="""{"schemaVersion":999}""";repo.store.file.writeText(raw)
        assertTrue(repo.store.load().records.isEmpty());assertFalse(repo.store.writable);assertEquals(raw,repo.store.file.readText())
    }

    @Test fun temporaryStorageFailureEndsTaskAndDoesNotPublishAnything()=test { repo ->
        val id=enqueue(repo);assertTrue(repo.files!!.stage(id).mkdir())
        ControlledTransfer(repo,HttpTransport { _,_,_->response(sample()) },AccessContextProvider { null }).run(id,TransferCancellation())
        assertEquals(TaskStatus.FAILED,repo.record(id)!!.taskStatus);assertEquals(FailureKind.STORAGE,repo.record(id)!!.failure)
        assertTrue(repo.stateSnapshot().assets.isEmpty());assertFalse(repo.files!!.stage(id).exists())
    }
    @Test fun arbitraryProviderUriCannotBeReadOrDeletedAsAnOwnedAsset()=test { repo ->
        val id=enqueue(repo);val record=repo.record(id)!!
        val asset=VideoAsset(id,null,"content://untrusted.example/arbitrary",record.name,record.displayName,1000,
            format=FormatCheck.PASSED,availability=FileAvailability.AVAILABLE,location=AssetLocation.MEDIASTORE_DOWNLOAD)
        assertFalse(repo.files!!.owned(asset));assertEquals(FileAvailability.UNREADABLE,repo.files!!.access(asset).availability)
        assertFalse(repo.files!!.delete(asset))
    }
}
