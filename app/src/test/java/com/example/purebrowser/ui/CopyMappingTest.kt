package com.example.purebrowser.ui

import com.example.purebrowser.download.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T118 failure→guidance contract: real transport-layer safeFailure wordings must map to honest
 * user guidance, and unknown inputs must fall back to generic retry text (never a claimed cause).
 */
class CopyMappingTest {
    @Test fun encryptedContentMapsToProtectedGuidance() {
        assertEquals(
            CopyMapping.PROTECTED,
            CopyMapping.failureGuidance(FailureKind.UNSUPPORTED, "本版不支持加密 HLS"),
        )
        assertEquals(
            CopyMapping.PROTECTED,
            CopyMapping.failureGuidance(FailureKind.UNSUPPORTED, "加密 DASH 内容不支持下载"),
        )
    }

    @Test fun expiredAccessMapsToReopenPageGuidance() {
        assertEquals(
            CopyMapping.LINK_EXPIRED,
            CopyMapping.failureGuidance(FailureKind.ACCESS_CONDITION, "当前访问条件不足"),
        )
        assertEquals(
            CopyMapping.LINK_EXPIRED,
            CopyMapping.failureGuidance(FailureKind.HTTP_REJECTED, "服务器拒绝了下载请求"),
        )
        assertEquals(
            CopyMapping.LINK_EXPIRED,
            CopyMapping.failureGuidance(null, "网站会话无法安全用于此下载"),
        )
    }

    @Test fun resumeRelatedFailuresMapToRestartGuidance() {
        assertEquals(
            CopyMapping.NO_RESUME,
            CopyMapping.failureGuidance(FailureKind.INTERRUPTED, "没有可靠续传缓存，请重新下载"),
        )
        assertEquals(
            CopyMapping.NO_RESUME,
            CopyMapping.failureGuidance(FailureKind.INTERRUPTED, "资源或续传校验信息已变化，请重新发现资源后开始新下载"),
        )
        assertEquals(
            CopyMapping.ONE_SHOT,
            CopyMapping.failureGuidance(FailureKind.UNSUPPORTED, "DASH 不保留续传字节，请返回来源重新解析并确认下载"),
        )
    }

    @Test fun networkAndStorageKeepTheirOwnGuidance() {
        assertEquals(CopyMapping.NETWORK, CopyMapping.failureGuidance(FailureKind.NETWORK, "视频响应未完整接收"))
        assertEquals(CopyMapping.NETWORK, CopyMapping.failureGuidance(FailureKind.NETWORK, "网络中断，可继续下载或返回来源"))
        assertEquals(CopyMapping.STORAGE, CopyMapping.failureGuidance(FailureKind.STORAGE, "任务无法完成，请检查本机存储"))
        assertEquals(CopyMapping.STORAGE, CopyMapping.failureGuidance(FailureKind.STORAGE, "可用空间不足，未保存成品"))
    }

    @Test fun unsupportedFormatsMapToFormatGuidance() {
        assertEquals(
            CopyMapping.FORMAT_UNSUPPORTED,
            CopyMapping.failureGuidance(FailureKind.NOT_VIDEO, "响应不是 MP4/WebM 视频"),
        )
        assertEquals(
            CopyMapping.FORMAT_UNSUPPORTED,
            CopyMapping.failureGuidance(FailureKind.UNSUPPORTED, "此档位视频编码不是 H.264，本版不支持分轨保存"),
        )
        assertEquals(
            CopyMapping.TOO_LARGE,
            CopyMapping.failureGuidance(FailureKind.UNSUPPORTED, "累计传输超过 8 GiB 上限"),
        )
    }

    @Test fun unknownInputsFallBackToHonestGenericRetry() {
        assertEquals(CopyMapping.UNKNOWN, CopyMapping.failureGuidance(null, null))
        assertEquals(CopyMapping.UNKNOWN, CopyMapping.failureGuidance(null, ""))
        assertEquals(CopyMapping.UNKNOWN, CopyMapping.failureGuidance(FailureKind.INTERRUPTED, "清单计划无法读取，请重新下载"))
        assertEquals(CopyMapping.UNKNOWN, CopyMapping.failureGuidance(null, "无法分类的诊断文本"))
    }
}
