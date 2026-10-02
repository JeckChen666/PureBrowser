package com.example.purebrowser.download.hls

import org.junit.Assert.*
import org.junit.Test

class HlsResumePlaylistTest {
    private fun media(sequence: String = ""): HlsPlaylist.Media = HlsPlaylistParser.parse(
        "#EXTM3U\n#EXT-X-TARGETDURATION:2\n$sequence#EXTINF:2,\npiece.ts\n#EXT-X-ENDLIST\n",
        "https://example.test/media.m3u8",
    ) as HlsPlaylist.Media

    @Test fun parserFreezesValidatedSequenceAndDefaultsOnlyAbsentTagsToZero() {
        assertEquals(0L, media().mediaSequence)
        assertEquals(1234L, media("#EXT-X-MEDIA-SEQUENCE:1234\n").mediaSequence)
        assertEquals(Long.MAX_VALUE, media("#EXT-X-MEDIA-SEQUENCE:${Long.MAX_VALUE}\n").mediaSequence)
    }

    @Test fun fullMediaEqualityAndHashBindSequenceWithoutChangingDefaultConstructors() {
        val frozen = media("#EXT-X-MEDIA-SEQUENCE:7\n")
        val changed = media("#EXT-X-MEDIA-SEQUENCE:8\n")
        assertNotEquals(frozen, changed); assertNotEquals(frozen.hashCode(), changed.hashCode())
        assertEquals(media(), HlsPlaylist.Media(media().segments, 2_000_000, 2_000_000))
        assertEquals(frozen, HlsPlaylist.Media(frozen.segments, 2_000_000, 2_000_000, 7))
    }
}
