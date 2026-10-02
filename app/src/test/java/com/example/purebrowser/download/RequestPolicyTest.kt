package com.example.purebrowser.download

import org.junit.Assert.*
import org.junit.Test

class RequestPolicyTest {
    private fun record()=DownloadRecord(recordId="test",name="test.mp4",transfer=TransferType.CONTROLLED,
        mediaUrl="https://site.example/media",sourceUrl="https://site.example/watch%2Fone?private=secret#fragment",frameUrl="https://site.example/frame",
        useAccessContext=true,reliableSource=true,userAgent="Agent")
    @Test fun defaultPortsAndHostCaseHaveSameOrigin() { assertTrue(RequestPolicy.sameOrigin("https://SITE.example/a","https://site.example:443/b")) }
    @Test fun differentSchemeAndPortAreNotSameOrigin() { assertFalse(RequestPolicy.sameOrigin("https://site.example","http://site.example"));assertFalse(RequestPolicy.sameOrigin("https://site.example","https://site.example:444")) }
    @Test fun userInfoNeverHasAnOrigin() { assertNull(RequestPolicy.origin("https://user:secret@site.example/video")) }
    @Test fun productionOnlyAllowsHttps() { try { RequestPolicy.validateUrl("http://127.0.0.1/video",false);fail() } catch(e:TransferFailure) { assertEquals(FailureKind.UNSUPPORTED,e.kind) } }
    @Test fun debugHttpIsLimitedToNamedLoopbacks() { RequestPolicy.validateUrl("http://127.0.0.1:8765/video",true);RequestPolicy.validateUrl("http://10.0.2.2/video",true);try { RequestPolicy.validateUrl("http://evil.example/video",true);fail() } catch(_:TransferFailure) {} }
    @Test fun invalidPortsSchemesAndVeryLongUrlsAreRejected() { listOf("https://site.example:70000/v","file:///tmp/a","https://site.example/"+"a".repeat(8192)).forEach { try { RequestPolicy.validateUrl(it,true);fail(it.take(30)) } catch(_:TransferFailure) {} } }
    @Test fun sameOriginRefererPreservesEscapedPathButRemovesSecrets() { assertEquals("https://site.example/watch%2Fone",RequestPolicy.headers(record(),"https://site.example/media","session=demo")["Referer"]) }
    @Test fun crossOriginGetsOriginRefererAndNoCookie() { val h=RequestPolicy.headers(record(),"https://cdn.example/media","session=demo");assertEquals("https://site.example/",h["Referer"]);assertFalse(h.containsKey("Cookie")) }
    @Test fun explicitDisableDropsBothCookieAndReferer() { val h=RequestPolicy.headers(record().copy(useAccessContext=false),"https://site.example/media","session=demo");assertFalse(h.containsKey("Cookie"));assertFalse(h.containsKey("Referer")) }
    @Test fun unreliableAndForeignFrameCannotGetSession() { listOf(record().copy(reliableSource=false),record().copy(frameUrl="https://foreign.example/frame")).forEach { assertFalse(RequestPolicy.headers(it,"https://site.example/v","s=demo").containsKey("Cookie")) } }
    @Test fun cookieIsOnlyAddedForSameOrigin() { assertEquals("session=demo",RequestPolicy.headers(record(),"https://site.example/media","session=demo")["Cookie"]) }
    @Test fun newlineAndOversizedCookieAreRejected() { listOf("s=a\r\nX-Bad: yes","a".repeat(16385)).forEach { try { RequestPolicy.headers(record(),"https://site.example/v",it);fail() } catch(e:TransferFailure) { assertEquals(FailureKind.ACCESS_CONDITION,e.kind) } } }
    @Test fun relativeRedirectKeepsSignedQueryExactly() { assertEquals("https://site.example/movie?token=a%2Bb",RequestPolicy.redirect("https://site.example/watch","/movie?token=a%2Bb",true,false)) }
    @Test fun credentialedCrossOriginRedirectIsBlocked() { try { RequestPolicy.redirect("https://site.example/v","https://cdn.example/v",true,false);fail() } catch(e:TransferFailure) { assertEquals(FailureKind.ACCESS_CONDITION,e.kind) } }
    @Test fun publicCrossOriginHttpsRedirectIsAllowed() { assertEquals("https://cdn.example/v",RequestPolicy.redirect("https://site.example/v","https://cdn.example/v",false,false)) }
    @Test fun httpsDowngradeAndCredentialRedirectTargetsAreRejected() { listOf("http://site.example/v","https://u:p@site.example/v").forEach { try { RequestPolicy.redirect("https://site.example/v",it,false,false);fail() } catch(_:TransferFailure) {} } }
    @Test fun exceptionsAndRecordsNeverPrintSignedUrlsOrHeaders() { assertFalse(record().toString().contains("secret"));assertFalse(TransferFailure(FailureKind.NETWORK,"网络失败").toString().contains("session")) }
    @Test fun initiallyForeignMediaCannotAcquireSourceCookieThroughRedirect() {
        val foreign=record().copy(mediaUrl="https://cdn.example/movie")
        assertFalse(RequestPolicy.cookieEligible(foreign,"https://site.example/state-changing"))
        assertNull(RequestPolicy.headers(foreign,"https://site.example/state-changing","session=demo")["Cookie"])
    }
}
