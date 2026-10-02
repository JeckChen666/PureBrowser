package com.example.purebrowser.privacy

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class PrivacyCategory { HISTORY, SITE_DATA, CACHE, DOWNLOAD_TEMP }

/** Fixed, non-sensitive outcomes; never display an exception's message to the user. */
enum class PrivacyClearResult(val message: String) {
    COMPLETED("所选项目已清理；已保存的视频未删除。"),
    REQUESTED("已请求清理；系统接口无法核实所有网站存储或缓存均已删除。已保存的视频未删除。"),
    UNAVAILABLE("此清理功能尚未接入。"),
    REQUIRES_STOPPED_ACTIVITY("请先停止所有网页、网站会话及下载活动，再清理。"),
    BUSY("另一项清理仍在进行，请稍后再试。"),
    FAILED("未能确认清理完成，部分数据可能已处理；请稍后重试。"),
}

/** Defaults fail closed. Stopped means joined/quiescent, not just a hidden screen or paused UI. */
data class PrivacyActivityState(
    val browserStopped: Boolean = false,
    val sessionsStopped: Boolean = false,
    val downloadsStopped: Boolean = false,
) {
    val isStopped: Boolean get() = browserStopped && sessionsStopped && downloadsStopped
}

/**
 * User-initiated, local-only cleanup. No work occurs in the constructor.
 *
 * Integration: keep one controller per browser/profile; call [clear] only after confirmation.
 * Before SITE_DATA, CACHE or DOWNLOAD_TEMP, stop browser/session activity and join all affected
 * managed writers. Quiesce current/background WebViews and service-worker network activity. Hold
 * the navigation/new-download exclusion throughout
 * the call; [activityState] is a fail-closed check, not a mechanism for stopping those owners.
 * Do not restart activity merely because this composable disappears. A REQUESTED result is only
 * the legacy SDK's request boundary, not proof that every storage technology was erased.
 *
 * [clearHistory] must persist only history removal, preserving tabs/bookmarks/settings.
 * [clearDownloadTemp] must run on IO and delete only app-owned stopped-task staging/resume caches.
 * Explicitly confirmed invalidation of stopped-task resume files is allowed: affected tasks must
 * be marked unable to continue and require re-download. Never delete active-task files, task
 * records or saved/public videos. Neither
 * callback is supplied by default; no recursive cache/files/public-Downloads deletion is done here.
 */
class LocalPrivacyController(
    private val activityState: () -> PrivacyActivityState = { PrivacyActivityState() },
    private val clearHistory: (suspend () -> Unit)? = null,
    private val websiteData: WebsiteData? = null,
    private val clearDownloadTemp: (suspend () -> Unit)? = null,
) {
    /** Small fakeable seam; implementations must await any callback before returning. */
    interface WebsiteData {
        suspend fun clearCookies()
        suspend fun requestStorageClear()
        suspend fun requestCacheClear()
    }

    private val clearing = Mutex()

    fun isAvailable(category: PrivacyCategory): Boolean = when (category) {
        PrivacyCategory.HISTORY -> clearHistory != null
        PrivacyCategory.SITE_DATA, PrivacyCategory.CACHE -> websiteData != null
        PrivacyCategory.DOWNLOAD_TEMP -> clearDownloadTemp != null
    }

    /** Concurrent requests are rejected, not queued for later unconfirmed destruction. */
    suspend fun clear(category: PrivacyCategory): PrivacyClearResult {
        currentCoroutineContext().ensureActive()
        if (!isAvailable(category)) return PrivacyClearResult.UNAVAILABLE
        if (!clearing.tryLock()) return PrivacyClearResult.BUSY
        try {
            if (category != PrivacyCategory.HISTORY && !activityState().isStopped) {
                return PrivacyClearResult.REQUIRES_STOPPED_ACTIVITY
            }
            return when (category) {
                PrivacyCategory.HISTORY -> {
                    clearHistory!!.invoke()
                    PrivacyClearResult.COMPLETED
                }
                PrivacyCategory.DOWNLOAD_TEMP -> {
                    clearDownloadTemp!!.invoke()
                    PrivacyClearResult.COMPLETED
                }
                PrivacyCategory.SITE_DATA -> withContext(NonCancellable) {
                    // Do not release exclusion while the non-cancellable platform request is live.
                    websiteData!!.clearCookies()
                    websiteData.requestStorageClear()
                    websiteData.requestCacheClear()
                    PrivacyClearResult.REQUESTED
                }
                PrivacyCategory.CACHE -> withContext(NonCancellable) {
                    websiteData!!.requestCacheClear()
                    PrivacyClearResult.REQUESTED
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Earlier steps may have succeeded; never claim failure implies no changes.
            return PrivacyClearResult.FAILED
        } finally {
            clearing.unlock()
        }
    }

    companion object {
        /**
         * Uses only framework APIs available at the app's min SDK (26). [webView] must be retained,
         * stopped and owned by this app's default WebView profile, never an external/destroyed view.
         * If all tabs were destroyed, pass a live, non-navigating maintenance WebView and destroy
         * it after clear returns. The owner may block service-worker network under its busy gate. Cache is
         * shared by WebViews, cookies/storage span all sites in that profile, NOT other apps.
         *
         * Legacy WebStorage/clearCache have no completion callback. This fallback intentionally
         * returns REQUESTED, not COMPLETED; no reflection, filesystem profile deletion, new WebKit
         * dependency, or unsupported promise to erase IndexedDB/service-worker/other stores.
         */
        fun forWebView(
            webView: WebView,
            activityState: () -> PrivacyActivityState,
            clearHistory: (suspend () -> Unit)? = null,
            clearDownloadTemp: (suspend () -> Unit)? = null,
        ): LocalPrivacyController = LocalPrivacyController(
            activityState = activityState,
            clearHistory = clearHistory,
            clearDownloadTemp = clearDownloadTemp,
            websiteData = object : WebsiteData {
                override suspend fun clearCookies() {
                    val cookies = withContext(Dispatchers.Main.immediate) {
                        val manager = CookieManager.getInstance()
                        suspendCoroutine<Unit> { continuation ->
                            // false means there were no cookies to remove, not failure.
                            manager.removeAllCookies { continuation.resume(Unit) }
                        }
                        manager
                    }
                    withContext(Dispatchers.IO) { cookies.flush() }
                }

                override suspend fun requestStorageClear() = withContext(Dispatchers.Main.immediate) {
                    WebStorage.getInstance().deleteAllData()
                }

                override suspend fun requestCacheClear() = withContext(Dispatchers.Main.immediate) {
                    webView.clearCache(true)
                }
            },
        )
    }
}
