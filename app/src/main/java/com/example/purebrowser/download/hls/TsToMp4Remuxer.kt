package com.example.purebrowser.download.hls

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsPayloadReader
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.example.purebrowser.download.*
import java.io.*
import java.nio.ByteBuffer
import kotlin.math.abs

/** No networking, transcoding, or TS concatenation disguised as MP4. All inputs are task-owned. */
@androidx.annotation.OptIn(UnstableApi::class)
class TsToMp4Remuxer {
    data class Result(val durationUs:Long, val videoSamples:Long, val audioSamples:Long)
    private class TrackStats(val format:Format) {
        var min=Long.MAX_VALUE; var max=Long.MIN_VALUE; var count=0L
        var previous=Long.MIN_VALUE; var smallestStep=Long.MAX_VALUE
        fun sample(time:Long) {
            if(time==C.TIME_UNSET) fail("分片缺少可靠时间戳")
            // PTS may go backwards for B frames, but large jumps are not this version's stable timeline.
            if(previous!=Long.MIN_VALUE && abs(time-previous)>30_000_000) fail("分片时间轴不连续")
            if(previous!=Long.MIN_VALUE && time>previous) smallestStep=minOf(smallestStep,time-previous)
            previous=time;min=minOf(min,time);max=maxOf(max,time);count++
            if(count>1_000_000) fail("音视频采样数量超过本版处理上限")
        }
        fun end():Long = max + if(format.sampleMimeType==MimeTypes.AUDIO_AAC) {
            if(format.sampleRate<=0) fail("音频采样率无效")
            1024_000_000L/format.sampleRate
        } else smallestStep.takeIf { it in 1..1_000_000 } ?: fail("视频帧时长无法确认")
    }
    fun remux(segments:List<File>, output:File, expectedDurationUs:Long,
              cancel:TransferCancellation, onProgress:(Long)->Unit={}):Result {
        require(segments.isNotEmpty())
        segments.forEach { file ->
            cancel.check()
            if(!file.isFile || file.length()<188*5 || file.length()%188!=0L) fail("分片不是完整 MPEG-TS 数据")
            file.inputStream().use { stream ->
                val prefix=ByteArray(188*5); if(stream.read(prefix)!=prefix.size || (0..4).any { prefix[it*188]!=0x47.toByte() }) fail("分片响应不是 MPEG-TS")
            }
        }
        val detectAccessUnits=!TsTrackInventory.validate(segments,cancel)
        val stats=linkedMapOf<Int,TrackStats>()
        extract(segments,cancel,stats,null,0L,onProgress,detectAccessUnits)
        val video=stats.values.singleOrNull { it.format.sampleMimeType==MimeTypes.VIDEO_H264 } ?: fail("需要一条 H.264 视频轨道")
        val audio=stats.values.singleOrNull { it.format.sampleMimeType==MimeTypes.AUDIO_AAC } ?: fail("需要一条同组 AAC 音频轨道")
        if(stats.size!=2 || video.count<2 || audio.count<2) fail("音视频轨道不完整")
        val start=stats.values.minOf { it.min }
        val end=stats.values.maxOf { it.end() }
        val duration=end-start
        val tolerance=maxOf(2_000_000L,minOf(5_000_000L,expectedDurationUs/1000))
        if(abs(duration-expectedDurationUs)>tolerance) fail("音视频时长与清单不符，未发布成品")
        // A second streaming pass permits one shared offset, preserves A/V timing and handles B-frame PTS.
        try {
            FileOutputStream(output).use { stream ->
                Mp4Muxer.Builder(SeekableMuxerOutput.of(stream)).setSampleBatchingEnabled(false).build().use { muxer ->
                    val ids=stats.mapValues { (_,s)->muxer.addTrack(s.format) }
                    extract(segments,cancel,linkedMapOf(),Pair(muxer,ids),start,onProgress,detectAccessUnits)
                    stats.forEach { (key,s)->
                        muxer.writeSampleData(ids.getValue(key),ByteBuffer.allocate(0),BufferInfo(s.end()-start,0,C.BUFFER_FLAG_END_OF_STREAM))
                    }
                }
            }
            cancel.check()
            RandomAccessFile(output,"rw").use { it.fd.sync() }
            return Result(duration,video.count,audio.count)
        } catch(e:Exception) { output.delete(); throw e }
    }
    private fun extract(files:List<File>,cancel:TransferCancellation,stats:MutableMap<Int,TrackStats>,
                        writer:Pair<Mp4Muxer,Map<Int,Int>>?, offsetUs:Long,progress:(Long)->Unit,detectAccessUnits:Boolean) {
        val endings=mutableListOf<PesReader>()
        val defaults=DefaultTsPayloadReaderFactory(if(detectAccessUnits)DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS else 0)
        val factory=object:TsPayloadReader.Factory {
            override fun createInitialPayloadReaders()=defaults.createInitialPayloadReaders()
            override fun createPayloadReader(type:Int,info:TsPayloadReader.EsInfo):TsPayloadReader? {
                val reader=defaults.createPayloadReader(type,info) ?: return null
                return if(reader is PesReader && type in setOf(0x1b,0x0f,0x11)) {
                    endings.add(reader)
                    // The HLS EOF guard reparses reused header scratch. Own one final flush instead.
                    object:TsPayloadReader by reader {}
                } else reader
            }
        }
        val extractor=TsExtractor(TsExtractor.MODE_HLS,TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,TimestampAdjuster(0),factory,TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
        val outputs=mutableMapOf<Int,TrackOutput>()
        extractor.init(object:ExtractorOutput {
            override fun track(id:Int,type:Int):TrackOutput = outputs.getOrPut(id) {
                if(type !in setOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO)) DiscardingTrackOutput()
                else Samples(id,cancel,stats,writer,offsetUs)
            }
            override fun endTracks() {}
            override fun seekMap(seekMap:SeekMap) {}
        })
        // One virtual stream keeps PES/NAL data at segment boundaries and flushes the final sample once.
        val enumeration=java.util.Collections.enumeration(files.map { file -> LazyFileInput(file,cancel) as InputStream })
        SequenceInputStream(enumeration).use { stream ->
            val reader=DataReader { buffer,off,len -> cancel.check(); stream.read(buffer,off,len) }
            val input=DefaultExtractorInput(reader,0,files.sumOf { it.length() })
            try {
                if(!extractor.sniff(input)) fail("无法识别 MPEG-TS 分片")
                input.resetPeekPosition()
                val position=PositionHolder();var lastProgress=0L
                while(true) {
                    cancel.check()
                    when(extractor.read(input,position)) {
                        Extractor.RESULT_END_OF_INPUT -> {
                            endings.forEach { reader ->
                                if(!reader.canConsumeSynthesizedEmptyPusi(false))fail("TS PES 数据未完整结束")
                                reader.consume(ParsableByteArray(),TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR)
                            }
                            break
                        }
                        Extractor.RESULT_SEEK -> fail("分片要求不支持的随机读取")
                    }
                    if(input.position-lastProgress>=1024*1024) { progress(input.position);lastProgress=input.position }
                }
                progress(input.position)
            } finally { extractor.release() }
        }
    }
    private class Samples(val id:Int,val cancel:TransferCancellation,val stats:MutableMap<Int,TrackStats>,
                          val writer:Pair<Mp4Muxer,Map<Int,Int>>?,val offsetUs:Long):TrackOutput {
        var bytes=ByteArray(65536);var used=0
        override fun format(format:Format) {
            if(format.sampleMimeType !in setOf(MimeTypes.VIDEO_H264,MimeTypes.AUDIO_AAC)) fail("本版只支持 H.264 与 AAC")
            val old=stats[id]?.format
            if(old!=null && (old.sampleMimeType!=format.sampleMimeType || old.width!=format.width || old.height!=format.height ||
                old.sampleRate!=format.sampleRate || old.channelCount!=format.channelCount ||
                old.initializationData.size!=format.initializationData.size || old.initializationData.indices.any { !old.initializationData[it].contentEquals(format.initializationData[it]) })) fail("分片编码参数发生变化")
            if(old==null) stats[id]=TrackStats(format)
            if(writer!=null && id !in writer.second) fail("分片轨道发生变化")
        }
        private fun reserve(n:Int) {
            cancel.check();if(n<0 || n>16*1024*1024-used) fail("单个媒体采样超过处理上限")
            if(used+n>bytes.size) bytes=bytes.copyOf(minOf(16*1024*1024,maxOf(used+n,bytes.size*2)))
        }
        override fun sampleData(input:DataReader,length:Int,allowEndOfInput:Boolean,sampleDataPart:Int):Int {
            reserve(length);val n=input.read(bytes,used,length)
            if(n<0) { if(!allowEndOfInput) throw EOFException();return -1 };used+=n;return n
        }
        override fun sampleData(data:ParsableByteArray,length:Int,sampleDataPart:Int) { reserve(length);data.readBytes(bytes,used,length);used+=length }
        override fun sampleMetadata(timeUs:Long,flags:Int,size:Int,offset:Int,cryptoData:TrackOutput.CryptoData?) {
            cancel.check();if(cryptoData!=null || flags and C.BUFFER_FLAG_ENCRYPTED!=0) fail("不支持加密媒体采样")
            val from=used-offset-size
            if(size<=0 || offset<0 || from<0) fail("媒体采样边界无效")
            val track=stats[id] ?: fail("媒体轨道缺少格式")
            if(track.format.sampleMimeType==MimeTypes.VIDEO_H264)verifyAvcParameters(bytes,from,size,track.format)
            val hasPicture=track.format.sampleMimeType!=MimeTypes.VIDEO_H264 || containsAvcPicture(bytes,from,size)
            if(!hasPicture) { bytes.copyInto(bytes,0,used-offset,used);used=offset;return }
            track.sample(timeUs)
            writer?.let { (muxer,ids) ->
                val pts=timeUs-offsetUs;if(pts<0) fail("媒体时间轴无效")
                muxer.writeSampleData(ids.getValue(id),ByteBuffer.wrap(bytes,from,size).slice(),BufferInfo(pts,size,flags and C.BUFFER_FLAG_KEY_FRAME))
            }
            bytes.copyInto(bytes,0,used-offset,used);used=offset
        }
    }
    private class LazyFileInput(val file:File,val cancel:TransferCancellation):InputStream() {
        var stream:InputStream?=null
        private fun stream():InputStream { cancel.check();return stream ?: file.inputStream().also { stream=it } }
        override fun read()=stream().read()
        override fun read(b:ByteArray,off:Int,len:Int)=stream().read(b,off,len)
        override fun close() { stream?.close() }
    }
    companion object {
        private fun fail(message:String):Nothing=throw TransferFailure(FailureKind.UNSUPPORTED,message)
        /** Access-unit detection can emit AUD/SPS-only units; they are not MP4 video samples. */
        private fun containsAvcPicture(bytes:ByteArray,offset:Int,size:Int):Boolean {
            var i=offset;val end=offset+size
            while(i+3<end) {
                if(bytes[i]==0.toByte() && bytes[i+1]==0.toByte() && bytes[i+2]==1.toByte()) {
                    val type=bytes[i+3].toInt() and 31
                    if(type in 1..5)return true
                    i+=3
                } else i++
            }
            return false
        }
        /** H264Reader may emit Format only once; validate later in-band SPS/PPS too. */
        private fun verifyAvcParameters(bytes:ByteArray,offset:Int,size:Int,format:Format) {
            fun canonical(data:ByteArray,start:Int,end:Int):ByteArray {
                var a=start;var b=end
                while(a<b && data[a]==0.toByte())a++
                if(a<b && data[a]==1.toByte())a++
                while(b>a && data[b-1]==0.toByte())b--
                return data.copyOfRange(a,b)
            }
            val allowed=format.initializationData.map { canonical(it,0,it.size) }
            var nalStart=-1;var i=offset;val end=offset+size
            fun checkNal(until:Int) {
                if(nalStart>=0 && nalStart<until) {
                    val type=bytes[nalStart].toInt() and 31
                    if(type==7 || type==8) {
                        val current=canonical(bytes,nalStart,until)
                        if(allowed.none { it.contentEquals(current) })fail("分片 H.264 编码参数发生变化")
                    }
                }
            }
            while(i+2<end) {
                if(bytes[i]==0.toByte() && bytes[i+1]==0.toByte() && bytes[i+2]==1.toByte()) {
                    checkNal(i);nalStart=i+3;i+=3
                } else i++
            }
            checkNal(end)
        }
    }
}
