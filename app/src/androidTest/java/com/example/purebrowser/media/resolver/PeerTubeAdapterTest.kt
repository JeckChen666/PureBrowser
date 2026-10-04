package com.example.purebrowser.media.resolver
import com.example.purebrowser.download.*
import com.example.purebrowser.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
/** org.json is an Android runtime dependency: these exact assertions run on device, not android.jar JVM stubs. */
class PeerTubeAdapterTest {
 private val id="ff8fe61b-026f-4f07-b66b-2a790d6f6ab1"
 private fun draft(source:String)=DownloadDraft(MediaCandidate(source,MediaKind.UNKNOWN,emptySet()),"QA",sourceUrl=source)
 @Test fun anonymousMetadataUsesSameOriginApiAndPublishedFiles()=runBlocking {
  val bytes="""{"uuid":"$id","privacy":{"id":1},"downloadEnabled":true,"name":"Authored movie","files":[{"fileUrl":"https://cdn.example/movie.mp4","size":1000,"resolution":{"id":360}},{"fileUrl":"file:///secret.mp4","size":1000,"resolution":{"id":360}}]}""".toByteArray()
  val adapter=PeerTubeMediaAdapter(HttpTransport { url,headers,_ ->
   assertEquals("https://another.example/api/v1/videos/$id",url);assertFalse(headers.containsKey("Cookie"));assertFalse(headers.containsKey("Authorization"))
   object:HttpResponse{override val status=200;override fun header(name:String)=if(name=="Content-Length")bytes.size.toString() else null;override fun body()=ByteArrayInputStream(bytes);override fun close(){}}
  })
  val result=SiteResolverRegistry(listOf(adapter)).resolve(draft("https://another.example/w/xymLD6rkpHNug3fzzMyhmZ"),TransferCancellation())
  assertEquals(1,result.options.size);assertEquals(MediaKind.FILE,result.options.single().candidate.kind);assertNull(result.options.single().dualTrackPlan)
 }
 @Test fun wrongWorkIdentityAndUnsupportedPagesNeverProducePlan()=runBlocking {
  val adapter=PeerTubeMediaAdapter(HttpTransport { _,_,_ ->object:HttpResponse{
   override val status=200;override fun header(name:String)=null;override fun body()=ByteArrayInputStream("{\"uuid\":\"wrong\",\"files\":[]}".toByteArray());override fun close(){}}
  })
  for(source in listOf("https://another.example/videos/watch/$id","https://another.example/watch")){
   try{SiteResolverRegistry(listOf(adapter)).resolve(draft(source),TransferCancellation());fail("must reject")}
   catch(e:TransferFailure){assertEquals(FailureKind.UNSUPPORTED,e.kind)}
  }
 }
}
