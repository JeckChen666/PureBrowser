package com.example.purebrowser.library

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.download.TaskStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Final signed APK: share the actual public asset retained across a cover upgrade. */
class SignedReleaseShareAudit {
    @Test fun upgradedPublicVideoIsReadByRealChooserRecipientAtDifferentUid() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("signedReleaseShare") == "true")
        val app = instrument.targetContext
        val recipient = instrument.context.packageName
        assertEquals("io.github.jeckchen666.purebrowser", app.packageName)
        assertNotEquals(app.applicationInfo.uid, instrument.context.applicationInfo.uid)
        val id = File(app.cacheDir, "signed-upgrade-id.txt").readText()
        val repo = DownloadRepository(app)
        assertEquals(TaskStatus.SUCCEEDED, repo.record(id)!!.taskStatus)
        val uri = repo.fileUri(id) ?: error("Upgraded public video must remain readable")
        val senderHash = app.contentResolver.openInputStream(uri)!!.use { input ->
            MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
        }
        instrument.uiAutomation.executeShellCommand("run-as $recipient rm -f files/fixture-file-received.json").close()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertNull(LocalFileActions.launch(it, id, uri, repo.mimeType(id), true)) }
            assertTrue("Real system chooser must expose the independent recipient", chooseFixtureRecipient(instrument, 20_000))
            var result: String? = null
            val deadline = System.currentTimeMillis() + 10_000
            while (result == null && System.currentTimeMillis() < deadline) {
                result = runCatching {
                    val fd = instrument.uiAutomation.executeShellCommand("run-as $recipient cat files/fixture-file-received.json")
                    ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }.takeIf { it.startsWith("{") }
                }.getOrNull()
                if (result == null) Thread.sleep(200)
            }
            assertNotNull("Independent UID must actually read the temporary grant", result)
            val evidence = JSONObject(result!!)
            assertEquals(Intent.ACTION_SEND, evidence.getString("action"))
            assertTrue(evidence.getBoolean("readable"))
            assertEquals(senderHash, evidence.getString("sha256"))
        }
    }
}
