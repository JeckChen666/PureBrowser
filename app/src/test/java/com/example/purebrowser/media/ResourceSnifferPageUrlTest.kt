package com.example.purebrowser.media

import com.example.purebrowser.media.verify.ProbeResult
import org.junit.Assert.*
import org.junit.Test

class ResourceSnifferPageUrlTest {
    private val url = "https://cdn.example.com/v.mp4?sig=one"

    @Test fun verifiedProbeStampsThePageAndObservationsNeverDropIt() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,url,Evidence.REQUEST)
        assertNull(sniffer.candidates.value.single().pageUrl)
        sniffer.applyProbeResult(url,page,ProbeResult.Verified(5000L,true,"video/mp4",MediaKind.FILE,pageUrl="https://cdn.example.com/watch"))
        val verified=sniffer.candidates.value.single()
        assertEquals(ProbeState.VERIFIED,verified.probeState)
        assertEquals("https://cdn.example.com/watch",verified.pageUrl)
        sniffer.observe(page,url,Evidence.TIMING)
        val merged=sniffer.candidates.value.single()
        assertEquals(ProbeState.VERIFIED,merged.probeState)
        assertEquals("https://cdn.example.com/watch",merged.pageUrl)
        assertEquals(setOf(Evidence.REQUEST,Evidence.TIMING),merged.sources)
    }
    @Test fun mergedPageAssociationKeepsTheLatestNonNullValue() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,url,Evidence.REQUEST)
        sniffer.applyProbeResult(url,page,ProbeResult.Verified(5000L,true,"video/mp4",MediaKind.FILE,pageUrl="https://cdn.example.com/watch"))
        sniffer.applyProbeResult(url,page,ProbeResult.Verified(null,false,"video/mp4",MediaKind.FILE,pageUrl="https://cdn.example.com/watch2"))
        assertEquals("https://cdn.example.com/watch2",sniffer.candidates.value.single().pageUrl)
        // A verified outcome without a page never clears an established association.
        sniffer.applyProbeResult(url,page,ProbeResult.Verified(1L,false,"video/mp4",MediaKind.FILE))
        assertEquals("https://cdn.example.com/watch2",sniffer.candidates.value.single().pageUrl)
    }
    @Test fun unprobedAndFailedCandidatesKeepTodayBehavior() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,"https://cdn.example.com/other.mp4",Evidence.REQUEST)
        sniffer.observe(page,url,Evidence.REQUEST)
        sniffer.applyProbeResult(url,page,ProbeResult.Verified(5000L,true,"video/mp4",MediaKind.FILE,pageUrl="https://cdn.example.com/watch"))
        sniffer.applyProbeResult(url,page,ProbeResult.Unreachable)
        val failed=sniffer.candidates.value.first { it.url==url }
        assertEquals(ProbeState.FAILED,failed.probeState)
        assertEquals("https://cdn.example.com/watch",failed.pageUrl)
        assertNull(sniffer.candidates.value.first { it.url=="https://cdn.example.com/other.mp4" }.pageUrl)
    }
}
