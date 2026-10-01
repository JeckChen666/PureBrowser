package com.example.purebrowser.download

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DownloadStoreTest {
    private fun withDirectory(action: (File) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "download-store-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { action(directory) } finally { directory.deleteRecursively() }
    }
    private fun completeData(): DownloadData {
        val record = DownloadRecord("r1", 7, "sample.mp4", "My video", "https://example.com/sample.mp4?token=a%2Bb",
            "https://example.com/watch?video=7", "Source", 123, "agent", true, "video/mp4", sourceTabId = "tab-7", sourceGeneration = 4)
        val asset = VideoAsset("r1", 7, "content://downloads/all_downloads/7", record.name, record.displayName, 456, 455, 1024,
            "video/mp4", FormatCheck.PASSED, FileAvailability.AVAILABLE, 2000)
        return DownloadData(listOf(record), listOf(asset))
    }
    @Test fun allMetadataRoundtripsIncludingExactSignedQueries() = withDirectory { directory ->
        val store = DownloadStore(directory)
        val data = completeData(); store.save(data)
        assertEquals(data, DownloadStore(directory).load())
        assertEquals(data.records.first().mediaUrl, DownloadStore.decode(store.file.readText()).records.first().mediaUrl)
    }
    @Test fun legacyMigrationIsOneTimeAndUnknownFieldsStayUnknown() = withDirectory { directory ->
        val raw = """[{"id":7,"name":"old.mp4"}]"""
        var reads = 0
        val store = DownloadStore(directory) { reads++; raw }
        val migrated = store.load()
        assertEquals("legacy-7", migrated.records.single().recordId)
        assertNull(migrated.records.single().mediaUrl); assertNull(migrated.records.single().sourceUrl)
        assertNull(migrated.records.single().createdAt); assertNull(migrated.records.single().wifiOnly)
        store.save(DownloadRules.forget(migrated, 7))
        assertTrue(DownloadStore(directory) { error("must not reimport legacy") }.load().records.isEmpty())
        assertEquals(1, reads); assertEquals("""[{"id":7,"name":"old.mp4"}]""", raw)
    }
    @Test fun corruptionIsBackedUpWithoutResurrectingLegacyRows() = withDirectory { directory ->
        val store = DownloadStore(directory) { error("must not import legacy on corrupt new store") }
        store.file.writeText("bad-new-data")
        assertTrue(store.load().records.isEmpty())
        assertEquals("bad-new-data", directory.listFiles()!!.first { it.name.contains(".corrupt-") }.readText())
        assertNotNull(store.takeNotice()); assertTrue(store.writable)
        assertTrue(DownloadStore(directory).load().records.isEmpty())
    }
    @Test fun unsupportedSchemaIsReadOnlyAndNeverOverwritten() = withDirectory { directory ->
        val store = DownloadStore(directory)
        val raw = """{"schemaVersion":99,"valuable":"future data"}"""
        store.file.writeText(raw)
        store.load(); assertFalse(store.writable)
        assertTrue(runCatching { store.save(DownloadData()) }.isFailure)
        assertEquals(raw, store.file.readText())
    }
    @Test fun interruptedWriteDoesNotReplaceTheLastCommittedData() = withDirectory { directory ->
        val store = DownloadStore(directory); val data = completeData(); store.save(data)
        File(store.file.path + ".new").writeText("partial-write")
        assertEquals(data, DownloadStore(directory).load())
        assertEquals(data, DownloadStore.decode(store.file.readText()))
    }
    @Test fun invalidSaveCannotDamageTheLastCommittedData() = withDirectory { directory ->
        val store = DownloadStore(directory); val data = completeData(); store.save(data)
        val invalid = data.copy(records = data.records + data.records.first())
        assertTrue(runCatching { store.save(invalid) }.isFailure)
        assertEquals(data, store.load())
    }
    @Test fun malformedLegacyIsPreservedAndBackedUpWithoutCreatingSystemTasks() = withDirectory { directory ->
        val raw = "broken legacy preferences"
        val store = DownloadStore(directory) { raw }
        assertTrue(store.load().records.isEmpty())
        assertEquals(raw, directory.listFiles()!!.first { it.name.contains("legacy.corrupt-") }.readText())
        assertNotNull(store.takeNotice())
    }
}
