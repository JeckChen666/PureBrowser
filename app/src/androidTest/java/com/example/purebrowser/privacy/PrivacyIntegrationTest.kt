package com.example.purebrowser.privacy

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.data.browser.BrowserData
import com.example.purebrowser.data.browser.LocalBrowserRepository
import com.example.purebrowser.data.browser.SavedPage
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.data.browser.ThemeMode
import com.example.purebrowser.download.AssetLocation
import com.example.purebrowser.download.DownloadData
import com.example.purebrowser.download.DownloadRecord
import com.example.purebrowser.download.DownloadRuntime
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.PauseReason
import com.example.purebrowser.download.TaskControlRules
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.download.TransferType
import com.example.purebrowser.download.VideoAsset
import com.example.purebrowser.ui.browser.BrowserScreen
import com.example.purebrowser.ui.browser.BrowserViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in actual ViewModel + BrowserScreen wiring tests: instrumentation argument privacyFixture=true.
 * Uses an isolated Application's private files/preferences, blank tabs, and a temporarily rebound
 * process runtime. Never runs alongside active transfers, requests a website, launches a share target,
 * clears global WebView data, or scans/deletes existing public downloads. The public-file test mints
 * and removes only its own exact MediaStore URI. No production-core test seam is required.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class PrivacyIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private var fixture: Fixture? = null
    private val showBrowser = mutableStateOf(true)
    private val model: BrowserViewModel get() = fixture!!.model!!

    @Before fun prepareIsolatedModel() {
        assumeTrue("Run this isolated suite explicitly with privacyFixture=true",
            InstrumentationRegistry.getArguments().getString("privacyFixture") == "true")
        fixture = Fixture()
        fixture!!.start()
        compose.waitUntil(10_000) { model.ready.value }
    }

    @After fun releaseOnlyFixtureResources() {
        val currentFixture = fixture ?: return
        try {
            onMain { showBrowser.value = false }
            compose.waitForIdle()
        } finally {
            // Restore the process runtime even when Compose reports an earlier composition failure.
            try {
                currentFixture.close()
            } finally {
                fixture = null
            }
        }
    }

    @Test fun actualViewModelHistoryClearIsPersistedBeforeReturningAndPreservesOtherBrowserData() {
        val before = model.data.value
        assertEquals(1, before.history.size)
        val result = runBlocking {
            withContext(Dispatchers.Main.immediate) { model.clearLocalData(PrivacyCategory.HISTORY) }
        }
        assertEquals(PrivacyClearResult.COMPLETED, result)
        val saved = LocalBrowserRepository(fixture!!.application).load()
        assertTrue(model.data.value.history.isEmpty())
        assertTrue(saved.history.isEmpty())
        assertEquals(before.tabs, saved.tabs)
        assertEquals(before.selectedId, saved.selectedId)
        assertEquals(before.bookmarks, saved.bookmarks)
        assertEquals(before.shortcuts, saved.shortcuts)
        assertEquals(ThemeMode.DARK, saved.theme)
        // Persist another setting and reload again: an older conflated snapshot must not resurrect history.
        onMain { model.setTheme(ThemeMode.LIGHT) }
        compose.waitUntil(10_000) { LocalBrowserRepository(fixture!!.application).load().theme == ThemeMode.LIGHT }
        assertTrue(LocalBrowserRepository(fixture!!.application).load().history.isEmpty())
    }

    @Test fun actualTempClearInvalidatesStoppedResumeCacheButLeavesPublishedPublicAsset() {
        assumeTrue("Public MediaStore fixture requires Android 10+", Build.VERSION.SDK_INT >= 29)
        val f = fixture!!
        val repository = model.repository
        val files = repository.files!!
        val stopped = record(TaskStatus.PAUSED).copy(
            received = 12, expected = 48, resumeAvailable = true, pauseReason = PauseReason.USER,
        )
        val stage = files.stage(stopped.recordId).apply { writeBytes(ByteArray(12) { it.toByte() }) }
        files.directCheckpoints.save(stopped.recordId, stage, PRIVATE_URL, PRIVATE_URL, "\"privacy-fixture\"", 48, 12)
        assertTrue(files.directCheckpoints.hasValid(stopped.recordId, stage))
        val completed = record(TaskStatus.SUCCEEDED)
        val payload = byteArrayOf(0, 1, 2, 3, 5, 8, 13)
        val publicUri = f.publicAsset(completed.name, payload)
        val asset = VideoAsset(
            recordId = completed.recordId, uri = publicUri.toString(), name = completed.name,
            displayName = completed.displayName, indexedAt = 1, sizeBytes = payload.size.toLong(),
            mimeType = "video/mp4", format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE,
            location = AssetLocation.MEDIASTORE_DOWNLOAD,
        )
        repository.store.save(DownloadData(records = listOf(stopped, completed), assets = listOf(asset)))
        assertTrue(TaskControlRules.canResume(repository.record(stopped.recordId)!!))
        assertEquals(PrivacyClearResult.COMPLETED, runBlocking {
            withContext(Dispatchers.Main.immediate) { model.clearLocalData(PrivacyCategory.DOWNLOAD_TEMP) }
        })
        val after = repository.record(stopped.recordId)!!
        assertFalse(stage.exists())
        assertFalse(files.directCheckpoints.hasValid(stopped.recordId, stage))
        assertFalse(after.resumeAvailable)
        assertFalse(TaskControlRules.canResume(after))
        assertEquals(TaskStatus.INTERRUPTED, after.taskStatus)
        assertEquals(2, repository.records().size)
        assertEquals(TaskStatus.SUCCEEDED, repository.record(completed.recordId)!!.taskStatus)
        assertEquals(publicUri.toString(), repository.store.load().assets.single().uri)
        assertArrayEquals(payload, f.application.contentResolver.openInputStream(publicUri)!!.use { it.readBytes() })
    }

    @Test fun actualSettingsShareUsesOnlyAllowlistedViewModelReportAndNeverRawUrlOrFile() {
        val task = record(TaskStatus.FAILED).copy(
            failure = FailureKind.NETWORK, received = 12, expected = 48,
            sourceTitle = "private-source-title", userAgent = "private-user-agent",
            safeFailure = "Cookie: private-cookie; Authorization: private-auth",
        )
        model.repository.store.save(DownloadData(records = listOf(task)))
        // Keep this isolated runtime from launching a real download service when the screen routes.
        model.repository.transfersAllowed = false
        val recording = RecordingContext(fixture!!.application)
        compose.setContent {
            // Resolve the host Activity owner before LocalContext becomes the recording Application.
            val hostOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
            if (showBrowser.value) {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides hostOwner,
                    LocalContext provides recording,
                ) {
                    MaterialTheme { BrowserScreen(model) }
                }
            }
        }
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("menu-settings").performClick()
        assertNull(recording.outgoing.get())
        compose.onNodeWithTag("diagnostic-generate").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("diagnostic-share").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("diagnostic-share").assertIsEnabled()
        assertNull("Generating/previewing must not share automatically", recording.outgoing.get())
        compose.onNodeWithTag("diagnostic-share").performClick()
        val chooser = recording.outgoing.get()!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals("text/plain", target.type)
        assertNull(target.data)
        assertNull(target.clipData)
        assertNull(target.component)
        assertNull(target.`package`)
        assertFalse(target.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(setOf(Intent.EXTRA_TEXT), target.extras!!.keySet())
        val text = target.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.startsWith("PureBrowser local diagnostics v1\n"))
        assertTrue(text.contains("task=1 state=FAILED failure=NETWORK received_bytes=12 expected_bytes=48"))
        listOf(PRIVATE_URL, "https://", "content://", "file://", "private-", task.recordId, task.name).forEach {
            assertFalse("Sensitive content in actual share: $it", text.contains(it))
        }
        assertEquals(0, target.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    private fun record(state: TaskStatus) = DownloadRecord(
        recordId = UUID.randomUUID().toString(), name = "privacy-fixture-${UUID.randomUUID()}.mp4",
        mediaUrl = PRIVATE_URL, sourceUrl = PRIVATE_URL, transfer = TransferType.CONTROLLED, taskStatus = state,
    )

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val outgoing = AtomicReference<Intent?>(null)
        override fun startActivity(intent: Intent) { outgoing.set(Intent(intent)) }
    }

    private class IsolatedApplication(base: Context, root: File) : Application() {
        private val privateFiles = File(root, "files").apply { mkdirs() }
        private val privateCache = File(root, "cache").apply { mkdirs() }
        private val preferencePrefix = "privacy-fixture-${UUID.randomUUID()}-"
        val preferences: MutableSet<String> = ConcurrentHashMap.newKeySet()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = privateFiles
        override fun getCacheDir(): File = privateCache
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = preferencePrefix + name
            preferences.add(scoped)
            return baseContext.getSharedPreferences(scoped, mode)
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val root = File(base.cacheDir, "privacy-integration-${UUID.randomUUID()}").apply { mkdirs() }
        val application = IsolatedApplication(base, root)
        private val instance = DownloadRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
        private val previous = instance.get(null) as DownloadRuntime?
        private val modelStore = ViewModelStore()
        private val publicUris = mutableListOf<Uri>()
        private var rebound = false
        private var runtime: DownloadRuntime? = null
        var model: BrowserViewModel? = null
            private set

        fun start() {
            val oldRunning = previous?.let {
                DownloadRuntime::class.java.getDeclaredField("running").apply { isAccessible = true }.get(it) as Map<*, *>
            }
            assumeTrue("Do not rebind an active runtime", oldRunning.isNullOrEmpty() && previous?.repository?.transfersAllowed != false)
            val tabs = listOf(TabRecord(), TabRecord()) // Never navigate to the sensitive fixture URLs.
            LocalBrowserRepository(application).save(BrowserData(
                tabs = tabs, selectedId = tabs.first().id, theme = ThemeMode.DARK,
                history = listOf(SavedPage(title = "private-history", url = PRIVATE_URL)),
                bookmarks = listOf(SavedPage(title = "private-bookmark", url = PRIVATE_URL)),
            ))
            instance.set(null, null)
            rebound = true
            runtime = DownloadRuntime.get(application)
            onMain {
                model = BrowserViewModel(application)
                modelStore.put("privacy-fixture", model!!)
            }
        }

        fun publicAsset(name: String, bytes: ByteArray): Uri {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/PureBrowser/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = application.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
            publicUris.add(uri)
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            return uri
        }

        override fun close() {
            try {
                val writer = model?.let {
                    BrowserViewModel::class.java.getDeclaredField("writer").apply { isAccessible = true }.get(it) as CoroutineScope
                }
                onMain { modelStore.clear() }
                runBlocking { writer?.coroutineContext?.get(Job)?.cancelAndJoin() }
                val scope = runtime?.let {
                    DownloadRuntime::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope
                }
                runBlocking { scope?.coroutineContext?.get(Job)?.cancelAndJoin() }
            } finally {
                if (rebound) instance.set(null, previous)
                publicUris.forEach { base.contentResolver.delete(it, null, null) }
                application.preferences.forEach { base.deleteSharedPreferences(it) }
                root.deleteRecursively() // Only the unique private root minted by this fixture.
            }
        }
    }

    companion object {
        private const val PRIVATE_URL = "https://private.example/video.mp4?auth=private-token"
        private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }
}
