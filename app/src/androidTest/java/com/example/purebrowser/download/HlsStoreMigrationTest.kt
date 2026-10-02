package com.example.purebrowser.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.hls.HlsDownloadPlan
import com.example.purebrowser.download.hls.HlsOptions
import com.example.purebrowser.download.hls.HlsPlaylist
import com.example.purebrowser.download.hls.HlsSegment
import com.example.purebrowser.download.hls.HlsVariant
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata-only tests use unique subdirectories and never touch the application's real store. */
@RunWith(AndroidJUnit4::class)
class HlsStoreMigrationTest {
    private fun withStore(block: (DownloadStore) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "hls-store-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(DownloadStore(directory)) } finally { directory.deleteRecursively() }
    }

    private fun systemRecord() = DownloadRecord(recordId = "legacy-7", systemId = 7, name = "old.mp4")
    private fun controlledRecord() = DownloadRecord(
        recordId = "controlled-8", name = "direct.mp4", transfer = TransferType.CONTROLLED,
        mediaUrl = "https://source.example/direct?token=exact%2Bprivate", taskStatus = TaskStatus.INTERRUPTED,
        received = 123, expected = null, failure = FailureKind.INTERRUPTED,
    )
    private fun hlsRecord() = DownloadRecord(
        recordId = "hls-summary-9", name = "hls.mp4", displayName = "Self-owned HLS",
        transfer = TransferType.CONTROLLED, protocol = DownloadProtocol.HLS,
        mediaUrl = "https://source.example/master.m3u8?token=entry-private%2Bexact",
        hlsPlaylistUrl = "https://source.example/video.m3u8?token=variant-private%2Bexact",
        hlsWidth = 320, hlsHeight = 180, hlsBandwidth = 500_000,
        plannedDurationUs = 8_000_000, segmentCount = 4, completedSegments = 2,
        taskStatus = TaskStatus.FAILED, failure = FailureKind.NETWORK, received = 1880,
        safeFailure = "网络中断，请重新下载",
    )

    private val hlsFields = listOf("protocol", "hlsPlaylistUrl", "hlsWidth", "hlsHeight", "hlsBandwidth",
        "plannedDurationUs", "segmentCount", "completedSegments", "safeFailure")
    private val v3Fields = listOf("transfer", "taskStatus", "received", "expected", "failure", "useAccessContext",
        "frameUrl", "reliableSource", "pendingUri")

    private fun oldJson(version: Int, data: DownloadData): String {
        val json = JSONObject(DownloadStore.encode(data)).put("schemaVersion", version)
        val records = json.getJSONArray("records")
        for (i in 0 until records.length()) {
            val row = records.getJSONObject(i)
            hlsFields.forEach { row.remove(it) }
            if (version == 2) v3Fields.forEach { row.remove(it) }
        }
        // Deliberately noncanonical formatting and UTF-8 to test byte-for-byte backup, not re-encoding.
        json.put("unknownLegacyNote", "旧记录应保留原字节")
        return " \n${json.toString(2)}\n"
    }

    private fun assertMigration(store: DownloadStore, version: Int, expected: DownloadData) {
        val bytes = oldJson(version, expected).toByteArray(Charsets.UTF_8)
        store.file.writeBytes(bytes)
        assertEquals(expected, store.load())
        assertTrue(store.writable)
        assertEquals(DownloadStore.SCHEMA_VERSION, JSONObject(store.file.readText()).getInt("schemaVersion"))
        val backups = store.file.parentFile!!.listFiles()!!.filter { it.name.startsWith("download-v$version-") }
        assertEquals(1, backups.size)
        assertArrayEquals(bytes, backups.single().readBytes())
        val migrated = store.file.readBytes()
        val reloaded = DownloadStore(store.file.parentFile!!)
        assertEquals(expected, reloaded.load())
        assertArrayEquals(migrated, store.file.readBytes())
        assertArrayEquals(bytes, backups.single().readBytes())
        reloaded.load().records.forEach { record ->
            assertEquals(DownloadProtocol.DIRECT, record.protocol)
            assertNull(record.hlsPlaylistUrl)
            assertNull(record.segmentCount)
            assertNull(record.plannedDurationUs)
            assertNull(record.hlsWidth)
            assertNull(record.hlsHeight)
            assertNull(record.hlsBandwidth)
            assertEquals(0, record.completedSegments)
            assertNull(record.safeFailure)
        }
    }

    @Test fun schema3To4KeepsExactBackupAndDefaultsBothTransferTypesToDirect() = withStore { store ->
        val legacy = systemRecord()
        val asset = VideoAsset(legacy.recordId, legacy.systemId, "content://downloads/all_downloads/7",
            legacy.name, legacy.displayName, 123, format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE)
        assertMigration(store, 3, DownloadData(records = listOf(legacy, controlledRecord()), assets = listOf(asset)))
    }

    @Test fun schema2To4RetainsUnknownLegacyFieldsAndOriginalBackupBytes() = withStore { store ->
        assertMigration(store, 2, DownloadData(records = listOf(systemRecord())))
    }

    @Test fun hlsSummaryRoundTripsWithoutPersistingSegmentsOrAuthenticationHeaders() = withStore { store ->
        val data = DownloadData(records = listOf(hlsRecord(), controlledRecord()))
        store.save(data)
        assertEquals(data, DownloadStore(store.file.parentFile!!).load())
        val json = JSONObject(store.file.readText())
        assertEquals(DownloadStore.SCHEMA_VERSION, json.getInt("schemaVersion"))
        val row = json.getJSONArray("records").getJSONObject(0)
        assertEquals("HLS", row.getString("protocol"))
        assertEquals(4, row.getInt("segmentCount"))
        assertEquals(2, row.getInt("completedSegments"))
        assertEquals(8_000_000L, row.getLong("plannedDurationUs"))
        assertEquals(hlsRecord().hlsPlaylistUrl, row.getString("hlsPlaylistUrl"))
        listOf("segments", "plan", "Cookie", "Set-Cookie", "Authorization", "headers").forEach { assertFalse(row.has(it)) }
        assertNull(hlsRecord().expected)
    }

    @Test fun futureSchemaIsReadOnlyAndPreservesFutureBytesAcrossReadsAndRejectedWrites() = withStore { store ->
        val bytes = " \n{\"schemaVersion\":99,\"valuable\":\"未来私有字段\"}\n".toByteArray(Charsets.UTF_8)
        store.file.writeBytes(bytes)
        repeat(2) {
            assertTrue(store.load().records.isEmpty())
            assertFalse(store.writable)
            assertArrayEquals(bytes, store.file.readBytes())
        }
        val write = runCatching { store.save(DownloadData(records = listOf(hlsRecord()))) }
        assertTrue(write.exceptionOrNull() is IllegalStateException)
        assertArrayEquals(bytes, store.file.readBytes())
        assertEquals(listOf(store.file.name), store.file.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun planOptionsAndAllUrlBearingModelsHaveRedactedToStrings() {
        val secret = "https://source.example/private-path?token=private%2Bexact"
        val variant = HlsVariant(secret, 500_000, 320, 180, "private-codecs", true, "private-reason")
        val segment = HlsSegment(secret, 8_000_000, 0)
        val media = HlsPlaylist.Media(listOf(segment), 8_000_000, 8_000_000)
        val plan = HlsDownloadPlan(secret, secret, media, variant)
        val options = HlsOptions(secret, secret, HlsPlaylist.Master(listOf(variant)))
        listOf(plan, options, variant, segment, media, options.playlist, hlsRecord()).forEach { model ->
            val printed = model.toString()
            assertFalse(printed.contains("://"))
            assertFalse(printed.contains("private"))
            assertFalse(printed.contains("token="))
        }
    }

    private fun assertMalformedField(field: String, value: Any) {
        val json = JSONObject(DownloadStore.encode(DownloadData(records = listOf(hlsRecord()))))
        json.getJSONArray("records").getJSONObject(0).put(field, value)
        assertTrue("Malformed $field must not decode", runCatching { DownloadStore.decode(json.toString()) }.isFailure)
    }

    @Test fun malformedHlsFieldsRejectOutOfBoundsAndUnsafeValues() {
        val badFields = listOf(
            "protocol" to "DASH", "transfer" to "SYSTEM",
            "segmentCount" to 0, "segmentCount" to 10_001,
            "completedSegments" to -1, "completedSegments" to 5,
            "plannedDurationUs" to 0L, "plannedDurationUs" to 86_400_000_001L,
            "hlsWidth" to 0, "hlsWidth" to 16_385, "hlsHeight" to -1, "hlsHeight" to 16_385,
            "hlsBandwidth" to 0L, "hlsBandwidth" to -1L,
            "hlsPlaylistUrl" to "file:///private/plan", "hlsPlaylistUrl" to "https://user:secret@source.example/video.m3u8",
            "hlsPlaylistUrl" to ("https://source.example/" + "a".repeat(8192)),
            "safeFailure" to "x".repeat(181), "safeFailure" to "https://source.example/private?token=secret",
            "safeFailure" to "message\nCookie: secret",
        )
        badFields.forEach { (field, value) -> assertMalformedField(field, value) }
    }

    @Test fun malformedIntegerFieldsCannotWrapLargeJsonNumbersIntoValidBounds() {
        // JSONObject.getInt can narrow long values; bounds must be checked before that narrowing.
        listOf("segmentCount", "completedSegments", "hlsWidth", "hlsHeight").forEach { field ->
            assertMalformedField(field, 4_294_967_300L) // wraps to 4 if decoded unsafely
        }
    }

    @Test fun hlsFieldBoundsAreInclusiveAndNullDirectSummaryRemainsValid() = withStore { store ->
        val min = hlsRecord().copy(recordId = "hls-min", segmentCount = 1, completedSegments = 0,
            plannedDurationUs = 1, hlsWidth = 1, hlsHeight = 1, hlsBandwidth = 1, safeFailure = "x")
        val max = hlsRecord().copy(recordId = "hls-max", segmentCount = 10_000, completedSegments = 10_000,
            plannedDurationUs = 86_400_000_000L, hlsWidth = 16_384, hlsHeight = 16_384, hlsBandwidth = Long.MAX_VALUE,
            safeFailure = "x".repeat(180))
        val data = DownloadData(records = listOf(min, max, controlledRecord()))
        store.save(data)
        assertEquals(data, store.load())
    }
}
