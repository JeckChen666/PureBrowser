package com.example.purebrowser.media.verify

import com.example.purebrowser.download.HttpResponse
import com.example.purebrowser.download.HttpTransport
import com.example.purebrowser.download.TransferCancellation
import java.io.InputStream
import java.util.Locale

/**
 * Minimal single-request HTTP abstraction so auto-verification is unit-testable without network
 * IO. Redirects are NOT followed here; the queue owns the bounded redirect loop and policy checks.
 */
interface UrlFetcher {
    fun open(url: String, headers: Map<String, String>): FetchedResponse
}

/** Header snapshot plus a body stream; callers must [close]. Lookup is ASCII-case-insensitive. */
class FetchedResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    fun header(name: String): String? {
        val target = name.lowercase(Locale.ROOT)
        return headers.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == target }?.value
    }
    override fun close() { runCatching { body.close() }; onClose() }
}

/** Bridges the app's existing HttpTransport; mirrors the header behavior of MediaProbe/HlsHttpClient. */
class HttpTransportUrlFetcher(private val transport: HttpTransport) : UrlFetcher {
    override fun open(url: String, headers: Map<String, String>): FetchedResponse {
        val response: HttpResponse = transport.open(url, headers, TransferCancellation())
        val snapshot = WATCHED_HEADERS.mapNotNull { name -> response.header(name)?.let { name to it } }.toMap()
        return FetchedResponse(response.status, snapshot, response.body()) { response.close() }
    }

    private companion object {
        // Only headers the auto-verifier consumes are copied into the test-visible snapshot.
        val WATCHED_HEADERS = listOf(
            "Content-Type", "Content-Length", "Content-Range", "Accept-Ranges",
            "Content-Encoding", "Transfer-Encoding", "Location",
        )
    }
}
