package com.example.purebrowser.download

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SchemaV5Test {
    @Test fun v4MigrationProtectsOriginalAndDoesNotInventResume() {
        val root=File(ApplicationProvider.getApplicationContext<Context>().cacheDir,"v5-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val old=DownloadRecord(name="old.mp4",transfer=TransferType.CONTROLLED,taskStatus=TaskStatus.INTERRUPTED,received=500)
            val raw=JSONObject(DownloadStore.encode(DownloadData(records=listOf(old)))).put("schemaVersion",4).toString()
            val store=DownloadStore(root);store.file.writeText(raw)
            val record=store.load().records.single()
            assertFalse(record.resumeAvailable)
            assertEquals(500L,record.received)
            assertEquals(5,JSONObject(store.file.readText()).getInt("schemaVersion"))
            assertEquals(raw,root.listFiles()!!.single { it.name.startsWith("download-v4-") }.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun stoppedTaskRoundTripHasNoCredentialsOrUrlInDebugString() {
        val r=DownloadRecord(name="v.mp4",transfer=TransferType.CONTROLLED,taskStatus=TaskStatus.PAUSED,
            pauseReason=PauseReason.USER,resumeAvailable=true,mediaUrl="https://example.org/video?private=secret")
        val raw=DownloadStore.encode(DownloadData(records=listOf(r)))
        assertEquals(r,DownloadStore.decode(raw).records.single())
        assertFalse(raw.contains("Cookie"));assertFalse(raw.contains("Authorization"))
        assertFalse(r.toString().contains("secret"))
    }
    @Test fun futureVersionStaysByteExact() {
        val root=File(ApplicationProvider.getApplicationContext<Context>().cacheDir,"future-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val store=DownloadStore(root);val raw=" {\"schemaVersion\":99,\"valuable\":\"keep\"} "
            store.file.writeText(raw);store.load()
            assertFalse(store.writable);assertEquals(raw,store.file.readText())
        } finally { root.deleteRecursively() }
    }
}
