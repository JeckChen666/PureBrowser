package com.example.purebrowser.media.site
import org.junit.Assert.*
import org.junit.Test
class YouTubeIdentityTest {
 @Test fun acceptedPublicSingleVideoForms(){for(u in listOf("https://www.youtube.com/watch?v=eRsGyueVLvQ&list=no-expand","https://m.youtube.com/watch?v=eRsGyueVLvQ","https://youtu.be/eRsGyueVLvQ"))assertEquals("eRsGyueVLvQ",YouTubeIdentity.videoId(u))}
 @Test fun rejectsSpoofedAmbiguousOrUnsupportedInputs(){for(u in listOf("https://youtube.com.evil.example/watch?v=eRsGyueVLvQ","https://user@youtube.com/watch?v=eRsGyueVLvQ","http://youtube.com/watch?v=eRsGyueVLvQ","https://youtube.com:444/watch?v=eRsGyueVLvQ","https://youtube.com/watch?v=eRsGyueVLvQ&v=abcdefghijk","https://youtube.com/shorts/eRsGyueVLvQ","https://youtu.be/eRsGyueVLvQ/other","https://youtube.com/watch?v=bad","https://youtube.com/watch?v=%ZZ"))assertNull(u,YouTubeIdentity.videoId(u))}
}
