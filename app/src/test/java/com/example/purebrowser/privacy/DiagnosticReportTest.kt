package com.example.purebrowser.privacy

import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadRecord
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.TaskStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticReportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val environment = DiagnosticReport.Environment("0.1.3-rc.1-debug", "16", 36, "140.0.7339.0")
    private val task = DiagnosticReport.TaskSnapshot(TaskStatus.FAILED, FailureKind.NETWORK, 12, 30)

    @Test fun exactAllowlistOutputUsesLocalOrdinalsAndOnlyNumericBytes() {
        assertEquals(
            """
                PureBrowser local diagnostics v1
                app_version=0.1.3-rc.1-debug
                os_version=16
                os_api=36
                webview_version=140.0.7339.0
                task_count=2
                reported_task_count=2
                task=1 state=FAILED failure=NETWORK received_bytes=12 expected_bytes=30
                task=2 state=SUCCEEDED failure=NONE received_bytes=30 expected_bytes=30

            """.trimIndent(),
            DiagnosticReport.render(environment, listOf(task, task.copy(state = TaskStatus.SUCCEEDED, failure = null, receivedBytes = 30))),
        )
    }

    @Test fun recordProjectionCannotCarryIdentityUrlsCookiesAuthNamesOrFailureDetails() {
        val record = DownloadRecord(
            recordId = "private-id", systemId = 87654321L, name = "private-file.mp4", displayName = "private-display",
            mediaUrl = "https://secret.example/video?auth=private-token", sourceUrl = "https://private-source.example",
            sourceTitle = "private-title", userAgent = "private-user-agent", sourceTabId = "private-tab",
            retryOf = "private-retry", frameUrl = "https://private-frame.example", pendingUri = "content://private-pending",
            hlsPlaylistUrl = "https://private-playlist.example", safeFailure = "Cookie: private-cookie; Authorization: private-auth",
            taskStatus = TaskStatus.FAILED, failure = FailureKind.ACCESS_CONDITION, received = 123, expected = 456,
        )
        val snapshot = DiagnosticReport.TaskSnapshot.from(record)
        val report = DiagnosticReport.render(environment, listOf(snapshot))
        listOf("private", "https://", "content://", "87654321", "Cookie", "Authorization", "recordId", "sourceTitle").forEach {
            assertFalse("Unexpected field: $it", report.contains(it))
            assertFalse(snapshot.toString().contains(it))
        }
        assertTrue(report.contains("task=1 state=FAILED failure=ACCESS_CONDITION received_bytes=123 expected_bytes=456"))
    }

    @Test fun itemProjectionExcludesRawSystemStatusAndDetailStrings() {
        val item = DownloadItem(
            id = "private-id", name = "private-file", status = 7654321, bytes = 20, total = 99,
            detail = "https://secret.example?token=private-cookie", recordId = "private-record",
            displayName = "private-name", sourceTitle = "private-title", sourceUrl = "https://private-source.example",
            taskStatus = TaskStatus.RUNNING,
        )
        val report = DiagnosticReport.render(environment, listOf(DiagnosticReport.TaskSnapshot.from(item)))
        assertFalse(report.contains("private"))
        assertFalse(report.contains("https://"))
        assertFalse(report.contains("7654321"))
        assertTrue(report.contains("state=RUNNING failure=NONE received_bytes=20 expected_bytes=99"))
    }

    @Test fun arbitraryEnvironmentStringsAreRejectedWholeNotRedactedOrTruncated() {
        val values = listOf(
            "https://secret.example", "16\nCookie: secret", "16 Authorization=secret", "140.0-private-id",
            "1.2/secret", "1.2+private-build", "1.2\r\nsecret", "1".repeat(10000), "", "１６",
        )
        values.forEach { value ->
            val report = DiagnosticReport.render(DiagnosticReport.Environment(value, value, -1, value), emptyList())
            assertTrue(report.contains("app_version=unknown\n"))
            assertTrue(report.contains("os_version=unknown\n"))
            assertTrue(report.contains("webview_version=unknown\n"))
            assertTrue(report.contains("os_api=unknown\n"))
            assertFalse(report.contains("secret"))
        }
    }

    @Test fun missingVersionsStatesAndNegativeByteSentinelsStayUnknown() {
        val report = DiagnosticReport.render(
            DiagnosticReport.Environment(null, null, 1000, null),
            listOf(task.copy(state = null, failure = null, receivedBytes = -1, expectedBytes = -1), task.copy(expectedBytes = null)),
        )
        assertTrue(report.contains("task=1 state=UNKNOWN failure=NONE received_bytes=unknown expected_bytes=unknown"))
        assertTrue(report.contains("task=2 state=FAILED failure=NETWORK received_bytes=12 expected_bytes=unknown"))
        assertFalse(report.contains("=-1"))
    }

    @Test fun everyTaskStateAndFailureKindIsRenderedOnlyAsAnEnumName() {
        TaskStatus.entries.forEach { state ->
            FailureKind.entries.forEach { failure ->
                val report = DiagnosticReport.render(environment, listOf(task.copy(state = state, failure = failure)))
                assertTrue(report.contains("task=1 state=${state.name} failure=${failure.name}"))
            }
        }
    }

    @Test fun reportIsBoundedAndSaysHowManyTasksWereOmitted() {
        val report = DiagnosticReport.render(environment, List(1000) { task.copy(receivedBytes = Long.MAX_VALUE) })
        assertTrue(report.contains("task_count=1000\nreported_task_count=200\n"))
        assertTrue(report.contains("task=200 "))
        assertFalse(report.contains("task=201 "))
        assertTrue(report.length < 40000)
    }

    @Test fun reorderRenumbersLocallyWithoutPersistentTaskIds() {
        val other = task.copy(state = TaskStatus.CANCELLED)
        val report = DiagnosticReport.render(environment, listOf(other, task))
        assertTrue(report.contains("task=1 state=CANCELLED"))
        assertTrue(report.contains("task=2 state=FAILED"))
    }

    @Test fun cacheExportIsFreshPrivateTextAndDoesNotTouchVideosOrOtherCache() = runTest {
        val root = temporary.newFolder("cache")
        val video = File(root, "saved-video.mp4").apply { writeText("saved video") }
        val unrelated = File(root, "unrelated.bin").apply { writeText("keep") }
        val first = DiagnosticReport.writeToCache(root, environment, listOf(task))
        val second = DiagnosticReport.writeToCache(root, environment, emptyList())
        assertEquals(File(root, "purebrowser-diagnostics").canonicalFile, first.parentFile!!.canonicalFile)
        assertEquals("txt", first.extension)
        assertNotEquals(first, second)
        assertEquals(DiagnosticReport.render(environment, listOf(task)), first.readText())
        assertEquals(DiagnosticReport.render(environment, emptyList()), second.readText())
        assertEquals("saved video", video.readText())
        assertEquals("keep", unrelated.readText())
        val permissions = Files.getPosixFilePermissions(first.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
    }

    @Test fun symlinkRedirectOutsidePrivateReportDirectoryIsRejectedWithoutWriting() = runTest {
        val root = temporary.newFolder("cache")
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(root, "purebrowser-diagnostics").toPath(), outside.toPath())
        val failure = runCatching { DiagnosticReport.writeToCache(root, environment, listOf(task)) }
        assertTrue(failure.isFailure)
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun reportDirectoryCollisionDoesNotDeleteExistingFile() = runTest {
        val root = temporary.newFolder("cache")
        val collision = File(root, "purebrowser-diagnostics").apply { writeText("keep") }
        assertTrue(runCatching { DiagnosticReport.writeToCache(root, environment, listOf(task)) }.isFailure)
        assertEquals("keep", collision.readText())
    }
}
