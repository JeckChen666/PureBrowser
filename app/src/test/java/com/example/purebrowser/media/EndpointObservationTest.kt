package com.example.purebrowser.media

import org.junit.Assert.*
import org.junit.Test

class EndpointObservationTest {
    @Test fun endpointHintsAreUnknownNotDirectFiles() {
        val sniffer=ResourceSniffer();val epoch=sniffer.beginPage()
        sniffer.observe(epoch,"https://cdn.example/stream/123?sig=secret",Evidence.REQUEST)
        assertEquals(MediaKind.UNKNOWN,sniffer.candidates.value.single().kind)
        assertTrue(sniffer.candidates.value.single().url.endsWith("sig=secret"))
        sniffer.observe(epoch,"https://cdn.example/stream/123?sig=secret",Evidence.PROBE,mimeType="video/mp4")
        assertEquals(MediaKind.FILE,sniffer.candidates.value.single().kind)
    }
    @Test fun weakHintsCannotStarveKnownMedia() {
        val sniffer=ResourceSniffer();val epoch=sniffer.beginPage()
        repeat(250){sniffer.observe(epoch,"https://cdn.example/stream/$it",Evidence.REQUEST)}
        assertEquals(32,sniffer.candidates.value.size)
        sniffer.observe(epoch,"https://cdn.example/movie.mp4",Evidence.REQUEST)
        assertEquals(33,sniffer.candidates.value.size)
    }
    @Test fun arbitraryAssetsAndStaleRequestsNotAdded() {
        val sniffer=ResourceSniffer();val old=sniffer.beginPage();val epoch=sniffer.beginPage()
        listOf("https://site.example/analytics", "https://cdn.example/photo.jpg", "https://cdn.example/seg.m4s").forEach { sniffer.observe(epoch,it,Evidence.REQUEST) }
        sniffer.observe(old,"https://cdn.example/stream/old",Evidence.REQUEST)
        assertTrue(sniffer.candidates.value.isEmpty())
    }
    @Test fun scriptEndpointsAndDeclaredMimeAreWeakNotVerified() {
        for(url in listOf("https://cdn.example/media.php?id=1","https://cdn.example/resource?mime=video%2Fmp4")) {
            val s=ResourceSniffer();val e=s.beginPage();s.observe(e,url,Evidence.REQUEST)
            assertEquals(MediaKind.UNKNOWN,s.candidates.value.single().kind)
        }
    }
    @Test fun rangeRequestsExposeOpaqueMediaEndpointsWithoutCopyingHeaders() {
        val s=ResourceSniffer();val e=s.beginPage()
        s.observe(e,"https://cdn.example/opaque-id",Evidence.REQUEST,requestHasRange=true)
        assertEquals(MediaKind.UNKNOWN,s.candidates.value.single().kind)
        s.observe(e,"https://cdn.example/seg.m4s",Evidence.REQUEST,requestHasRange=true)
        assertEquals(1,s.candidates.value.size)
    }
}
