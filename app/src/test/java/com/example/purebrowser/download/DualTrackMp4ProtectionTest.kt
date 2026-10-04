package com.example.purebrowser.download

import org.junit.Assert.*
import org.junit.Test
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files

class DualTrackMp4ProtectionTest {
    private fun box(type: String, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(payload.size + 8).putInt(payload.size + 8)
            .put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()
    private fun entry(type: String, children: ByteArray = byteArrayOf()) =
        box(type, ByteArray(if(type in setOf("avc1", "avc3", "encv")) 78 else 28) + children)
    private fun descriptions(type: String = "avc1", children: ByteArray = byteArrayOf()) = box("stsd",
        ByteBuffer.allocate(8).putInt(0).putInt(1).array() + entry(type, children))
    private fun movie(description: ByteArray = descriptions(), extra: ByteArray = byteArrayOf()) =
        box("moov", box("trak", box("mdia", box("minf", box("stbl", description + extra)))))
    private fun clear(bytes: ByteArray, token: TransferCancellation? = null): Boolean {
        val file = Files.createTempFile("dual-mp4-protection-", ".mp4").toFile()
        return try { file.writeBytes(bytes); FileInputStream(file).use { DualTrackMp4Protection.clear(it.channel, token) } }
            finally { file.delete() }
    }
    @Test fun clearAvcAacEntriesPassAndMdatMarkersAreNotTreatedAsBoxes() {
        assertTrue(clear(movie() + movie(descriptions("mp4a")) + box("mdat", "psshsinfencvsenc".toByteArray())))
        assertTrue(clear(movie(descriptions("avc3", box("avcC", byteArrayOf(1, 2, 3))))))
    }
    @Test fun psshAnyVersionAndEncryptedSampleEntriesRejectWithoutNativeApis() {
        for(version in listOf(0, 1, 2)) {
            val pssh = box("pssh", ByteArray(28).apply { this[0] = version.toByte() })
            assertFalse(clear(movie() + pssh))
            assertFalse(clear(box("moov", pssh) + movie()))
        }
        assertFalse(clear(movie(descriptions("encv"))))
        assertFalse(clear(movie(descriptions("enca"))))
        assertFalse(clear(movie(descriptions("avc1", box("sinf", box("schm", byteArrayOf()))))))
    }
    @Test fun cencSampleGroupsAuxiliaryProtectionAndPiffExtensionsReject() {
        val group = ByteArray(4) + "seig".toByteArray() + ByteArray(4)
        for(type in listOf("sgpd", "sbgp"))assertFalse(clear(movie(extra = box(type, group))))
        for(type in listOf("senc", "tenc", "saiz", "saio", "uuid"))assertFalse(clear(movie(extra = box(type))))
        assertFalse(clear(movie() + box("moof", box("traf", box("senc")))))
        assertTrue(clear(movie(extra = box("sgpd", ByteArray(4) + "roll".toByteArray() + ByteArray(4)))))
    }
    @Test fun malformedUnsupportedAndOverBudgetLayoutsFailClosed() {
        assertFalse(clear(box("mdat", ByteArray(8)))) // No supported track descriptions.
        assertFalse(clear(movie(descriptions("hvc1"))))
        val mismatchedCount = box("stsd", ByteBuffer.allocate(8).putInt(0).putInt(2).array() + entry("avc1"))
        assertTrue(runCatching { clear(movie(mismatchedCount)) }.isFailure)
        assertTrue(runCatching { clear(movie() + ByteBuffer.allocate(8).putInt(100).put("free".toByteArray()).array()) }.isFailure)
        assertTrue(runCatching { clear(movie() + ByteArray(3)) }.isFailure)
        var nested = movie()
        repeat(20) { nested = box("moov", nested) }
        assertTrue(runCatching { clear(nested) }.isFailure)
        val tooMany = java.io.ByteArrayOutputStream().apply { repeat(8193) { write(box("free")) } }.toByteArray()
        assertTrue(runCatching { clear(tooMany) }.isFailure)
    }
    @Test fun extendedAndEndSizedBoxesAreBoundedAndCancellationPropagates() {
        val extended = ByteBuffer.allocate(20).putInt(1).put("free".toByteArray()).putLong(20).putInt(123).array()
        assertTrue(clear(extended + movie()))
        val toEnd = ByteBuffer.allocate(12).putInt(0).put("mdat".toByteArray()).putInt(123).array()
        assertTrue(clear(movie() + toEnd))
        val stopped = TransferCancellation().apply { cancel() }
        assertTrue(runCatching { clear(movie(), stopped) }.exceptionOrNull() is java.util.concurrent.CancellationException)
    }
}
