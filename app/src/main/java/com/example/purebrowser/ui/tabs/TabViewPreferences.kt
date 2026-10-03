package com.example.purebrowser.ui.tabs

import android.content.Context

/** Only the explicit local layout choice is persisted. No query, URL or preview enters this file. */
class TabViewPreferences(context: Context) {
    private val preferences = runCatching {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }.getOrNull()

    fun read(): TabViewMode = runCatching {
        TabViewMode.fromStored(preferences?.getString(KEY, null))
    }.getOrDefault(TabViewMode.GRID)

    fun write(mode: TabViewMode) {
        // A storage failure must not prevent switching views or disturb existing browser data.
        runCatching { preferences?.edit()?.putString(KEY, mode.name)?.apply() }
    }

    companion object {
        internal const val FILE = "tab_overview_ui"
        internal const val KEY = "view_mode"
    }
}
