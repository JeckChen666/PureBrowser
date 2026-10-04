package com.example.purebrowser.media.site

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.purebrowser.download.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import java.net.URI

/** Real public/CC-BY Sintel metadata and bounded real track bytes; not a full-download claim. */
@RunWith(AndroidJUnit4::class)
class YouTubeResolverAudit {
 @Test fun publicAuthorizedSintelResolvesInsideIsolatedAndroidWorker()= runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("v015RealSites")=="true")
  val context=ApplicationProvider.getApplicationContext<android.content.Context>()
  val media=YouTubeResolver(context).resolve("eRsGyueVLvQ")
  assertEquals("eRsGyueVLvQ",media.videoId);assertTrue(media.videos.isNotEmpty());assertTrue(media.audios.isNotEmpty())
  val video=media.videos.minBy { it.height };val audio=media.audios.first()
  assertTrue(video.mime.contains("avc1"));assertTrue(audio.mime.contains("mp4a.40.2"))
  for(track in listOf(video,audio)) {
   val token=TransferCancellation()
   UrlConnectionTransport().open(track.url,mapOf("Range" to "bytes=0-63","Accept-Encoding" to "identity"),token).use { r ->
    assertEquals(206,r.status);val prefix=r.body().use { input -> val b=ByteArray(64);var p=0;while(p<b.size){val n=input.read(b,p,b.size-p);if(n<0)break;p+=n};b.copyOf(p) };assertEquals(64,prefix.size)
    assertEquals("ftyp",String(prefix,4,4,Charsets.US_ASCII))
   }
  }
 }
}
