package com.example.purebrowser.download

import android.app.DownloadManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in maintenance, not a product workflow. Never enumerates global downloads. */
class RoundTwoFixtureCleanupTest {
    @Test fun removeOnlyThisRunsSyntheticSystemTasks() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cleanupFixture")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=app.getSystemService(DownloadManager::class.java)
        val excluded=args.getString("originalIds").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
        val ids=mutableSetOf<Long>()
        val inventory=File(app.cacheDir,"round2-owned-task-ids.txt")
        if(inventory.exists()) ids+=inventory.readLines().mapNotNull { it.toLongOrNull() }
        ids+=args.getString("extraOwnedIds").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        // Records not forgotten are also validated against the synthetic endpoint before removal.
        ids+=DownloadStore(app).load().records.filter { it.createdAt!=null }.map { it.systemId }
        val paths=setOf("/sample.mp4","/sample.webm","/second.mp4","/bad.mp4","/private.mp4","/expired.mp4","/unknown.mp4","/slow.mp4")
        var removed=0
        for(id in ids-excluded) {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if(!cursor.moveToFirst()) return@use
                val url=URI(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
                assertTrue("Refuse cleanup of non-fixture ID $id",url.scheme=="http" && url.host=="127.0.0.1" && url.port==8765 && url.path in paths && url.rawUserInfo==null)
                assertTrue(manager.remove(id)>0)
                removed++
            }
        }
        val output=android.os.Bundle().apply { putString("stream","Removed $removed validated synthetic tasks; original IDs excluded; no global query or directory deletion.\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(2,output)
    }
}
