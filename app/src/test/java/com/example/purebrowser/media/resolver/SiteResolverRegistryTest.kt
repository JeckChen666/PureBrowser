package com.example.purebrowser.media.resolver
import com.example.purebrowser.download.*
import com.example.purebrowser.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
class SiteResolverRegistryTest {
 private val id="ff8fe61b-026f-4f07-b66b-2a790d6f6ab1"
 private fun draft(source:String)=DownloadDraft(MediaCandidate(source,MediaKind.UNKNOWN,emptySet()),"QA",sourceUrl=source)
 @Test fun peerTubeIdentityHandlesPublicUuidAndDefaultShortUuidWithoutHostList(){
  assertEquals(id,PeerTubeIdentity.videoId("https://video.blender.org/videos/watch/$id"))
  assertEquals(id,PeerTubeIdentity.videoId("https://another.example/w/xymLD6rkpHNug3fzzMyhmZ"))
  for(u in listOf("https://user@another.example/w/xymLD6rkpHNug3fzzMyhmZ","http://another.example/videos/watch/$id","https://another.example/videos/watch/$id/extra","https://another.example/w/ZZZZZZZZZZZZZZZZZZZZZZ"))assertNull(u,PeerTubeIdentity.videoId(u))
 }
 @Test fun canonicalWFullUuidKeepsIdentityAndRejectsMalformedOrAmbiguousPaths(){
  val charge="04da454b-9893-4184-98f3-248d00625efe"
  assertEquals(charge,PeerTubeIdentity.videoId("https://video.blender.org/w/$charge"))
  assertEquals(charge,PeerTubeIdentity.videoId("https://another.example/w/${charge.uppercase()}/"))
  for(u in listOf(
   "https://another.example/w/$charge/extra", "https://another.example/w/p/$charge",
   "https://another.example/w/${charge.dropLast(1)}", "https://another.example/w/${charge}0",
   "https://another.example/w/${charge.replace('4','g')}", "https://another.example/w/$charge#other",
   "https://another.example:444/w/$charge", "http://another.example/w/$charge",
   "https://user:pass@another.example/w/$charge")) assertNull(u,PeerTubeIdentity.videoId(u))
 }
 @Test fun pageDeclaredFileRemainsUnknownUntilAnotherEvidenceSource(){
  val sniffer=ResourceSniffer();val e=sniffer.beginPage()
  sniffer.observe(e,"https://cdn.example/movie.mp4",Evidence.METADATA,videoElement=true)
  assertEquals(MediaKind.UNKNOWN,sniffer.candidates.value.single().kind)
  sniffer.observe(e,"https://cdn.example/movie.mp4",Evidence.REQUEST)
  assertEquals(MediaKind.FILE,sniffer.candidates.value.single().kind)
 }
}
