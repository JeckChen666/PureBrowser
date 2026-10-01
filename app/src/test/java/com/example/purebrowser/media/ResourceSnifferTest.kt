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
}
