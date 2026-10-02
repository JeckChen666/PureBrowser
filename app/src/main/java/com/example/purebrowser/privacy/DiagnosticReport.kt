package com.example.purebrowser.privacy

import android.content.Context
import android.os.Build
import android.webkit.WebView
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadRecord
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.TaskStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explicit allowlist, not a redactor of arbitrary logs or model.toString(). No network/Intent work.
 * The caller generates a fresh snapshot only on user request and previews [render] before sharing.
 * Do not append logs, task names/IDs/titles, URLs, headers, cookies, auth, paths or exception text.
 */
object DiagnosticReport {
    const val MAX_TASKS = 200

    data class Environment(
        val appVersion: String?,
        val osVersion: String?,
        val osApi: Int,
        val webViewVersion: String?,
    )

    /** Deliberately cannot hold a task identity, filename, source or free-form failure reason. */
    data class TaskSnapshot(
        val state: TaskStatus?,
        val failure: FailureKind?,
        val receivedBytes: Long,
        val expectedBytes: Long?,
    ) {
        companion object {
            fun from(record: DownloadRecord) = TaskSnapshot(
                record.taskStatus, record.failure, record.received, record.expected,
            )

            fun from(item: DownloadItem) = TaskSnapshot(
                item.taskStatus, item.failure, item.bytes, item.total,
            )
        }
    }

    // Reject whole invalid values instead of trying to salvage potentially sensitive substrings.
    private val appVersionPattern = Regex(
        "[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}(?:-(?:alpha|beta|rc)(?:\\.[0-9]{1,4})?)?(?:-debug)?",
    )
    private val numericVersionPattern = Regex("[0-9]{1,4}(?:\\.[0-9]{1,4}){0,3}")

    /** Stable text; task numbers are report-local, one-based list positions, never persisted IDs. */
    fun render(environment: Environment, tasks: List<TaskSnapshot>): String = buildString {
        appendLine("PureBrowser local diagnostics v1")
        appendLine("app_version=${safeVersion(environment.appVersion, appVersionPattern)}")
        appendLine("os_version=${safeVersion(environment.osVersion, numericVersionPattern)}")
        appendLine("os_api=${environment.osApi.takeIf { it in 1..999 } ?: "unknown"}")
        appendLine("webview_version=${safeVersion(environment.webViewVersion, numericVersionPattern)}")
        appendLine("task_count=${tasks.size}")
        appendLine("reported_task_count=${minOf(tasks.size, MAX_TASKS)}")
        tasks.take(MAX_TASKS).forEachIndexed { index, task ->
            append("task=${index + 1} state=${task.state?.name ?: "UNKNOWN"}")
            append(" failure=${task.failure?.name ?: "NONE"}")
            append(" received_bytes=${safeBytes(task.receivedBytes)}")
            appendLine(" expected_bytes=${safeBytes(task.expectedBytes)}")
        }
    }

    /** Environment only: no device model, locale, serial, account, package name or provider name. */
    suspend fun environment(context: Context): Environment {
        val appVersion = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                .getOrNull()
        }
        val webViewVersion = withContext(Dispatchers.Main.immediate) {
            runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        }
        return Environment(appVersion, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, webViewVersion)
    }

    /**
     * Optional private-cache export, never public Downloads. Returns a local File, NOT a share URI.
     * Current .files provider exposes only Download/PureBrowser, so it cannot safely share this file.
     * Use render(...) with ACTION_SEND + text/plain + EXTRA_TEXT after user preview/confirmation.
     * Do not use file://, broaden the provider or copy diagnostics into public video directories.
     * This function does not launch a chooser, upload, grant access or delete unrelated cache files.
     */
    suspend fun writePrivateCache(
        context: Context,
        environment: Environment,
        tasks: List<TaskSnapshot>,
    ): File = writeToCache(context.cacheDir, environment, tasks)

    internal suspend fun writeToCache(
        cacheDirectory: File,
        environment: Environment,
        tasks: List<TaskSnapshot>,
    ): File = withContext(Dispatchers.IO) {
        val root = cacheDirectory.canonicalFile
        val directory = File(root, "purebrowser-diagnostics")
        // Reject a pre-existing symlink redirect; all outputs stay under app-private cache.
        check(directory.canonicalFile == directory.absoluteFile)
        check(directory.isDirectory || directory.mkdir())
        check(directory.canonicalFile == directory.absoluteFile)
        val file = File.createTempFile("report-", ".txt", directory)
        try {
            check(file.setReadable(false, false) && file.setWritable(false, false))
            check(file.setReadable(true, true) && file.setWritable(true, true))
            file.writeText(render(environment, tasks), Charsets.UTF_8)
            file
        } catch (failure: Exception) {
            file.delete() // Only the fresh report from this attempt, not the entire directory.
            throw failure
        }
    }

    private fun safeVersion(value: String?, pattern: Regex): String =
        value?.takeIf { it.length <= 64 && pattern.matches(it) } ?: "unknown"

    private fun safeBytes(value: Long?): String = value?.takeIf { it >= 0 }?.toString() ?: "unknown"
}
