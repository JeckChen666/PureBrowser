package com.example.purebrowser.media.fingerprint

import org.junit.Assert.*
import org.junit.Test

class FamilyDetectorTest {
    @Test fun flashvarsPrefixOutranksAllOtherFamilies() {
        assertEquals(PlayerFamily.FLASHVARS, FamilyDetector.detect(setOf("kvsplayer", "html5player", "flashvars_123456")))
    }

    @Test fun eachKnownGlobalMapsToItsFamily() {
        assertEquals(PlayerFamily.KVS, FamilyDetector.detect(setOf("kvsplayer")))
        assertEquals(PlayerFamily.HTML5PLAYER, FamilyDetector.detect(setOf("html5player")))
        assertEquals(PlayerFamily.XPLAYER, FamilyDetector.detect(setOf("xplayerSettings")))
        assertEquals(PlayerFamily.STREAM_DATA, FamilyDetector.detect(setOf("stream_data")))
    }

    @Test fun dottedHarvestPathsMatchOnTheirFinalSegment() {
        assertEquals(PlayerFamily.XPLAYER, FamilyDetector.detect(setOf("initials.xplayerSettings")))
        assertEquals(PlayerFamily.FLASHVARS, FamilyDetector.detect(setOf("window.flashvars_9")))
    }

    @Test fun earlierFamiliesWinWhenSeveralMatchesArePresent() {
        assertEquals(PlayerFamily.KVS, FamilyDetector.detect(setOf("stream_data", "kvsplayer")))
        assertEquals(PlayerFamily.XPLAYER, FamilyDetector.detect(setOf("stream_data", "xplayerSettings")))
        assertEquals(PlayerFamily.HTML5PLAYER, FamilyDetector.detect(setOf("xplayerSettings", "html5player")))
    }

    @Test fun unknownWhenNothingMatches() {
        assertEquals(PlayerFamily.UNKNOWN, FamilyDetector.detect(emptySet()))
        assertEquals(PlayerFamily.UNKNOWN, FamilyDetector.detect(setOf("jQuery", "MY_PLAYER")))
    }

    @Test fun matchingIsCaseInsensitive() {
        assertEquals(PlayerFamily.KVS, FamilyDetector.detect(setOf("KVSPlayer")))
        assertEquals(PlayerFamily.STREAM_DATA, FamilyDetector.detect(setOf("STREAM_DATA")))
    }
}
