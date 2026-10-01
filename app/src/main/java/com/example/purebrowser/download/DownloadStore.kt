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

    fun takeNotice(): String? = synchronized(transactionLock) { notice.also { notice = null } }

    fun load(): DownloadData = synchronized(transactionLock) {
        if (!file.exists() && !File(file.path + ".bak").exists()) {
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
            decode(raw)
        } catch (_: FutureSchemaException) {
            writable = false
            notice = "下载记录版本不兼容，原始数据未改动"
            DownloadData()
        } catch (_: Exception) {
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
        val bytes = encode(data).toByteArray(Charsets.UTF_8)
        require(bytes.size <= DownloadRules.MAX_FILE_BYTES)
        file.parentFile?.mkdirs()
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
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
        const val SCHEMA_VERSION = 2

        private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
        private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
        private fun JSONObject.nullableBoolean(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
        private fun JSONObject.field(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

        fun encode(data: DownloadData): String {
            DownloadRules.validate(data)
            val records = JSONArray().apply { data.records.forEach { r ->
                put(JSONObject().put("recordId", r.recordId).put("systemId", r.systemId).put("name", r.name)
                    .put("displayName", r.displayName).field("mediaUrl", r.mediaUrl).field("sourceUrl", r.sourceUrl)
                    .field("sourceTitle", r.sourceTitle).field("createdAt", r.createdAt).field("userAgent", r.userAgent)
                    .field("wifiOnly", r.wifiOnly).field("mimeType", r.mimeType).field("retryOf", r.retryOf)
                    .field("sourceTabId", r.sourceTabId).field("sourceGeneration", r.sourceGeneration))
            } }
            val assets = JSONArray().apply { data.assets.forEach { a ->
                put(JSONObject().put("recordId", a.recordId).put("systemId", a.systemId).put("uri", a.uri)
                    .put("name", a.name).put("displayName", a.displayName).put("indexedAt", a.indexedAt)
                    .field("systemUpdatedAt", a.systemUpdatedAt).field("sizeBytes", a.sizeBytes)
                    .field("mimeType", a.mimeType).put("format", a.format.name).put("availability", a.availability.name)
                    .field("durationMillis", a.durationMillis))
            } }
            return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("legacyMigrationDone", true)
                .put("records", records).put("assets", assets).toString()
        }

        fun decode(raw: String): DownloadData {
            require(raw.toByteArray(Charsets.UTF_8).size <= DownloadRules.MAX_FILE_BYTES)
            val obj = JSONObject(raw)
            if (obj.getInt("schemaVersion") != SCHEMA_VERSION) throw FutureSchemaException()
            require(obj.getBoolean("legacyMigrationDone"))
            val records = obj.getJSONArray("records").let { a ->
                require(a.length() <= DownloadRules.MAX_RECORDS)
                (0 until a.length()).map { a.getJSONObject(it).let { r ->
                    DownloadRecord(recordId = r.getString("recordId"), systemId = r.getLong("systemId"),
                        name = r.getString("name"), displayName = r.getString("displayName"),
                        mediaUrl = r.nullableString("mediaUrl"), sourceUrl = r.nullableString("sourceUrl"),
                        sourceTitle = r.nullableString("sourceTitle"), createdAt = r.nullableLong("createdAt"),
                        userAgent = r.nullableString("userAgent"), wifiOnly = r.nullableBoolean("wifiOnly"),
                        mimeType = r.nullableString("mimeType"), retryOf = r.nullableString("retryOf"),
                        sourceTabId = r.nullableString("sourceTabId"), sourceGeneration = r.nullableLong("sourceGeneration"))
                } }
            }
            val assets = obj.getJSONArray("assets").let { a ->
                require(a.length() <= DownloadRules.MAX_RECORDS)
                (0 until a.length()).map { a.getJSONObject(it).let { r ->
                    VideoAsset(recordId = r.getString("recordId"), systemId = r.getLong("systemId"),
                        uri = r.getString("uri"), name = r.getString("name"), displayName = r.getString("displayName"),
                        indexedAt = r.getLong("indexedAt"), systemUpdatedAt = r.nullableLong("systemUpdatedAt"),
                        sizeBytes = r.nullableLong("sizeBytes"), mimeType = r.nullableString("mimeType"),
                        format = FormatCheck.valueOf(r.getString("format")), availability = FileAvailability.valueOf(r.getString("availability")),
                        durationMillis = r.nullableLong("durationMillis"))
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
