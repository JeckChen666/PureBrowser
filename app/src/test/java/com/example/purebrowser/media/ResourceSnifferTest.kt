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
    // v0.1.7 S-E leftover: a master whose URL ends .mp4 (no m3u8 marker anywhere) is pinned FILE
    // at first sight; the probe's served Content-Type must correct the kind so the HLS path runs.
    @org.junit.Test fun applyProbeResultUpgradesMp4SuffixedMasterToHlsOnVerifiedMime() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,"https://cdn.example/media/hls4/multi=1920x1080:1080p/_TPL_.mp4",Evidence.REQUEST)
        org.junit.Assert.assertEquals(MediaKind.FILE,sniffer.candidates.value.single().kind)
        val variants=listOf(
            VariantSummary(1080,3_000_000L,"avc1.640028,mp4a.40.2","https://cdn.example/gear1080.m3u8",null),
            VariantSummary(720,1_800_000L,"avc1.4d401f,mp4a.40.2","https://cdn.example/gear720.m3u8",null),
            VariantSummary(480,900_000L,"avc1.4d401e,mp4a.40.2","https://cdn.example/gear480.m3u8",null))
        sniffer.applyProbeResult("https://cdn.example/media/hls4/multi=1920x1080:1080p/_TPL_.mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(null,false,"application/vnd.apple.mpegurl",MediaKind.HLS,variants))
        val upgraded=sniffer.candidates.value.single()
        org.junit.Assert.assertEquals(MediaKind.HLS,upgraded.kind)
        org.junit.Assert.assertEquals("application/vnd.apple.mpegurl",upgraded.mimeType)
        org.junit.Assert.assertEquals("application/vnd.apple.mpegurl",upgraded.verifiedMime)
        org.junit.Assert.assertEquals(3,upgraded.variants!!.size)
        org.junit.Assert.assertEquals(1080,upgraded.variants!!.first().height)
        // A late re-sighting of the same address must not revert the corrected kind to FILE.
        sniffer.observe(page,"https://cdn.example/media/hls4/multi=1920x1080:1080p/_TPL_.mp4",Evidence.TIMING)
        val reseen=sniffer.candidates.value.single{it.url.contains("_TPL_.mp4")}
        org.junit.Assert.assertEquals(MediaKind.HLS,reseen.kind)
        org.junit.Assert.assertEquals(3,reseen.variants!!.size)
        org.junit.Assert.assertEquals(setOf(Evidence.REQUEST,Evidence.TIMING),reseen.sources)
        // A real video/mp4 answer keeps the FILE routing and never invents a manifest.
        sniffer.observe(page,"https://cdn.example/real.mp4",Evidence.REQUEST)
        sniffer.applyProbeResult("https://cdn.example/real.mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(6_180_220L,true,"video/mp4",MediaKind.FILE))
        val keptFile=sniffer.candidates.value.first{it.url=="https://cdn.example/real.mp4"}
        org.junit.Assert.assertEquals(MediaKind.FILE,keptFile.kind)
        org.junit.Assert.assertEquals("video/mp4",keptFile.verifiedMime)
        org.junit.Assert.assertNull(keptFile.variants)
        // Without a served mpegurl mime the suffix correction stays off: kind follows evidence only.
        sniffer.observe(page,"https://cdn.example/no-mime.mp4",Evidence.REQUEST)
        sniffer.applyProbeResult("https://cdn.example/no-mime.mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(null,false,null,MediaKind.HLS))
        org.junit.Assert.assertEquals(MediaKind.FILE,sniffer.candidates.value.first{it.url=="https://cdn.example/no-mime.mp4"}.kind)
    }

    // T76 ranking weighting: primary signals break ties WITHIN an evidence tier only.
    @org.junit.Test fun variantMasterOutranksPreviewFileWithinTheSameEvidenceTier() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        // Correct-but-not-main preview file (T61 跟进2 shape): 10 MB progressive file.
        sniffer.observe(page,"https://cdn.example/preview.mp4",Evidence.REQUEST,sizeBytes=10_048_775L,mimeType="video/mp4")
        sniffer.applyProbeResult("https://cdn.example/preview.mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(10_048_775L,true,"video/mp4",MediaKind.FILE))
        // Main video: master manifest with a 720p first variant.
        sniffer.observe(page,"https://cdn.example/master.m3u8",Evidence.REQUEST,mimeType="application/vnd.apple.mpegurl")
        sniffer.applyProbeResult("https://cdn.example/master.m3u8",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(145L,false,"application/vnd.apple.mpegurl",MediaKind.HLS,
                listOf(VariantSummary(720,800_000L,null,"https://cdn.example/gear-720.m3u8",null))))
        val order=sniffer.candidates.value
        org.junit.Assert.assertEquals("https://cdn.example/master.m3u8",order.first().url)
        org.junit.Assert.assertEquals("https://cdn.example/preview.mp4",order.last().url)
    }
    @org.junit.Test fun tinyLibraryFilesGetNoLengthBonusWithinTier() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,"https://cdn.example/library.mp4",Evidence.REQUEST,sizeBytes=57_344L,mimeType="video/mp4")
        sniffer.observe(page,"https://cdn.example/full.mp4",Evidence.REQUEST,sizeBytes=900_000_000L,mimeType="video/mp4")
        // 57 KB < 5 MiB scores nothing; the in-window file leads despite being observed second.
        org.junit.Assert.assertEquals("https://cdn.example/full.mp4",sniffer.candidates.value.first().url)
        org.junit.Assert.assertEquals("https://cdn.example/library.mp4",sniffer.candidates.value.last().url)
    }
    @org.junit.Test fun rankingIsUnchangedWhenPrimarySignalFieldsAreAbsent() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,"https://cdn.example/a.mp4",Evidence.REQUEST)
        sniffer.observe(page,"https://cdn.example/b.mp4",Evidence.REQUEST)
        // Equal tier, zero signal, equal probe state: insertion order survives (stable sort).
        org.junit.Assert.assertEquals(listOf("https://cdn.example/a.mp4","https://cdn.example/b.mp4"),
            sniffer.candidates.value.map{it.url})
        sniffer.observe(page,"https://cdn.example/b.mp4",Evidence.TIMING)
        org.junit.Assert.assertEquals(listOf("https://cdn.example/a.mp4","https://cdn.example/b.mp4"),
            sniffer.candidates.value.map{it.url})
    }
    @org.junit.Test fun sALikeQualityMastersOutrankTheRequestTierPreviewWithinDomTier() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        // Four DOM-evidence quality masters (240/480/720/1080), each with one ladder variant.
        listOf(240,480,720,1080).forEach { height ->
            sniffer.observe(page,"https://site.example/q/$height/index.m3u8",Evidence.DOM,mimeType="application/vnd.apple.mpegurl")
            sniffer.applyProbeResult("https://site.example/q/$height/index.m3u8",page,
                com.example.purebrowser.media.verify.ProbeResult.Verified(145L,false,"application/vnd.apple.mpegurl",MediaKind.HLS,
                    listOf(VariantSummary(height,1_000_000L,null,"https://site.example/q/$height/gear.m3u8",null))))
        }
        // The 10 MB REQUEST-tier preview file has a +2 length signal but must never cross tiers.
        sniffer.observe(page,"https://site.example/preview.mp4",Evidence.REQUEST,sizeBytes=10_048_775L,mimeType="video/mp4")
        sniffer.applyProbeResult("https://site.example/preview.mp4",page,
            com.example.purebrowser.media.verify.ProbeResult.Verified(10_048_775L,true,"video/mp4",MediaKind.FILE))
        val order=sniffer.candidates.value
        org.junit.Assert.assertEquals(5,order.size)
        org.junit.Assert.assertTrue(order.take(4).all { it.kind==MediaKind.HLS && it.variants!=null })
        // Inside the DOM tier the >=480p masters lead; the 240p one (no variant bonus) trails them.
        org.junit.Assert.assertTrue(order.first().variants!!.first().height!!>=480)
        org.junit.Assert.assertEquals(240,order[3].variants!!.first().height)
        org.junit.Assert.assertEquals(MediaKind.FILE,order.last().kind)
    }
    @org.junit.Test fun ruleEvidenceAdmitsExtensionlessUrlsAsUnknownAndRanksAboveDownloadTier() {
        val sniffer=ResourceSniffer();val page=sniffer.beginPage()
        sniffer.observe(page,"https://site.example/dl",Evidence.DOWNLOAD,mimeType="video/mp4")
        sniffer.observe(page,"https://api.example/video.get?id=1",Evidence.RULE,title="Clip")
        val list=sniffer.candidates.value
        org.junit.Assert.assertEquals(2,list.size)
        org.junit.Assert.assertEquals("https://api.example/video.get?id=1",list.first().url)
        org.junit.Assert.assertEquals(MediaKind.UNKNOWN,list.first{it.url.startsWith("https://api")}.kind)
        org.junit.Assert.assertEquals(setOf(Evidence.RULE),list.first{it.url.startsWith("https://api")}.sources)
        org.junit.Assert.assertEquals("Clip",list.first{it.url.startsWith("https://api")}.title)
        // DOM evidence still outranks the rules tier.
        sniffer.observe(page,"https://play.example/v.mp4",Evidence.DOM,videoElement=true,playing=true)
        org.junit.Assert.assertEquals("https://play.example/v.mp4",sniffer.candidates.value.first().url)
    }
    @org.junit.Test fun recentRequestsRecordRawGetsIncludingUnclassifiedEndpointsAndResetPerEpoch() {
        val sniffer=ResourceSniffer()
        val old=sniffer.beginPage()
        sniffer.observe(old,"https://api.example/method/video.get?v=1",Evidence.REQUEST)
        sniffer.observe(old,"https://img.example/a.jpg",Evidence.DOM)
        sniffer.observe(old,"ftp://not-http.example/x",Evidence.REQUEST)
        org.junit.Assert.assertEquals(listOf("https://api.example/method/video.get?v=1"),sniffer.recentRequests())
        val page=sniffer.beginPage()
        org.junit.Assert.assertTrue(sniffer.recentRequests().isEmpty())
        sniffer.observe(old,"https://stale.example/late.mp4",Evidence.REQUEST)
        sniffer.observe(page,"https://cdn.example/video1234/title/",Evidence.REQUEST)
        org.junit.Assert.assertEquals(listOf("https://cdn.example/video1234/title/"),sniffer.recentRequests())
    }
}
