package com.example.purebrowser.browser

import java.net.URI
import java.net.URLEncoder

/** Only ordinary web navigation is accepted; input never becomes JavaScript or an Intent. */
object BrowserAddress {
    fun resolve(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "请输入网址或搜索内容" }
        require(value.length <= 8192) { "网址过长" }
        val explicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").find(value)?.value
        val candidate = when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            explicitScheme != null && !Regex("^[^ /:]+\\.[^ /:]+:\\d+").containsMatchIn(value) ->
                throw IllegalArgumentException("只支持 HTTP / HTTPS 网页")
            !value.any(Char::isWhitespace) && (value.substringBefore('/').contains('.') || value.startsWith("localhost")) -> "https://$value"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(value, "UTF-8")}" 
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
        require(uri != null && uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) { "网址格式不正确" }
        require(uri.rawUserInfo == null && uri.port in -1..65535) { "不支持此网址格式" }
        return uri.toASCIIString()
    }

    fun isWebUrl(value: String): Boolean = runCatching {
        require(value.length <= 8192)
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
}
