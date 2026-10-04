package com.example.purebrowser.media

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.browser.BrowserEngine
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread

/** Self-authored HTML only; declared sources are never fetched or counted as real-site success. */
class DeclaredMediaObservationTest {
 private fun observe(body:String,check:(ResourceSniffer)->Unit) {
  val instrument=InstrumentationRegistry.getInstrumentation()
  val server=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
  val worker=thread(isDaemon=true){
   while(!server.isClosed)try{server.accept().use{socket->
    socket.soTimeout=3000;val reader=socket.getInputStream().bufferedReader()
    while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break}
    val bytes=body.toByteArray();socket.getOutputStream().apply{
     write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()
    }
   }}catch(_:SocketException){break}
  }
  val sniffer=ResourceSniffer();val engine=BrowserEngine(sniffer,{})
  var view:WebView?=null
  try{
   instrument.runOnMainSync{view=WebView(instrument.targetContext);engine.attach(view!!);engine.navigate("http://127.0.0.1:${server.localPort}/page")}
   val deadline=System.currentTimeMillis()+10000
   while(engine.page.value.progress!=100 && System.currentTimeMillis()<deadline)Thread.sleep(100)
   Thread.sleep(1500);instrument.runOnMainSync{engine.scanMedia()};Thread.sleep(1000)
   check(sniffer)
  }finally{instrument.runOnMainSync{view?.let(engine::detach)};server.close();worker.join(4000)}
 }
 @Test fun schemaDeclaredFileIsCapturedAsUntrustedAnalyzableCandidate() {
  observe("""<!doctype html><title>Authored metadata fixture</title><script type="application/ld+json">{"@type":"VideoObject","name":"Authored video","contentUrl":"https://cdn.example/movie.mp4","encodingFormat":"video/mp4"}</script>"""){s->
   val c=s.candidates.value.single{Evidence.METADATA in it.sources}
   assertEquals(MediaKind.UNKNOWN,c.kind);assertFalse(c.reliableSource);assertFalse(c.playing)
   assertEquals("https://cdn.example/movie.mp4",c.url);assertEquals("Authored video",c.title)
  }
 }
 @Test fun overBudgetSchemaAndNonVideoDeclarationsDoNotCreateMovies() {
  val huge="x".repeat(65537)
  observe("""<!doctype html><title>Bounded fixture</title><script type="application/ld+json">{"@type":"ImageObject","contentUrl":"https://cdn.example/picture.mp4"}</script><script type="application/ld+json">{"@type":"VideoObject","contentUrl":"https://cdn.example/movie.mp4","description":"$huge"}</script>"""){s->
   assertTrue(s.candidates.value.none{Evidence.METADATA in it.sources})
  }
 }
}
