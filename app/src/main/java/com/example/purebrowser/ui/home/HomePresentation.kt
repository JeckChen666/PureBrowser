package com.example.purebrowser.ui.home

import com.example.purebrowser.data.browser.SavedPage
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.VideoAsset

internal const val HOME_RECENT_LIMIT = 3

/** Home is a bounded preview of existing local records, not another history/library store. */
internal fun recentHomeVisits(history: List<SavedPage>): List<SavedPage> =
    history.sortedByDescending { it.time }.take(HOME_RECENT_LIMIT)

internal fun recentHomeSaves(assets: List<VideoAsset>): List<VideoAsset> =
    assets.filter { it.availability == FileAvailability.AVAILABLE }
        .sortedByDescending { it.indexedAt }.take(HOME_RECENT_LIMIT)

/** Leave room for labels at larger system font scales; never force three columns on a phone. */
internal fun homeSiteColumns(widthDp: Float, fontScale: Float): Int =
    ((widthDp + 8f) / (136f * fontScale.coerceAtLeast(1f) + 8f)).toInt().coerceIn(1, 4)
