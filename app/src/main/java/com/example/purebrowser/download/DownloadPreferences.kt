package com.example.purebrowser.download

import android.content.Context

/** Independent local preferences; browser-state schema and old transfers are not rewritten. */
class DownloadPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("download_preferences", Context.MODE_PRIVATE)
    fun wifiOnly(): Boolean = preferences.getBoolean("default_wifi_only", true)
    fun saveWifiOnly(value: Boolean) { check(preferences.edit().putBoolean("default_wifi_only", value).commit()) }
}
