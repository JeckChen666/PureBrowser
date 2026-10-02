package com.example.purebrowser.media

import org.junit.Assert.*
import org.junit.Test

class ResourceSnifferTest {
    @Test fun deduplicatesEvidenceWithoutRemovingSignatures() {
        val sniffer = ResourceSniffer()
        val page = sniffer.beginPage()
        sniffer.observe(page, "https://cdn.example/v.mp4?sig=one", Evidence.REQUEST)
        sniffer.observe(page, "https://cdn.example/v.mp4?sig=one#ignored", Evidence.DOM)
        sniffer.observe(page, "https://cdn.example/v.mp4?sig=two", Evidence.REQUEST)
        assertEquals(2, sniffer.candidates.value.size)
        assertEquals(setOf(Evidence.REQUEST, Evidence.DOM), sniffer.candidates.value.first().sources)
        assertTrue(sniffer.candidates.value.first().url.endsWith("?sig=one"))
    }
    @Test fun oldNavigationCannotRepopulateCurrentResources() {
        val sniffer = ResourceSniffer()
        val old = sniffer.beginPage()
        sniffer.observe(old, "https://cdn.example/old.mp4", Evidence.REQUEST)
        val current = sniffer.beginPage()
        sniffer.observe(old, "https://cdn.example/late.mp4", Evidence.DOM)
        assertTrue(sniffer.candidates.value.isEmpty())
        sniffer.observe(current, "https://cdn.example/new.mp4", Evidence.REQUEST)
        assertEquals("new.mp4", sniffer.candidates.value.single().displayName)
    }
    @Test fun candidateStoreIsBounded() {
        val sniffer = ResourceSniffer()
        val page = sniffer.beginPage()
        repeat(300) { sniffer.observe(page, "https://cdn.example/video$it.mp4", Evidence.REQUEST) }
        assertEquals(200, sniffer.candidates.value.size)
    }

    @org.junit.Test fun playingDomPrecedesOtherEvidenceAndSameUrlMerges() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,"https://site.example/a.mp4?sig=a",Evidence.REQUEST)
        sniffer.observe(page,"https://site.example/b.mp4?sig=b",Evidence.DOM,videoElement=true,playing=true,frameUrl="https://site.example/watch",reliableSource=true)
        sniffer.observe(page,"https://site.example/a.mp4?sig=a",Evidence.DOM,videoElement=true,title="Movie")
        org.junit.Assert.assertEquals("https://site.example/b.mp4?sig=b",sniffer.candidates.value.first().url)
        org.junit.Assert.assertEquals(2,sniffer.candidates.value.size)
        org.junit.Assert.assertEquals(setOf(Evidence.REQUEST,Evidence.DOM),sniffer.candidates.value.last().sources)
    }
    @org.junit.Test fun differentSignaturesAreNeverStrippedAndOldGenerationCannotPollute() {
        val sniffer=ResourceSniffer();val old=sniffer.beginPage();val page=sniffer.beginPage()
        sniffer.observe(old,"https://site.example/old.mp4",Evidence.DOM)
        listOf("a%2Bb","c%2Fd").forEach { sniffer.observe(page,"https://site.example/a.mp4?sig=$it",Evidence.REQUEST) }
        org.junit.Assert.assertEquals(2,sniffer.candidates.value.size)
        org.junit.Assert.assertTrue(sniffer.candidates.value.none { it.url.contains("old") })
    }

    @org.junit.Test fun newScanClearsStalePlaybackWithoutLosingHistoricalEvidence() {
        val s=ResourceSniffer();val e=s.beginPage();val a="https://site.example/a.mp4";val b="https://site.example/b.mp4"
        s.observe(e,a,Evidence.DOM,playing=true);s.observe(e,a,Evidence.TIMING)
        org.junit.Assert.assertTrue(s.candidates.value.single().playing)
        s.observe(e,b,Evidence.DOM,playing=true);s.updatePlayback(e,setOf(b))
        org.junit.Assert.assertEquals(b,s.candidates.value.first().url)
        org.junit.Assert.assertFalse(s.candidates.value.first { it.url==a }.playing)
    }
}
