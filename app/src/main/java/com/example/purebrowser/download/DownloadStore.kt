package com.example.purebrowser.download

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Sole download-metadata authority. Never writes or clears legacy preferences. */
class DownloadStore(
    directory: File,
    private val legacyReader: () -> String = { "[]" },
) {
    constructor(context: Context) : this(context.filesDir, {
        context.getSharedPreferences("download_records", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"
    })

    val file = File(directory, "download-state.json")
    private val atomic = AtomicFile(file)
    var writable = true
        private set
    private var notice: String? = null
    // Cache parsing, NOT disk identity: every load still reads AtomicFile and compares the entire JSON text.
    // Stat-only caches would miss equal-length external edits and could bypass future-schema protection.
    private var parsedRaw: String? = null
    private var parsedData: DownloadData? = null
    private var parsedGeneration=-1L
    private fun invalidateParsed() { parsedRaw=null;parsedData=null }

    fun takeNotice(): String? = synchronized(transactionLock) { notice.also { notice = null } }

    fun load(): DownloadData = synchronized(transactionLock) {
        if (!file.exists() && !File(file.path + ".bak").exists()) {
            invalidateParsed()
            val raw = legacyReader()
            val data = try { DownloadData(records = decodeLegacy(raw)) }
            catch (_: Exception) {
                // Keep the original preferences untouched, even if the backup cannot be made.
                writable = backupBytes(raw.toByteArray(Charsets.UTF_8), "legacy")
                notice = "旧下载记录无法读取；原始记录未改动"
                DownloadData()
            }
            if (writable) save(data)
            return@synchronized data
        }
        try {
            val raw = atomic.openRead().use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(result.size() + count <= DownloadRules.MAX_FILE_BYTES)
                    result.write(buffer, 0, count)
                }
                result.toString("UTF-8")
            }
            parsedData?.let { if(raw==parsedRaw) { parsedGeneration=transactionGeneration;return@synchronized it } }
            val data = decode(raw)
            val version=JSONObject(raw).getInt("schemaVersion")
            if(version==SCHEMA_VERSION) {
                val immutable=data.copy(records=java.util.Collections.unmodifiableList(data.records.toList()),
                    assets=java.util.Collections.unmodifiableList(data.assets.toList()))
                parsedRaw=raw;parsedData=immutable;parsedGeneration=transactionGeneration
                return@synchronized immutable
            }
            if (version in setOf(2, 3, 4)) {
                val bytes = raw.toByteArray(Charsets.UTF_8)
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
                val oldVersion = JSONObject(raw).getInt("schemaVersion")
                val backup = File(file.parentFile, "download-v$oldVersion-$hash.json")
                val protected = runCatching {
                    if (!backup.exists()) backup.writeBytes(bytes)
                    check(backup.readBytes().contentEquals(bytes))
                }.isSuccess
                if (protected) {
                    runCatching { save(data) }.onFailure { writable = false; notice = "迁移写入失败，旧记录与备份已保留" }
                } else { writable = false; notice = "无法保护旧下载记录，已停止迁移写入" }
            }
            data
        } catch (_: FutureSchemaException) {
            invalidateParsed()
            writable = false
            notice = "下载记录版本不兼容，原始数据未改动"
            DownloadData()
        } catch (_: Exception) {
            invalidateParsed()
            // Do not resurrect removed records by importing the old preference file again.
            writable = runCatching {
                file.copyTo(backupFile("state"), overwrite = false)
            }.isSuccess
            notice = if (writable) "下载记录读取失败，已保留原始备份" else "下载记录读取失败；为保护原数据，已停止写入"
            val empty = DownloadData()
            if (writable) save(empty)
            empty
        }
    }

    fun save(data: DownloadData) = synchronized(transactionLock) {
        check(writable) { "Download storage is read-only" }
        invalidateParsed() // The next load still validates the successfully written representation.
        val bytes = encode(data).toByteArray(Charsets.UTF_8)
        require(bytes.size <= DownloadRules.MAX_FILE_BYTES)
        file.parentFile?.mkdirs()
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream);transactionGeneration++ }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }

    /** Hot writer gate only. All app commits invalidate this view under the same transaction lock.
     * Normal load/change still read the actual AtomicFile; coordinator/progress/final publication
     * revalidate disk. Resume, migration and ownership never authorize from this transient view. */
    internal fun writerRecord(id:TaskId):DownloadRecord?=synchronized(transactionLock) {
        val data=if(parsedData!=null && parsedGeneration==transactionGeneration)parsedData!! else load()
        if(writable)data.records.firstOrNull { it.recordId==id } else null
    }

    private fun backupFile(label: String) = File(file.parentFile,
        "download-$label.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json")
    private fun backupBytes(bytes: ByteArray, label: String) = runCatching {
        file.parentFile?.mkdirs()
        backupFile(label).writeBytes(bytes)
    }.isSuccess

    private class FutureSchemaException : Exception()

    companion object {
        // Covers read-modify-save across repository instances, not only atomic file writes.
        internal val transactionLock = Any()
        private var transactionGeneration=0L
        const val SCHEMA_VERSION = 5

        private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
        private fun JSONObject.exactLong(key:String):Long {
            val n=get(key);require(n is Number)
            return java.math.BigDecimal(n.toString()).longValueExact()
        }
        private fun JSONObject.exactInt(key:String):Int = Math.toIntExact(exactLong(key))
        private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else exactLong(key)
        private fun JSONObject.nullableBoolean(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
        private fun JSONObject.field(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

        fun encode(data: DownloadData): String {
            DownloadRules.validate(data)
            val records = JSONArray().apply { data.records.forEach { r ->
                put(JSONObject().put("recordId", r.recordId).field("systemId", r.systemId).put("name", r.name)
                    .put("displayName", r.displayName).field("mediaUrl", r.mediaUrl).field("sourceUrl", r.sourceUrl)
                    .field("sourceTitle", r.sourceTitle).field("createdAt", r.createdAt).field("userAgent", r.userAgent)
                    .field("wifiOnly", r.wifiOnly).field("mimeType", r.mimeType).field("retryOf", r.retryOf)
                    .field("sourceTabId", r.sourceTabId).field("sourceGeneration", r.sourceGeneration).put("cancelled", r.cancelled)
                    .put("transfer",r.transfer.name).put("taskStatus",r.taskStatus.name).put("received",r.received)
                    .field("expected",r.expected).field("failure",r.failure?.name).put("useAccessContext",r.useAccessContext)
                    .field("frameUrl",r.frameUrl).put("reliableSource",r.reliableSource).field("pendingUri",r.pendingUri)
                    .put("protocol",r.protocol.name).field("hlsPlaylistUrl",r.hlsPlaylistUrl)
                    .field("hlsWidth",r.hlsWidth).field("hlsHeight",r.hlsHeight).field("hlsBandwidth",r.hlsBandwidth)
                    .field("plannedDurationUs",r.plannedDurationUs).field("segmentCount",r.segmentCount)
                    .put("completedSegments",r.completedSegments).field("safeFailure",r.safeFailure).field("pauseReason",r.pauseReason?.name).put("resumeAvailable",r.resumeAvailable))
            } }
            val assets = JSONArray().apply { data.assets.forEach { a ->
                put(JSONObject().put("recordId", a.recordId).field("systemId", a.systemId).put("uri", a.uri)
                    .put("name", a.name).put("displayName", a.displayName).put("indexedAt", a.indexedAt)
                    .field("systemUpdatedAt", a.systemUpdatedAt).field("sizeBytes", a.sizeBytes)
                    .field("mimeType", a.mimeType).put("format", a.format.name).put("availability", a.availability.name)
                    .field("durationMillis", a.durationMillis).put("location",a.location.name))
            } }
            return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("legacyMigrationDone", true)
                .put("records", records).put("assets", assets).toString()
        }

        fun decode(raw: String): DownloadData {
            require(raw.toByteArray(Charsets.UTF_8).size <= DownloadRules.MAX_FILE_BYTES)
            val obj = JSONObject(raw)
            val version=runCatching { obj.exactLong("schemaVersion") }.getOrElse { throw FutureSchemaException() }
            if(version !in setOf(2L,3L,4L,SCHEMA_VERSION.toLong()))throw FutureSchemaException()
            require(obj.getBoolean("legacyMigrationDone"))
            val records = obj.getJSONArray("records").let { a ->
                require(a.length() <= DownloadRules.MAX_RECORDS)
                (0 until a.length()).map { a.getJSONObject(it).let { r ->
                    DownloadRecord(recordId = r.getString("recordId"), systemId = r.nullableLong("systemId"),
                        name = r.getString("name"), displayName = r.getString("displayName"),
                        mediaUrl = r.nullableString("mediaUrl"), sourceUrl = r.nullableString("sourceUrl"),
                        sourceTitle = r.nullableString("sourceTitle"), createdAt = r.nullableLong("createdAt"),
                        userAgent = r.nullableString("userAgent"), wifiOnly = r.nullableBoolean("wifiOnly"),
                        mimeType = r.nullableString("mimeType"), retryOf = r.nullableString("retryOf"),
                        sourceTabId = r.nullableString("sourceTabId"), sourceGeneration = r.nullableLong("sourceGeneration"), cancelled = r.optBoolean("cancelled", false),
                        transfer=TransferType.valueOf(r.optString("transfer","SYSTEM")),
                        taskStatus=TaskStatus.valueOf(r.optString("taskStatus","QUEUED")),received=if(r.has("received"))r.exactLong("received") else 0,
                        expected=if(r.has("expected")) r.nullableLong("expected") else null,
                        failure=if(r.has("failure")) r.nullableString("failure")?.let(FailureKind::valueOf) else null,
                        useAccessContext=r.optBoolean("useAccessContext",false),reliableSource=r.optBoolean("reliableSource",false),
                        frameUrl=if(r.has("frameUrl")) r.nullableString("frameUrl") else null,
                        pendingUri=if(r.has("pendingUri")) r.nullableString("pendingUri") else null,
                        protocol=DownloadProtocol.valueOf(r.optString("protocol","DIRECT")),
                        hlsPlaylistUrl=if(r.has("hlsPlaylistUrl"))r.nullableString("hlsPlaylistUrl") else null,
                        hlsWidth=if(r.has("hlsWidth") && !r.isNull("hlsWidth"))r.exactInt("hlsWidth") else null,
                        hlsHeight=if(r.has("hlsHeight") && !r.isNull("hlsHeight"))r.exactInt("hlsHeight") else null,
                        hlsBandwidth=if(r.has("hlsBandwidth"))r.nullableLong("hlsBandwidth") else null,
                        plannedDurationUs=if(r.has("plannedDurationUs"))r.nullableLong("plannedDurationUs") else null,
                        segmentCount=if(r.has("segmentCount") && !r.isNull("segmentCount"))r.exactInt("segmentCount") else null,
                        completedSegments=if(r.has("completedSegments"))r.exactInt("completedSegments") else 0,
                        safeFailure=if(r.has("safeFailure"))r.nullableString("safeFailure") else null,
                        pauseReason=if(r.has("pauseReason"))r.nullableString("pauseReason")?.let(PauseReason::valueOf) else null,
                        resumeAvailable=version>=5 && r.optBoolean("resumeAvailable",false))
                } }
            }
            val assets = obj.getJSONArray("assets").let { a ->
                require(a.length() <= DownloadRules.MAX_RECORDS)
                (0 until a.length()).map { a.getJSONObject(it).let { r ->
                    VideoAsset(recordId = r.getString("recordId"), systemId = r.nullableLong("systemId"),
                        uri = r.getString("uri"), name = r.getString("name"), displayName = r.getString("displayName"),
                        indexedAt = r.getLong("indexedAt"), systemUpdatedAt = r.nullableLong("systemUpdatedAt"),
                        sizeBytes = r.nullableLong("sizeBytes"), mimeType = r.nullableString("mimeType"),
                        format = FormatCheck.valueOf(r.getString("format")), availability = FileAvailability.valueOf(r.getString("availability")),
                        durationMillis = r.nullableLong("durationMillis"), location=AssetLocation.valueOf(r.optString("location","SYSTEM_DOWNLOAD")))
                } }
            }
            return DownloadData(records, assets).also(DownloadRules::validate)
        }

        fun decodeLegacy(raw: String): List<DownloadRecord> {
            require(raw.toByteArray(Charsets.UTF_8).size <= DownloadRules.MAX_FILE_BYTES)
            val array = JSONArray(raw)
            require(array.length() <= DownloadRules.MAX_RECORDS)
            val records = (0 until array.length()).map { array.getJSONObject(it).let { r ->
                val id = r.getLong("id")
                DownloadRecord(recordId = "legacy-$id", systemId = id, name = r.getString("name"))
            } }.distinctBy { it.systemId }
            DownloadRules.validate(DownloadData(records = records))
            return records
        }
    }
}
