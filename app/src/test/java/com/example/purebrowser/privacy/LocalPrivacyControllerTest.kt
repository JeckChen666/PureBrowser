package com.example.purebrowser.privacy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPrivacyControllerTest {
    private val stopped = PrivacyActivityState(true, true, true)

    private class Website : LocalPrivacyController.WebsiteData {
        val calls = mutableListOf<String>()
        var cookieGate: CompletableDeferred<Unit>? = null
        var failStorage = false
        override suspend fun clearCookies() {
            calls += "cookies-start"
            cookieGate?.await()
            calls += "cookies-finished"
        }
        override suspend fun requestStorageClear() {
            calls += "storage"
            if (failStorage) error("https://secret.example/?auth=secret")
        }
        override suspend fun requestCacheClear() { calls += "cache" }
    }

    @Test fun noCallbacksMeansUnavailableAndNoConstructorWork() = runTest {
        val controller = LocalPrivacyController()
        PrivacyCategory.entries.forEach {
            assertFalse(controller.isAvailable(it))
            assertEquals(PrivacyClearResult.UNAVAILABLE, controller.clear(it))
        }
    }

    @Test fun historyDoesNotRequireStoppingAndTouchesOnlyHistory() = runTest {
        val website = Website()
        var historyCalls = 0
        var tempCalls = 0
        val controller = LocalPrivacyController(
            clearHistory = { historyCalls++ }, websiteData = website, clearDownloadTemp = { tempCalls++ },
        )
        assertEquals(0, historyCalls)
        assertEquals(PrivacyClearResult.COMPLETED, controller.clear(PrivacyCategory.HISTORY))
        assertEquals(1, historyCalls)
        assertEquals(0, tempCalls)
        assertTrue(website.calls.isEmpty())
    }

    @Test fun allThreeActivityOwnersMustBeStoppedForEachDestructiveProfileOperation() = runTest {
        val states = listOf(
            PrivacyActivityState(), PrivacyActivityState(false, true, true),
            PrivacyActivityState(true, false, true), PrivacyActivityState(true, true, false),
        )
        for (state in states) {
            val website = Website()
            var tempCalls = 0
            val controller = LocalPrivacyController(
                activityState = { state }, websiteData = website, clearDownloadTemp = { tempCalls++ },
            )
            listOf(PrivacyCategory.SITE_DATA, PrivacyCategory.CACHE, PrivacyCategory.DOWNLOAD_TEMP).forEach {
                assertEquals(PrivacyClearResult.REQUIRES_STOPPED_ACTIVITY, controller.clear(it))
            }
            assertTrue(website.calls.isEmpty())
            assertEquals(0, tempCalls)
        }
    }

    @Test fun absentActivityStateFailsClosedEvenWithBackend() = runTest {
        val website = Website()
        val controller = LocalPrivacyController(websiteData = website)
        assertTrue(controller.isAvailable(PrivacyCategory.SITE_DATA))
        assertEquals(PrivacyClearResult.REQUIRES_STOPPED_ACTIVITY, controller.clear(PrivacyCategory.SITE_DATA))
        assertTrue(website.calls.isEmpty())
    }

    @Test fun activityStateIsCheckedAtActionTimeNotConstruction() = runTest {
        var state = stopped
        val website = Website()
        val controller = LocalPrivacyController(activityState = { state }, websiteData = website)
        state = PrivacyActivityState()
        assertEquals(PrivacyClearResult.REQUIRES_STOPPED_ACTIVITY, controller.clear(PrivacyCategory.SITE_DATA))
        state = stopped
        assertEquals(PrivacyClearResult.REQUESTED, controller.clear(PrivacyCategory.SITE_DATA))
    }

    @Test fun siteDataAwaitsCookiesThenRequestsStorageAndCacheWithoutTouchingOtherCategories() = runTest {
        val website = Website().apply { cookieGate = CompletableDeferred() }
        val controller = LocalPrivacyController(
            activityState = { stopped }, websiteData = website,
            clearHistory = { error("Must not touch history") },
            clearDownloadTemp = { error("Must not touch downloads") },
        )
        val result = async { controller.clear(PrivacyCategory.SITE_DATA) }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(listOf("cookies-start"), website.calls)
        website.cookieGate!!.complete(Unit)
        assertEquals(PrivacyClearResult.REQUESTED, result.await())
        assertEquals(listOf("cookies-start", "cookies-finished", "storage", "cache"), website.calls)
    }

    @Test fun cacheOnlyDoesNotRemoveCookiesStorageHistoryOrDownloads() = runTest {
        val website = Website()
        val controller = LocalPrivacyController(
            activityState = { stopped }, websiteData = website,
            clearHistory = { error("history") }, clearDownloadTemp = { error("temp") },
        )
        assertEquals(PrivacyClearResult.REQUESTED, controller.clear(PrivacyCategory.CACHE))
        assertEquals(listOf("cache"), website.calls)
    }

    @Test fun tempCleanupIsOnlyExplicitOwnerCallbackAndAwaitsIt() = runTest {
        val website = Website()
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val controller = LocalPrivacyController(
            activityState = { stopped }, websiteData = website,
            clearDownloadTemp = { gate.await(); finished = true },
        )
        val result = async { controller.clear(PrivacyCategory.DOWNLOAD_TEMP) }
        runCurrent()
        assertFalse(result.isCompleted)
        assertFalse(finished)
        assertTrue(website.calls.isEmpty())
        gate.complete(Unit)
        assertEquals(PrivacyClearResult.COMPLETED, result.await())
        assertTrue(finished)
    }

    @Test fun concurrentActionsAreRejectedNotQueued() = runTest {
        val gate = CompletableDeferred<Unit>()
        var tempCalls = 0
        val controller = LocalPrivacyController(
            activityState = { stopped }, clearHistory = { gate.await() }, clearDownloadTemp = { tempCalls++ },
        )
        val first = async { controller.clear(PrivacyCategory.HISTORY) }
        runCurrent()
        assertEquals(PrivacyClearResult.BUSY, controller.clear(PrivacyCategory.DOWNLOAD_TEMP))
        gate.complete(Unit)
        first.await()
        assertEquals(0, tempCalls)
        assertEquals(PrivacyClearResult.COMPLETED, controller.clear(PrivacyCategory.DOWNLOAD_TEMP))
        assertEquals(1, tempCalls)
    }

    @Test fun partialFailuresNeverClaimNoChangesAndDoNotExposeExceptionText() = runTest {
        val website = Website().apply { failStorage = true }
        val controller = LocalPrivacyController(activityState = { stopped }, websiteData = website)
        val result = controller.clear(PrivacyCategory.SITE_DATA)
        assertEquals(PrivacyClearResult.FAILED, result)
        assertFalse(result.message.contains("secret"))
        assertEquals(listOf("cookies-start", "cookies-finished", "storage"), website.calls)
        website.failStorage = false
        assertEquals(PrivacyClearResult.REQUESTED, controller.clear(PrivacyCategory.SITE_DATA))
    }

    @Test fun cancellingScreenCannotReleaseLockBeforeWebsiteCallbackFinishes() = runTest {
        val website = Website().apply { cookieGate = CompletableDeferred() }
        val controller = LocalPrivacyController(activityState = { stopped }, websiteData = website)
        val operation = launch { controller.clear(PrivacyCategory.SITE_DATA) }
        runCurrent()
        operation.cancel()
        runCurrent()
        assertFalse(operation.isCompleted)
        assertEquals(PrivacyClearResult.BUSY, controller.clear(PrivacyCategory.CACHE))
        website.cookieGate!!.complete(Unit)
        operation.join()
        assertEquals(listOf("cookies-start", "cookies-finished", "storage", "cache"), website.calls)
        assertEquals(PrivacyClearResult.REQUESTED, controller.clear(PrivacyCategory.CACHE))
    }

    @Test fun cancellableOwnerCallbackReleasesLockAndDoesNotSwallowCancellation() = runTest {
        var block = true
        val controller = LocalPrivacyController(clearHistory = { if (block) awaitCancellation() })
        val operation = launch { controller.clear(PrivacyCategory.HISTORY) }
        runCurrent()
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        block = false
        assertEquals(PrivacyClearResult.COMPLETED, controller.clear(PrivacyCategory.HISTORY))
    }

    @Test fun anAlreadyCancelledCallerCannotStartNewWebsiteCleanup() = runTest {
        val website = Website()
        val controller = LocalPrivacyController(activityState = { stopped }, websiteData = website)
        var cancellationObserved = false
        val operation = launch {
            coroutineContext.cancel()
            try {
                controller.clear(PrivacyCategory.SITE_DATA)
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
        }
        operation.join()
        assertTrue(cancellationObserved)
        assertTrue(website.calls.isEmpty())
    }

    @Test fun activityInspectionFailureDoesNotExposeDetailsOrStartCleanup() = runTest {
        val website = Website()
        val controller = LocalPrivacyController(activityState = { error("private-state") }, websiteData = website)
        assertEquals(PrivacyClearResult.FAILED, controller.clear(PrivacyCategory.SITE_DATA))
        assertTrue(website.calls.isEmpty())
    }
}
