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

    @org.junit.Test fun autoVerifyHookFiresOncePerUrlPerEpochForHttpMediaOnly() {
        val sniffer=ResourceSniffer()
        val calls=mutableListOf<Triple<String,MediaKind,Long>>()
        sniffer.autoVerifyHook={url,kind,epoch->calls+=Triple(url,kind,epoch)}
        val page=sniffer.beginPage()
        sniffer.observe(page,"https://cdn.example/v.mp4?sig=one",Evidence.REQUEST)
        sniffer.observe(page,"https://cdn.example/v.mp4?sig=one#frag",Evidence.DOM,videoElement=true)
        sniffer.observe(page,"https://cdn.example/manifest.mpd",Evidence.REQUEST)
        sniffer.observe(page,"blob:local-media",Evidence.DOM,videoElement=true)
        org.junit.Assert.assertEquals(listOf("https://cdn.example/v.mp4?sig=one","https://cdn.example/manifest.mpd"),calls.map{it.first})
        org.junit.Assert.assertEquals(listOf(MediaKind.FILE,MediaKind.DASH),calls.map{it.second})
        org.junit.Assert.assertEquals(ProbeState.PENDING,sniffer.candidates.value.first{it.url.contains("v.mp4")}.probeState)
        val next=sniffer.beginPage()
        sniffer.observe(next,"https://cdn.example/v.mp4?sig=one",Evidence.REQUEST)
        org.junit.Assert.assertEquals(3,calls.size)
        org.junit.Assert.assertEquals(next,calls[2].third)
    }

    @org.junit.Test fun applyProbeResultVerifiesFieldsAndKeepsThemAcrossMerges() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,"https://cdn.example/b.mp4",Evidence.REQUEST)
        sniffer.observe(page,"https://cdn.example/a.mp4",Evidence.REQUEST)
        sniffer.applyProbeResult("https://cdn.example/a.mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(5000L,true,"video/mp4",MediaKind.FILE))
        val verified=sniffer.candidates.value.first()
        org.junit.Assert.assertEquals("https://cdn.example/a.mp4",verified.url)
        org.junit.Assert.assertEquals(ProbeState.VERIFIED,verified.probeState)
        org.junit.Assert.assertEquals(5000L,verified.totalBytes)
        org.junit.Assert.assertEquals(true,verified.resumable)
        org.junit.Assert.assertEquals("video/mp4",verified.verifiedMime)
        sniffer.observe(page,"https://cdn.example/a.mp4",Evidence.TIMING)
        val merged=sniffer.candidates.value.first{it.url=="https://cdn.example/a.mp4"}
        org.junit.Assert.assertEquals(ProbeState.VERIFIED,merged.probeState)
        org.junit.Assert.assertEquals(5000L,merged.totalBytes)
        org.junit.Assert.assertEquals(setOf(Evidence.REQUEST,Evidence.TIMING),merged.sources)
    }

    @org.junit.Test fun applyProbeResultUpgradesUnknownToHlsWithVariantsAndFailsHonestly() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,"https://cdn.example/get_media?mime=video/mp4",Evidence.REQUEST)
        val variants=listOf(VariantSummary(720,800000L,"avc1.4d401f,mp4a.40.2","https://cdn.example/gear1.m3u8",null))
        sniffer.applyProbeResult("https://cdn.example/get_media?mime=video/mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(null,false,"application/vnd.apple.mpegurl",MediaKind.HLS,variants))
        val upgraded=sniffer.candidates.value.single()
        org.junit.Assert.assertEquals(MediaKind.HLS,upgraded.kind)
        org.junit.Assert.assertEquals("application/vnd.apple.mpegurl",upgraded.mimeType)
        org.junit.Assert.assertEquals(1,upgraded.variants!!.size)
        sniffer.applyProbeResult("https://cdn.example/get_media?mime=video/mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Unreachable)
        val failed=sniffer.candidates.value.single()
        org.junit.Assert.assertEquals(ProbeState.FAILED,failed.probeState)
        org.junit.Assert.assertEquals(1,failed.variants!!.size)
        // Stale epochs and unknown URLs never touch the store.
        sniffer.applyProbeResult("https://cdn.example/unknown.mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(1L,false,null,null))
        sniffer.applyProbeResult("https://cdn.example/a.mp4",page+9,
            com.example.purebrowser.media.verify.ProbeResult.Verified(1L,false,null,null))
        org.junit.Assert.assertNull(sniffer.candidates.value.firstOrNull{it.url=="https://cdn.example/unknown.mp4"})
        org.junit.Assert.assertNull(sniffer.candidates.value.firstOrNull{it.url=="https://cdn.example/a.mp4"})
    }
}
