package com.example.purebrowser.download.site

import com.example.purebrowser.download.RequestPolicy
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * A user-confirmed pair of complete MP4 resources, not a DASH segment list.
 * resourceId is an opaque work/format identity supplied by the resolver, NEVER a credential.
 * Track URLs (including signatures) live only in memory. Identity is hashed before persistence.
 * safeSourceUrl is a separate, adapter-approved canonical PUBLIC page reference for navigation.
 * Its caller MUST construct it from validated public identity, never copy a signed/session URL;
 * structural validation cannot prove public access or detect every secret in arbitrary URL values.
 * Declarations constrain transfer/mux verification; they do not authorize partial-byte continuation.
 */
data class DualTrackDownloadPlan(
    val resourceId: String,
    val videoFormatId: String,
    val audioFormatId: String,
    val videoUrl: String,
    val audioUrl: String,
    val videoCodec: String,
    val audioCodec: String,
    val videoLength: Long? = null,
    val audioLength: Long? = null,
    val durationUs: Long,
    val version: Int = 1,
    val safeSourceUrl: String? = null,
) {
    init {
        listOf(resourceId, videoFormatId, audioFormatId).forEach {
            require(Regex("[A-Za-z0-9._:-]{1,256}").matches(it)) { "资源或格式身份无效" }
        }
        metadata().validate()
        // Local HTTP is structurally permitted only for debug fixtures; enqueue enforces policy.
        listOf(videoUrl, audioUrl).forEach { value ->
            val uri = runCatching { URI(value) }.getOrNull()
            require(value.length in 1..8192 && value.none { it.isISOControl() } && uri != null &&
                uri.rawFragment == null && uri.rawUserInfo == null && RequestPolicy.origin(value) != null &&
                (uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) &&
                    uri.host in setOf("127.0.0.1", "10.0.2.2")))) { "轨道地址无效" }
        }
        require(videoUrl != audioUrl) { "双轨需要不同的完整资源" }
        validateSafeSourceUrl(safeSourceUrl)
        require(safeSourceUrl == null || listOf(videoUrl, audioUrl).none {
            URI(safeSourceUrl).normalize() == URI(it).normalize()
        }) { "公开来源页面不能是轨道地址" }
    }

    fun validate(allowLocalHttp: Boolean = false) {
        metadata().validate()
        validateSafeSourceUrl(safeSourceUrl)
        RequestPolicy.validateUrl(videoUrl, allowLocalHttp)
        RequestPolicy.validateUrl(audioUrl, allowLocalHttp)
    }

    fun metadata() = DualTrackMetadata(
        identityHash = identityHash(resourceId), videoFormatHash = identityHash(videoFormatId),
        audioFormatHash = identityHash(audioFormatId),
        videoCodec = videoCodec, audioCodec = audioCodec, videoLength = videoLength,
        audioLength = audioLength, durationUs = durationUs, version = version,
    )

    private fun identityHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    override fun toString() = "DualTrackDownloadPlan(version=$version, credentials=redacted)"

    companion object {
        /** Structural defence only; the trusted adapter remains responsible for public provenance. */
        fun validateSafeSourceUrl(value: String?) {
            if(value == null)return
            val uri = runCatching { URI(value) }.getOrNull()
            require(value.length in 1..8192 && value.none { it.isISOControl() } && uri != null &&
                uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.rawFragment == null &&
                RequestPolicy.origin(value) != null) { "公开来源页面地址无效" }
            // Reject common accidental credentials, not a site-specific public-query allowlist.
            // Passing these checks is NOT proof that arbitrary query values are public.
            uri.rawQuery?.split('&')?.forEach { part ->
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase()
                    .replace("_", "").replace("-", "")
                require(key.none { it.isISOControl() } && key !in PRIVATE_QUERY_KEYS &&
                    !key.endsWith("token") && !key.endsWith("signature") && !key.endsWith("credential")) {
                    "公开来源页面不能包含会话或签名参数"
                }
            }
        }
        private val PRIVATE_QUERY_KEYS = setOf("auth", "authorization", "cookie", "session", "sessionid", "sid",
            "signature", "sig", "token", "jwt", "password", "passwd", "apikey", "credential", "credentials")
    }
}

/** Versioned, non-secret task description. No URLs, headers, cookies, or session material. */
data class DualTrackMetadata(
    val identityHash: String,
    val videoFormatHash: String,
    val audioFormatHash: String,
    val videoCodec: String,
    val audioCodec: String,
    val videoLength: Long?,
    val audioLength: Long?,
    val durationUs: Long,
    val version: Int = 1,
) {
    val expectedBytes: Long? get() = if (videoLength != null && audioLength != null) videoLength + audioLength else null

    fun validate() {
        require(version == 1 && listOf(identityHash, videoFormatHash, audioFormatHash).all { Regex("[a-f0-9]{64}").matches(it) }) { "双轨方案版本或身份无效" }
        require(videoCodec in setOf("video/avc", "h264", "avc1") ||
            Regex("avc1\\.[a-fA-F0-9]{6}").matches(videoCodec)) { "只支持 H.264 视频轨" }
        require(audioCodec in setOf("audio/mp4a-latm", "aac", "mp4a.40.2")) { "只支持 AAC 音轨" }
        require(videoLength == null || videoLength in 1..MAX_VIDEO_BYTES) { "视频轨超出预算" }
        require(audioLength == null || audioLength in 1..MAX_AUDIO_BYTES) { "音频轨超出预算" }
        require(durationUs in 1..MAX_DURATION_US) { "时长超出预算" }
    }

    companion object {
        const val MAX_VIDEO_BYTES = 512L * 1024 * 1024
        const val MAX_AUDIO_BYTES = 128L * 1024 * 1024
        const val MAX_DURATION_US = 3_600_000_000L
        const val OUTPUT_OVERHEAD_BYTES = 16L * 1024 * 1024
        const val STORAGE_RESERVE_BYTES = 64L * 1024 * 1024
        const val MAX_TRANSFER_NANOS = 45L * 60 * 1_000_000_000
    }
}
