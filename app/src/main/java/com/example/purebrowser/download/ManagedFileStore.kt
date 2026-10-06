package com.example.purebrowser.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import com.example.purebrowser.media.codec.DeviceAv1CapabilityProvider
import java.io.File
import java.security.MessageDigest

/** Where publish() mints a finished file for a given platform level. */
enum class PublishRoute { MEDIASTORE, APP_EXTERNAL }

/**
 * Pure publish-route decision (SDK injected for tests). A targetSdk-30+ app never receives the
 * sdcard_rw gid for WRITE_EXTERNAL_STORAGE on Android 9 and below, so the legacy public
 * Download write fails deterministically (v0.1.7 E3 field evidence: createNewFile EACCES with
 * the runtime grant present). Those devices publish into app-specific external storage instead.
 */
object PublishRoutePolicy {
    fun forSdk(sdkInt: Int): PublishRoute = if (sdkInt >= 29) PublishRoute.MEDIASTORE else PublishRoute.APP_EXTERNAL

    /** User-facing save-location label for the same decision (settings and pre-save copy). */
    fun savePathLabel(sdkInt: Int): String = if (sdkInt >= 29) "Download/PureBrowser" else "应用专属外部目录（Download）"
}

/** All public outputs are minted here; never accepts webpage file paths. */
class ManagedFileStore(private val app: Context) {
    companion object {
        /** User-visible relocation note for app-external outputs (Android 9-: no public write). */
        const val APP_EXTERNAL_HINT = "已保存到应用专属目录；此系统版本不允许直接写入公共下载，可打开或分享后另存"
    }

    val directCheckpoints=DirectCheckpointStore(app.filesDir)
    val dualTrackWorkspace=DualTrackWorkspace(app.filesDir)
    val hlsWorkspace=com.example.purebrowser.download.hls.HlsWorkspace(File(app.filesDir,"hls"))
    val dashWorkspace=com.example.purebrowser.download.dash.DashWorkspace(app.filesDir)
    private val stages=File(app.filesDir,"transfers").apply { mkdirs() }
    fun stage(id:TaskId):File {
        require(Regex("[a-zA-Z0-9-]{1,100}").matches(id))
        require(!java.nio.file.Files.isSymbolicLink(stages.toPath()))
        val f=File(stages,"$id.part")
        require(!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.canonicalFile.parentFile==stages.canonicalFile)
        return f
    }
    fun removeStage(id:TaskId) { val f=stage(id); if(f.exists()) check(f.delete()) { "临时文件暂时无法清理" } }
    fun cacheBytes(id:TaskId):Long = runCatching { stage(id).length()+hlsWorkspace.cacheBytes(id)+dualTrackWorkspace.cacheBytes(id)+dashWorkspace.cacheBytes(id) }.getOrDefault(0L)
    fun clearPrivate(id:TaskId) { removeStage(id);directCheckpoints.delete(id);hlsWorkspace.delete(id);dualTrackWorkspace.delete(id);dashWorkspace.delete(id) }
    fun inspect(file:File):MediaInspection {
        val header=file.inputStream().use { input -> ByteArray(4096).let { bytes -> val n=input.read(bytes); bytes.copyOf(n.coerceAtLeast(0)) } }
        val mime=MediaContainer.mime(header) ?: return MediaInspection(FormatCheck.INVALID)
        val extractor=MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val i=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                ?: return MediaInspection(FormatCheck.INVALID)
            extractor.selectTrack(i)
            if(extractor.sampleTime<0) return MediaInspection(FormatCheck.INVALID)
            val format=extractor.getTrackFormat(i)
            // T110 成品校验含解码可用性: an AV1 track saved on a device with no AV1 decoder at
            // all can never play back — reject it here instead of publishing an unusable file.
            if(com.example.purebrowser.media.codec.Av1Capability.rejectsFinishedProduct(
                    format.getString(MediaFormat.KEY_MIME), DeviceAv1CapabilityProvider.support()))
                return MediaInspection(FormatCheck.INVALID)
            val duration=if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)/1000 else null
            MediaInspection(FormatCheck.PASSED,mime,duration)
        } catch(_:Exception) { MediaInspection(FormatCheck.UNCONFIRMED) } finally { extractor.release() }
    }
    /** HLS requires both tracks, reliable duration and readable beginning/middle/end samples. */
    fun inspectHls(file:File,expectedDurationUs:Long):MediaInspection {
        val base=inspect(file)
        if(base.format!=FormatCheck.PASSED || base.mimeType!="video/mp4")return MediaInspection(FormatCheck.INVALID)
        val extractor=MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val tracks=(0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            if(tracks.size!=2 || tracks.count { it.second.getString(MediaFormat.KEY_MIME)=="video/avc" }!=1 ||
                tracks.count { it.second.getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm" }!=1)return MediaInspection(FormatCheck.INVALID)
            val tolerance=maxOf(2_000_000L,minOf(5_000_000L,expectedDurationUs/1000))
            val duration=tracks.maxOf { (_,format)->if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION) else -1L }
            if(duration<=0 || kotlin.math.abs(duration-expectedDurationUs)>tolerance)return MediaInspection(FormatCheck.INVALID)
            tracks.forEach { (index,format)->
                extractor.selectTrack(index)
                val ownDuration=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION) else -1L
                if(ownDuration<=0 || kotlin.math.abs(ownDuration-expectedDurationUs)>tolerance)return MediaInspection(FormatCheck.INVALID)
                val buffer=java.nio.ByteBuffer.allocate(1024*1024)
                for(point in listOf(0L,duration/2,(duration-1_000_000L).coerceAtLeast(0))) {
                    extractor.seekTo(point,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if(extractor.sampleTime<0 || extractor.readSampleData(buffer,0)<=0)return MediaInspection(FormatCheck.INVALID)
                    buffer.clear()
                }
                extractor.unselectTrack(index)
            }
            MediaInspection(FormatCheck.PASSED,"video/mp4",duration/1000)
        } catch(_:Exception) { MediaInspection(FormatCheck.UNCONFIRMED) } finally { extractor.release() }
    }
    /**
     * Independent dual-track check: match the muxer's 4 MiB sample bound without changing HLS.
     * Each track is probed on its OWN timeline (a shorter track must not seek past its tail).
     * This is structural/readability verification, not proof of decoded playback or work identity.
     */
    fun inspectDualTrack(file:File,expectedDurationUs:Long,cancel:TransferCancellation?=null):MediaInspection {
        cancel?.check()
        val clearStructure=try {
            DirectCheckpointStore.checkLeaf(file)
            DirectCheckpointStore.noFollowInput(file).use { DualTrackMp4Protection.clear(it.channel,cancel) }
        } catch(e:java.util.concurrent.CancellationException) { throw e }
        catch(_:Exception) { return MediaInspection(FormatCheck.UNCONFIRMED) }
        if(!clearStructure)return MediaInspection(FormatCheck.INVALID)
        val base=inspect(file)
        if(base.format!=FormatCheck.PASSED || base.mimeType!="video/mp4")return MediaInspection(FormatCheck.INVALID)
        val extractor=MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            if(!extractor.psshInfo.isNullOrEmpty() || (Build.VERSION.SDK_INT>=24 && extractor.drmInitData!=null))
                return MediaInspection(FormatCheck.INVALID)
            if(extractor.trackCount!=2)return MediaInspection(FormatCheck.INVALID)
            val tracks=(0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            if(tracks.count { it.second.getString(MediaFormat.KEY_MIME)=="video/avc" }!=1 ||
                tracks.count { it.second.getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm" }!=1)
                return MediaInspection(FormatCheck.INVALID)
            val tolerance=maxOf(2_000_000L,minOf(5_000_000L,expectedDurationUs/1000))
            val buffer=java.nio.ByteBuffer.allocate(4*1024*1024)
            var duration=0L
            tracks.forEach { (index,format)->
                cancel?.check()
                if(Build.VERSION.SDK_INT>=26 && extractor.getCasInfo(index)!=null)return MediaInspection(FormatCheck.INVALID)
                val ownDuration=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION) else -1L
                if(ownDuration<=0 || ownDuration>com.example.purebrowser.download.site.DualTrackMetadata.MAX_DURATION_US+tolerance ||
                    kotlin.math.abs(ownDuration-expectedDurationUs)>tolerance)return MediaInspection(FormatCheck.INVALID)
                duration=maxOf(duration,ownDuration)
                extractor.selectTrack(index)
                for(point in listOf(0L,ownDuration/2,(ownDuration-1_000_000L).coerceAtLeast(0))) {
                    cancel?.check()
                    extractor.seekTo(point,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if(extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)!=0)
                        return MediaInspection(FormatCheck.INVALID)
                    if(extractor.sampleTime<0 || extractor.sampleTime>ownDuration+tolerance ||
                        (Build.VERSION.SDK_INT>=28 && extractor.sampleSize !in 1..buffer.capacity().toLong()))
                        return MediaInspection(FormatCheck.INVALID)
                    buffer.clear()
                    if(extractor.readSampleData(buffer,0) !in 1..buffer.capacity())return MediaInspection(FormatCheck.INVALID)
                }
                extractor.unselectTrack(index)
            }
            cancel?.check()
            MediaInspection(FormatCheck.PASSED,"video/mp4",duration/1000)
        } catch(e:java.util.concurrent.CancellationException) { throw e }
        catch(_:Exception) { MediaInspection(FormatCheck.UNCONFIRMED) } finally { extractor.release() }
    }

    private fun legacyFile(name: String): File {
        require(name == DownloadRules.safeFileName(name) || (name.length <= 200 && Regex("[\\p{L}\\p{N}._-]+").matches(name)))
        // Android's legacy emulated-storage root itself is a legitimate platform alias.
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = File(downloads, "PureBrowser")
        if (base.exists()) require(OsConstants.S_ISDIR(Os.lstat(base.path).st_mode) && !OsConstants.S_ISLNK(Os.lstat(base.path).st_mode))
        val f = File(base, name)
        require(f.canonicalFile.parentFile == base.canonicalFile)
        return f
    }

    /** App-specific external root for the API<29 route; null while external storage is unavailable. */
    private fun appExternalBase(): File? {
        val root = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val base = File(root, "PureBrowser")
        if (base.exists()) require(OsConstants.S_ISDIR(Os.lstat(base.path).st_mode) && !OsConstants.S_ISLNK(Os.lstat(base.path).st_mode))
        return base
    }

    private fun appExternalFile(name: String): File {
        require(name == DownloadRules.safeFileName(name) || (name.length <= 200 && Regex("[\\p{L}\\p{N}._-]+").matches(name)))
        val base = appExternalBase() ?: throw TransferFailure(FailureKind.STORAGE, "外部存储暂不可用，无法保存成品")
        val f = File(base, name)
        require(f.canonicalFile.parentFile == base.canonicalFile)
        return f
    }

    fun publish(record: DownloadRecord, file: File, inspection: MediaInspection, onPending: (String) -> Unit, cancel: TransferCancellation): VideoAsset {
        cancel.check()
        val mime = inspection.mimeType ?: error("容器未确认")
        val uri: Uri
        val location: AssetLocation
        if (Build.VERSION.SDK_INT >= 29 && PublishRoutePolicy.forSdk(Build.VERSION.SDK_INT) == PublishRoute.MEDIASTORE) {
            val values=ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,record.name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/PureBrowser/");put(MediaStore.MediaColumns.IS_PENDING,1)
            }
            uri=app.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values)
                ?: throw TransferFailure(FailureKind.STORAGE,"无法创建公共下载文件")
            try {
                onPending(uri.toString())
                check(mediaOwned(uri,record.name)) { "目标文件名不一致，未覆盖原文件" }
                app.contentResolver.openOutputStream(uri,"w")?.use { output -> file.inputStream().use { input ->
                    val buffer=ByteArray(65536)
                    while(true) { cancel.check();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n) }
                } } ?: throw TransferFailure(FailureKind.STORAGE,"无法写入公共下载文件")
                cancel.check()
                val digest=hash(file)
                val copied=app.contentResolver.openInputStream(uri)?.use { hash(it) }
                check(copied==digest) { "成品写入校验失败" }
                val update=ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) }
                check(app.contentResolver.update(uri,update,null,null)==1)
            } catch(e:Exception) { runCatching { app.contentResolver.delete(uri,null,null) };throw e }
            location=AssetLocation.MEDIASTORE_DOWNLOAD
        } else {
            // Android 9-: no public-write gid, so the finished file lands in the app's own
            // external folder (user-reachable on those versions) with a relocation hint in the UI.
            val out = appExternalFile(record.name)
            out.parentFile!!.mkdirs()
            check(!out.exists()) { "目标文件已存在，未覆盖" }
            val partial = File(out.parentFile, ".pb-${record.recordId}.part")
            check(partial.createNewFile()) { "临时公共文件已存在，未覆盖" }
            uri = FileProvider.getUriForFile(app, "${app.packageName}.files", out)
            try {
                onPending(uri.toString())
                partial.outputStream().use { output -> file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while(true) { cancel.check();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n) }
                } }
                cancel.check();check(hash(partial)==hash(file)) { "成品写入校验失败" }
                check(!out.exists()) { "目标文件已存在，未覆盖" }
                java.nio.file.Files.move(partial.toPath(),out.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch(e:Exception) { partial.delete();throw e }
            location = AssetLocation.APP_EXTERNAL_FILE
        }
        return VideoAsset(record.recordId,null,uri.toString(),record.name,record.displayName,System.currentTimeMillis(),
            sizeBytes=file.length(),mimeType=mime,format=FormatCheck.PASSED,availability=FileAvailability.AVAILABLE,
            durationMillis=inspection.durationMillis,location=location)
    }
    private fun hash(file:File)=file.inputStream().use(::hash)
    private fun hash(input:java.io.InputStream):String {
        val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true) { val n=input.read(buffer);if(n<0)break;md.update(buffer,0,n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun mediaShape(uri:Uri):Boolean = uri.scheme=="content" && uri.authority=="media" &&
        uri.pathSegments.size==3 && uri.pathSegments[0]=="external_primary" && uri.pathSegments[1]=="downloads" &&
        uri.lastPathSegment?.toLongOrNull()?.let { it>0 }==true && uri.query==null && uri.fragment==null
    private fun mediaOwned(uri:Uri,expectedName:String?=null):Boolean {
        if(Build.VERSION.SDK_INT<29 || !mediaShape(uri))return false
        return runCatching { app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use {
            it.moveToFirst() && it.getString(0)==app.packageName && it.getString(1)=="Download/PureBrowser/" && (expectedName==null || it.getString(2)==expectedName)
        }==true }.getOrDefault(false)
    }
    fun owned(asset:VideoAsset):Boolean=when(asset.location) {
        AssetLocation.SYSTEM_DOWNLOAD -> false
        AssetLocation.MEDIASTORE_DOWNLOAD -> mediaOwned(Uri.parse(asset.uri),asset.name)
        AssetLocation.APP_EXTERNAL_FILE -> runCatching {
            val f=appExternalFile(asset.name)
            asset.uri==FileProvider.getUriForFile(app,"${app.packageName}.files",f).toString() &&
                (!f.exists() || OsConstants.S_ISREG(Os.lstat(f.path).st_mode))
        }.getOrDefault(false)
        AssetLocation.LEGACY_PUBLIC_FILE -> runCatching {
            val f=legacyFile(asset.name)
            asset.uri==FileProvider.getUriForFile(app,"${app.packageName}.files",f).toString() &&
                (!f.exists() || OsConstants.S_ISREG(Os.lstat(f.path).st_mode))
        }.getOrDefault(false)
    }
    fun access(asset:VideoAsset):FileAccess {
        if(asset.location==AssetLocation.MEDIASTORE_DOWNLOAD && !mediaShape(Uri.parse(asset.uri)))return FileAccess(FileAvailability.UNREADABLE)
        if(asset.location==AssetLocation.MEDIASTORE_DOWNLOAD && !mediaOwned(Uri.parse(asset.uri),asset.name)) {
            val missing=runCatching { app.contentResolver.query(Uri.parse(asset.uri),arrayOf("_id"),null,null,null)?.use { !it.moveToFirst() }==true }.getOrDefault(false)
            return FileAccess(if(missing) FileAvailability.MISSING else FileAvailability.UNREADABLE)
        }
        if(!owned(asset)) return FileAccess(FileAvailability.UNREADABLE)
        return try { app.contentResolver.openFileDescriptor(Uri.parse(asset.uri),"r")?.use { FileAccess(FileAvailability.AVAILABLE,it.statSize.takeIf { n->n>=0 }) } ?: FileAccess(FileAvailability.UNKNOWN) }
        catch(_:java.io.FileNotFoundException) { FileAccess(FileAvailability.MISSING) }
        catch(_:SecurityException) { FileAccess(FileAvailability.UNREADABLE) }
        catch(_:Exception) { FileAccess(FileAvailability.UNKNOWN) }
    }
    fun delete(asset:VideoAsset):Boolean {
        val access=access(asset)
        if(access.availability==FileAvailability.MISSING) return true
        if(!owned(asset)) return false
        return when(asset.location) {
            AssetLocation.MEDIASTORE_DOWNLOAD -> runCatching {
                val uri=Uri.parse(asset.uri)
                app.contentResolver.delete(uri,null,null)>0 && app.contentResolver.query(uri,arrayOf("_id"),null,null,null)?.use { !it.moveToFirst() }==true
            }.getOrDefault(false)
            AssetLocation.APP_EXTERNAL_FILE -> runCatching { val f=appExternalFile(asset.name); f.delete() && !f.exists() }.getOrDefault(false)
            AssetLocation.LEGACY_PUBLIC_FILE -> runCatching { val f=legacyFile(asset.name); f.delete() && !f.exists() }.getOrDefault(false)
            else -> false
        }
    }
    fun cleanupPending(record:DownloadRecord) {
        if(record.taskStatus==TaskStatus.SUCCEEDED)return
        require(Regex("[a-zA-Z0-9-]{1,100}").matches(record.recordId))
        val uri=record.pendingUri
        if(Build.VERSION.SDK_INT>=29) {
            if(uri!=null) {
                val parsed=Uri.parse(uri)
                if(mediaOwned(parsed,record.name)) check(app.contentResolver.delete(parsed,null,null)>0)
            } else if(record.name.startsWith("${record.recordId.take(8)}_")) {
                // Insert can precede the metadata callback. Only this app's exact task pending row;
                // never published rows, arbitrary URI shapes, or a global Downloads sweep.
                val collection=MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val selection="${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
                val args=arrayOf(record.name,"Download/PureBrowser/",app.packageName)
                val cursor=if(Build.VERSION.SDK_INT>=30)app.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
                    android.os.Bundle().apply {
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,selection)
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args)
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE)
                    },null)
                else {
                    @Suppress("DEPRECATION")
                    val pendingCollection=MediaStore.setIncludePending(collection)
                    app.contentResolver.query(pendingCollection,arrayOf(MediaStore.MediaColumns._ID),selection,args,null)
                }
                val ids=cursor?.use { cursor ->
                    buildList { while(cursor.moveToNext())add(cursor.getLong(0)) }
                }.orEmpty()
                ids.forEach { id -> val row=android.content.ContentUris.withAppendedId(collection,id)
                    if(mediaOwned(row,record.name))check(app.contentResolver.delete(row,null,null)>0)
                }
            }
        } else {
            // The current API<29 route is app-specific external storage; the legacy public file
            // stays covered for records published by older versions while the gid was granted.
            listOf(appExternalBase(), runCatching { legacyFile(record.name).parentFile }.getOrNull())
                .filterNotNull().distinctBy { it.canonicalPath }.forEach { base ->
                    val partial=File(base,".pb-${record.recordId}.part")
                    require(!java.nio.file.Files.isSymbolicLink(partial.toPath()) && partial.canonicalFile.parentFile==base.canonicalFile)
                    if(partial.exists())check(partial.delete())
                    val out=File(base,record.name)
                    if(uri!=null && uri==FileProvider.getUriForFile(app,"${app.packageName}.files",out).toString() && out.exists())check(out.delete())
                }
        }
    }
}
