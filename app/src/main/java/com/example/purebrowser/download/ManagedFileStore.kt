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
import java.io.File
import java.security.MessageDigest

/** All public outputs are minted here; never accepts webpage file paths. */
class ManagedFileStore(private val app: Context) {
    private val stages=File(app.filesDir,"transfers").apply { mkdirs() }
    fun stage(id:TaskId):File {
        require(Regex("[a-zA-Z0-9-]{1,100}").matches(id))
        return File(stages,"$id.part")
    }
    fun removeStage(id:TaskId) { val f=stage(id); if(f.exists()) check(f.delete()) { "临时文件暂时无法清理" } }
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
            val duration=if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)/1000 else null
            MediaInspection(FormatCheck.PASSED,mime,duration)
        } catch(_:Exception) { MediaInspection(FormatCheck.UNCONFIRMED) } finally { extractor.release() }
    }
    private fun legacyFile(name:String):File {
        require(name==DownloadRules.safeFileName(name) || (name.length<=200 && Regex("[\\p{L}\\p{N}._-]+").matches(name)))
        // Android's legacy emulated-storage root itself is a legitimate platform alias.
        val downloads=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base=File(downloads,"PureBrowser")
        if(base.exists()) require(OsConstants.S_ISDIR(Os.lstat(base.path).st_mode) && !OsConstants.S_ISLNK(Os.lstat(base.path).st_mode))
        val f=File(base,name)
        require(f.canonicalFile.parentFile==base.canonicalFile)
        return f
    }
    fun publish(record:DownloadRecord,file:File,inspection:MediaInspection,onPending:(String)->Unit,cancel:TransferCancellation):VideoAsset {
        cancel.check()
        val mime=inspection.mimeType ?: error("容器未确认")
        val uri:Uri
        val location:AssetLocation
        if(Build.VERSION.SDK_INT>=29) {
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
            val out=legacyFile(record.name)
            out.parentFile!!.mkdirs()
            check(!out.exists()) { "目标文件已存在，未覆盖" }
            val partial=File(out.parentFile,".pb-${record.recordId}.part")
            check(partial.createNewFile()) { "临时公共文件已存在，未覆盖" }
            uri=FileProvider.getUriForFile(app,"${app.packageName}.files",out)
            try {
                onPending(uri.toString())
                partial.outputStream().use { output ->file.inputStream().use { input ->
                    val buffer=ByteArray(65536)
                    while(true) { cancel.check();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n) }
                } }
                cancel.check();check(hash(partial)==hash(file)) { "成品写入校验失败" }
                check(!out.exists()) { "目标文件已存在，未覆盖" }
                java.nio.file.Files.move(partial.toPath(),out.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch(e:Exception) { partial.delete();throw e }
            location=AssetLocation.LEGACY_PUBLIC_FILE
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
            AssetLocation.LEGACY_PUBLIC_FILE -> runCatching { val f=legacyFile(asset.name); f.delete() && !f.exists() }.getOrDefault(false)
            else -> false
        }
    }
    fun cleanupPending(record:DownloadRecord) {
        val uri=record.pendingUri ?: return
        if(Build.VERSION.SDK_INT>=29) {
            val parsed=Uri.parse(uri)
            if(mediaOwned(parsed,record.name)) check(app.contentResolver.delete(parsed,null,null)>0)
        } else {
            val f=legacyFile(record.name)
            if(uri==FileProvider.getUriForFile(app,"${app.packageName}.files",f).toString()) {
                val partial=File(f.parentFile,".pb-${record.recordId}.part")
                if(partial.exists())check(partial.delete())
                if(f.exists())check(f.delete())
            }
        }
    }
}
