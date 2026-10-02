package com.example.purebrowser.download

/** Small container discriminator; EBML is not automatically WebM and QuickTime is not MP4. */
object MediaContainer {
    fun mime(prefix:ByteArray):String? {
        if(prefix.size>=12 && String(prefix,4,4,Charsets.US_ASCII)=="ftyp") {
            val allowed=setOf("isom","iso2","iso3","iso4","iso5","iso6","mp41","mp42","avc1","M4V ","MSNV","dash","cmfc","cmfs")
            return if(String(prefix,8,4,Charsets.US_ASCII) in allowed) "video/mp4" else null
        }
        if(prefix.size<5 || prefix.take(4).map { it.toInt() and 255 }!=listOf(0x1a,0x45,0xdf,0xa3))return null
        fun number(offset:Int,strip:Boolean):Pair<Long,Int>? {
            if(offset>=prefix.size)return null
            val first=prefix[offset].toInt() and 255
            val bytes=(1..8).firstOrNull { first and (0x80 shr (it-1)) !=0 } ?: return null
            if(offset+bytes>prefix.size)return null
            var value=if(strip) (first and ((0x80 shr (bytes-1))-1)).toLong() else first.toLong()
            for(i in 1 until bytes)value=(value shl 8) or (prefix[offset+i].toLong() and 255)
            return value to bytes
        }
        val length=number(4,true) ?: return null
        val limit=(4L+length.second+length.first).coerceAtMost(prefix.size.toLong()).toInt()
        var position=4+length.second
        while(position<limit) {
            val id=number(position,false) ?: return null;position+=id.second
            val size=number(position,true) ?: return null;position+=size.second
            if(size.first>limit-position || size.first<0)return null
            if(id.first==0x4282L) return if(String(prefix,position,size.first.toInt(),Charsets.US_ASCII)=="webm") "video/webm" else null
            position+=size.first.toInt()
        }
        return null
    }
}
