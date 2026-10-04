package com.example.purebrowser.media.resolver

import com.example.purebrowser.download.*
import com.example.purebrowser.media.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MediaProbeTest {
    private val mp4=byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()+ByteArray(12)
    private fun draft(url:String="https://cdn.example/stream")=DownloadDraft(MediaCandidate(url,MediaKind.UNKNOWN,setOf(Evidence.REQUEST)),"UA",sourceUrl="https://source.example/watch")
    private fun response(bytes:ByteArray=mp4,status:Int=200,headers:Map<String,String> = mapOf("Content-Length" to bytes.size.toString())) = object:HttpResponse {
        override val status=status
        override fun header(name:String)=headers[name]
        override fun body():InputStream=ByteArrayInputStream(bytes)
        override fun close() {}
    }
    @Test fun extensionlessMagicIsEvidenceNotSuccessAndNoCredentials() {
        var requested:Map<String,String>?=null
        val result=MediaProbe(HttpTransport { _,h,_ -> requested=h;response() }).analyze(draft(),TransferCancellation()) as MediaProbeResult.Candidate
        assertEquals(MediaKind.FILE,result.media.kind);assertEquals("video/mp4",result.media.mimeType)
        assertTrue(Evidence.PROBE in result.media.sources)
        assertEquals("bytes=0-65535",requested!!["Range"]);assertFalse(requested!!.containsKey("Cookie"));assertFalse(requested!!.containsKey("Authorization"))
    }
    @Test fun mimeIsNotProofOfMovie() {
        val result=MediaProbe(HttpTransport { _,_,_ ->response("<html>not a video</html>".toByteArray(),headers=mapOf("Content-Type" to "video/mp4")) }).analyze(draft(),TransferCancellation())
        assertTrue(result is MediaProbeResult.Unsupported)
    }
    @Test fun stopsReadingWhenServerIgnoresRange() {
        var read=0;var closed=false
        val result=MediaProbe(HttpTransport { _,_,_->object:HttpResponse {
            override val status=200
            override fun header(name:String)=null
            override fun body()=object:InputStream() { override fun read():Int { val i=read++;return if(i<mp4.size)mp4[i].toInt() and 255 else 0 } }
            override fun close(){closed=true}
        } }).analyze(draft(),TransferCancellation()) as MediaProbeResult.Candidate
        assertEquals(65536,result.prefixBytes);assertEquals(65536,read);assertTrue(closed)
    }
    @Test fun validatesRangeAndRecordsFullSizeNotPrefix() {
        val headers=mapOf("Content-Range" to "bytes 0-23/1000","Content-Length" to "24")
        val result=MediaProbe(HttpTransport { _,_,_->response(status=206,headers=headers) }).analyze(draft(),TransferCancellation()) as MediaProbeResult.Candidate
        assertEquals(1000L,result.media.sizeBytes)
        for(range in listOf("bytes 1-24/1000","bytes 0-24/1000","bytes 0-23/*","bytes 0-23/23")) {
            assertThrows(TransferFailure::class.java){ MediaProbe(HttpTransport { _,_,_->response(status=206,headers=headers+("Content-Range" to range)) }).analyze(draft(),TransferCancellation()) }
        }
    }
    @Test fun audioAndFragmentNeverBecomeCompleteVideo() {
        val audio=MediaProbe(HttpTransport { _,_,_->response(headers=mapOf("Content-Type" to "audio/mp4")) }).analyze(draft(),TransferCancellation())
        assertTrue(audio is MediaProbeResult.Unsupported)
        val fragment=MediaProbe(HttpTransport { _,_,_->response() }).analyze(draft("https://cdn.example/init.mp4"),TransferCancellation())
        assertTrue(fragment is MediaProbeResult.Unsupported)
    }
    @Test fun recognizesHlsAndRejectsWrongMime() {
        val hls=MediaProbe(HttpTransport { _,_,_->response("#EXTM3U\n#EXT-X-ENDLIST".toByteArray(),headers=emptyMap()) }).analyze(draft(),TransferCancellation()) as MediaProbeResult.Candidate
        assertEquals(MediaKind.HLS,hls.media.kind)
        assertTrue(MediaProbe(HttpTransport { _,_,_->response(headers=mapOf("Content-Type" to "video/webm")) }).analyze(draft(),TransferCancellation()) is MediaProbeResult.Unsupported)
    }
    @Test fun rejectsUnsafeRedirectAndCancelledOperation() {
        assertThrows(TransferFailure::class.java){MediaProbe(HttpTransport { _,_,_->response(status=302,headers=mapOf("Location" to "http://evil.example/stream")) }).analyze(draft(),TransferCancellation())}
        val token=TransferCancellation();token.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java){MediaProbe(HttpTransport { _,_,_->error("must not open") }).analyze(draft(),token)}
    }
}
