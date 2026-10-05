package com.example.purebrowser.download.hls

import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.TransferFailure

/**
 * Media-playlist segment container classification (v0.1.9 separate-audio HLS).
 *
 * Single seam for fMP4 (T96): the parser only CLASSIFIES a media playlist as [FMP4]
 * (via `#EXT-X-MAP` or `.m4s`/`.mp4`/`.m4a` segment addresses) instead of guessing a
 * transfer strategy, the download declaration carries the classified format, and
 * [requireTransferSupported] is the one enforcement point that keeps fMP4 out of
 * transfer until the fMP4 segment-assembly foundation (download/dash, T96) can extend
 * this enum with a transfer-capable branch. MPEG-TS stays the only transferable form.
 */
enum class SegmentFormat {
    MPEG_TS, FMP4;

    /** Honest unsupported gate for classified-but-not-yet-transferable segment formats. */
    fun requireTransferSupported() {
        if (this != MPEG_TS) throw TransferFailure(FailureKind.UNSUPPORTED,
            "本版暂不支持 fMP4 分片清单，待后续版本提供支持")
    }
}
