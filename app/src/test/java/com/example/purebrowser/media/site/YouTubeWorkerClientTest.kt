package com.example.purebrowser.media.site
import org.junit.Assert.*
import org.junit.Test
class YouTubeWorkerClientTest {
 @Test fun allowlistedClientNamesPassThroughUnchanged(){for(c in listOf("IOS","ANDROID","ANDROID_VR","TV"))assertEquals(c,sanitizeYouTubeWorkerClient(c))}
 @Test fun anythingElseFallsBackToTheDefaultIosPath(){for(c in listOf(null,"","ios","IOS ","TVHTML5","WEB","EMBEDDED","IOS&client=WEB","ANDROID_VR_SIM","IOS\r","IOS/TV","IOS'--"))assertNull(sanitizeYouTubeWorkerClient(c))}
 @Test fun allowlistIsExactlyTheFourWorkerNames(){assertEquals(setOf("IOS","ANDROID","ANDROID_VR","TV"),YOUTUBE_WORKER_CLIENTS)}
}
