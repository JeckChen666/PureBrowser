package com.example.purebrowser.media.site

import java.net.URI
import java.net.URLDecoder

object YouTubeIdentity {
    fun videoId(value:String):String? = runCatching {
        if(value.length>8192)return null
        val u=URI(value)
        if(!u.scheme.equals("https",true) || u.rawUserInfo!=null || u.port !in setOf(-1,443))return null
        val host=u.host?.lowercase() ?: return null
        val id=when(host) {
            "youtu.be" -> u.path?.removePrefix("/")?.takeIf { '/' !in it }
            "youtube.com","www.youtube.com","m.youtube.com" -> {
                if(u.path!="/watch")return null
                val ids=u.rawQuery.orEmpty().split('&').mapNotNull { entry ->
                    val parts=entry.split('=',limit=2)
                    if(parts.first()=="v" && parts.size==2)URLDecoder.decode(parts[1],"UTF-8") else null
                }
                ids.singleOrNull()
            }
            else -> null
        }
        id?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
    }.getOrNull()
}
