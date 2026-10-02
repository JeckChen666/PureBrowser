package com.example.purebrowser.download
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
class LegacyPublicFileDiagnosticTest {
    @Test fun publishStageThroughActualPlatformPublicFileBackend() {
        val i=InstrumentationRegistry.getInstrumentation();val app=i.targetContext
        val files=ManagedFileStore(app);val id=UUID.randomUUID().toString();val stage=files.stage(id)
        i.context.assets.open("test-video.mp4").use { input ->stage.outputStream().use { input.copyTo(it) } }
        try {
            val inspection=files.inspect(stage);assertEquals(FormatCheck.PASSED,inspection.format)
            val record=DownloadRecord(recordId=id,name="${id.take(8)}_platform-test.mp4",transfer=TransferType.CONTROLLED)
            val asset=files.publish(record,stage,inspection,{},TransferCancellation())
            try { assertEquals(FileAvailability.AVAILABLE,files.access(asset).availability) } finally { assertTrue(files.delete(asset)) }
        } finally { files.removeStage(id) }
    }
}
