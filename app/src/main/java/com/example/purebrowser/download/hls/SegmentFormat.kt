package com.example.purebrowser.download.hls

import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.TransferFailure

/**
 * Media-playlist segment container classification (v0.1.9 separate-audio HLS).
 *
 * Single seam for fMP4 (T96): the parser only CLASSIFIES a media playlist as [FMP4]
 * (via `#EXT-X-MAP` or `.m4s`/`.mp4`/`.m4a` segment addresses) instead of guessing a
 * transfer strategy, the download declaration carries the classified format, and
 * [requireTransferSupported] is the one enforcement point that decides which pipeline may
 * carry a classified format. MPEG-TS stays transferable everywhere.
 *
 * T107 opens fMP4 for both dual-track roles: each track's init + segments are assembled
 * through the DASH-proven [com.example.purebrowser.download.dash.Fmp4SegmentAssembler] and
 * the existing DualTrackMuxer, with the same budgets and cleanup semantics as TS tracks. The
 * single-track HLS transfer and its resumable TS-only workspace keep refusing fMP4 honestly.
 */
enum class SegmentFormat {
    MPEG_TS, FMP4;

    /** Which pipeline carries this media playlist; the honest fMP4 gate depends on it (T107). */
    enum class Role {
        /** One bare (muxed) media playlist through the TS-only single-track remux path. */
        SINGLE_TRACK,
        /** The video variant track of a separate-audio dual-track plan. */
        DUAL_TRACK_VIDEO,
        /** An independent audio rendition track of a separate-audio dual-track plan. */
        DUAL_TRACK_AUDIO,
    }

    /** Honest unsupported gate for classified-but-not-transferable segment containers. */
    fun requireTransferSupported(role: Role) {
        if (this == MPEG_TS) return
        if (role == Role.SINGLE_TRACK)
            throw TransferFailure(FailureKind.UNSUPPORTED, "本版暂不支持 fMP4 分片清单，待后续版本提供支持")
    }
}
