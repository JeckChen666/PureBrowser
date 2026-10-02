package com.example.purebrowser.download.hls

/** URL-bearing snapshots are private and never printed. No authentication material belongs here. */
data class HlsDownloadPlan(
    val entryUrl:String,
    val playlistUrl:String,
    val media:HlsPlaylist.Media,
    val variant:HlsVariant?=null,
) { override fun toString()="HlsDownloadPlan(segments=${media.segments.size})" }
data class HlsOptions(val entryUrl:String,val finalUrl:String,val playlist:HlsPlaylist) {
    override fun toString()="HlsOptions()"
}
