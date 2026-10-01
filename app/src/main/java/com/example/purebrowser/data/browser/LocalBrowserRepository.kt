package com.example.purebrowser.data.browser

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalBrowserRepository(context: Context) {
    val file = File(context.filesDir, "browser-state.json")
    private val atomic = AtomicFile(file)
    private var safeToSave = true
    var recoveredCorruption = false
        private set

    fun load(): BrowserData = synchronized(ioLock) {
        if (!file.exists() && !File(file.path + ".bak").exists()) BrowserData()
        else try {
            val bytes = atomic.readFully()
            require(bytes.size <= 8 * 1024 * 1024)
            decode(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            recoveredCorruption = true
            if (file.exists()) safeToSave = runCatching { file.copyTo(File(file.parentFile, "browser-state.corrupt-${System.currentTimeMillis()}.json"), overwrite = false) }.isSuccess
            BrowserData()
        }
    }
    fun save(data: BrowserData) = synchronized(ioLock) {
        check(safeToSave) { "Original data could not be backed up" }
        val stream = atomic.startWrite()
        try {
            stream.write(encode(data).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
    companion object {
        private val ioLock = Any()
        fun encode(data: BrowserData): String {
            fun pages(list: List<SavedPage>) = JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("time", it.time)) } }
            return JSONObject().put("schemaVersion", 1).put("selectedId", data.selectedId).put("theme", data.theme.name)
                .put("tabs", JSONArray().apply { data.tabs.forEach { put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)) } })
                .put("bookmarks", pages(data.bookmarks)).put("history", pages(data.history))
                .put("shortcuts", JSONArray().apply { data.shortcuts.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)) } }).toString()
        }
        fun decode(raw: String): BrowserData {
            val obj = JSONObject(raw)
            require(obj.getInt("schemaVersion") == 1) { "Unsupported browser data version" }
            fun pages(key: String): List<SavedPage> = obj.getJSONArray(key).let { array ->
                require(array.length() <= 1000)
                (0 until array.length()).map { array.getJSONObject(it).let { p -> SavedPage(p.getString("id"), p.getString("title").take(180), p.getString("url"), p.getLong("time")) } }
            }
            val tabs = obj.getJSONArray("tabs").let { a ->
                require(a.length() <= 50)
                (0 until a.length()).map { a.getJSONObject(it).let { t -> TabRecord(t.getString("id"), t.getString("url"), t.getString("title").take(180)) } }
            }
            val shortcuts = obj.getJSONArray("shortcuts").let { a ->
                require(a.length() <= 12)
                (0 until a.length()).map { a.getJSONObject(it).let { s -> Shortcut(s.getString("id"), s.getString("title").take(30), s.getString("url")) } }
            }
            return BrowserRules.normalize(BrowserData(tabs, obj.getString("selectedId"), pages("bookmarks"), pages("history"), shortcuts, ThemeMode.valueOf(obj.getString("theme"))))
        }
    }
}
