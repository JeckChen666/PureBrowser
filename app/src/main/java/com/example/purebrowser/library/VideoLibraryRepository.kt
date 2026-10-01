package com.example.purebrowser.library

import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.download.DownloadState
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.VideoAsset

/** Read view over one authoritative store. A missing, previously checked file remains visible as missing. */
class VideoLibraryRepository(private val downloads: DownloadRepository) {
    fun entries(state: DownloadState): List<VideoAsset> = state.assets.filter { it.format == FormatCheck.PASSED }
    fun snapshot(): List<VideoAsset> = entries(downloads.stateSnapshot())
}
