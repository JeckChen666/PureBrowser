package com.example.purebrowser.media.verify

import com.example.purebrowser.download.hls.HlsPlaylistParser
import com.example.purebrowser.media.resolver.MediaProbe

/** Immutable budget for background auto-verification; one policy for every queue instance. */
object ProbePolicy {
    /** Anonymous cheap probes allowed per navigation epoch. */
    const val MAX_PROBES_PER_EPOCH = 20
    /** Master-playlist text fetches allowed per navigation epoch. */
    const val MAX_PLAYLIST_FETCHES_PER_EPOCH = 3
    /** Playlist text is bounded by the same cap the HLS HTTP client applies (2 MiB). */
    const val PLAYLIST_BYTE_CAP = HlsPlaylistParser.MAX_PLAYLIST_BYTES
    /** Container sniffing stays delegated to the explicit MediaProbe prefix limit (64 KiB). */
    const val PREFIX_BYTE_CAP = MediaProbe.PREFIX_LIMIT
    /** A single candidate URL is not re-probed within this window. */
    const val COOLDOWN_MS = 15_000L
    /** HLS observations are batched for this long before master playlists are fetched. */
    const val DEBOUNCE_MS = 150L
    /** Redirect bound for every auto-verification request chain. */
    const val MAX_REDIRECTS = 3
}
