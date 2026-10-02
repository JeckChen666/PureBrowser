package com.example.purebrowser.download.hls

import android.util.AtomicFile
import com.example.purebrowser.download.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Bounded, atomically frozen plans. Paths come from TaskId, never from websites or persisted paths. */
class HlsWorkspace(private val root:File) {
    private fun directory(id:TaskId):File {
        require(Regex("[a-zA-Z0-9-]{1,100}").matches(id));root.mkdirs()
        require(!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val dir=File(root,id);require(!java.nio.file.Files.isSymbolicLink(dir.toPath()))
        require(dir.canonicalFile.parentFile==root.canonicalFile);return dir
    }
    fun segment(id:TaskId,index:Int):File {
        require(index in 0..9999);return File(directory(id).apply { mkdirs() },"segment-$index.ts")
    }
    fun requireSpace(id:TaskId,bytes:Long=65536) {
        val dir=directory(id).apply { mkdirs() }
        if(android.os.StatFs(dir.path).availableBytes < bytes+256L*1024*1024) throw TransferFailure(FailureKind.STORAGE,"可用空间不足，未保存成品")
    }
    fun save(id:TaskId,plan:HlsDownloadPlan) {
        require(plan.media.segments.size in 1..10000)
        val rows=JSONArray();plan.media.segments.forEachIndexed { i,s ->
            require(i==s.index && s.durationUs>0);rows.put(JSONObject().put("url",s.url).put("durationUs",s.durationUs))
        }
        val json=JSONObject().put("version",1).put("entryUrl",plan.entryUrl).put("playlistUrl",plan.playlistUrl)
            .put("durationUs",plan.media.durationUs).put("targetDurationUs",plan.media.targetDurationUs).put("segments",rows)
        plan.variant?.let { v -> json.put("variant",JSONObject().put("url",v.url).put("width",v.width ?: JSONObject.NULL)
            .put("height",v.height ?: JSONObject.NULL).put("bandwidth",v.bandwidth ?: JSONObject.NULL).put("codecs",v.codecs ?: JSONObject.NULL)) }
        val bytes=json.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=4*1024*1024) { "分片计划超过处理上限" }
        val atomic=AtomicFile(File(directory(id).apply { mkdirs() },"plan.json"));val out=atomic.startWrite()
        try { out.write(bytes);atomic.finishWrite(out) } catch(e:Exception) { atomic.failWrite(out);throw e }
    }
    fun load(id:TaskId):HlsDownloadPlan {
        val bytes=AtomicFile(File(directory(id),"plan.json")).openRead().use { input -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=4*1024*1024);out.write(buffer,0,n) };out.toByteArray() }
        require(bytes.size<=4*1024*1024)
        val o=JSONObject(bytes.toString(Charsets.UTF_8));require(o.getInt("version")==1)
        val a=o.getJSONArray("segments");require(a.length() in 1..10000)
        val seg=(0 until a.length()).map { i -> a.getJSONObject(i).let { HlsSegment(it.getString("url"),it.getLong("durationUs"),i) } }
        require(seg.all { it.durationUs>0 && it.url.length<=8192 } && seg.sumOf { it.durationUs }==o.getLong("durationUs"))
        val variant=if(o.has("variant"))o.getJSONObject("variant").let { v -> HlsVariant(v.getString("url"),
            if(v.isNull("bandwidth"))null else v.getLong("bandwidth"),if(v.isNull("width"))null else v.getInt("width"),
            if(v.isNull("height"))null else v.getInt("height"),if(v.isNull("codecs"))null else v.getString("codecs"),true,null) } else null
        return HlsDownloadPlan(o.getString("entryUrl"),o.getString("playlistUrl"),HlsPlaylist.Media(seg,o.getLong("durationUs"),o.getLong("targetDurationUs")),variant)
    }
    fun cleanSegments(id:TaskId) { val dir=directory(id);dir.listFiles()?.filter { Regex("segment-[0-9]+\\.ts(?:\\.part)?").matches(it.name) }?.forEach { check(it.delete()) } }
    fun delete(id:TaskId) { val dir=directory(id);if(dir.exists())check(dir.deleteRecursively()) { "任务临时文件暂时无法清理" } }
}
