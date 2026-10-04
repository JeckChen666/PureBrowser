package com.example.purebrowser.download

import org.junit.Assert.*
import org.junit.Test

class RequestPolicyProbedContextTest {
    private val page = "https://cdn.example.com/watch?episode=private"
    private val media = "https://cdn.example.com/video/movie.mp4?sig=secret"

    @Test fun sameOriginProbedPageMayOfferTheSessionToggle() { assertTrue(RequestPolicy.canUseProbedContext(media,page,null)) }
    @Test fun crossOriginPageOrMediaHidesTheSessionToggle() {
        assertFalse(RequestPolicy.canUseProbedContext("https://media.example.com/movie.mp4",page,null))
        assertFalse(RequestPolicy.canUseProbedContext(media,"https://media.example.com/watch",null))
        assertFalse(RequestPolicy.canUseProbedContext(media,page,"https://cdn.example.com:444/watch"))
    }
    @Test fun foreignFrameOrMissingPageNeverOffersTheSessionToggle() {
        assertFalse(RequestPolicy.canUseProbedContext(media,page,"https://foreign.example/frame"))
        assertFalse(RequestPolicy.canUseProbedContext(media,null,null))
    }
    @Test fun nonWebOrUntrustworthyPageAddressesNeverOfferTheSessionToggle() {
        listOf("about:blank","https://user:secret@cdn.example.com/watch","https://cdn.example.com:70000/watch","file:///tmp/watch.html")
            .forEach { assertFalse(RequestPolicy.canUseProbedContext(media,it,null)) }
    }
    @Test fun consentedProbedTaskReusesTheExistingRecordGateAndHeaderRules() {
        val record=DownloadRecord(recordId="probed",name="movie.mp4",transfer=TransferType.CONTROLLED,
            mediaUrl=media,sourceUrl=page,useAccessContext=true,reliableSource=true,userAgent="Agent")
        assertTrue(RequestPolicy.canUseContext(record.sourceUrl,record.frameUrl,record.reliableSource))
        val headers=RequestPolicy.headers(record,media,"session=demo")
        assertEquals("session=demo",headers["Cookie"])
        assertEquals("https://cdn.example.com/watch",headers["Referer"])
        assertFalse(headers.toString().contains("episode=private"))
        // A cross-origin resource under the same consent still gets no cookie, only a minimal referer.
        val foreign=record.copy(mediaUrl="https://media.example.com/movie.mp4")
        assertFalse(RequestPolicy.cookieEligible(foreign,foreign.mediaUrl!!))
        assertNull(RequestPolicy.headers(foreign,foreign.mediaUrl!!,"session=demo")["Cookie"])
        assertEquals("https://cdn.example.com/",RequestPolicy.headers(foreign,foreign.mediaUrl!!,"session=demo")["Referer"])
        // Declining the toggle keeps today's session-free behavior.
        assertFalse(RequestPolicy.headers(record.copy(useAccessContext=false),media,"session=demo").containsKey("Cookie"))
    }
    @Test fun credentialedProbedTaskStillCannotRedirectAcrossOrigins() {
        try { RequestPolicy.redirect(media,"https://media.example.com/movie.mp4",true,false);fail() }
        catch(e:TransferFailure) { assertEquals(FailureKind.ACCESS_CONDITION,e.kind) }
    }
}
