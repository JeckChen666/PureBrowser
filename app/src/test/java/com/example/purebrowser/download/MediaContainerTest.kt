package com.example.purebrowser.download
import org.junit.Assert.*
import org.junit.Test
class MediaContainerTest {
    private fun ebml(type:String):ByteArray=byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte(),(0x80+3+type.length).toByte(),0x42,0x82.toByte(),(0x80+type.length).toByte())+type.toByteArray()
    @Test fun isoMp4IsRecognizedButQuicktimeAndHeifAreRejected() {
        assertEquals("video/mp4",MediaContainer.mime(byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()))
        assertNull(MediaContainer.mime(byteArrayOf(0,0,0,24)+"ftypqt  ".toByteArray()))
        assertNull(MediaContainer.mime(byteArrayOf(0,0,0,24)+"ftypheic".toByteArray()))
    }
    @Test fun webmDocTypeMustNotBeConfusedWithGenericMatroska() { assertEquals("video/webm",MediaContainer.mime(ebml("webm")));assertNull(MediaContainer.mime(ebml("matroska"))) }
    @Test fun malformedOrTruncatedEbmlIsRejected() { assertNull(MediaContainer.mime(ebml("webm").copyOf(9)));assertNull(MediaContainer.mime(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte(),0))) }
}
