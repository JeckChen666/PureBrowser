package com.example.purebrowser.download.hls

import com.example.purebrowser.download.*
import java.io.File
import java.io.InputStream

/** HLS-mode extractors may pick one PID per codec; explicitly reject multi-track TS first. */
internal object TsTrackInventory {
    fun validate(files:List<File>,cancel:TransferCancellation):Boolean {
        var hasAud=false
        var expected:Map<Int,Int>?=null
        files.forEach { file -> file.inputStream().use { input ->
            val result=read(input,cancel);val tracks=result.first;hasAud=hasAud || result.second
            if(expected!=null && expected!=tracks)fail("分片轨道配置发生变化")
            expected=tracks
        } };return hasAud
    }
    private fun read(input:InputStream,cancel:TransferCancellation):Pair<Map<Int,Int>,Boolean> {
        var aud=false;var zeroes=0;var nalHeader=false
        var pmtPid:Int?=null;var inventory:Map<Int,Int>?=null
        val assemblers=mutableMapOf<Int,Section>()
        val packet=ByteArray(188)
        while(true) {
            cancel.check();var read=0
            while(read<188) { val n=input.read(packet,read,188-read);if(n<0)break;read+=n }
            if(read==0)break
            if(read!=188 || packet[0]!=0x47.toByte())fail("TS 包边界无效")
            val a=packet[1].toInt() and 255;val b=packet[3].toInt() and 255
            if(a and 0x80!=0 || b and 0xc0!=0)fail("TS 包损坏或包含不支持的扰码")
            val pid=((a and 31) shl 8) or (packet[2].toInt() and 255)
            val adaptation=(b shr 4) and 3
            if(adaptation==0)fail("TS 包头无效")
            if(adaptation==2)continue
            var offset=4
            if(adaptation==3) { offset+=1+(packet[4].toInt() and 255);if(offset>188)fail("TS 适配字段越界") }
            if(offset==188)continue
            val start=a and 0x40!=0
            if(pid!=0 && pid!=pmtPid) {
                if(inventory?.get(pid)==0x1b) {
                    if(start && offset+9<=188 && packet[offset]==0.toByte() && packet[offset+1]==0.toByte() && packet[offset+2]==1.toByte())
                        offset=(offset+9+(packet[offset+8].toInt() and 255)).coerceAtMost(188)
                    for(j in offset until 188) {
                        val n=packet[j].toInt() and 255
                        if(nalHeader) { if(n and 31==9)aud=true;nalHeader=false }
                        if(n==1 && zeroes>=2) { nalHeader=true;zeroes=0 }
                        else if(n==0)zeroes++ else zeroes=0
                    }
                }
                continue
            }
            if(start) {
                val pointer=packet[offset].toInt() and 255;offset++
                if(offset+pointer>188)fail("TS 清单指针越界")
                // Complete a preceding split section using the pointer prefix, then start a new one.
                assemblers[pid]?.takeIf { it.started }?.append(packet,offset,pointer)?.let { section ->
                    if(pid==0)pmtPid=parsePat(section) else inventory=merge(inventory,parsePmt(section))
                }
                offset+=pointer;assemblers[pid]=Section()
            }
            val accumulator=assemblers[pid] ?: continue
            if(offset<188)accumulator.append(packet,offset,188-offset)?.let { section ->
                if(pid==0)pmtPid=parsePat(section) else inventory=merge(inventory,parsePmt(section))
            }
        }
        if(pmtPid==null)return fail("TS 缺少单节目 PAT")
        return (inventory ?: fail("TS 缺少可确认的音视频轨道")) to aud
    }
    private fun merge(old:Map<Int,Int>?,next:Map<Int,Int>):Map<Int,Int> {
        if(old!=null && old!=next)fail("TS 轨道配置发生变化")
        return next
    }
    private class Section {
        private val bytes=ByteArray(1024);private var used=0;private var expected=0;var started=true
        fun append(data:ByteArray,offset:Int,length:Int):ByteArray? {
            if(!started)return null
            for(i in offset until offset+length) {
                if(used==0 && data[i]==0xff.toByte()) { started=false;return null }
                if(used>=bytes.size)fail("TS 节目表过大")
                bytes[used++]=data[i]
                if(used==3) { expected=3+(((bytes[1].toInt() and 15) shl 8) or (bytes[2].toInt() and 255))
                    if(expected !in 12..1024)fail("TS 节目表长度无效") }
                if(expected>0 && used==expected) { started=false;return bytes.copyOf(used).also { verifyCrc(it) } }
            };return null
        }
    }
    private fun parsePat(s:ByteArray):Int {
        if(s[0].toInt()!=0 || s[1].toInt() and 0x80==0 || s[5].toInt() and 1==0 || s[6].toInt()!=0 || s[7].toInt()!=0)fail("不支持的 TS 节目表")
        val programs=mutableMapOf<Int,Int>();var i=8
        while(i<s.size-4) {
            if(i+4>s.size-4)fail("PAT 长度无效")
            val number=((s[i].toInt() and 255) shl 8) or (s[i+1].toInt() and 255)
            val pid=((s[i+2].toInt() and 31) shl 8) or (s[i+3].toInt() and 255)
            if(number!=0) { if(programs.put(number,pid)!=null)fail("PAT 节目重复") };i+=4
        }
        if(programs.size!=1)fail("本版只支持单节目 TS")
        return programs.values.single()
    }
    private fun parsePmt(s:ByteArray):Map<Int,Int> {
        if(s.size<16 || s[0].toInt()!=2 || s[1].toInt() and 0x80==0 || s[5].toInt() and 1==0 || s[6].toInt()!=0 || s[7].toInt()!=0)fail("不支持的 TS PMT")
        var i=12+(((s[10].toInt() and 15) shl 8) or (s[11].toInt() and 255))
        if(i>s.size-4)fail("PMT 描述字段越界")
        val tracks=linkedMapOf<Int,Int>()
        while(i<s.size-4) {
            if(i+5>s.size-4)fail("PMT 轨道越界")
            val type=s[i].toInt() and 255
            val pid=((s[i+1].toInt() and 31) shl 8) or (s[i+2].toInt() and 255)
            val length=((s[i+3].toInt() and 15) shl 8) or (s[i+4].toInt() and 255)
            i+=5+length;if(i>s.size-4)fail("PMT 轨道描述越界")
            if(type==0x15)continue // ID3 metadata is not an exported audio/video track.
            if(type !in setOf(0x1b,0x0f,0x11))fail("TS 包含不支持的编码或附加轨道")
            if(tracks.put(pid,type)!=null)fail("TS 轨道重复")
        }
        if(tracks.size!=2 || tracks.values.count { it==0x1b }!=1 || tracks.values.count { it==0x0f || it==0x11 }!=1)
            fail("本版需要一条 H.264 和一条 AAC 轨道，不自动挑选多语言音轨")
        return tracks
    }
    private fun verifyCrc(s:ByteArray) {
        var crc=-1
        for(byte in s) { crc=crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc=if(crc<0)(crc shl 1) xor 0x04c11db7 else crc shl 1 } }
        if(crc!=0)fail("TS 节目表校验失败")
    }
    private fun fail(message:String):Nothing=throw TransferFailure(FailureKind.UNSUPPORTED,message)
}
