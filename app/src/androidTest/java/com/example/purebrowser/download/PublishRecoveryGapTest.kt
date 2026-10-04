package com.example.purebrowser.download

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only UUID-scoped rows/files created by this test are touched; never an existing download. */
@RunWith(AndroidJUnit4::class)
class PublishRecoveryGapTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun record(status: TaskStatus = TaskStatus.PUBLISHING): DownloadRecord {
        val id = UUID.randomUUID().toString()
        return DownloadRecord(recordId = id, name = "${id.take(8)}_gap-${UUID.randomUUID()}.mp4",
            transfer = TransferType.CONTROLLED, taskStatus = status, pendingUri = null)
    }

    private fun rows(block: (ManagedFileStore, (String, String, Boolean) -> Uri) -> Unit) {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val created = mutableListOf<Uri>()
        val resolver = app.contentResolver
        val files = ManagedFileStore(app)
        val insert: (String, String, Boolean) -> Uri = { name, path, pending ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
            }
            val uri = checkNotNull(resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
            created += uri
            // Ensure the intended exact-name/path ownership boundary is really under test.
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(name, cursor.getString(0))
                assertEquals(path, cursor.getString(1))
                assertEquals(app.packageName, cursor.getString(2))
            }
            uri
        }
        try { block(files, insert) } finally {
            created.forEach { uri -> resolver.delete(uri, null, null) }
        }
    }

    private fun exists(uri: Uri): Boolean = checkNotNull(app.contentResolver.query(
        uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)).use { it.moveToFirst() }

    @Test fun missingUriFindsOnlyExactOwnedPendingNameAndPath() = rows { files, insert ->
        val task = record()
        val pending = insert(task.name, "Download/PureBrowser/", true)
        val differentName = insert("unrelated-${UUID.randomUUID()}.mp4", "Download/PureBrowser/", true)
        val differentPath = insert(task.name, "Download/pb-gap-${UUID.randomUUID()}/", true)
        files.cleanupPending(task)
        assertFalse(exists(pending))
        assertTrue(exists(differentName))
        assertTrue(exists(differentPath))
    }

    @Test fun missingUriNeverDeletesExactNamedNonPendingOutput() = rows { files, insert ->
        val task = record()
        val published = insert(task.name, "Download/PureBrowser/", false)
        files.cleanupPending(task)
        assertTrue(exists(published))
    }

    @Test fun succeededRecordSkipsCleanupEvenWithPendingOrExplicitUri() = rows { files, insert ->
        val task = record(TaskStatus.SUCCEEDED)
        val pending = insert(task.name, "Download/PureBrowser/", true)
        files.cleanupPending(task)
        assertTrue(exists(pending))
        files.cleanupPending(task.copy(pendingUri = pending.toString()))
        assertTrue(exists(pending))
    }

    @Test fun apiBelow29MissingUriDeletesOnlyItsDeterministicPartial() {
        assumeTrue(Build.VERSION.SDK_INT in 26..28)
        // targetSdk-30+ apps get no public-write gid on Android 9-, so publishes land in the
        // app-specific external folder and the deterministic partial is created there; cleanup
        // must stay scoped to this task's own partial and never touch unrelated files.
        val task = record()
        val base = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "PureBrowser")
        check(base.isDirectory || base.mkdirs())
        val ownedPartial = File(base, ".pb-${task.recordId}.part")
        val unrelatedPartial = File(base, ".pb-${UUID.randomUUID()}.part")
        val published = File(base, task.name)
        val created = mutableListOf<File>()
        try {
            listOf(ownedPartial, unrelatedPartial, published).forEach { file ->
                check(file.createNewFile()); created += file; file.writeText("test-owned")
            }
            val files = ManagedFileStore(app)
            files.cleanupPending(task.copy(taskStatus = TaskStatus.SUCCEEDED))
            assertTrue(ownedPartial.exists())
            files.cleanupPending(task)
            assertFalse(ownedPartial.exists())
            assertTrue(unrelatedPartial.exists())
            assertEquals("test-owned", published.readText())
        } finally { created.forEach { it.delete() } }
    }
}
