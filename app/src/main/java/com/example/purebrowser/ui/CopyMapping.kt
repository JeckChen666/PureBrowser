package com.example.purebrowser.ui

import com.example.purebrowser.download.FailureKind

/**
 * T118 failure→guidance mapping. Primary task copy says what happened and what to do next;
 * the raw diagnostic wording that produced the classification stays available as 技术详情 in the
 * task detail view. Pure mapping only: no Android dependencies, no repository access, honest
 * semantics — never claims success or a cause the transport layer did not report.
 *
 * Inputs are the persisted [FailureKind] and the safe (URL-free) failure text surfaced through
 * DownloadRepository/DownloadItem. Unknown combinations fall back to generic retry guidance.
 */
object CopyMapping {
    /** Encrypted/DRM-protected content cannot be saved by this version. */
    const val PROTECTED = "该视频受站点保护，暂无法保存"

    /** The link expired or the site requires more access than we have. */
    const val LINK_EXPIRED = "链接已过期，请回到视频页面重新打开后再试"

    /** The site does not support resuming; an interrupted download must restart. */
    const val NO_RESUME = "该网站不支持断点续传，中断后需重新下载"

    /** This kind of task (merged audio+video tracks) must finish in one go. */
    const val ONE_SHOT = "该视频需要一次性下载完成，中断后请回到视频页面重新保存"

    /** The format is not supported for saving. */
    const val FORMAT_UNSUPPORTED = "该视频的格式暂不支持保存"

    /** Network transfer failed. */
    const val NETWORK = "网络连接失败，请检查网络后重试"

    /** Local storage is full or unwritable. */
    const val STORAGE = "本机存储空间不足或无法写入，请清理空间后重试"

    /** The system blocked the transfer. */
    const val SYSTEM_LIMIT = "系统限制了下载，请返回应用后重试"

    /** The file is larger than this version's saving limits. */
    const val TOO_LARGE = "视频文件过大，本版暂不支持保存"

    /** Honest fallback when no classification is known. */
    const val UNKNOWN = "保存没有完成，请回到视频页面重试"

    // tech-detail:begin
    // Diagnostic marker substrings matched against the transport layer's own safeFailure
    // wording (see download/ TransferFailure and safeFailure assignment sites). They exist to
    // recognize technical diagnostics and are never shown to the user as primary copy.
    private val PROTECTED_MARKERS = listOf("加密", "DRM")
    private val ONE_SHOT_MARKERS = listOf("不保留续传字节", "重新解析")
    private val NO_RESUME_MARKERS = listOf("续传", "ETag", "检查点", "Range", "恢复缓存")
    private val LINK_MARKERS = listOf("过期", "访问条件", "访问不足", "会话", "登录", "跨源", "跳转", "拒绝", "变化", "失效")
    private val NETWORK_MARKERS = listOf("网络", "未完整接收", "连接中断")
    private val STORAGE_MARKERS = listOf("存储", "空间", "写入", "磁盘")
    private val TOO_LARGE_MARKERS = listOf("上限", "预算", "超过")
    private val FORMAT_MARKERS = listOf("不支持", "格式", "编码", "MPEG-TS", "fMP4", "分轨", "音轨", "不是 MP4", "未通过")
    // tech-detail:end

    /** User-facing next-step guidance for a failed task, classified from marker text first. */
    fun failureGuidance(kind: FailureKind?, safeFailure: String?): String {
        val text = safeFailure.orEmpty()
        return when {
            PROTECTED_MARKERS.any(text::contains) -> PROTECTED
            ONE_SHOT_MARKERS.any(text::contains) -> ONE_SHOT
            NO_RESUME_MARKERS.any(text::contains) -> NO_RESUME
            LINK_MARKERS.any(text::contains) -> LINK_EXPIRED
            NETWORK_MARKERS.any(text::contains) -> NETWORK
            STORAGE_MARKERS.any(text::contains) -> STORAGE
            TOO_LARGE_MARKERS.any(text::contains) -> TOO_LARGE
            FORMAT_MARKERS.any(text::contains) -> FORMAT_UNSUPPORTED
            else -> when (kind) {
                FailureKind.NETWORK -> NETWORK
                FailureKind.ACCESS_CONDITION, FailureKind.HTTP_REJECTED -> LINK_EXPIRED
                FailureKind.NOT_VIDEO, FailureKind.UNSUPPORTED -> FORMAT_UNSUPPORTED
                FailureKind.STORAGE -> STORAGE
                FailureKind.SYSTEM_LIMIT -> SYSTEM_LIMIT
                FailureKind.INTERRUPTED -> UNKNOWN
                null -> UNKNOWN
            }
        }
    }
}
