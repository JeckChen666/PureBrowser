package com.example.purebrowser.ui.resources

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.purebrowser.download.*
import com.example.purebrowser.media.*
import com.example.purebrowser.media.resolver.*
import com.example.purebrowser.theme.PureBrowserTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class MediaAnalysisDialogTest {
 @get:Rule val compose=createComposeRule()
 private val draft=DownloadDraft(MediaCandidate("https://cdn.example/stream",MediaKind.UNKNOWN,setOf(Evidence.REQUEST)),"QA")
 @Test fun analysisIsExplicitAndReadyStillDoesNotDownload() {
  val calls=AtomicInteger();val accepted=mutableListOf<MediaCandidate>()
  val bytes=byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()+ByteArray(12)
  val probe=MediaProbe(HttpTransport { _,_,_->calls.incrementAndGet();object:HttpResponse{
   override val status=200;override fun header(name:String)=if(name=="Content-Length")bytes.size.toString() else null
   override fun body()=ByteArrayInputStream(bytes);override fun close(){}
  }})
  compose.setContent{PureBrowserTheme{MediaAnalysisDialog(draft,probe,{},accepted::add)}}
  compose.waitForIdle();assertEquals(0,calls.get());assertTrue(accepted.isEmpty())
  compose.onNodeWithTag("media-analysis-start").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithTag("media-analysis-ready").fetchSemanticsNodes().isNotEmpty()}
  assertEquals(1,calls.get());assertTrue(accepted.isEmpty())
  compose.onNodeWithTag("media-analysis-ready").performClick()
  assertEquals(MediaKind.FILE,accepted.single().kind)
 }
 @Test fun invalidResponseNeverShowsReadyAction() {
  val probe=MediaProbe(HttpTransport{_,_,_->object:HttpResponse{
   override val status=200;override fun header(name:String)=null
   override fun body()=ByteArrayInputStream("<html>not movie</html>".toByteArray());override fun close(){}
  }})
  compose.setContent{PureBrowserTheme{MediaAnalysisDialog(draft,probe,{}, {error("no ready")})}}
  compose.onNodeWithTag("media-analysis-start").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithText("响应未确认是完整视频容器或受支持 HLS；可能是网页、片段或其他格式").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("media-analysis-ready").assertDoesNotExist()
 }
}
