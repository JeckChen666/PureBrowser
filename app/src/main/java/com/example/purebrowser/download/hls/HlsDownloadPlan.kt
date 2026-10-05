package com.example.purebrowser.download.hls

/** URL-bearing snapshots are private and never printed. No authentication material belongs here. */
data class HlsDownloadPlan(
    val entryUrl:String,
    val playlistUrl:String,
    val media:HlsPlaylist.Media,
    val variant:HlsVariant?=null,
    /** Non-null when the variant's audio group carries a separate rendition: the v0.1.9 dual-track shape. */
    val audio:HlsAudioTrack?=null,
) { override fun toString()="HlsDownloadPlan(segments=${media.segments.size}, audioSegments=${audio?.media?.segments?.size ?: 0})" }

/** The selected audio rendition and its already-fetched, parser-verified media playlist. */
data class HlsAudioTrack(
    val playlistUrl:String,
    val media:HlsPlaylist.Media,
    val rendition:HlsAudioRendition,
) { override fun toString()="HlsAudioTrack(segments=${media.segments.size}, rendition=$rendition)" }

data class HlsOptions(val entryUrl:String,val finalUrl:String,val playlist:HlsPlaylist) {
    override fun toString()="HlsOptions()"
}
