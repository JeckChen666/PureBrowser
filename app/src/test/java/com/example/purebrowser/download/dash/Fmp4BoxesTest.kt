package com.example.purebrowser.download.dash

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** T96 pure structural integrity: box walks, init/segment shape, moof/mdat ordering, truncation. */
class Fmp4BoxesTest {
    private fun box(type: String, payload: Int = 8) =
        byteArrayOf(
            ((8 + payload).shr(24)).and(0xff).toByte(),
            ((8 + payload).shr(16)).and(0xff).toByte(),
            ((8 + payload).shr(8)).and(0xff).toByte(),
            ((8 + payload).shr(0)).and(0xff).toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + ByteArray(payload)

    private fun boxes(vararg types: String) = types.flatMap { type -> box(type, 16).toList() }.toByteArray()

    @Test fun initRequiresFtypThenMoovWithoutMdat() {
        Fmp4Boxes.validateInit(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("ftyp", "moov"))))
        Fmp4Boxes.validateInit(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("ftyp", "sidx", "moov"))))
        assertFails { Fmp4Boxes.validateInit(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("moov", "ftyp")))) }
        assertFails { Fmp4Boxes.validateInit(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("ftyp")))) }
        assertFails { Fmp4Boxes.validateInit(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("ftyp", "moov", "mdat")))) }
    }

    @Test fun segmentRequiresMoofBeforeMdat() {
        Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("styp", "moof", "mdat"))))
        Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("prft", "moof", "mdat", "moof", "mdat"))))
        Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("moof", "mdat", "mdat"))))
        // mdat before the first moof is a moof/mdat ordering violation.
        assertFails { Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("styp", "mdat", "moof")))) }
        assertFails { Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("styp")))) }
        assertFails { Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("moof")))) }
        // TS or plain MP4 bytes are not fragment headers.
        assertFails { Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(boxes("ftyp", "moov")))) }
    }

    @Test fun truncatedBoxPayloadFailsTheWalk() {
        val complete = boxes("moof", "mdat")
        val truncated = complete.copyOf(complete.size - 7) // cut into the last box payload
        try {
            Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(truncated))
            fail("expected IOException")
        } catch (expected: IOException) { assertTrue(expected.message!!.contains("不完整")) }
        // A size that runs past the end of the stream is also truncation, not silence.
        val lieing = box("mdat", 64).let { it.copyOf(it.size - 1) }
        try {
            Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(box("moof") + lieing))
            fail("expected IOException")
        } catch (expected: IOException) { assertNotNull(expected.message) }
    }

    @Test fun absurdBoxSizeIsRejected() {
        val broken = byteArrayOf(0, 0, 0, 1) + "mdat".toByteArray(Charsets.US_ASCII) +
            ByteArray(8) + ByteArray(8) // size==1 requires a 64-bit largesize; truncated here
        try {
            Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(broken))
            fail("expected IOException")
        } catch (expected: IOException) { assertNotNull(expected.message) }
    }

    @Test fun splitRangesCutAtMoofBoundaries() {
        val stream = boxes("ftyp", "moov", "moof", "mdat", "moof", "mdat")
        val boxes = Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(stream))
        val (init, segments) = Fmp4Boxes.splitRanges(boxes, stream.size.toLong())
        assertEquals(0L until 48L, init)
        assertEquals(2, segments.size)
        // Each authored box is 24 bytes: init=ftyp+moov, then one moof+mdat pair per segment.
        assertEquals(48L until 96L, segments[0])
        assertEquals(96L until 144L, segments[1])
    }

    @Test fun nonBoxDataIsRejected() {
        try {
            Fmp4Boxes.topLevelBoxes(ByteArrayInputStream("this is mpeg-ts junk data!!".toByteArray()))
            fail("expected IOException")
        } catch (expected: IOException) { assertNotNull(expected.message) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }
}
