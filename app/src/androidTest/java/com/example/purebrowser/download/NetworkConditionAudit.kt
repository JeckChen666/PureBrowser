package com.example.purebrowser.download

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit lab opt-in. Operator restores this dedicated AVD's original Wi-Fi setting. */
class NetworkConditionAudit {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(!condition() && System.currentTimeMillis()<end)Thread.sleep(100)
        assertTrue("real network condition timeout",condition())
    }
    private fun clean(rt:DownloadRuntime,id:String) {
        val repo=rt.repository
        runCatching { if(repo.record(id)?.taskStatus!=TaskStatus.SUCCEEDED)repo.cancel(id) }
        await { !repo.transferInFlight(id) }
        if(repo.stateSnapshot().assets.any { it.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id)
    }
    @Test fun actualSocketTruncationFailsWithoutAutomaticRetryOrFakeSuccess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkFixture")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val rt=DownloadRuntime.get(i.targetContext);rt.recover();val repo=rt.repository
            val id=repo.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8770/drop.mp4",MediaKind.FILE,emptySet()),
                "PureBrowser-SocketAudit",useAccessContext=false),false,"socket-owned.mp4")
            try {
                rt.kick();await { repo.record(id)?.taskStatus !in DownloadRepository.activeStatuses }
                val stopped=repo.record(id)!!;assertEquals(TaskStatus.FAILED,stopped.taskStatus);assertEquals(FailureKind.NETWORK,stopped.failure)
                assertTrue(repo.files!!.directCheckpoints.hasValid(id,repo.files.stage(id)))
                assertFalse(repo.stateSnapshot().assets.any { it.recordId==id })
                Thread.sleep(1000);assertEquals(TaskStatus.FAILED,repo.record(id)!!.taskStatus)
                i.sendStatus(0,android.os.Bundle().apply { putString("socketEvidence","failed_network;valid_checkpoint;no_asset;no_auto_restart") })
            } finally { clean(rt,id) }
        }
    }
    @Test fun actualWifiDisableStopsWriterAndUserReturnResumesSameTask() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wifiFixture")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val rt=DownloadRuntime.get(i.targetContext);rt.recover();val repo=rt.repository
            i.uiAutomation.executeShellCommand("svc wifi enable").close();await { rt.wifiAvailable() }
            val id=repo.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8770/range.mp4",MediaKind.FILE,emptySet()),
                "PureBrowser-WifiAudit",useAccessContext=false),true,"wifi-owned.mp4")
            try {
                rt.kick();await { repo.record(id)?.let { r->r.taskStatus==TaskStatus.RUNNING && r.resumeAvailable && r.received>0 }==true }
                i.uiAutomation.executeShellCommand("svc wifi disable").close();await { !rt.wifiAvailable() }
                await { repo.record(id)?.taskStatus==TaskStatus.WAITING_WIFI && !repo.transferInFlight(id) }
                val length=repo.files!!.stage(id).length();Thread.sleep(1000);assertEquals(length,repo.files.stage(id).length())
                assertEquals(PauseReason.WIFI,repo.record(id)!!.pauseReason)
                i.uiAutomation.executeShellCommand("svc wifi enable").close();await { rt.wifiAvailable() }
                rt.kick() // Same coordinator entry used by the Activity's user-return event.
                await { repo.record(id)?.taskStatus==TaskStatus.SUCCEEDED }
                assertEquals(id,repo.record(id)!!.recordId)
                i.sendStatus(0,android.os.Bundle().apply { putString("wifiEvidence","real_radio_toggle;waiting_wifi;writer_frozen;user_return_hook;same_id_succeeded;ADB_tunnel_not_network_outage_proof") })
            } finally { i.uiAutomation.executeShellCommand("svc wifi enable").close();clean(rt,id) }
        }
    }
}
