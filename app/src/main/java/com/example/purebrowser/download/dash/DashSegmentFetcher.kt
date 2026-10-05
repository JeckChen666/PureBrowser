package com.example.purebrowser.download.dash

import com.example.purebrowser.download.*
import com.example.purebrowser.download.hls.HlsHttpClient
import com.example.purebrowser.download.hls.HlsTransientFailure
import java.io.File
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * T96/T97 bounded fMP4 segment transport. One instance owns one task's cumulative byte ledger
 * (the HLS-style 128 MiB per-segment and 8 GiB total caps) and fetches a representation's init +
 * media segments with the same two-worker scheduler, retry/backoff and TLS-never-retried rules as
 * the HLS segment path. Pure JVM (space checks are injected), so fake-transport fixtures are
 * unit-testable without Android.
 */
class DashSegmentFetcher(
    private val client: HlsHttpClient,
    private val requireSpace: (Long) -> Unit = {},
    /** Production mirrors the HLS 1s/3s backoff; tests may inject zero to stay fast. */
    private val backoffMs: (Int) -> Long = { attempt -> if (attempt == 1) 1000L else 3000L },
) {
    private val received = AtomicLong(0L)
    private val retries = AtomicInteger(0)

    /** Fetches the representation initialization segment into [file]; returns its byte length. */
    fun fetchInit(
        record: DownloadRecord,
        url: String,
        file: File,
        cancel: TransferCancellation,
        onBytes: (Long) -> Unit,
    ): Long {
        var attempt = 0
        while (true) {
            cancel.check()
            try {
                fetchOne(record, url, file, cancel, setOf("ftyp"), "初始化分片", onBytes)
                return file.length()
            } catch (e: IOException) {
                file.delete()
                if (e is javax.net.ssl.SSLException || attempt >= 2 || retries.incrementAndGet() > MAX_RETRIES) throw e
                attempt++
                backoff(cancel, attempt)
            }
        }
    }

    /** Fetches every media segment URL into [fileFor] files, two workers, in plan order. */
    fun fetchMediaSegments(
        record: DownloadRecord,
        urls: List<String>,
        fileFor: (Int) -> File,
        cancel: TransferCancellation,
        onBytes: (Long) -> Unit,
        onSegmentComplete: (Int) -> Unit,
    ) {
        require(urls.isNotEmpty())
        val pool = Executors.newFixedThreadPool(2)
        val completed = ExecutorCompletionService<Int>(pool)
        val tokens = java.util.Collections.synchronizedSet(mutableSetOf<TransferCancellation>())
        val futures = mutableSetOf<Future<Int>>()
        cancel.bind { synchronized(tokens) { tokens.forEach { it.cancel() } } }
        fun submit(index: Int) {
            val token = TransferCancellation()
            tokens.add(token)
            val future = completed.submit(Callable {
                try {
                    val file = fileFor(index)
                    var attempt = 0
                    var attemptBytes = 0L
                    while (true) {
                        cancel.check(); token.check()
                        try {
                            attemptBytes = 0L
                            fetchOne(record, urls[index], file, cancel, SEGMENT_PREFIX, "分片 ${index + 1}") { bytes ->
                                attemptBytes += bytes
                                onBytes(bytes)
                            }
                            cancel.check(); token.check()
                            onSegmentComplete(index)
                            break
                        } catch (e: IOException) {
                            received.addAndGet(-attemptBytes)
                            file.delete()
                            // TLS identity failures are not transient. We never loosen validation.
                            if (e is javax.net.ssl.SSLException || attempt >= 2 || retries.incrementAndGet() > MAX_RETRIES) throw e
                            attempt++
                            backoff(cancel, token, attempt)
                        }
                    }
                    index
                } finally {
                    tokens.remove(token); token.clear()
                }
            })
            futures.add(future)
        }
        var next = 0
        var done = 0
        try {
            repeat(minOf(2, urls.size)) { submit(next++) }
            while (done < urls.size) {
                cancel.check()
                val future = completed.poll(250, TimeUnit.MILLISECONDS) ?: continue
                futures.remove(future)
                try { future.get() } catch (e: ExecutionException) {
                    throw (e.cause as? Exception ?: IOException("分片传输失败"))
                }
                done++
                if (next < urls.size) submit(next++)
            }
        } finally {
            synchronized(tokens) { tokens.forEach { it.cancel() } }
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
            var interrupted = false
            while (true) {
                try { if (pool.awaitTermination(250, TimeUnit.MILLISECONDS)) break }
                catch (_: InterruptedException) { interrupted = true }
                synchronized(tokens) { tokens.forEach { it.cancel() } }
            }
            cancel.clear()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun backoff(cancel: TransferCancellation, attempt: Int) = backoff(cancel, null, attempt)

    private fun backoff(cancel: TransferCancellation, token: TransferCancellation?, attempt: Int) {
        val end = System.currentTimeMillis() + backoffMs(attempt)
        while (System.currentTimeMillis() < end) {
            cancel.check()
            token?.check()
            Thread.sleep(minOf(100L, end - System.currentTimeMillis()).coerceAtLeast(1L))
        }
    }

    /** One bounded GET; exact length enforced, fMP4 prefix box verified after the body lands. */
    private fun fetchOne(
        record: DownloadRecord,
        url: String,
        file: File,
        cancel: TransferCancellation,
        allowedPrefixes: Set<String>,
        label: String,
        onBytes: (Long) -> Unit,
    ) {
        client.get(record, url, cancel) { response, _ ->
            val length = HlsHttpClient.contentLength(response)
            if (length != null && length > DashBudgets.MAX_SEGMENT_BYTES)
                throw TransferFailure(FailureKind.UNSUPPORTED, "单片超过 128 MiB 上限")
            requireSpace(length ?: 65536L)
            if (received.get() > DashBudgets.MAX_TOTAL_BYTES)
                throw TransferFailure(FailureKind.UNSUPPORTED, "累计传输超过 8 GiB 上限")
            val stream = try { file.outputStream() }
            catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "临时分片无法创建") }
            var count = 0L
            stream.use { output ->
                response.body().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        cancel.check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > DashBudgets.MAX_SEGMENT_BYTES)
                            throw TransferFailure(FailureKind.UNSUPPORTED, "单片超过 128 MiB 上限")
                        if (received.addAndGet(n.toLong()) > DashBudgets.MAX_TOTAL_BYTES)
                            throw TransferFailure(FailureKind.UNSUPPORTED, "累计传输超过 8 GiB 上限")
                        requireSpace(n.toLong())
                        try { output.write(buffer, 0, n) }
                        catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "临时分片写入失败") }
                        onBytes(n.toLong())
                    }
                    try { output.fd.sync() } catch (_: IOException) {
                        throw TransferFailure(FailureKind.STORAGE, "临时分片无法完整保存")
                    }
                }
            }
            if (length != null && count != length) throw HlsTransientFailure()
            if (count < 8L) throw TransferFailure(FailureKind.NOT_VIDEO, "$label 不是 fMP4 数据")
            file.inputStream().use { input ->
                val header = ByteArray(8)
                if (input.read(header) != header.size) throw TransferFailure(FailureKind.NOT_VIDEO, "$label 不是 fMP4 数据")
                val size = u32(header)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (size < 8 || type !in allowedPrefixes)
                    throw TransferFailure(FailureKind.NOT_VIDEO, "$label 响应不是 fMP4 分片")
            }
        }
    }

    private fun u32(bytes: ByteArray): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or (bytes[it].toLong() and 0xff) }
        return value
    }

    companion object {
        internal val SEGMENT_PREFIX = setOf("styp", "prft", "sidx", "moof")
        internal const val MAX_RETRIES = 20
    }
}
