package com.example.purebrowser.download

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DownloadStoreCacheTest {
    private fun test(block:(DownloadStore,File)->Unit) {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "store-cache-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(DownloadStore(directory),directory) } finally { directory.deleteRecursively() }
    }
    private fun data(name:String="One")=DownloadData(records=listOf(DownloadRecord(recordId="owned",name="owned.mp4",displayName=name,transfer=TransferType.CONTROLLED,mediaUrl="https://example.org/owned.mp4")))
    @Test fun unchangedDiskBytesReuseValidatedImmutableParse()=test { store,_ ->
        store.save(data());val first=store.load()
        repeat(100) { assertSame(first,store.load()) }
        @Suppress("UNCHECKED_CAST")
        assertTrue(runCatching { (first.records as MutableList<DownloadRecord>).clear() }.isFailure)
        assertEquals("One",store.load().records.single().displayName)
    }
    @Test fun equalLengthExternalEditCannotBeHiddenByCache()=test { store,_ ->
        store.save(data());val first=store.load();val raw=store.file.readText()
        val changed=raw.replace("\"One\"","\"Two\"");assertEquals(raw.length,changed.length)
        store.file.writeText(changed)
        val second=store.load();assertNotSame(first,second);assertEquals("Two",second.records.single().displayName)
    }
    @Test fun otherStoreAtomicCommitInvalidatesExactByteCache()=test { store,directory ->
        store.save(data());val first=store.load()
        DownloadStore(directory).save(data("New"))
        assertNotSame(first,store.load());assertEquals("New",store.load().records.single().displayName)
    }
    @Test fun futureSchemaAfterWarmCacheIsStillReadOnlyAndUntouched()=test { store,_ ->
        store.save(data());store.load()
        val future="""{"schemaVersion":99,"valuable":"do not reset"}"""
        store.file.writeText(future)
        assertTrue(store.load().records.isEmpty());assertFalse(store.writable)
        assertTrue(runCatching { store.save(data()) }.isFailure);assertEquals(future,store.file.readText())
    }
    @Test fun corruptBytesAfterWarmCacheAreBackedUpNotResurrected()=test { store,directory ->
        store.save(data());store.load();store.file.writeText("owned-corrupt-test")
        assertTrue(store.load().records.isEmpty())
        assertTrue(directory.listFiles()!!.any { it.name.contains(".corrupt-") && it.readText()=="owned-corrupt-test" })
        assertTrue(store.load().records.isEmpty())
    }
    @Test fun writerGateSeesOtherInstancesPauseCommitImmediately()=test { store,directory ->
        store.save(data().copy(records=data().records.map { it.copy(taskStatus=TaskStatus.RUNNING) }))
        assertEquals(TaskStatus.RUNNING,store.writerRecord("owned")!!.taskStatus)
        val other=DownloadStore(directory);val current=other.load()
        other.save(current.copy(records=current.records.map { it.copy(taskStatus=TaskStatus.PAUSING) }))
        assertEquals(TaskStatus.PAUSING,store.writerRecord("owned")!!.taskStatus)
    }
    @Test fun normalRevalidationStillDetectsExternalFutureSchemaAfterWriterCache()=test { store,_ ->
        store.save(data());assertNotNull(store.writerRecord("owned"))
        store.file.writeText("""{"schemaVersion":99,"valuable":"future"}""")
        store.load();assertNull(store.writerRecord("owned"));assertFalse(store.writable)
    }

}
